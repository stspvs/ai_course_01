@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.example.ai_develop.domain.agent
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.ai_develop.data.EmbeddingFloatCodec
import com.example.ai_develop.data.OllamaEmbeddingClient
import com.example.ai_develop.data.OllamaRagRerankClient
import com.example.ai_develop.data.RagContextRetriever
import com.example.ai_develop.data.RagEmbeddingRepository
import com.example.ai_develop.data.SqlDelightRagPipelineSettingsRepository
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Связка [AutonomousAgent] + RAG: режим JSON, атрибуция, rewrite, пустая база.
 */
class AutonomousAgentRagTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val agentId = "rag_integration_agent"

    private lateinit var driver: SqlDriver
    private lateinit var database: AgentDatabase
    private lateinit var ragRepository: RagEmbeddingRepository
    private lateinit var settingsRepository: SqlDelightRagPipelineSettingsRepository

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
        settingsRepository = SqlDelightRagPipelineSettingsRepository(database, json)
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

    private fun rerankClientStub(): OllamaRagRerankClient {
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

    private class RagMockChatRepository : ChatRepository {
        val agentStateMap = mutableMapOf<String, AgentState>()
        private val _agentUpdates = MutableSharedFlow<AgentState>(replay = 1)
        var rewriteQueryForRagCalls = 0
        var lastRewriteInput: String? = null
        var rewriteResult: String = "rewritten query"

        override suspend fun rewriteQueryForRag(userQuery: String, provider: LLMProvider): Result<String> {
            rewriteQueryForRagCalls++
            lastRewriteInput = userQuery
            return Result.success(rewriteResult)
        }

        override fun chatStreaming(
            m: List<ChatMessage>,
            s: String,
            mt: Int,
            t: Double,
            sw: String,
            j: Boolean,
            p: LLMProvider
        ) = flowOf(Result.success("Mock"))

        override suspend fun saveAgentState(state: AgentState) {
            agentStateMap[state.agentId] = state
            _agentUpdates.emit(state)
        }

        override suspend fun getAgentState(id: String) = agentStateMap[id]

        override fun observeAgentState(id: String): Flow<AgentState?> =
            _agentUpdates.filter { it.agentId == id }.onStart { agentStateMap[id]?.let { emit(it) } }

        override suspend fun deleteAgent(agentId: String) {
            agentStateMap.remove(agentId)
        }

        override suspend fun extractFacts(m: List<ChatMessage>, cf: ChatFacts, p: LLMProvider) =
            Result.success(ChatFacts())

        override suspend fun getProfile(id: String) = null
        override suspend fun saveProfile(id: String, p: UserProfile) {}
        override suspend fun getInvariants(id: String, s: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(i: Invariant) {}
        override suspend fun summarize(m: List<ChatMessage>, ps: String?, i: String, p: LLMProvider) =
            Result.success("Summary")

        override suspend fun analyzeTask(m: List<ChatMessage>, i: String, p: LLMProvider) =
            Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(m: List<ChatMessage>, i: String, p: LLMProvider) =
            Result.success(WorkingMemoryAnalysis())
    }

    private class RagFakeAgentEngine(
        repo: ChatRepository,
        mem: ChatMemoryManager,
    ) : AgentEngine(repo, mem, emptyList()) {
        val responses = mutableListOf<List<String>>()

        fun queueResponse(chunks: List<String>) {
            responses.add(chunks)
        }

        override fun streamFromPrepared(agent: Agent, prepared: PreparedLlmRequest): kotlinx.coroutines.flow.Flow<String> =
            flow {
                val chunks = if (responses.isNotEmpty()) responses.removeAt(0) else listOf("Default")
                for (chunk in chunks) {
                    emit(chunk)
                    yield()
                }
            }
    }

    private fun createRetriever(): RagContextRetriever =
        RagContextRetriever(embeddingClientFixedQueryVector(), ragRepository, rerankClientStub())

    private suspend fun insertSingleChunk(
        chunkId: String,
        docId: String,
        text: String,
        embedding: FloatArray,
        model: String = "m1",
    ) {
        ragRepository.insertDocumentWithChunks(
            RagDocumentEntity(
                id = docId,
                title = "T",
                sourceFileName = "f.txt",
                sourcePath = "c:/f.txt",
                ollamaModel = model,
                chunkSize = 10L,
                overlap = 0L,
                chunkStrategy = "FIXED_WINDOW",
                createdAt = 1L,
                fullText = text,
            ),
            listOf(
                RagChunkEntity(
                    id = chunkId,
                    documentId = docId,
                    chunkIndex = 0L,
                    startOffset = 0L,
                    endOffset = text.length.toLong(),
                    text = text,
                    embeddingBlob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(embedding),
                )
            )
        )
    }

    @Test
    fun ragDisabled_noJsonMode_noStructuredPayload() = runTest {
        val repo = RagMockChatRepository()
        repo.agentStateMap[agentId] = AgentState(agentId = agentId, name = "RAG off", ragEnabled = false)
        val engine = RagFakeAgentEngine(repo, ChatMemoryManager())
        engine.queueResponse(listOf("plain reply"))
        val agent = AutonomousAgent(
            agentId,
            repo,
            engine,
            backgroundScope,
            null,
            createRetriever(),
            settingsRepository,
        )
        advanceUntilIdle()
        agent.sendMessage("hello").collect { }
        advanceUntilIdle()
        val assistant = agent.uiState.value.agent?.messages?.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertEquals("plain reply", assistant.message.trim())
        assertFalse(assistant.llmRequestSnapshot?.isJsonMode == true)
        agent.dispose()
    }

    @Test
    fun ragEnabled_emptyDb_ungrounded_noJsonMode_plainReply() = runTest {
        val repo = RagMockChatRepository()
        repo.agentStateMap[agentId] = AgentState(agentId = agentId, name = "RAG on", ragEnabled = true)
        val engine = RagFakeAgentEngine(repo, ChatMemoryManager())
        engine.queueResponse(listOf("В базе нет данных, отвечаю без цитат."))
        val agent = AutonomousAgent(
            agentId,
            repo,
            engine,
            backgroundScope,
            null,
            createRetriever(),
            settingsRepository,
        )
        advanceUntilIdle()
        agent.sendMessage("query").collect { }
        advanceUntilIdle()
        val assistant = agent.uiState.value.agent?.messages?.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertFalse(assistant.llmRequestSnapshot?.isJsonMode == true)
        val attr = assistant.llmRequestSnapshot?.ragAttribution
        assertNotNull(attr)
        assertFalse(attr.used)
        assertNotNull(attr.debug)
        assertEquals("В базе нет проиндексированных чанков", attr.debug?.emptyReason)
        assertEquals(null, assistant.llmRequestSnapshot?.ragStructuredContent)
        assertEquals("В базе нет данных, отвечаю без цитат.", assistant.message.trim())
        agent.dispose()
    }

    @Test
    fun ragEnabled_withChunk_groundedJson_structuredPayload() = runTest {
        val chunkId = Uuid.random().toString()
        val docId = Uuid.random().toString()
        insertSingleChunk(chunkId, docId, "alpha match", floatArrayOf(1f, 0f))
        val repo = RagMockChatRepository()
        repo.agentStateMap[agentId] = AgentState(agentId = agentId, name = "RAG on", ragEnabled = true)
        val engine = RagFakeAgentEngine(repo, ChatMemoryManager())
        val jsonOut =
            """{"answer":"Ответ","sources":[{"source":"f.txt","chunk_id":"$chunkId","chunk_index":0}],"quotes":[{"text":"alpha","chunk_id":"$chunkId"}]}"""
        engine.queueResponse(listOf(jsonOut))
        val agent = AutonomousAgent(
            agentId,
            repo,
            engine,
            backgroundScope,
            null,
            createRetriever(),
            settingsRepository,
        )
        advanceUntilIdle()
        agent.sendMessage("match").collect { }
        advanceUntilIdle()
        val assistant = agent.uiState.value.agent?.messages?.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertTrue(assistant.llmRequestSnapshot?.isJsonMode == true)
        val attr = assistant.llmRequestSnapshot?.ragAttribution
        assertNotNull(attr)
        assertTrue(attr.used)
        assertNotNull(assistant.llmRequestSnapshot?.ragStructuredContent)
        assertEquals("Ответ", assistant.llmRequestSnapshot?.ragStructuredContent?.answer)
        agent.dispose()
    }

    @Test
    fun queryRewrite_callsRepositoryAndUsesRetrievalQuery() = runTest {
        settingsRepository.saveConfig(
            RagRetrievalConfig.Default.copy(
                queryRewriteEnabled = true,
            )
        )
        val chunkId = Uuid.random().toString()
        val docId = Uuid.random().toString()
        insertSingleChunk(chunkId, docId, "alpha match", floatArrayOf(1f, 0f))
        val repo = RagMockChatRepository()
        repo.rewriteResult = "expanded match text"
        repo.agentStateMap[agentId] = AgentState(agentId = agentId, name = "RAG on", ragEnabled = true)
        val engine = RagFakeAgentEngine(repo, ChatMemoryManager())
        val jsonOut =
            """{"answer":"ok","sources":[{"source":"f.txt","chunk_id":"$chunkId","chunk_index":0}],"quotes":[{"text":"alpha","chunk_id":"$chunkId"}]}"""
        engine.queueResponse(listOf(jsonOut))
        val agent = AutonomousAgent(
            agentId,
            repo,
            engine,
            backgroundScope,
            null,
            createRetriever(),
            settingsRepository,
        )
        advanceUntilIdle()
        agent.sendMessage("short").collect { }
        advanceUntilIdle()
        assertEquals(1, repo.rewriteQueryForRagCalls)
        assertEquals("short", repo.lastRewriteInput)
        val assistant = agent.uiState.value.agent?.messages?.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertTrue(assistant.llmRequestSnapshot?.ragAttribution?.debug?.rewriteApplied == true)
        agent.dispose()
    }

    @Test
    fun globalRagDisabled_skipsRewriteAndRag_despiteAgentRagOn() = runTest {
        settingsRepository.saveConfig(
            RagRetrievalConfig.Default.copy(
                globalRagEnabled = false,
                queryRewriteEnabled = true,
            )
        )
        val chunkId = Uuid.random().toString()
        val docId = Uuid.random().toString()
        insertSingleChunk(chunkId, docId, "alpha match", floatArrayOf(1f, 0f))
        val repo = RagMockChatRepository()
        repo.agentStateMap[agentId] = AgentState(agentId = agentId, name = "RAG on", ragEnabled = true)
        val engine = RagFakeAgentEngine(repo, ChatMemoryManager())
        engine.queueResponse(listOf("plain reply"))
        val agent = AutonomousAgent(
            agentId,
            repo,
            engine,
            backgroundScope,
            null,
            createRetriever(),
            settingsRepository,
        )
        advanceUntilIdle()
        agent.sendMessage("hello").collect { }
        advanceUntilIdle()
        assertEquals(0, repo.rewriteQueryForRagCalls)
        val assistant = agent.uiState.value.agent?.messages?.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertEquals("plain reply", assistant.message.trim())
        assertFalse(assistant.llmRequestSnapshot?.isJsonMode == true)
        agent.dispose()
    }
}
