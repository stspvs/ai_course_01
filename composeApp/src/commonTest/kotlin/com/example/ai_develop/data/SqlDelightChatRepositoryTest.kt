package com.example.ai_develop.data

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.domain.*
import com.example.aidevelop.database.AgentMessageEntity
import com.example.aidevelop.database.AgentStateEntity
import com.example.aidevelop.database.InvariantEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightChatRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: AgentDatabase
    private lateinit var driver: SqlDriver
    private lateinit var networkRepository: FakeNetworkRepository
    private lateinit var repository: SqlDelightChatRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgentDatabase.Schema.create(driver)
        
        val stageAdapter = EnumColumnAdapter<AgentStage>()
        
        db = AgentDatabase(
            driver = driver,
            AgentMessageEntityAdapter = AgentMessageEntity.Adapter(stageAdapter),
            AgentStateEntityAdapter = AgentStateEntity.Adapter(stageAdapter),
            InvariantEntityAdapter = InvariantEntity.Adapter(stageAdapter)
        )
        
        networkRepository = FakeNetworkRepository()
        repository = SqlDelightChatRepository(db, networkRepository)
    }

    @After
    fun tearDown() {
        driver.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `saveAgentState stores all fields correctly`() = runTest {
        val state = createFullState("agent-1")
        repository.saveAgentState(state)

        val retrieved = repository.getAgentState("agent-1")
        assertEquals(state, retrieved)
    }

    @Test
    fun `saveAgentState corner cases validation`() = runTest {
        val cases = listOf(
            createFullState("c1").copy(plan = AgentPlan(emptyList())),
            createFullState("c2").copy(workingMemory = WorkingMemory()),
            createFullState("c3").copy(systemPrompt = ""),
            createFullState("c4").copy(stopWord = ""),
            createFullState("c5").copy(temperature = 0.0),
            createFullState("c6").copy(temperature = 1.0),
            createFullState("c7").copy(temperature = 1.5),
            createFullState("c8").copy(maxTokens = 0),
            createFullState("c9").copy(maxTokens = Int.MAX_VALUE)
        )

        cases.forEach { state ->
            repository.saveAgentState(state)
            assertEquals(state, repository.getAgentState(state.agentId), "Failed for ${state.agentId}")
        }
    }

    @Test
    fun `getAgentState returns null when record missing`() = runTest {
        assertNull(repository.getAgentState("missing"))
    }

    @Test
    fun `getAgentState handles empty JSON fields with defaults`() = runTest {
        db.agentDatabaseQueries.saveAgentState(
            agentId = "empty",
            name = "Default",
            systemPrompt = "",
            temperature = 0.5,
            maxTokens = 100,
            stopWord = "",
            currentStage = AgentStage.PLANNING,
            currentStepId = null,
            planJson = "{\"steps\":[]}",
            memoryStrategyJson = "",
            workingMemoryJson = ""
        )

        val state = repository.getAgentState("empty")
        assertNotNull(state)
        assertTrue(state.memoryStrategy is ChatMemoryStrategy.SlidingWindow)
        assertEquals(10, (state.memoryStrategy as ChatMemoryStrategy.SlidingWindow).windowSize)
        assertEquals(WorkingMemory(), state.workingMemory)
    }

    @Test
    fun `getAgentState handles corrupted JSON`() = runTest {
        db.agentDatabaseQueries.saveAgentState(
            agentId = "corrupted",
            name = "Bad JSON",
            systemPrompt = "",
            temperature = 0.5,
            maxTokens = 100,
            stopWord = "",
            currentStage = AgentStage.PLANNING,
            currentStepId = null,
            planJson = "{invalid_json}",
            memoryStrategyJson = "",
            workingMemoryJson = ""
        )

        assertFails {
            repository.getAgentState("corrupted")
        }
    }

    @Test
    fun `observeAgentState emits new states on updates`() = runTest {
        val agentId = "obs-1"
        repository.observeAgentState(agentId).test {
            assertNull(awaitItem())

            val state1 = createFullState(agentId).copy(name = "Initial")
            repository.saveAgentState(state1)
            assertEquals("Initial", awaitItem()?.name)

            val state2 = state1.copy(name = "Updated")
            repository.saveAgentState(state2)
            assertEquals("Updated", awaitItem()?.name)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeAgentState cancels correctly`() = runTest {
        val agentId = "cancel-test"
        val job = launch {
            repository.observeAgentState(agentId).collect { }
        }
        yield()
        assertTrue(job.isActive)
        job.cancel()
        yield()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `getProfile and saveProfile roundtrip`() = runTest {
        val agentId = "p1"
        val profile = UserProfile(
            preferences = "Pref",
            constraints = "Cons",
            memoryModelProvider = LLMProvider.DeepSeek("ds-model")
        )

        repository.saveProfile(agentId, profile)
        assertEquals(profile, repository.getProfile(agentId))
    }

    @Test
    fun `getInvariants filtered by stage`() = runTest {
        val inv1 = Invariant("1", "rule 1", AgentStage.PLANNING, true)
        val inv2 = Invariant("2", "rule 2", AgentStage.EXECUTION, true)
        
        repository.saveInvariant(inv1)
        repository.saveInvariant(inv2)
        
        val planningInvariants = repository.getInvariants("default", AgentStage.PLANNING)
        assertEquals(1, planningInvariants.size)
        assertEquals("rule 1", planningInvariants[0].rule)
    }

    @Test
    fun `delegation - all network calls forwarded`() = runTest {
        val messages = listOf(ChatMessage(message = "test"))
        val provider = LLMProvider.Yandex()
        
        repository.extractFacts(messages, ChatFacts(), provider)
        assertTrue(networkRepository.extractFactsCalled)

        repository.summarize(messages, "prev", "inst", provider)
        assertTrue(networkRepository.summarizeCalled)

        repository.analyzeTask(messages, "inst", provider)
        assertTrue(networkRepository.analyzeTaskCalled)

        repository.analyzeWorkingMemory(messages, "inst", provider)
        assertTrue(networkRepository.analyzeWorkingMemoryCalled)
    }

    @Test
    fun `concurrency stress test - rapid parallel updates`() = runTest {
        val agentId = "concurrent-agent"
        val count = 1000
        
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(count) { i ->
                    launch {
                        repository.saveAgentState(createFullState(agentId).copy(name = "Name $i"))
                    }
                }
            }
        }
        
        val finalState = repository.getAgentState(agentId)
        assertNotNull(finalState)
        assertTrue(finalState.name.startsWith("Name "))
    }

    @Test
    fun `database error handling`() = runTest {
        // Driver closure simulates DB error/unavailability
        driver.close()
        
        assertFails {
            repository.getAgentState("any")
        }
    }

    private fun createFullState(id: String) = AgentState(
        agentId = id,
        name = "Test Agent",
        systemPrompt = "System Prompt",
        temperature = 0.7,
        maxTokens = 2000,
        stopWord = "STOP",
        currentStage = AgentStage.REVIEW,
        currentStepId = "step-42",
        plan = AgentPlan(listOf(AgentStep("step-42", "The Step", true, "Output"))),
        memoryStrategy = ChatMemoryStrategy.StickyFacts(15, ChatFacts(listOf("fact1"))),
        workingMemory = WorkingMemory(currentTask = "Work", progress = "50%")
    )

    private class FakeNetworkRepository : ChatRepository {
        var extractFactsCalled = false
        var summarizeCalled = false
        var analyzeTaskCalled = false
        var analyzeWorkingMemoryCalled = false
        var lastMessages: List<ChatMessage>? = null

        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider): Flow<Result<String>> = emptyFlow()
        
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> {
            extractFactsCalled = true
            return Result.success(ChatFacts())
        }
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> {
            summarizeCalled = true
            return Result.success("")
        }
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<TaskAnalysisResult> {
            analyzeTaskCalled = true
            return Result.success(TaskAnalysisResult())
        }
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<WorkingMemoryAnalysis> {
            analyzeWorkingMemoryCalled = true
            return Result.success(WorkingMemoryAnalysis())
        }

        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = emptyFlow()
    }
}
