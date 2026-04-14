@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.ai_develop.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * Автономный агент: три уровня сценариев.
 *
 * **Ordinary** — базовый поток без сюрпризов (стриминг, инструменты, факты).
 * **Corner** — пустые/странные входы, ошибки стрима/инструмента, suppress follow-up.
 * **Stress** — параллельные отправки и длинные последовательности сообщений.
 */
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

    // --- 1. Ordinary ---

    @Test
    fun ordinary_singleAssistantReplyNoTools() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        fakeEngine.queueResponse(listOf("Hello world"))
        agent.sendMessage("Hi").collect()
        advanceUntilIdle()

        val assistant = agent.agent.value?.messages?.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertTrue(assistant.message.contains("Hello world"), assistant.message)
    }

    @Test
    fun ordinary_severalSuppressToolsInOneResponse_thenContinuationLlm() = runTest {
        val mcpA = object : AgentTool {
            override val name = "mcp_a"
            override val description = "stub"
            override val suppressLlmFollowUp = true
            override suspend fun execute(input: String) = "first($input)"
        }
        val mcpB = object : AgentTool {
            override val name = "mcp_b"
            override val description = "stub"
            override val suppressLlmFollowUp = true
            override suspend fun execute(input: String) = "second($input)"
        }
        val engineWithMcp = FakeAgentEngine(repository, memoryManager, listOf(mcpA, mcpB))
        engineWithMcp.queueResponse(listOf("[TOOL: mcp_a(q1)] [TOOL: mcp_b(q2)]"))
        engineWithMcp.queueResponse(listOf("Продолжаю остальные шаги."))

        val agent = AutonomousAgent(agentId, repository, engineWithMcp, backgroundScope)
        advanceUntilIdle()

        agent.sendMessage("run both").collect()
        advanceUntilIdle()

        assertEquals(
            2,
            engineWithMcp.streamFromPreparedCallCount,
            "Первый ответ с [TOOL:], затем обязательный раунд продолжения после MCP-батча"
        )
        val assistantTexts = agent.agent.value?.messages
            ?.filter { it.role == "assistant" }
            ?.map { it.message }
            .orEmpty()
        val merged = assistantTexts.find { it.contains("mcp_a") && it.contains("mcp_b") }
        assertNotNull(merged, "ожидается пузырь с результатами обоих MCP: $assistantTexts")
        assertTrue(merged!!.contains("first(q1)"), merged)
        assertTrue(merged.contains("second(q2)"), merged)
    }

    @Test
    fun ordinary_suppressToolsBatch_preservesProseAroundToolLines() = runTest {
        val mcpA = object : AgentTool {
            override val name = "mcp_a"
            override val description = "stub"
            override val suppressLlmFollowUp = true
            override suspend fun execute(input: String) = "first($input)"
        }
        val mcpB = object : AgentTool {
            override val name = "mcp_b"
            override val description = "stub"
            override val suppressLlmFollowUp = true
            override suspend fun execute(input: String) = "second($input)"
        }
        val engineWithMcp = FakeAgentEngine(repository, memoryManager, listOf(mcpA, mcpB))
        engineWithMcp.queueResponse(
            listOf("Файл будет сохранён.\n\n[TOOL: mcp_a(q1)] [TOOL: mcp_b(q2)]")
        )
        engineWithMcp.queueResponse(listOf("Дальше по плану."))

        val agent = AutonomousAgent(agentId, repository, engineWithMcp, backgroundScope)
        advanceUntilIdle()

        agent.sendMessage("run both").collect()
        advanceUntilIdle()

        val assistantTexts = agent.agent.value?.messages
            ?.filter { it.role == "assistant" }
            ?.map { it.message }
            .orEmpty()
        val withProseAndTools = assistantTexts.find {
            it.contains("Файл будет сохранён") && it.contains("mcp_b")
        }
        assertNotNull(withProseAndTools, assistantTexts.toString())
    }

    @Test
    fun ordinary_suppressToolThenNonSuppressToolInOneResponse_runsBothBeforeFollowUpLlm() = runTest {
        var fetchCalls = 0
        var writeCalls = 0
        val fetch = object : AgentTool {
            override val name = "mcp_fetch"
            override val description = "stub"
            override val suppressLlmFollowUp = true
            override suspend fun execute(input: String): String {
                fetchCalls++
                return "rates($input)"
            }
        }
        val writer = object : AgentTool {
            override val name = "write_file"
            override val description = "stub"
            override val suppressLlmFollowUp = false
            override suspend fun execute(input: String): String {
                writeCalls++
                return "saved($input)"
            }
        }
        val engineMixed = FakeAgentEngine(repository, memoryManager, listOf(fetch, writer))
        engineMixed.queueResponse(listOf("[TOOL: mcp_fetch(USD)] [TOOL: write_file(/tmp/q.txt)]"))
        engineMixed.queueResponse(listOf("Готово."))

        val agent = AutonomousAgent(agentId, repository, engineMixed, backgroundScope)
        advanceUntilIdle()

        agent.sendMessage("save quotes").collect()
        advanceUntilIdle()

        assertEquals(1, fetchCalls)
        assertEquals(1, writeCalls)
        assertEquals(
            2,
            engineMixed.streamFromPreparedCallCount,
            "LLM: ответ с двумя TOOL, затем финальный ответ после write_file"
        )
        val assistant = agent.agent.value?.messages?.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertTrue(assistant.message.contains("Готово"), assistant.message)
    }

    @Test
    fun ordinary_toolCallCalculatorThenFinalAnswer() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        fakeEngine.queueResponse(listOf("Hello! ", "TOOL_CALL: calculator\nINPUT: 25 * 48"))
        fakeEngine.queueToolResult("1200")
        fakeEngine.queueResponse(listOf("Result is 1200."))

        val output = agent.sendMessage("Run test").toList()
        advanceUntilIdle()

        val messages = agent.agent.value?.messages ?: emptyList()
        val assistants = messages.filter { it.role == "assistant" }
        assertEquals(1, assistants.size, "After tool + final LLM answer there must be one assistant bubble, got: $messages")
        assertEquals(2, messages.size, "Expected user + assistant, got ${messages.size}")
        assertTrue(output.any { it.contains("1200") }, "Output should contain tool result")
    }

    @Test
    fun ordinary_extractFactsIntoWorkingMemory() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        repository.extractedFactsToReturn = ChatFacts(listOf("Name: John"))
        fakeEngine.queueResponse(listOf("I'll remember that."))

        agent.sendMessage("My name is John").collect()
        advanceUntilIdle()

        val facts = agent.agent.value?.workingMemory?.extractedFacts?.facts ?: emptyList()
        assertTrue(facts.contains("Name: John"), "Fact 'John' should be in working memory")
    }

    // --- 2. Corner cases ---

    @Test
    fun corner_unicodeEmojiAndUnknownTool() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        fakeEngine.queueResponse(listOf("🚀 Привет! 你好! ", "TOOL_CALL: ghost_tool\nINPUT: test"))

        val output = agent.sendMessage("Edge cases test").toList()
        advanceUntilIdle()

        assertTrue(output.any { it.contains("你好") }, "Should handle Chinese characters")
        assertTrue(output.any { it.contains("🚀") }, "Should handle emojis")
        val messages = agent.agent.value?.messages ?: emptyList()
        assertTrue(
            messages.any { m ->
                m.role == "assistant" && m.message.contains("ghost_tool") &&
                    (m.message.contains("Tool error") || m.message.contains("unknown tool"))
            },
            "Unknown tool should surface error in merged assistant message, got: $messages"
        )
    }

    @Test
    fun corner_emptyUserMessage_completes() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        fakeEngine.queueResponse(listOf("ack"))
        agent.sendMessage("").collect()
        advanceUntilIdle()

        assertFalse(agent.isProcessing.value, "Processing should be false after completion")
        val users = agent.agent.value?.messages?.filter { it.role == "user" } ?: emptyList()
        assertEquals(1, users.size)
        assertEquals("", users.single().message)
    }

    @Test
    fun corner_whitespaceOnlyUserMessage_completes() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        val ws = "  \t\n  "
        fakeEngine.queueResponse(listOf("noted"))
        agent.sendMessage(ws).collect()
        advanceUntilIdle()

        val users = agent.agent.value?.messages?.filter { it.role == "user" } ?: emptyList()
        assertEquals(1, users.size)
        assertEquals(ws, users.single().message)
    }

    @Test
    fun corner_streamThrowsException_surfacesError() = runTest {
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
    fun corner_suppressLlmFollowUp_skipsSecondLlmStream() = runTest {
        val stubTool = object : AgentTool {
            override val name = "news_search"
            override val description = "stub"
            override val suppressLlmFollowUp = true
            override suspend fun execute(input: String) = "Headlines: ok ($input)"
        }
        val engineWithStub = FakeAgentEngine(repository, memoryManager, listOf(stubTool))
        engineWithStub.queueResponse(listOf("Calling MCP.\nTOOL_CALL: news_search\nINPUT: tech"))
        engineWithStub.queueResponse(listOf("Готово: новости получены."))

        val agent = AutonomousAgent(agentId, repository, engineWithStub, backgroundScope)
        advanceUntilIdle()

        agent.sendMessage("news please").collect()
        advanceUntilIdle()

        assertEquals(
            2,
            engineWithStub.streamFromPreparedCallCount,
            "После одного MCP без второго [TOOL:] в том же ответе — второй раунд LLM (продолжение без «продолжай»)"
        )
        val messages = agent.agent.value?.messages ?: emptyList()
        assertTrue(messages.any { it.message.contains("Headlines: ok") }, "Tool output should be in history")
    }

    @Test
    fun corner_duplicateToolCall_stripsRawToolSyntaxFromSecondAssistantMessage() = runTest {
        val stubTool = object : AgentTool {
            override val name = "dup_tool"
            override val description = "stub"
            override suspend fun execute(input: String) = "ok($input)"
        }
        val engineWithStub = FakeAgentEngine(repository, memoryManager, listOf(stubTool))
        engineWithStub.queueResponse(listOf("[TOOL: dup_tool(abc)]"))
        engineWithStub.queueToolResult("done")
        engineWithStub.queueResponse(listOf("[TOOL: dup_tool(abc)]"))

        val agent = AutonomousAgent(agentId, repository, engineWithStub, backgroundScope)
        advanceUntilIdle()

        agent.sendMessage("please").collect()
        advanceUntilIdle()

        val texts = agent.agent.value?.messages?.map { it.message } ?: emptyList()
        assertTrue(
            texts.none { it.contains("[TOOL:") },
            "Raw [TOOL: …] must not remain after duplicate follow-up, got: $texts"
        )
        assertTrue(
            texts.any { it.contains("— Инструмент: dup_tool —") && it.contains("done") },
            "Merged tool output should remain, got: $texts"
        )
    }

    @Test
    fun corner_toolExecuteThrows_errorInHistory() = runTest {
        val badTool = object : AgentTool {
            override val name = "bad_tool"
            override val description = "always fails"
            override suspend fun execute(input: String): String = error("intentional failure")
        }
        val engineWithBad = FakeAgentEngine(repository, memoryManager, listOf(badTool))
        engineWithBad.queueResponse(listOf("TOOL_CALL: bad_tool\nINPUT: x"))

        val agent = AutonomousAgent(agentId, repository, engineWithBad, backgroundScope)
        advanceUntilIdle()

        agent.sendMessage("trigger").collect()
        advanceUntilIdle()

        val messages = agent.agent.value?.messages ?: emptyList()
        assertTrue(
            messages.any { m ->
                m.role == "assistant" && m.message.contains("Tool error") && m.message.contains("intentional")
            },
            "Exception from execute should appear in merged assistant message, got: $messages"
        )
    }

    // --- 3. Stress ---

    @Test
    fun stress_concurrentMessages_bothUserRowsPersisted() = runTest {
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

    @Test
    fun stress_sequentialTenRoundTrips() = runTest {
        val agent = AutonomousAgent(agentId, repository, fakeEngine, backgroundScope)
        advanceUntilIdle()

        repeat(10) { i ->
            fakeEngine.queueResponse(listOf("Reply $i"))
        }
        repeat(10) { i ->
            agent.sendMessage("msg-$i").collect()
            advanceUntilIdle()
        }

        val userMessages = agent.agent.value?.messages?.filter { it.role == "user" } ?: emptyList()
        assertEquals(10, userMessages.size)
        assertEquals("msg-9", userMessages.last().message)
    }

    class FakeAgentEngine(
        repo: ChatRepository,
        mem: ChatMemoryManager,
        tools: List<AgentTool> = emptyList()
    ) : AgentEngine(repo, mem, tools) {
        private val responses = mutableListOf<List<String>>()
        private val toolResults = mutableListOf<String?>()

        var streamFromPreparedCallCount = 0
            private set

        fun queueResponse(chunks: List<String>) = responses.add(chunks)
        fun queueToolResult(res: String?) = toolResults.add(res)

        override fun streamFromPrepared(agent: Agent, prepared: PreparedLlmRequest): Flow<String> = flow {
            streamFromPreparedCallCount++
            val chunks = if (responses.isNotEmpty()) responses.removeAt(0) else listOf("Default")
            for (chunk in chunks) {
                emit(chunk)
                yield()
            }
        }

        override suspend fun executeToolCall(call: ParsedToolCall): String? {
            if (toolResults.isNotEmpty()) {
                return toolResults.removeAt(0)
            }
            return super.executeToolCall(call)
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
