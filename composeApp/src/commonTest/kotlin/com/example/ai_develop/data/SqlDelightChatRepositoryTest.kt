@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
import com.example.ai_develop.domain.*
import com.example.ai_develop.util.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SqlDelightChatRepositoryTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: AgentDatabase
    private lateinit var repository: SqlDelightChatRepository
    private lateinit var networkRepository: FakeNetworkRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgentDatabase.Schema.create(driver)
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
        networkRepository = FakeNetworkRepository()
        repository = SqlDelightChatRepository(database, networkRepository)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun testSaveAndGetAgentState() = runTest {
        val state = AgentState(
            agentId = "test-123",
            name = "Test Agent",
            systemPrompt = "System",
            temperature = 0.5,
            maxTokens = 1000,
            stopWord = "STOP",
            currentStage = AgentStage.EXECUTION,
            currentStepId = "step-1",
            plan = AgentPlan(listOf(AgentStep("step-1", "Desc", false))),
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(10),
            workingMemory = WorkingMemory(currentTask = "Task"),
            messages = emptyList(),
            branches = emptyList(),
            currentBranchId = null
        )

        repository.saveAgentState(state)
        val retrieved = repository.getAgentState("test-123")

        assertNotNull(retrieved)
        assertEquals(state.agentId, retrieved.agentId)
        assertEquals(state.name, retrieved.name)
        assertEquals(state.systemPrompt, retrieved.systemPrompt)
        assertEquals(state.temperature, retrieved.temperature)
        assertEquals(state.maxTokens, retrieved.maxTokens)
        assertEquals(state.stopWord, retrieved.stopWord)
        assertEquals(state.currentStage, retrieved.currentStage)
        assertEquals(state.currentStepId, retrieved.currentStepId)
        assertEquals(state.plan.steps.size, retrieved.plan.steps.size)
        assertEquals(state.memoryStrategy, retrieved.memoryStrategy)
        assertEquals(state.workingMemory.currentTask, retrieved.workingMemory.currentTask)
    }

    @Test
    fun testObserveAgentState() = runTest {
        val state = AgentState("obs-1", name = "Initial")
        repository.saveAgentState(state)

        val flow = repository.observeAgentState("obs-1")
        val firstEmit = flow.first()
        assertEquals("Initial", firstEmit?.name)

        repository.saveAgentState(state.copy(name = "Updated"))
        val secondEmit = flow.first { it?.name == "Updated" }
        assertEquals("Updated", secondEmit?.name)
    }

    @Test
    fun testDeleteAgent() = runTest {
        val state = AgentState("del-1", name = "To Delete")
        repository.saveAgentState(state)
        assertNotNull(repository.getAgentState("del-1"))

        repository.deleteAgent("del-1")
        assertNull(repository.getAgentState("del-1"))
    }

    private class FakeNetworkRepository : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider): Flow<Result<String>> = emptyFlow()
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> = Result.success("Summary")
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<TaskAnalysisResult> = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<WorkingMemoryAnalysis> = Result.success(WorkingMemoryAnalysis())
        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun deleteAgent(agentId: String) {}
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = emptyFlow()
    }
}
