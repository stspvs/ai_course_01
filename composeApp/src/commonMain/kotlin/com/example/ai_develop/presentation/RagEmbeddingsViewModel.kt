package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.EmbeddingFloatCodec
import com.example.ai_develop.data.OllamaEmbeddingClient
import com.example.ai_develop.data.OllamaModelsClient
import com.example.ai_develop.data.RagContextRetriever
import com.example.ai_develop.data.RagEmbeddingRepository
import com.example.ai_develop.data.RagPipelineSettingsRepository
import com.example.ai_develop.data.RagStoredChunk
import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.domain.ChunkStrategy
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.domain.OllamaDefaultModelName
import com.example.ai_develop.domain.RagRetrievalConfig
import com.example.ai_develop.domain.TextChunk
import com.example.ai_develop.domain.TextChunker
import com.example.ai_develop.platform.openTextFileDialog
import com.example.ai_develop.platform.readTextFileUtf8
import com.example.aidevelop.database.RagChunkEntity
import com.example.aidevelop.database.RagDocumentEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class RagEmbeddingsUiState(
    val sourcePath: String? = null,
    val sourceFileName: String = "",
    val fullText: String = "",
    val ollamaModel: String = OllamaDefaultModelName,
    val chunkStrategy: ChunkStrategy = ChunkStrategy.FIXED_WINDOW,
    val chunkSize: Int = 512,
    val overlap: Int = 64,
    val chunks: List<TextChunk> = emptyList(),
    val embeddings: List<FloatArray>? = null,
    val isGenerating: Boolean = false,
    val generationDone: Int = 0,
    val generationTotal: Int = 0,
    val error: String? = null,
    val saveMessage: String? = null,
    val expandedDbDocId: String? = null,
    val dbPreviewText: String? = null,
    val dbPreviewChunks: List<RagStoredChunk> = emptyList(),
    val ragPipelineConfig: RagRetrievalConfig = RagRetrievalConfig.Default,
    /** Последний конфиг, записанный в БД; для сравнения с [ragPipelineConfig]. */
    val savedRagPipelineConfig: RagRetrievalConfig = RagRetrievalConfig.Default,
    val ollamaLocalModels: List<String> = emptyList(),
    val ollamaModelsLoadError: String? = null,
    val ragPreviewQuery: String = "",
    val ragPreviewText: String? = null,
    val ragPreviewLoading: Boolean = false,
)

