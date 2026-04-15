package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.EmbeddingFloatCodec
import com.example.ai_develop.data.OllamaEmbeddingClient
import com.example.ai_develop.data.RagEmbeddingRepository
import com.example.ai_develop.data.RagStoredChunk
import com.example.ai_develop.domain.OllamaDefaultModelName
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
)

@OptIn(ExperimentalUuidApi::class)
class RagEmbeddingsViewModel(
    private val ollamaEmbeddingClient: OllamaEmbeddingClient,
    private val ragRepository: RagEmbeddingRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(RagEmbeddingsUiState())
    val uiState: StateFlow<RagEmbeddingsUiState> = _ui.asStateFlow()

    val savedDocuments = ragRepository.observeAllDocuments().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

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

    fun recomputeChunks() {
        val s = _ui.value
        val chunks = try {
            TextChunker.chunk(s.fullText, s.chunkSize, s.overlap)
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
                    ollamaModel = s.ollamaModel.trim().ifEmpty { OllamaDefaultModelName },
                    chunkSize = s.chunkSize.toLong(),
                    overlap = s.overlap.toLong(),
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
                ragRepository.insertDocumentWithChunks(doc, chunkRows)
                _ui.update { it.copy(saveMessage = "Сохранено в базу", error = null) }
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
}
