@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class
)

package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.*

class AutonomousAgentTest {
    private lateinit var repository: MockChatRepository
    private lateinit var memoryManager: ChatMemoryManager
    private val agentId = "test_agent"

    @BeforeTest
    fun setup() {
        repository = MockChatRepository()
        memoryManager = ChatMemoryManager()
    }

    @Test
    fun testInitialLoading() = runTest {
        val initialState = AgentState(agentId, name = "Test Agent", currentStage = AgentStage.PLANNING)
        repository.agentStateMap[agentId] = initialState

        val autonomousAgent = AutonomousAgent(agentId, repository, memoryManager, this)
        autonomousAgent.refreshAgent()

        val agentValue = autonomousAgent.agent.value
        assertNotNull(agentValue)
        assertEquals("Test Agent", agentValue.name)
    }

    @Test
    fun testTransitionToExecution() = runTest {
        repository.agentStateMap[agentId] = AgentState(agentId, currentStage = AgentStage.PLANNING)
        val autonomousAgent = AutonomousAgent(agentId, repository, memoryManager, this)
        autonomousAgent.refreshAgent()

        val result = autonomousAgent.transitionTo(AgentStage.EXECUTION)
        
        assertTrue(result.isSuccess)
        assertEquals(AgentStage.EXECUTION, repository.agentStateMap[agentId]?.currentStage)
    }

    @Test
    fun testSendMessageAndStreaming() = runTest(UnconfinedTestDispatcher()) {
        repository.agentStateMap[agentId] = AgentState(agentId)
        val autonomousAgent = AutonomousAgent(agentId, repository, memoryManager, this)
        autonomousAgent.refreshAgent()

        val tokens = mutableListOf<String>()
        val collectJob = launch {
            autonomousAgent.partialResponse.collect { 
                tokens.add(it) 
            }
        }

        autonomousAgent.sendMessage("Hello")
        advanceUntilIdle()

        assertTrue(tokens.isNotEmpty(), "Tokens should not be empty")
        assertEquals("Mock ", tokens[0])
        assertEquals("Response", tokens[1])
        
        val messages = autonomousAgent.agent.value?.messages ?: emptyList()
        assertEquals(2, messages.size)
        assertEquals("Mock Response", messages.last().message)
        
        collectJob.cancel()
    }

    class MockChatRepository : ChatRepository {
        var saveAgentStateCalled = false
        val agentStateMap = mutableMapOf<String, AgentState>()

        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ): Flow<Result<String>> = flow {
            emit(Result.success("Mock "))
            emit(Result.success("Response"))
        }

        override suspend fun saveAgentState(state: AgentState) {
            saveAgentStateCalled = true
            agentStateMap[state.agentId] = state
        }

        override suspend fun getAgentState(agentId: String): AgentState? = agentStateMap[agentId]
        
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(agentStateMap[agentId])

        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider) = Result.success("")
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(WorkingMemoryAnalysis())
    }
}
