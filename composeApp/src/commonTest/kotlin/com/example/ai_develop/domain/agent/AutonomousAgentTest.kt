@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class
)

package com.example.ai_develop.domain.agent
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

class AutonomousAgentTest {

    @Test
    fun mergeObserveMessages_keepsLongerLocalWhenProcessing() {
        val user = ChatMessage(id = "u", role = "user", message = "hi", timestamp = 1L, source = SourceType.USER)
        val assistant = ChatMessage(id = "a", role = "assistant", message = "hi", timestamp = 2L, source = SourceType.AI)
        val local = listOf(user, assistant)
        val observed = AgentState(agentId = "x", messages = listOf(user))
        val merged = mergeObserveMessages(
            isProcessing = true,
            localMessages = local,
            observed = observed,
            fallbackWhenObservedEmpty = emptyList()
        )
        assertEquals(2, merged.size)
        assertEquals("a", merged.last().id)
    }

    @Test
    fun mergeObserveMessages_usesObservedWhenNotProcessing() {
        val user = ChatMessage(id = "u", role = "user", message = "hi", timestamp = 1L, source = SourceType.USER)
        val assistant = ChatMessage(id = "a", role = "assistant", message = "hi", timestamp = 2L, source = SourceType.AI)
        val local = listOf(user, assistant)
        val observed = AgentState(agentId = "x", messages = listOf(user))
        val merged = mergeObserveMessages(
            isProcessing = false,
            localMessages = local,
            observed = observed,
            fallbackWhenObservedEmpty = emptyList()
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun mergeObserveMessages_processingPrefersLocalWhenObserveIsStaleLonger() {
        val user = ChatMessage(id = "u", role = "user", message = "hi", timestamp = 1L, source = SourceType.USER)
        val a1 = ChatMessage(id = "a1", role = "assistant", message = "merged", timestamp = 2L, source = SourceType.AI)
        val a2 = ChatMessage(id = "a2", role = "assistant", message = "[TOOL: x(y)]", timestamp = 3L, source = SourceType.AI)
        val local = listOf(user, a1)
        val observed = AgentState(agentId = "x", messages = listOf(user, a1, a2))
        val merged = mergeObserveMessages(
            isProcessing = true,
            localMessages = local,
            observed = observed,
            fallbackWhenObservedEmpty = emptyList()
        )
        assertEquals(2, merged.size)
        assertEquals("merged", merged.last().message)
    }
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

        val agentValue = autonomousAgent.uiState.value.agent
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
    fun testTransitionRejectedWhenChatMode() = runTest {
        repository.agentStateMap[agentId] = AgentState(
            agentId = agentId,
            workflowStagesEnabled = false,
        )
        val autonomousAgent = AutonomousAgent(agentId, repository, engine, backgroundScope)
        autonomousAgent.refreshAgent()
        advanceUntilIdle()

        val result = autonomousAgent.transitionTo(AgentStage.EXECUTION)
        assertTrue(result.isFailure)

        autonomousAgent.dispose()
    }

    @Test
    fun testSendMessageAndStreaming() = runTest {
        repository.agentStateMap[agentId] = AgentState(agentId)
        val autonomousAgent = AutonomousAgent(agentId, repository, engine, backgroundScope)
        autonomousAgent.refreshAgent()
        advanceUntilIdle()

        val streamDeltas = mutableListOf<String>()
        var prevPreview = ""
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            autonomousAgent.uiState.collect { s ->
                val p = s.streamingPreview
                if (p.startsWith(prevPreview) && p.length > prevPreview.length) {
                    streamDeltas.add(p.substring(prevPreview.length))
                }
                prevPreview = p
            }
        }

        autonomousAgent.sendMessage("Hello").collect()
        advanceUntilIdle()

        assertTrue(streamDeltas.isNotEmpty(), "Streaming preview deltas should not be empty")
        assertEquals("Mock ", streamDeltas[0])
        assertEquals("Response", streamDeltas[1])
        
        val messages = autonomousAgent.uiState.value.agent?.messages ?: emptyList()
        assertTrue(messages.size >= 2, "Should have at least user and assistant messages")
        assertEquals("Mock Response", messages.last().message)
        
        collectJob.cancel()
        autonomousAgent.dispose()
    }

    @Test
    fun testSendMessage_attachesPhaseTimingsToLastAssistant() = runTest {
        repository.agentStateMap[agentId] = AgentState(agentId)
        val autonomousAgent = AutonomousAgent(agentId, repository, engine, backgroundScope)
        autonomousAgent.refreshAgent()
        advanceUntilIdle()

        autonomousAgent.sendMessage("Hello").collect()
        advanceUntilIdle()

        val lastAssistant = autonomousAgent.uiState.value.agent?.messages?.lastOrNull { it.role == "assistant" }
        assertNotNull(lastAssistant?.phaseTimings)
        assertTrue(lastAssistant!!.phaseTimings!!.totalMs >= 0L)
        assertEquals(
            lastAssistant.phaseTimings,
            autonomousAgent.uiState.value.lastCompletedTimings,
        )

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
