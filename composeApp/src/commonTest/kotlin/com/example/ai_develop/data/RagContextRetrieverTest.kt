@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.example.ai_develop.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
import com.example.ai_develop.domain.rag.RagPipelineMode
import com.example.ai_develop.domain.rag.RagRetrievalConfig
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
import kotlinx.coroutines.test.runTest
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

/**
 * Интеграционные тесты [RagContextRetriever]: пустая база, пороги, режимы пайплайна, размерности.
 */
class RagContextRetrieverTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var driver: SqlDriver
    private lateinit var database: AgentDatabase
    private lateinit var ragRepository: RagEmbeddingRepository

    @BeforeTest
    fun setup() {
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
        driver.close()
    }

    private fun embeddingClientFixedQueryVector(): OllamaEmbeddingClient {
        val mockEngine = MockEngine {
            respond(
                content = """{"embeddings": [[1.0, 0.0]]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        return OllamaEmbeddingClient(http, baseUrl = "http://127.0.0.1:11434")
    }

    private fun embeddingClientFailing(): OllamaEmbeddingClient {
        val mockEngine = MockEngine {
            throw RuntimeException("network down")
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        return OllamaEmbeddingClient(http, baseUrl = "http://127.0.0.1:11434")
    }

    private fun rerankClientStub(response: String = "5"): OllamaRagRerankClient {
        val mockEngine = MockEngine {
            respond(
                content = """{"response":"$response"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        return OllamaRagRerankClient(http, json, baseUrl = "http://127.0.0.1:11434")
    }

    private suspend fun insertTwoOrthogonalChunks(sameModel: String = "m1") {
        val docId = Uuid.random().toString()
        val e1 = floatArrayOf(1f, 0f)
        val e2 = floatArrayOf(0f, 1f)
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "T",
                sourceFileName = "f.txt",
                sourcePath = "c:/f.txt",
                ollamaModel = sameModel,
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = "ab",
            ),
            listOf(
                RagChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = docId,
                    chunkIndex = 0L,
                    startOffset = 0L,
                    endOffset = 1L,
                    text = "alpha match",
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(e1),
                ),
                RagChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = docId,
                    chunkIndex = 1L,
                    startOffset = 1L,
                    endOffset = 2L,
                    text = "beta unrelated",
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(e2),
                ),
            )
        )
    }

    @Test
    fun retrieve_blankQuery_returnsNull() = runTest {
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        assertNull(sut.retrieve("orig", "   ", RagRetrievalConfig.Default, rewriteApplied = false))
        assertNull(sut.retrieve("orig", "\t\n", RagRetrievalConfig.Default, rewriteApplied = false))
    }

    @Test
    fun retrieve_noChunks_returnsEmptyWithDebug() = runTest {
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val r = sut.retrieve("q1", "hello", RagRetrievalConfig.Default, rewriteApplied = true)
        assertNotNull(r)
        assertEquals("", r.contextText)
        assertFalse(r.attribution.used)
        val dbg0 = r.attribution.debug
        assertNotNull(dbg0)
        assertEquals("В базе нет проиндексированных чанков", dbg0.emptyReason)
        assertTrue(dbg0.rewriteApplied)
    }

    @Test
    fun retrieve_embedFailure_returnsEmptyWithReason() = runTest {
        insertTwoOrthogonalChunks()
        val sut = RagContextRetriever(
            embeddingClientFailing(),
            ragRepository,
            rerankClientStub(),
        )
        val r = sut.retrieve("q", "hello", RagRetrievalConfig.Default, rewriteApplied = false)
        assertNotNull(r)
        assertFalse(r.attribution.used)
        val dbg1 = r.attribution.debug
        assertNotNull(dbg1)
        assertEquals(
            "Не удалось получить эмбеддинг запроса (Ollama или размерность)",
            dbg1.emptyReason,
        )
    }

    @Test
    fun retrieve_baseline_ignoresMinSimilarity() = runTest {
        insertTwoOrthogonalChunks()
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Baseline,
            recallTopK = 10,
            finalTopK = 10,
            minSimilarity = 0.99f,
        )
        val r = sut.retrieve("q", "x", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertTrue(r.attribution.used)
        assertEquals(2, r.attribution.sources.size)
    }

    @Test
    fun retrieve_threshold_allBelowMin_returnsEmptyWithReason() = runTest {
        val docId = Uuid.random().toString()
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "T",
                sourceFileName = "f.txt",
                sourcePath = "c:/f.txt",
                ollamaModel = "m1",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = "a",
            ),
            listOf(
                RagChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = docId,
                    chunkIndex = 0L,
                    startOffset = 0L,
                    endOffset = 1L,
                    text = "low",
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(floatArrayOf(0f, 1f)),
                )
            )
        )
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Threshold,
            recallTopK = 10,
            finalTopK = 10,
            minSimilarity = 0.99f,
        )
        val r = sut.retrieve("q", "x", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertFalse(r.attribution.used)
        val dbg = r.attribution.debug
        assertNotNull(dbg)
        assertEquals("После фильтрации не осталось релевантных чанков", dbg.emptyReason)
    }

    @Test
    fun retrieve_threshold_filtersLowCosine() = runTest {
        insertTwoOrthogonalChunks()
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Threshold,
            recallTopK = 10,
            finalTopK = 10,
            minSimilarity = 0.5f,
        )
        val r = sut.retrieve("q", "x", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertTrue(r.attribution.used)
        assertEquals(1, r.attribution.sources.size)
        assertTrue(r.contextText.contains("alpha match"))
    }

    @Test
    fun retrieve_hybrid_includesLexicalRerank() = runTest {
        insertTwoOrthogonalChunks()
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Hybrid,
            recallTopK = 10,
            finalTopK = 2,
            hybridLexicalWeight = 0.5f,
        )
        val r = sut.retrieve("q", "alpha match", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertTrue(r.attribution.used)
        assertNotNull(r.attribution.sources[0].finalScore)
    }

    @Test
    fun retrieve_llmRerank_usesModelWhenSet() = runTest {
        insertTwoOrthogonalChunks()
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub("9"),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.LlmRerank,
            recallTopK = 10,
            finalTopK = 2,
            llmRerankOllamaModel = "rank-model",
            llmRerankMaxCandidates = 2,
        )
        val r = sut.retrieve("q", "x", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertTrue(r.attribution.used)
        val dbg2 = r.attribution.debug
        assertNotNull(dbg2)
        assertEquals("rank-model", dbg2.llmRerankModel)
        assertNotNull(r.attribution.sources[0].finalScore)
    }

    @Test
    fun retrieve_llmRerank_blankModel_fallsBackToCosineOrder() = runTest {
        insertTwoOrthogonalChunks()
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.LlmRerank,
            recallTopK = 10,
            finalTopK = 2,
            llmRerankOllamaModel = "   ",
        )
        val r = sut.retrieve("q", "x", cfg, rewriteApplied = false)
        assertNotNull(r)
        val dbg3 = r.attribution.debug
        assertNotNull(dbg3)
        assertNull(dbg3.llmRerankModel)
    }

    @Test
    fun retrieve_skipsMismatchedEmbeddingDim() = runTest {
        val docId = Uuid.random().toString()
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "T",
                sourceFileName = "f.txt",
                sourcePath = "c:/f.txt",
                ollamaModel = "m1",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = "a",
            ),
            listOf(
                RagChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = docId,
                    chunkIndex = 0L,
                    startOffset = 0L,
                    endOffset = 1L,
                    text = "bad dim",
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(floatArrayOf(1f, 0f, 0f)),
                )
            )
        )
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val r = sut.retrieve("q", "x", RagRetrievalConfig.Default, rewriteApplied = false)
        assertNotNull(r)
        assertFalse(r.attribution.used)
    }

    @Test
    fun answerRelevanceThreshold_belowBestScore_emptiesContextAndFlagsInsufficient() = runTest {
        insertTwoOrthogonalChunks()
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Baseline,
            answerRelevanceThreshold = 1.01f,
        )
        val r = sut.retrieve("q", "hello", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertEquals("", r.contextText)
        assertFalse(r.attribution.used)
        assertTrue(r.attribution.insufficientRelevance)
        assertTrue(r.attribution.sources.isEmpty())
        assertTrue(r.attribution.debug?.emptyReason?.contains("answerRelevanceThreshold") == true)
    }

    @Test
    fun recallAndFinalTopK_respectLimits() = runTest {
        insertTwoOrthogonalChunks()
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Baseline,
            recallTopK = 1,
            finalTopK = 1,
        )
        val r = sut.retrieve("q", "x", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertTrue(r.attribution.used)
        assertEquals(1, r.attribution.sources.size)
        val dbg4 = r.attribution.debug
        assertNotNull(dbg4)
        assertEquals(1, dbg4.candidatesAfterRecall)
    }

    @Test
    fun stress_manyChunks_recallCapStable() = runTest {
        val docId = Uuid.random().toString()
        val shared = floatArrayOf(1f, 0f)
        val chunks = (0 until 40).map { i ->
            RagChunkEntity(
                id = Uuid.random().toString(),
                documentId = docId,
                chunkIndex = i.toLong(),
                startOffset = 0L,
                endOffset = 1L,
                text = "chunk $i",
                embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(shared),
            )
        }
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "Big",
                sourceFileName = "big.txt",
                sourcePath = "c:/big.txt",
                ollamaModel = "m1",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = "x",
            ),
            chunks
        )
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Baseline,
            recallTopK = 5,
            finalTopK = 3,
        )
        val r = sut.retrieve("q", "stress", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertTrue(r.attribution.used)
        assertEquals(3, r.attribution.sources.size)
        val dbg5 = r.attribution.debug
        assertNotNull(dbg5)
        assertEquals(5, dbg5.candidatesAfterRecall)
    }

    @Test
    fun retrieve_scanAllChunks_includesAllChunksDespiteSmallTopK() = runTest {
        val docId = Uuid.random().toString()
        val shared = floatArrayOf(1f, 0f)
        val chunks = (0 until 40).map { i ->
            RagChunkEntity(
                id = Uuid.random().toString(),
                documentId = docId,
                chunkIndex = i.toLong(),
                startOffset = 0L,
                endOffset = 1L,
                text = "chunk $i",
                embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(shared),
            )
        }
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "Big",
                sourceFileName = "big.txt",
                sourcePath = "c:/big.txt",
                ollamaModel = "m1",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = "x",
            ),
            chunks
        )
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub(),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Baseline,
            recallTopK = 3,
            finalTopK = 3,
            scanAllChunks = true,
        )
        val r = sut.retrieve("q", "stress", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertTrue(r.attribution.used)
        assertEquals(40, r.attribution.sources.size)
        val dbg = r.attribution.debug
        assertNotNull(dbg)
        assertEquals(40, dbg.candidatesAfterRecall)
        assertEquals(40, dbg.finalTopK)
    }

    @Test
    fun retrieve_llmRerank_keepsTailAfterRerankedHead() = runTest {
        val docId = Uuid.random().toString()
        val shared = floatArrayOf(1f, 0f)
        val chunkEntities = (0 until 5).map { i ->
            RagChunkEntity(
                id = Uuid.random().toString(),
                documentId = docId,
                chunkIndex = i.toLong(),
                startOffset = 0L,
                endOffset = 1L,
                text = "c$i",
                embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(shared),
            )
        }
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "Multi",
                sourceFileName = "m.txt",
                sourcePath = "c:/m.txt",
                ollamaModel = "m1",
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = "x",
            ),
            chunkEntities
        )
        val sut = RagContextRetriever(
            embeddingClientFixedQueryVector(),
            ragRepository,
            rerankClientStub("9"),
        )
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.LlmRerank,
            recallTopK = 10,
            finalTopK = 5,
            llmRerankOllamaModel = "rank-model",
            llmRerankMaxCandidates = 2,
        )
        val r = sut.retrieve("q", "x", cfg, rewriteApplied = false)
        assertNotNull(r)
        assertTrue(r.attribution.used)
        assertEquals(5, r.attribution.sources.size)
    }
}
