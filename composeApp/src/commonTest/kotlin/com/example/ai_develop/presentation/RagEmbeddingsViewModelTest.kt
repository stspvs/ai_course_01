@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.example.ai_develop.presentation

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.ai_develop.data.EmbeddingFloatCodec
import com.example.ai_develop.data.OllamaEmbeddingClient
import com.example.ai_develop.data.RagEmbeddingRepository
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
import com.example.ai_develop.domain.ChunkStrategy
import com.example.ai_develop.domain.OllamaDefaultModelName
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

    private fun newViewModel(ollama: OllamaEmbeddingClient) {
        viewModel = RagEmbeddingsViewModel(ollama, ragRepository)
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
}
