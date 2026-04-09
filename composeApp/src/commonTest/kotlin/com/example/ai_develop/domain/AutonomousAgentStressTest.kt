@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.ai_develop.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class AutonomousAgentStressTest {
    private lateinit var repository: MockChatRepository
    private lateinit var memoryManager: ChatMemoryManager
    private lateinit var fakeEngine: FakeAgentEngine
    private val agentId = "stress_test_agent"

    @BeforeTest
    fun setup() {
        repository = MockChatRepository()
        memoryManager = ChatMemoryManager()
        fakeEngine = FakeAgentEngine(repository, memoryManager)
        
        repository.agentStateMap[agentId] = AgentState(
            agentId = agentId,
            name = "Stress Agent",
            workingMemory = WorkingMemory(isAutoUpdateEnabled = true)
        )
    }

    @Test
    fun testFullCoverageScenario() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle() // Даем инициализироваться

        // 1. Tool Call
        fakeEngine.queueResponse(listOf("Hello! ", "TOOL_CALL: calculator\nINPUT: 25 * 48"))
        fakeEngine.queueToolResult("1200")
        
        // 2. Final response
        fakeEngine.queueResponse(listOf("Result is 1200."))

        // Собираем результаты sendMessage
        val output = agent.sendMessage("Run test").toList()
        advanceUntilIdle()

        val messages = agent.agent.value?.messages ?: emptyList()
        // Ожидаем: 1 (user) + 1 (assistant с tool call) + 1 (system с tool result) + 1 (assistant финальный) = 4
        assertTrue(messages.size >= 4, "Expected at least 4 messages, got ${messages.size}")
        assertTrue(output.any { it.contains("1200") }, "Output should contain tool result")
    }

    @Test
    fun testCornerCasesScenario() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        fakeEngine.queueResponse(listOf("🚀 Привет! 你好! ", "TOOL_CALL: ghost_tool\nINPUT: test"))
        fakeEngine.queueToolResult(null)

        val output = agent.sendMessage("Edge cases test").toList()
        advanceUntilIdle()

        assertTrue(output.any { it.contains("你好") }, "Should handle Chinese characters")
        assertTrue(output.any { it.contains("🚀") }, "Should handle emojis")
    }

    @Test
    fun testMemoryExtraction() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        repository.extractedFactsToReturn = ChatFacts(listOf("Name: John"))
        fakeEngine.queueResponse(listOf("I'll remember that."))

        agent.sendMessage("My name is John").collect()
        advanceUntilIdle()

        val facts = agent.agent.value?.workingMemory?.extractedFacts?.facts ?: emptyList()
        assertTrue(facts.contains("Name: John"), "Fact 'John' should be in working memory")
    }

    @Test
    fun testFailureRecovery() = runTest {
        val brokenEngine = object : AgentEngine(repository, memoryManager) {
            override fun streamFromPrepared(agent: Agent, prepared: PreparedLlmRequest): Flow<String> = flow {
                emit("Starting... ")
                throw IllegalStateException("API connection lost")
            }
        }
        
        val agent = AutonomousAgent(agentId, repository, brokenEngine, backgroundScope)
        advanceUntilIdle()

        val output = agent.sendMessage("Help").toList()
        advanceUntilIdle()

        assertTrue(output.any { it.contains("Error: API connection lost") }, "Should report error")
        assertFalse(agent.isProcessing.value, "Processing should be false")
    }

    @Test
    fun testConcurrencyMutex() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        fakeEngine.queueResponse(listOf("Response A"))
        fakeEngine.queueResponse(listOf("Response B"))

        val job1 = launch { agent.sendMessage("Request 1").collect() }
        val job2 = launch { agent.sendMessage("Request 2").collect() }

        joinAll(job1, job2)
        advanceUntilIdle()

        val messages = agent.agent.value?.messages ?: emptyList()
        val userMessages = messages.filter { it.role == "user" }
        assertEquals(2, userMessages.size, "Both user messages should be present")
    }

    class FakeAgentEngine(repo: ChatRepository, mem: ChatMemoryManager) : AgentEngine(repo, mem) {
        private val responses = mutableListOf<List<String>>()
        private val toolResults = mutableListOf<String?>()

        fun queueResponse(chunks: List<String>) = responses.add(chunks)
        fun queueToolResult(res: String?) = toolResults.add(res)

        override fun streamFromPrepared(agent: Agent, prepared: PreparedLlmRequest): Flow<String> = flow {
            val chunks = if (responses.isNotEmpty()) responses.removeAt(0) else listOf("Default")
            for (chunk in chunks) {
                emit(chunk)
                yield()
            }
        }

        override suspend fun processTools(text: String): String? {
            if (text.contains("TOOL_CALL:")) {
                return if (toolResults.isNotEmpty()) toolResults.removeAt(0) else null
            }
            return null
        }
    }

    class MockChatRepository : ChatRepository {
        val agentStateMap = mutableMapOf<String, AgentState>()
        private val _agentUpdates = MutableSharedFlow<AgentState>(replay = 1)
        var extractedFactsToReturn = ChatFacts()

        override fun chatStreaming(m: List<ChatMessage>, s: String, mt: Int, t: Double, sw: String, j: Boolean, p: LLMProvider) = flowOf(Result.success("Mock"))
        
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
        
        override suspend fun extractFacts(m: List<ChatMessage>, cf: ChatFacts, p: LLMProvider) = Result.success(extractedFactsToReturn)
        override suspend fun getProfile(id: String) = null
        override suspend fun saveProfile(id: String, p: UserProfile) {}
        override suspend fun getInvariants(id: String, s: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(i: Invariant) {}
        override suspend fun summarize(m: List<ChatMessage>, ps: String?, i: String, p: LLMProvider) = Result.success("Summary")
        override suspend fun analyzeTask(m: List<ChatMessage>, i: String, p: LLMProvider) = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(m: List<ChatMessage>, i: String, p: LLMProvider) = Result.success(WorkingMemoryAnalysis())
    }
}
