@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.example.ai_develop.presentation

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.ai_develop.data.EmbeddingFloatCodec
import com.example.ai_develop.data.OllamaEmbeddingClient
import com.example.ai_develop.data.OllamaModelsClient
import com.example.ai_develop.data.OllamaRagRerankClient
import com.example.ai_develop.data.RagContextRetriever
import com.example.ai_develop.data.RagEmbeddingRepository
import com.example.ai_develop.data.SqlDelightRagPipelineSettingsRepository
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
import com.example.ai_develop.domain.AgentState
import com.example.ai_develop.domain.AgentStage
import com.example.ai_develop.domain.ChatFacts
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.domain.ChunkStrategy
import com.example.ai_develop.domain.Invariant
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.domain.OllamaDefaultModelName
import com.example.ai_develop.domain.RagPipelineMode
import com.example.ai_develop.domain.RagRetrievalConfig
import com.example.ai_develop.domain.TaskAnalysisResult
import com.example.ai_develop.domain.UserProfile
import com.example.ai_develop.domain.WorkingMemoryAnalysis
import com.example.aidevelop.database.RagChunkEntity
import com.example.aidevelop.database.RagDocumentEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RagEmbeddingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var driver: SqlDriver
    private lateinit var database: AgentDatabase
    private lateinit var ragRepository: RagEmbeddingRepository
    private lateinit var viewModel: RagEmbeddingsViewModel

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgentDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        database = AgentDatabase(
            driver = driver,
            AgentMessageEntityAdapter = com.example.aidevelop.database.AgentMessageEntity.Adapter(
                stageAdapter = stageAdapter
            ),
            AgentStateEntityAdapter = com.example.aidevelop.database.AgentStateEntity.Adapter(
                currentStageAdapter = stageAdapter
            ),
            InvariantEntityAdapter = com.example.aidevelop.database.InvariantEntity.Adapter(
                stageAdapter = stageAdapter
            )
        )
        ragRepository = RagEmbeddingRepository(database)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
    }

    private fun clientTagsEmpty(): OllamaModelsClient {
        val mockEngine = MockEngine {
            respond(
                content = """{"models":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        return OllamaModelsClient(http, json, baseUrl = "http://127.0.0.1:11434")
    }

    private fun clientRerankStub(): OllamaRagRerankClient {
        val mockEngine = MockEngine {
            respond(
                content = """{"response":"5"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        return OllamaRagRerankClient(http, json, baseUrl = "http://127.0.0.1:11434")
    }

    private fun newViewModel(
        ollama: OllamaEmbeddingClient,
        models: OllamaModelsClient = clientTagsEmpty(),
        chat: ChatRepository = FakeChatRepositoryForRagVm(),
    ) {
        val retriever = RagContextRetriever(ollama, ragRepository, clientRerankStub())
        val pipelineRepo = SqlDelightRagPipelineSettingsRepository(database, json)
        viewModel = RagEmbeddingsViewModel(
            ollama,
            ragRepository,
            retriever,
            pipelineRepo,
            models,
            chat,
        )
    }

    private fun clientTagsWithModels(vararg names: String): OllamaModelsClient {
        val arr = names.joinToString(",") { """{"name":"$it"}""" }
        val mockEngine = MockEngine {
            respond(
                content = """{"models":[$arr]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        return OllamaModelsClient(http, json, baseUrl = "http://127.0.0.1:11434")
    }

    private fun clientTagsFailing(): OllamaModelsClient {
        val mockEngine = MockEngine {
            throw RuntimeException("tags unavailable")
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        return OllamaModelsClient(http, json, baseUrl = "http://127.0.0.1:11434")
    }

    private class FakeChatRepositoryForRagVm(
        private val rewriteResult: suspend (String) -> Result<String> = { Result.success(it) },
    ) : ChatRepository {
        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider,
        ) = emptyFlow<Result<String>>()

        override suspend fun extractFacts(
            messages: List<ChatMessage>,
            currentFacts: ChatFacts,
            provider: LLMProvider,
        ) = Result.success(ChatFacts())

        override suspend fun summarize(
            messages: List<ChatMessage>,
            previousSummary: String?,
            instruction: String,
            provider: LLMProvider,
        ) = Result.success("")

        override suspend fun analyzeTask(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider,
        ) = Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider,
        ) = Result.success(WorkingMemoryAnalysis())

        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String) = null
        override suspend fun deleteAgent(agentId: String) {}
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String) = emptyFlow<AgentState?>()

        override suspend fun rewriteQueryForRag(userQuery: String, provider: LLMProvider) =
            rewriteResult(userQuery)
    }

    private fun clientFixedEmbedding(dim: Int = 4): OllamaEmbeddingClient {
        val ones = List(dim) { 1.0 }
        val arr = ones.joinToString(",", prefix = "[", postfix = "]")
        val mockEngine = MockEngine {
            respond(
                content = """{"embeddings": [$arr]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        return OllamaEmbeddingClient(http, baseUrl = "http://127.0.0.1:11434")
    }

    private suspend fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            delay(1)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    private suspend fun waitUntilNotGenerating() {
        waitUntil { !viewModel.uiState.value.isGenerating }
    }

    @Test
    fun setChunkSize_and_overlap_coerceAndRecomputeChunks() = runBlocking {
        newViewModel(clientFixedEmbedding())
        viewModel.setSourceTextForTests("abcdefghijklm", sourceFileName = "t.txt")
        viewModel.setChunkSize(4)
        viewModel.setOverlap(1)
        val chunks = viewModel.uiState.value.chunks
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.text.length <= 4 })

        viewModel.setChunkSize(0)
        assertEquals(1, viewModel.uiState.value.chunkSize)

        viewModel.setOverlap(-3)
        assertEquals(0, viewModel.uiState.value.overlap)
    }

    @Test
    fun setChunkStrategy_updatesChunks() = runBlocking {
        newViewModel(clientFixedEmbedding())
        val text = "Para one.\n\nPara two."
        viewModel.setSourceTextForTests(text)
        viewModel.setChunkStrategy(ChunkStrategy.PARAGRAPH)
        viewModel.setChunkSize(100)
        viewModel.setOverlap(0)
        val byPara = viewModel.uiState.value.chunks
        assertEquals(2, byPara.size)

        viewModel.setChunkStrategy(ChunkStrategy.FIXED_WINDOW)
        viewModel.setChunkSize(8)
        viewModel.setOverlap(2)
        val fixed = viewModel.uiState.value.chunks
        assertTrue(fixed.size >= 2)
    }

    @Test
    fun generateEmbeddings_emptyChunks_setsError() = runBlocking {
        newViewModel(clientFixedEmbedding())
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()
        assertNull(viewModel.uiState.value.embeddings)
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun generateEmbeddings_progressAndEmbeddings() = runBlocking {
        newViewModel(clientFixedEmbedding(dim = 3))
        viewModel.setSourceTextForTests("abcdefghijklmnop")
        viewModel.setChunkSize(4)
        viewModel.setOverlap(2)
        val n = viewModel.uiState.value.chunks.size
        assertTrue(n >= 2)

        viewModel.generateEmbeddings()
        waitUntilNotGenerating()

        val s = viewModel.uiState.value
        assertFalse(s.isGenerating)
        assertNull(s.error)
        assertNotNull(s.embeddings)
        val emb = requireNotNull(s.embeddings)
        assertEquals(n, emb.size)
        assertEquals(n, s.generationTotal)
        assertEquals(n, s.generationDone)
        assertEquals(3, emb[0].size)
    }

    @Test
    fun generateEmbeddings_onFailure_clearsPartialEmbeddings() = runBlocking {
        var calls = 0
        val mockEngine = MockEngine {
            calls++
            if (calls >= 2) {
                throw RuntimeException("ollama down")
            }
            respond(
                content = """{"embeddings": [[1.0,0.0,0.0,1.0]]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        newViewModel(OllamaEmbeddingClient(http, baseUrl = "http://127.0.0.1:11434"))

        viewModel.setSourceTextForTests("abcdefghijklmnopqr")
        viewModel.setChunkSize(4)
        viewModel.setOverlap(2)
        assertTrue(viewModel.uiState.value.chunks.size >= 2)

        viewModel.generateEmbeddings()
        waitUntilNotGenerating()

        val s = viewModel.uiState.value
        assertFalse(s.isGenerating)
        assertNull(s.embeddings)
        assertNotNull(s.error)
    }

    @Test
    fun saveToDatabase_withoutEmbeddings_setsError() = runBlocking {
        newViewModel(clientFixedEmbedding())
        viewModel.setSourceTextForTests("abc")
        viewModel.saveToDatabase()
        waitUntil {
            viewModel.uiState.value.error != null || viewModel.uiState.value.saveMessage != null
        }
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun saveToDatabase_persistsAnd_setsSaveMessage() = runBlocking {
        newViewModel(clientFixedEmbedding(dim = 2))
        viewModel.setSourceTextForTests("hello rag", sourceFileName = "doc.txt", sourcePath = "c:/tmp/doc.txt")
        viewModel.setChunkSize(100)
        viewModel.setOverlap(0)
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()

        viewModel.saveToDatabase()
        waitUntil { viewModel.uiState.value.saveMessage != null || viewModel.uiState.value.error != null }

        val s = viewModel.uiState.value
        assertNull(s.error)
        assertNotNull(s.saveMessage)

        val docs = ragRepository.observeAllDocuments().first()
        assertEquals(1, docs.size)
        assertEquals("doc.txt", docs[0].sourceFileName)
    }

    @Test
    fun toggleDbDocumentExpand_loadsPreviewAndCollapse() = runBlocking {
        newViewModel(clientFixedEmbedding(dim = 2))
        val docId = Uuid.random().toString()
        val body = "stored body"
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "X",
                sourceFileName = "f.txt",
                sourcePath = "c:/f.txt",
                ollamaModel = "m",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = body,
            ),
            listOf(
                RagChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = docId,
                    chunkIndex = 0L,
                    startOffset = 0L,
                    endOffset = body.length.toLong(),
                    text = body,
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(floatArrayOf(1f, 0f)),
                )
            )
        )

        newViewModel(clientFixedEmbedding())
        viewModel.toggleDbDocumentExpand(docId)
        waitUntil { viewModel.uiState.value.expandedDbDocId == docId }
        assertEquals(docId, viewModel.uiState.value.expandedDbDocId)
        assertEquals(body, viewModel.uiState.value.dbPreviewText)
        assertEquals(1, viewModel.uiState.value.dbPreviewChunks.size)

        viewModel.toggleDbDocumentExpand(docId)
        waitUntil { viewModel.uiState.value.expandedDbDocId == null }
        assertNull(viewModel.uiState.value.expandedDbDocId)
        assertTrue(viewModel.uiState.value.dbPreviewChunks.isEmpty())
    }

    @Test
    fun deleteDbDocument_clearsExpandWhenSameId() = runBlocking {
        newViewModel(clientFixedEmbedding(dim = 2))
        val docId = Uuid.random().toString()
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "Y",
                sourceFileName = "y.txt",
                sourcePath = "c:/y.txt",
                ollamaModel = "m",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = "y",
            ),
            listOf(
                RagChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = docId,
                    chunkIndex = 0L,
                    startOffset = 0L,
                    endOffset = 1L,
                    text = "y",
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(floatArrayOf(1f, 0f)),
                )
            )
        )

        viewModel.toggleDbDocumentExpand(docId)
        waitUntil { viewModel.uiState.value.expandedDbDocId == docId }
        viewModel.deleteDbDocument(docId)
        waitUntil {
            viewModel.uiState.value.saveMessage != null || viewModel.uiState.value.error != null
        }

        assertNull(viewModel.uiState.value.expandedDbDocId)
        assertEquals("Удалено", viewModel.uiState.value.saveMessage)
        assertTrue(ragRepository.observeAllDocuments().first().isEmpty())
    }

    @Test
    fun dismissMessages_clearsErrorAndSaveMessage() = runBlocking {
        newViewModel(clientFixedEmbedding())
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()
        assertNotNull(viewModel.uiState.value.error)

        viewModel.dismissMessages()
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.saveMessage)
    }

    @Test
    fun setOllamaModel_updatesValue() = runBlocking {
        newViewModel(clientFixedEmbedding())
        viewModel.setOllamaModel("custom-model")
        assertEquals("custom-model", viewModel.uiState.value.ollamaModel)
    }

    @Test
    fun recomputeChunks_invalidOverlap_setsError() = runBlocking {
        newViewModel(clientFixedEmbedding())
        viewModel.setSourceTextForTests("abc")
        viewModel.setChunkSize(5)
        viewModel.setOverlap(5)
        val s = viewModel.uiState.value
        assertNotNull(s.error)
        assertTrue(s.chunks.isEmpty())
    }

    @Test
    fun saveToDatabase_secondSaveSameSource_showsReplacedMessage() = runBlocking {
        newViewModel(clientFixedEmbedding(dim = 2))
        viewModel.setSourceTextForTests("v1", sourceFileName = "same.txt", sourcePath = "c:/data/same.txt")
        viewModel.setOverlap(0)
        viewModel.setChunkSize(200)
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()
        viewModel.saveToDatabase()
        waitUntil { viewModel.uiState.value.saveMessage != null }
        viewModel.dismissMessages()

        viewModel.setSourceTextForTests("v2 text", sourceFileName = "same.txt", sourcePath = "c:/data/same.txt")
        viewModel.setChunkSize(200)
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()
        viewModel.saveToDatabase()
        waitUntil { viewModel.uiState.value.saveMessage != null }

        assertTrue(viewModel.uiState.value.saveMessage!!.contains("обновл"))
        assertEquals(1, ragRepository.observeAllDocuments().first().size)
    }

    @Test
    fun saveToDatabase_twoSavesWithoutFilePath_bothDocumentsPersist() = runBlocking {
        newViewModel(clientFixedEmbedding(dim = 2))
        viewModel.setSourceTextForTests("first", sourcePath = "")
        viewModel.setOverlap(0)
        viewModel.setChunkSize(50)
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()
        viewModel.saveToDatabase()
        waitUntil { viewModel.uiState.value.saveMessage != null }
        viewModel.dismissMessages()

        viewModel.setSourceTextForTests("second text longer", sourcePath = "")
        viewModel.setChunkSize(50)
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()
        viewModel.saveToDatabase()
        waitUntil { viewModel.uiState.value.saveMessage != null }

        assertEquals(2, ragRepository.observeAllDocuments().first().size)
    }

    @Test
    fun saveToDatabase_usesDefaultModelWhenBlank() = runBlocking {
        newViewModel(clientFixedEmbedding(dim = 2))
        viewModel.setSourceTextForTests("hi")
        viewModel.setOllamaModel("   ")
        viewModel.setOverlap(0)
        viewModel.setChunkSize(50)
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()
        viewModel.saveToDatabase()
        waitUntil { viewModel.uiState.value.saveMessage != null || viewModel.uiState.value.error != null }

        assertNull(viewModel.uiState.value.error)
        val doc = ragRepository.observeAllDocuments().first().single()
        assertEquals(OllamaDefaultModelName, doc.ollamaModel)
    }

    @Test
    fun init_loadsRagPipelineConfigFromDatabase() = runBlocking {
        val pipelineRepo = SqlDelightRagPipelineSettingsRepository(database, json)
        val saved = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Hybrid,
            recallTopK = 77,
            finalTopK = 4,
            minSimilarity = 0.3f,
        )
        pipelineRepo.saveConfig(saved)

        newViewModel(clientFixedEmbedding())
        waitUntil { viewModel.uiState.value.ragPipelineConfig.recallTopK == 77 }
        val s = viewModel.uiState.value
        assertEquals(RagPipelineMode.Hybrid, s.ragPipelineConfig.pipelineMode)
        assertEquals(4, s.ragPipelineConfig.finalTopK)
        assertEquals(0.3f, s.ragPipelineConfig.minSimilarity!!, 1e-5f)
        assertEquals(saved, s.savedRagPipelineConfig)
    }

    @Test
    fun updateRagPipeline_and_saveRagPipeline_persists() = runBlocking {
        newViewModel(clientFixedEmbedding())
        waitUntil { viewModel.uiState.value.ragPipelineConfig == RagRetrievalConfig.Default }

        viewModel.updateRagPipeline { it.copy(recallTopK = 42, queryRewriteEnabled = true) }
        assertEquals(42, viewModel.uiState.value.ragPipelineConfig.recallTopK)
        assertTrue(viewModel.uiState.value.ragPipelineConfig.queryRewriteEnabled)

        viewModel.saveRagPipeline()
        waitUntil { viewModel.uiState.value.saveMessage != null }

        val fromDb = SqlDelightRagPipelineSettingsRepository(database, json).getConfig()
        assertEquals(42, fromDb.recallTopK)
        assertTrue(fromDb.queryRewriteEnabled)
        assertEquals(viewModel.uiState.value.ragPipelineConfig, viewModel.uiState.value.savedRagPipelineConfig)
    }

    @Test
    fun refreshOllamaModelList_success() = runBlocking {
        newViewModel(clientFixedEmbedding(), models = clientTagsWithModels("m1", "m2"))
        viewModel.refreshOllamaModelList()
        waitUntil { viewModel.uiState.value.ollamaLocalModels.size == 2 }
        assertNull(viewModel.uiState.value.ollamaModelsLoadError)
        assertEquals(listOf("m1", "m2"), viewModel.uiState.value.ollamaLocalModels)
    }

    @Test
    fun refreshOllamaModelList_failure_setsError() = runBlocking {
        newViewModel(clientFixedEmbedding(), models = clientTagsFailing())
        viewModel.refreshOllamaModelList()
        waitUntil { viewModel.uiState.value.ollamaModelsLoadError != null }
        assertTrue(viewModel.uiState.value.ollamaLocalModels.isEmpty())
    }

    @Test
    fun runRagPreviewDryRun_emptyQuery_setsError() = runBlocking {
        newViewModel(clientFixedEmbedding())
        viewModel.setRagPreviewQuery("   ")
        viewModel.runRagPreviewDryRun()
        assertEquals("Введите тестовый запрос", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.ragPreviewLoading)
    }

    @Test
    fun runRagPreviewDryRun_withIndexedChunks_showsPreview() = runBlocking {
        val docId = Uuid.random().toString()
        val body = "hello rag body"
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "DocTitle",
                sourceFileName = "preview.txt",
                sourcePath = "c:/preview.txt",
                ollamaModel = "embed-model",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = body,
            ),
            listOf(
                RagChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = docId,
                    chunkIndex = 0L,
                    startOffset = 0L,
                    endOffset = body.length.toLong(),
                    text = body,
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(floatArrayOf(1f, 1f)),
                )
            )
        )
        newViewModel(clientFixedEmbedding(dim = 2))
        viewModel.setRagPreviewQuery("search me")
        viewModel.runRagPreviewDryRun()
        waitUntil { !viewModel.uiState.value.ragPreviewLoading }
        val text = requireNotNull(viewModel.uiState.value.ragPreviewText)
        assertTrue(text.contains("Использован RAG"))
        assertTrue(text.contains("DocTitle") || text.contains("preview.txt"))
    }

    @Test
    fun runRagPreviewDryRun_queryRewrite_showsOriginalAndRetrievalQuery() = runBlocking {
        val docId = Uuid.random().toString()
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "T",
                sourceFileName = "rw.txt",
                sourcePath = "c:/rw.txt",
                ollamaModel = "embed-model",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = "x",
            ),
            listOf(
                RagChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = docId,
                    chunkIndex = 0L,
                    startOffset = 0L,
                    endOffset = 1L,
                    text = "chunk",
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(floatArrayOf(1f, 1f)),
                )
            )
        )
        newViewModel(
            clientFixedEmbedding(dim = 2),
            chat = FakeChatRepositoryForRagVm(
                rewriteResult = { Result.success("переписанный запрос") },
            ),
        )
        viewModel.updateRagPipeline { it.copy(queryRewriteEnabled = true) }
        viewModel.setRagPreviewQuery("исходный")
        viewModel.runRagPreviewDryRun()
        waitUntil { !viewModel.uiState.value.ragPreviewLoading }
        val text = requireNotNull(viewModel.uiState.value.ragPreviewText)
        assertTrue(text.contains("Исходный запрос"))
        assertTrue(text.contains("переписанный"))
        assertTrue(text.contains("Запрос для поиска"))
    }

    @Test
    fun runRagPreviewDryRun_globalRagOff_stillRuns() = runBlocking {
        val docId = Uuid.random().toString()
        val body = "hello rag body"
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "DocTitle",
                sourceFileName = "preview.txt",
                sourcePath = "c:/preview.txt",
                ollamaModel = "embed-model",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = body,
            ),
            listOf(
                RagChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = docId,
                    chunkIndex = 0L,
                    startOffset = 0L,
                    endOffset = body.length.toLong(),
                    text = body,
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(floatArrayOf(1f, 1f)),
                )
            )
        )
        newViewModel(clientFixedEmbedding(dim = 2))
        viewModel.updateRagPipeline { it.copy(globalRagEnabled = false) }
        viewModel.setRagPreviewQuery("search me")
        viewModel.runRagPreviewDryRun()
        waitUntil { !viewModel.uiState.value.ragPreviewLoading }
        assertNull(viewModel.uiState.value.error)
        val text = requireNotNull(viewModel.uiState.value.ragPreviewText)
        assertTrue(text.contains("Использован RAG"))
    }

    @Test
    fun generateEmbeddings_globalRagOff_succeeds() = runBlocking {
        val cfgOff = RagRetrievalConfig(globalRagEnabled = false)
        assertFalse(cfgOff.globalRagEnabled)
        newViewModel(clientFixedEmbedding())
        delay(50L)
        viewModel.setRagPipelineConfigForTests(cfgOff)
        assertFalse(viewModel.uiState.value.ragPipelineConfig.globalRagEnabled)
        viewModel.setSourceTextForTests("hello world long enough")
        viewModel.setChunkSize(50)
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()
        assertNull(viewModel.uiState.value.error)
        assertNotNull(viewModel.uiState.value.embeddings)
    }

    @Test
    fun saveToDatabase_globalRagOff_succeeds() = runBlocking {
        newViewModel(clientFixedEmbedding())
        delay(50L)
        viewModel.setRagPipelineConfigForTests(RagRetrievalConfig(globalRagEnabled = false))
        viewModel.setSourceTextForTests("hello world long enough")
        viewModel.setChunkSize(50)
        viewModel.generateEmbeddings()
        waitUntilNotGenerating()
        assertNotNull(viewModel.uiState.value.embeddings)
        viewModel.saveToDatabase()
        waitUntil { viewModel.uiState.value.saveMessage != null }
        assertNull(viewModel.uiState.value.error)
        assertNotNull(viewModel.uiState.value.saveMessage)
    }
}
