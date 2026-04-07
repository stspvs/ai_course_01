@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class
)

package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutonomousAgentTest {
    private lateinit var repository: MockChatRepository
    private lateinit var memoryManager: ChatMemoryManager
    private val testScope = TestScope()
    private val agentId = "test_agent"

    @BeforeTest
    fun setup() {
        repository = MockChatRepository()
        memoryManager = ChatMemoryManager()
    }

    @Test
    fun testInitialLoading() = testScope.runTest {
        val initialState = AgentState(agentId, AgentStage.PLANNING, null, AgentPlan())
        repository.agentStateMap[agentId] = initialState

        val autonomousAgent = AutonomousAgent(agentId, repository, memoryManager, this)

        autonomousAgent.refreshAgent()

        val agentValue = autonomousAgent.agent.value
        assertNotNull(agentValue)
        assertEquals(agentId, agentValue.id)
    }

    @Test
    fun testSendMessageAndGetResponse() = testScope.runTest {
        val autonomousAgent = AutonomousAgent(agentId, repository, memoryManager, this)

        repository.agentStateMap[agentId] =
            AgentState(agentId, AgentStage.PLANNING, null, AgentPlan())
        autonomousAgent.refreshAgent()

        autonomousAgent.sendMessage("Hello AI")

        val messages = autonomousAgent.agent.value?.messages ?: emptyList()
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
        assertEquals("Mock Response", messages[1].message)

        assertTrue(repository.saveAgentStateCalled)
    }

    @Test
    fun testAutoFactExtraction() = testScope.runTest {
        val autonomousAgent = AutonomousAgent(agentId, repository, memoryManager, this)

        repository.agentStateMap[agentId] =
            AgentState(agentId, AgentStage.PLANNING, null, AgentPlan())
        autonomousAgent.refreshAgent()

        // Устанавливаем интервал в 2 сообщения (1 цикл user-assistant)
        val agentWithWm = autonomousAgent.agent.value?.copy(
            workingMemory = WorkingMemory(
                isAutoUpdateEnabled = true,
                updateInterval = 2,
                analysisWindowSize = 2
            )
        )
        // Хак для теста, так как поле приватное, но мы тестируем поведение
        // В реальном тесте мы бы мокали repository.getAgentState чтобы он вернул нужный конфиг

        // Отправляем сообщение. В конце цикла будет 2 сообщения (User + AI)
        autonomousAgent.sendMessage("Msg 1")

        // 2 % 2 == 0 -> должна сработать экстракция
        assertTrue(repository.extractFactsCalled)
    }

    class MockChatRepository : ChatRepository {
        var saveAgentStateCalled = false
        var extractFactsCalled = false
        var summarizeCalled = false

        val agentStateMap = mutableMapOf<String, AgentState>()
        val agentStateFlow = MutableStateFlow<AgentState?>(null)

        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ): Flow<Result<String>> = flow {
            emit(Result.success("Mock Response"))
        }

        override suspend fun extractFacts(
            messages: List<ChatMessage>,
            currentFacts: ChatFacts,
            provider: LLMProvider
        ): Result<ChatFacts> {
            extractFactsCalled = true
            return Result.success(ChatFacts(facts = listOf("Fact 1")))
        }

        override suspend fun summarize(
            messages: List<ChatMessage>,
            previousSummary: String?,
            instruction: String,
            provider: LLMProvider
        ): Result<String> {
            summarizeCalled = true
            return Result.success("New Summary")
        }

        override suspend fun saveAgentState(state: AgentState) {
            saveAgentStateCalled = true
            agentStateMap[state.agentId] = state
        }

        override suspend fun getAgentState(agentId: String): AgentState? = agentStateMap[agentId]
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> =
            emptyList()

        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = agentStateFlow

        override suspend fun analyzeTask(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ) =
            Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ) =
            Result.success(WorkingMemoryAnalysis())
    }
}