@OptIn(ExperimentalUuidApi::class)
class RagEmbeddingsViewModel(
    private val ollamaEmbeddingClient: OllamaEmbeddingClient,
    private val ragRepository: RagEmbeddingRepository,
    private val ragContextRetriever: RagContextRetriever,
    private val ragPipelineSettingsRepository: RagPipelineSettingsRepository,
    private val ollamaModelsClient: OllamaModelsClient,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(RagEmbeddingsUiState())
    val uiState: StateFlow<RagEmbeddingsUiState> = _ui.asStateFlow()

    val savedDocuments = ragRepository.observeAllDocuments().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    init {
        viewModelScope.launch {
            runCatching { ragPipelineSettingsRepository.getConfig() }.getOrNull()?.let { cfg ->
                _ui.update { it.copy(ragPipelineConfig = cfg, savedRagPipelineConfig = cfg) }
            }
        }
        viewModelScope.launch { refreshOllamaModelList() }
    }

    fun updateRagPipeline(transform: (RagRetrievalConfig) -> RagRetrievalConfig) {
        _ui.update { it.copy(ragPipelineConfig = transform(it.ragPipelineConfig)) }
    }

    fun saveRagPipeline() {
        viewModelScope.launch {
            try {
                ragPipelineSettingsRepository.saveConfig(_ui.value.ragPipelineConfig)
                _ui.update { cur ->
                    cur.copy(
                        savedRagPipelineConfig = cur.ragPipelineConfig,
                        saveMessage = "Настройки RAG сохранены",
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    fun refreshOllamaModelList() {
        viewModelScope.launch {
            ollamaModelsClient.listModelNames()
                .onSuccess { names ->
                    _ui.update { it.copy(ollamaLocalModels = names, ollamaModelsLoadError = null) }
                }
                .onFailure { e ->
                    _ui.update { it.copy(ollamaModelsLoadError = e.message ?: e.toString()) }
                }
        }
    }

    fun setRagPreviewQuery(q: String) {
        _ui.update { it.copy(ragPreviewQuery = q) }
    }

    fun runRagPreviewDryRun() {
        val q = _ui.value.ragPreviewQuery.trim()
        if (q.isEmpty()) {
            _ui.update { it.copy(error = "Введите тестовый запрос") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(ragPreviewLoading = true, ragPreviewText = null, error = null) }
            try {
                val cfg = _ui.value.ragPipelineConfig
                var retrievalQuery = q
                var rewriteApplied = false
                if (cfg.queryRewriteEnabled) {
                    val provider = LLMProvider.Ollama(
                        model = cfg.rewriteOllamaModel.trim().ifBlank { OllamaDefaultModelName },
                    )
                    chatRepository.rewriteQueryForRag(q, provider).onSuccess { rw ->
                        if (rw.isNotBlank()) {
                            retrievalQuery = rw.trim()
                            rewriteApplied = true
                        }
                    }
                }
                if (retrievalQuery.isBlank()) retrievalQuery = q

                val r = ragContextRetriever.retrieve(
                    originalQuery = q,
                    retrievalQuery = retrievalQuery,
                    config = cfg,
                    rewriteApplied = rewriteApplied,
                )
                val text = buildString {
                    if (r == null) {
                        appendLine("Нет результата (пустой запрос).")
                    } else {
                        if (rewriteApplied || retrievalQuery != q) {
                            appendLine("Исходный запрос: $q")
                            appendLine("Запрос для поиска (после rewrite): ${retrievalQuery.take(500)}")
                            appendLine()
                        }
                        r.attribution.debug?.let { d ->
                            appendLine("Режим: ${d.pipelineMode}")
                            appendLine("Recall K: ${d.recallTopK}, final K: ${d.finalTopK}")
                            appendLine("После recall: ${d.candidatesAfterRecall}, после порога: ${d.candidatesAfterThreshold}, после rerank: ${d.candidatesAfterRerank}")
                            d.minSimilarity?.let { appendLine("Порог: $it") }
                            d.emptyReason?.let { appendLine("Причина пусто: $it") }
                            appendLine()
                        }
                        appendLine("Использован RAG: ${r.attribution.used}")
                        r.attribution.sources.forEachIndexed { i, s ->
                            appendLine("${i + 1}. ${s.documentTitle} score=${s.score} final=${s.finalScore}")
                        }
                    }
                }
                _ui.update { it.copy(ragPreviewText = text.trim(), ragPreviewLoading = false) }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(ragPreviewLoading = false, error = e.message ?: e.toString())
                }
            }
        }
    }

    fun setOllamaModel(value: String) {
        _ui.update { it.copy(ollamaModel = value, error = null, saveMessage = null) }
    }

    fun setChunkSize(value: Int) {
        _ui.update { it.copy(chunkSize = value.coerceAtLeast(1), error = null, saveMessage = null) }
        recomputeChunks()
    }

    fun setOverlap(value: Int) {
        _ui.update { it.copy(overlap = value.coerceAtLeast(0), error = null, saveMessage = null) }
        recomputeChunks()
    }

    fun setChunkStrategy(strategy: ChunkStrategy) {
        _ui.update { it.copy(chunkStrategy = strategy, error = null, saveMessage = null) }
        recomputeChunks()
    }

    fun recomputeChunks() {
        val s = _ui.value
        val chunks = try {
            TextChunker.chunk(s.fullText, s.chunkStrategy, s.chunkSize, s.overlap)
        } catch (e: IllegalArgumentException) {
            _ui.update { it.copy(chunks = emptyList(), embeddings = null, error = e.message) }
            return
        }
        _ui.update { it.copy(chunks = chunks, embeddings = null, error = null) }
    }

    fun pickTextFile() {
        viewModelScope.launch {
            val path = openTextFileDialog() ?: return@launch
            try {
                val text = readTextFileUtf8(path)
                val name = path.substringAfterLast('/').substringAfterLast('\\')
                _ui.update {
                    it.copy(
                        sourcePath = path,
                        sourceFileName = name,
                        fullText = text,
                        chunks = emptyList(),
                        embeddings = null,
                        error = null,
                        saveMessage = null,
                    )
                }
                recomputeChunks()
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    fun generateEmbeddings() {
        val s = _ui.value
        if (s.chunks.isEmpty()) {
            _ui.update { it.copy(error = "Нет чанков: загрузите файл и проверьте параметры") }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    isGenerating = true,
                    generationDone = 0,
                    generationTotal = s.chunks.size,
                    error = null,
                    saveMessage = null,
                )
            }
            val model = s.ollamaModel.trim().ifEmpty { OllamaDefaultModelName }
            val vectors = ArrayList<FloatArray>(s.chunks.size)
            try {
                for ((i, ch) in s.chunks.withIndex()) {
                    val vec = ollamaEmbeddingClient.embed(model, ch.text)
                    vectors.add(vec)
                    _ui.update { it.copy(generationDone = i + 1) }
                }
                _ui.update { it.copy(embeddings = vectors, isGenerating = false) }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        isGenerating = false,
                        embeddings = null,
                        error = e.message ?: e.toString(),
                    )
                }
            }
        }
    }

    fun saveToDatabase() {
        val s = _ui.value
        val emb = s.embeddings
        if (emb == null || emb.size != s.chunks.size) {
            _ui.update { it.copy(error = "Сначала сгенерируйте эмбеддинги") }
            return
        }
        viewModelScope.launch {
            try {
                val docId = Uuid.random().toString()
                val title = s.sourceFileName.ifBlank { "Документ" }
                val now = System.currentTimeMillis()
                val doc = RagDocumentEntity(
                    id = docId,
                    title = title,
                    sourceFileName = s.sourceFileName.ifBlank { "(вставка)" },
                    sourcePath = s.sourcePath.orEmpty(),
                    ollamaModel = s.ollamaModel.trim().ifEmpty { OllamaDefaultModelName },
                    chunkSize = s.chunkSize.toLong(),
                    overlap = s.overlap.toLong(),
                    chunkStrategy = s.chunkStrategy.id,
                    createdAt = now,
                    fullText = s.fullText,
                )
                val chunkRows = s.chunks.mapIndexed { idx, ch ->
                    RagChunkEntity(
                        id = Uuid.random().toString(),
                        documentId = docId,
                        chunkIndex = idx.toLong(),
                        startOffset = ch.start.toLong(),
                        endOffset = ch.end.toLong(),
                        text = ch.text,
                        embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(emb[idx]),
                    )
                }
                val replaced = ragRepository.insertDocumentWithChunks(doc, chunkRows)
                _ui.update {
                    it.copy(
                        saveMessage = if (replaced) {
                            "Документ для этого файла обновлён в базе"
                        } else {
                            "Сохранено в базу"
                        },
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString(), saveMessage = null) }
            }
        }
    }

    fun toggleDbDocumentExpand(id: String) {
        viewModelScope.launch {
            val current = _ui.value.expandedDbDocId
            if (current == id) {
                _ui.update {
                    it.copy(expandedDbDocId = null, dbPreviewText = null, dbPreviewChunks = emptyList())
                }
                return@launch
            }
            val doc = ragRepository.getDocument(id) ?: return@launch
            val chunks = ragRepository.getChunks(id)
            _ui.update {
                it.copy(
                    expandedDbDocId = id,
                    dbPreviewText = doc.fullText,
                    dbPreviewChunks = chunks,
                )
            }
        }
    }

    fun deleteDbDocument(id: String) {
        viewModelScope.launch {
            try {
                ragRepository.deleteDocument(id)
                _ui.update {
                    if (it.expandedDbDocId == id) {
                        it.copy(
                            expandedDbDocId = null,
                            dbPreviewText = null,
                            dbPreviewChunks = emptyList(),
                            saveMessage = "Удалено",
                        )
                    } else {
                        it.copy(saveMessage = "Удалено")
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    fun dismissMessages() {
        _ui.update { it.copy(error = null, saveMessage = null) }
    }

    /**
     * Для unit-тестов: задаёт текст без платформенного диалога выбора файла.
     * [internal] — виден только в модуле composeApp (в т.ч. commonTest).
     */
    internal fun setSourceTextForTests(
        fullText: String,
        sourceFileName: String = "test.txt",
        sourcePath: String = "/test/doc.txt",
    ) {
        _ui.update {
            it.copy(
                sourcePath = sourcePath,
                sourceFileName = sourceFileName,
                fullText = fullText,
                chunks = emptyList(),
                embeddings = null,
                error = null,
                saveMessage = null,
            )
        }
        recomputeChunks()
    }
}
