@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class
)

package com.example.ai_develop.domain

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

class AutonomousAgentTest {
    private lateinit var repository: MockChatRepository
    private lateinit var memoryManager: ChatMemoryManager
    private lateinit var engine: AgentEngine
    private val agentId = "test_agent"

    @BeforeTest
    fun setup() {
        repository = MockChatRepository()
        memoryManager = ChatMemoryManager()
        engine = AgentEngine(repository, memoryManager)
    }

    @Test
    fun testInitialLoading() = runTest {
        val initialState = AgentState(agentId, name = "Test Agent", currentStage = AgentStage.PLANNING)
        repository.agentStateMap[agentId] = initialState

        val autonomousAgent = AutonomousAgent(agentId, repository, engine, backgroundScope)
        autonomousAgent.refreshAgent()
        advanceUntilIdle()

        val agentValue = autonomousAgent.agent.value
        assertNotNull(agentValue)
        assertEquals("Test Agent", agentValue.name)
        
        autonomousAgent.dispose()
    }

    @Test
    fun testTransitionToExecution() = runTest {
        repository.agentStateMap[agentId] = AgentState(agentId, currentStage = AgentStage.PLANNING)
        val autonomousAgent = AutonomousAgent(agentId, repository, engine, backgroundScope)
        autonomousAgent.refreshAgent()
        advanceUntilIdle()

        val result = autonomousAgent.transitionTo(AgentStage.EXECUTION)
        
        assertTrue(result.isSuccess)
        assertEquals(AgentStage.EXECUTION, repository.agentStateMap[agentId]?.currentStage)
        
        autonomousAgent.dispose()
    }

    @Test
    fun testSendMessageAndStreaming() = runTest {
        repository.agentStateMap[agentId] = AgentState(agentId)
        val autonomousAgent = AutonomousAgent(agentId, repository, engine, backgroundScope)
        autonomousAgent.refreshAgent()
        advanceUntilIdle()

        val tokens = mutableListOf<String>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            autonomousAgent.partialResponse.collect { 
                tokens.add(it) 
            }
        }

        autonomousAgent.sendMessage("Hello").collect()
        advanceUntilIdle()

        assertTrue(tokens.isNotEmpty(), "Tokens should not be empty")
        assertEquals("Mock ", tokens[0])
        assertEquals("Response", tokens[1])
        
        val messages = autonomousAgent.agent.value?.messages ?: emptyList()
        assertTrue(messages.size >= 2, "Should have at least user and assistant messages")
        assertEquals("Mock Response", messages.last().message)
        
        collectJob.cancel()
        autonomousAgent.dispose()
    }

    class MockChatRepository : ChatRepository {
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
            agentStateMap[state.agentId] = state
        }

        override suspend fun getAgentState(agentId: String): AgentState? = agentStateMap[agentId]
        
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flow {
            emit(agentStateMap[agentId])
        }

        override suspend fun deleteAgent(agentId: String) {
            agentStateMap.remove(agentId)
        }

        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider) = Result.success("")
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(WorkingMemoryAnalysis())
    }
}
