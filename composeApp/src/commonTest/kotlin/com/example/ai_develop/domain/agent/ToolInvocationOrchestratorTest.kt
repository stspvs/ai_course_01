@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.example.ai_develop.domain.agent
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import com.example.ai_develop.data.stripLeadingJsonColonLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolInvocationOrchestratorTest {

    private class FakeChatRepository : ChatRepository {
        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider,
        ): Flow<Result<String>> = flowOf(Result.success(""))

        override suspend fun extractFacts(
            messages: List<ChatMessage>,
            currentFacts: ChatFacts,
            provider: LLMProvider,
        ) = Result.success(ChatFacts())

        override suspend fun summarize(
            messages: List<ChatMessage>,
            previousSummary: String?,
            instruction: String,
            provider: LLMProvider,
        ) = Result.success("")

        override suspend fun analyzeTask(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider,
        ) = Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider,
        ) = Result.success(WorkingMemoryAnalysis())

        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun deleteAgent(agentId: String) {}
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(null)
    }

    private class CountingTool(
        override val name: String,
        override val description: String = "",
        val onExecute: suspend (String) -> String = { "ok:$it" },
    ) : AgentTool {
        var callCount = 0
        override suspend fun execute(input: String): String {
            callCount++
            return onExecute(input)
        }
    }

    private class ThrowingEngine(
        repository: ChatRepository,
        memoryManager: ChatMemoryManager,
        tools: List<AgentTool>,
    ) : AgentEngine(repository, memoryManager, tools) {
        override suspend fun executeToolCall(agent: Agent, call: ParsedToolCall): String? {
            throw IllegalStateException("tool boom")
        }
    }

    private val repository = FakeChatRepository()
    private val memory = ChatMemoryManager()

    private fun baseAgent(messages: List<ChatMessage> = emptyList()) = Agent(
        name = "T",
        systemPrompt = "sys",
        temperature = 0.0,
        provider = LLMProvider.DeepSeek(),
        stopWord = "",
        maxTokens = 100,
        messages = messages,
        workingMemory = WorkingMemory(isAutoUpdateEnabled = true),
    )

    private class Harness(
        var agent: Agent?,
        val timing: PhaseTimingCollector? = null,
    ) {
        val mutex = Mutex()
        var syncCount = 0
        val phaseHints = mutableListOf<PhaseStatusHint?>()
        val activities = mutableListOf<AgentActivity>()
        val llmQueue = ArrayDeque<String>()
        var llmCallCount = 0
        private var messageId = 0

        fun createMessage(
            role: String,
            content: String,
            parentId: String?,
            agentStage: AgentStage,
            llmSnapshot: LlmRequestSnapshot?,
        ): ChatMessage = ChatMessage(
            id = "m-${messageId++}",
            role = role,
            message = content,
            timestamp = 0L,
            parentId = parentId,
            source = if (role == "assistant") SourceType.AI else SourceType.SYSTEM,
            llmRequestSnapshot = llmSnapshot,
        )

        fun context(): ToolInvocationContext = ToolInvocationContext(
            processingMutex = mutex,
            timing = timing,
            getAgent = { agent },
            updateAgent = { transform ->
                agent = transform(agent)
            },
            syncWithRepository = { syncCount++ },
            setActivity = { activities += it },
            setPhaseHint = { phaseHints += it },
            createMessage = { role, content, parentId, stage, snap ->
                createMessage(role, content, parentId, stage, snap)
            },
            executeStreamingStep = { _, _ ->
                llmCallCount++
                llmQueue.removeFirstOrNull() ?: ""
            },
        )
    }

    @Test
    fun suppress_emptyCalls_noActivity() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val h = Harness(baseAgent())
        orch.runSuppressOnlyToolSequence(
            calls = emptyList(),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "hello",
            ctx = h.context(),
        )
        assertEquals(0, tool.callCount)
        assertTrue(h.activities.none { it is AgentActivity.RunningTool })
    }

    @Test
    fun suppress_singleCall_updatesAssistantAndSyncs() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "pre", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        orch.runSuppressOnlyToolSequence(
            calls = listOf(ParsedToolCall("calculator", "2+2")),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "[TOOL: calculator(2+2)]",
            ctx = h.context(),
        )
        assertEquals(1, tool.callCount)
        assertTrue(h.syncCount >= 1)
        val last = h.agent!!.messages.last { it.role == "assistant" }
        assertTrue(last.message.contains("— Инструмент: calculator —"), last.message)
        assertTrue(last.message.contains("ok:2+2"), last.message)
    }

    @Test
    fun suppress_duplicateCallKey_skipsSecondInvocation() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        orch.runSuppressOnlyToolSequence(
            calls = listOf(
                ParsedToolCall("calculator", "same"),
                ParsedToolCall("calculator", "same"),
            ),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "",
            ctx = h.context(),
        )
        assertEquals(1, tool.callCount)
    }

    @Test
    fun suppress_noAssistant_addsSystemWithMergedText() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val user = ChatMessage(id = "u1", role = "user", message = "hi", timestamp = 1L, source = SourceType.USER)
        val h = Harness(baseAgent(listOf(user)))
        orch.runSuppressOnlyToolSequence(
            calls = listOf(ParsedToolCall("calculator", "1")),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "",
            ctx = h.context(),
        )
        val sys = h.agent!!.messages.last { it.role == "system" }
        assertTrue(sys.message.contains("— Инструмент: calculator —"))
    }

    @Test
    fun suppress_nullAgent_errorTextNoSync() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val h = Harness(agent = null)
        orch.runSuppressOnlyToolSequence(
            calls = listOf(ParsedToolCall("calculator", "1")),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "",
            ctx = h.context(),
        )
        assertEquals(0, tool.callCount)
        assertEquals(0, h.syncCount)
    }

    @Test
    fun suppress_unknownTool_noToolsRegistered() = runTest {
        val engine = AgentEngine(repository, memory, emptyList())
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        orch.runSuppressOnlyToolSequence(
            calls = listOf(ParsedToolCall("ghost", "x")),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "",
            ctx = h.context(),
        )
        val last = h.agent!!.messages.last { it.role == "assistant" }
        assertTrue(last.message.contains("unknown tool"), last.message)
        assertTrue(last.message.contains("Registered: none"), last.message)
    }

    @Test
    fun suppress_unknownTool_listsRegisteredNames() = runTest {
        val engine = AgentEngine(
            repository,
            memory,
            listOf(CountingTool("alpha"), CountingTool("beta")),
        )
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        orch.runSuppressOnlyToolSequence(
            calls = listOf(ParsedToolCall("zeta", "1")),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "",
            ctx = h.context(),
        )
        val last = h.agent!!.messages.last { it.role == "assistant" }
        assertTrue(last.message.contains("alpha"), last.message)
        assertTrue(last.message.contains("beta"), last.message)
    }

    @Test
    fun suppress_executeThrows_mergesError() = runTest {
        val tool = CountingTool("calculator")
        val engine = ThrowingEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        orch.runSuppressOnlyToolSequence(
            calls = listOf(ParsedToolCall("calculator", "1")),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "",
            ctx = h.context(),
        )
        val last = h.agent!!.messages.last { it.role == "assistant" }
        assertTrue(last.message.contains("Tool error:"), last.message)
        assertTrue(last.message.contains("boom"), last.message)
    }

    @Test
    fun suppress_proseFromModel_empty_usesOnlyToolBlocks() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        orch.runSuppressOnlyToolSequence(
            calls = listOf(ParsedToolCall("calculator", "1")),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "   ",
            ctx = h.context(),
        )
        val last = h.agent!!.messages.last { it.role == "assistant" }
        assertFalse(last.message.contains("hello"))
    }

    @Test
    fun suppress_proseFromModel_nonEmpty_prependsProse() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        orch.runSuppressOnlyToolSequence(
            calls = listOf(ParsedToolCall("calculator", "1")),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "hello prose",
            ctx = h.context(),
        )
        val last = h.agent!!.messages.last { it.role == "assistant" }
        assertTrue(last.message.startsWith("hello prose"), last.message)
        assertTrue(last.message.contains("— Инструмент: calculator —"), last.message)
    }

    @Test
    fun suppress_timing_recordsToolDuration() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val timing = PhaseTimingCollector()
        val h = Harness(baseAgent(listOf(assistant)), timing = timing)
        orch.runSuppressOnlyToolSequence(
            calls = listOf(ParsedToolCall("calculator", "1")),
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "",
            ctx = h.context(),
        )
        val built = timing.build()
        assertTrue(built.toolDurations.any { it.toolName == "calculator" }, built.toolDurations.toString())
    }

    @Test
    fun chain_noToolInInitialText_doesNothing() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val h = Harness(baseAgent())
        val collector: FlowCollector<String> = FlowCollector { }
        orch.runToolChainLoop(
            collector = collector,
            currentStage = { AgentStage.EXECUTION },
            initialResponseText = "no tool here",
            ctx = h.context(),
        )
        assertEquals(0, tool.callCount)
        assertEquals(0, h.llmCallCount)
    }

    @Test
    fun chain_oneTool_thenNoTool_singleLlmRound() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        h.llmQueue.add("final answer without tools")
        val collector: FlowCollector<String> = FlowCollector { }
        orch.runToolChainLoop(
            collector = collector,
            currentStage = { AgentStage.EXECUTION },
            initialResponseText = "[TOOL: calculator(1)]",
            ctx = h.context(),
        )
        assertEquals(1, tool.callCount)
        assertEquals(1, h.llmCallCount)
    }

    @Test
    fun chain_twoToolsInOneResponse_thenLlm_oneLlmAfterBoth() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        h.llmQueue.add("done")
        val collector: FlowCollector<String> = FlowCollector { }
        orch.runToolChainLoop(
            collector = collector,
            currentStage = { AgentStage.EXECUTION },
            initialResponseText = "[TOOL: calculator(1)][TOOL: calculator(2)]",
            ctx = h.context(),
        )
        assertEquals(2, tool.callCount)
        assertEquals(1, h.llmCallCount)
    }

    @Test
    fun chain_toolFails_noLlmFollowUp() = runTest {
        val tool = CountingTool("calculator")
        val engine = ThrowingEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        h.llmQueue.add("should not run")
        val collector: FlowCollector<String> = FlowCollector { }
        orch.runToolChainLoop(
            collector = collector,
            currentStage = { AgentStage.EXECUTION },
            initialResponseText = "[TOOL: calculator(1)]",
            ctx = h.context(),
        )
        assertEquals(0, h.llmCallCount)
    }

    @Test
    fun chain_duplicateToolCallAfterLlm_recoversAndStops() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(
            id = "a1",
            role = "assistant",
            message = "[TOOL: calculator(dup)]",
            timestamp = 1L,
            source = SourceType.AI,
        )
        val h = Harness(baseAgent(listOf(assistant)))
        h.llmQueue.add("[TOOL: calculator(dup)]")
        val collector: FlowCollector<String> = FlowCollector { }
        orch.runToolChainLoop(
            collector = collector,
            currentStage = { AgentStage.EXECUTION },
            initialResponseText = "[TOOL: calculator(dup)]",
            ctx = h.context(),
        )
        assertEquals(1, tool.callCount)
        assertEquals(1, h.llmCallCount)
        val lastAssistant = h.agent!!.messages.last { it.role == "assistant" }
        assertFalse(lastAssistant.message.contains("[TOOL:"), lastAssistant.message)
    }

    @Test
    fun chain_nullAgentBeforeLlm_skipsStreamingStep() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        h.llmQueue.add("nope")
        val ctx = ToolInvocationContext(
            processingMutex = h.mutex,
            timing = null,
            getAgent = { h.agent },
            updateAgent = { transform ->
                h.agent = transform(h.agent)
            },
            syncWithRepository = {
                h.syncCount++
                h.agent = null
            },
            setActivity = { h.activities += it },
            setPhaseHint = { h.phaseHints += it },
            createMessage = { role, content, parentId, stage, snap ->
                h.createMessage(role, content, parentId, stage, snap)
            },
            executeStreamingStep = { _, _ ->
                h.llmCallCount++
                h.llmQueue.removeFirstOrNull() ?: ""
            },
        )
        val collector: FlowCollector<String> = FlowCollector { }
        orch.runToolChainLoop(
            collector = collector,
            currentStage = { AgentStage.EXECUTION },
            initialResponseText = "[TOOL: calculator(1)]",
            ctx = ctx,
        )
        assertEquals(1, tool.callCount)
        assertEquals(0, h.llmCallCount)
    }

    @Test
    fun chain_noAssistant_addsSystemToolResult() = runTest {
        val tool = CountingTool("calculator") { """JSON: {"a":1}""" }
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val user = ChatMessage(id = "u1", role = "user", message = "hi", timestamp = 1L, source = SourceType.USER)
        val h = Harness(baseAgent(listOf(user)))
        h.llmQueue.add("no more tools")
        val collector: FlowCollector<String> = FlowCollector { }
        orch.runToolChainLoop(
            collector = collector,
            currentStage = { AgentStage.EXECUTION },
            initialResponseText = "[TOOL: calculator(1)]",
            ctx = h.context(),
        )
        val sys = h.agent!!.messages.last { it.role == "system" }
        assertTrue(sys.message.startsWith("Tool Result:"), sys.message)
        val payload = sys.message.removePrefix("Tool Result:").trim()
        assertEquals(stripLeadingJsonColonLabel("""JSON: {"a":1}"""), payload)
    }

    @Test
    fun chain_maxIterations_stopsBeforeExtraTool() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine, maxToolChainIterations = 2)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        h.llmQueue.add("[TOOL: calculator(3)]")
        val collector: FlowCollector<String> = FlowCollector { }
        orch.runToolChainLoop(
            collector = collector,
            currentStage = { AgentStage.EXECUTION },
            initialResponseText = "[TOOL: calculator(1)][TOOL: calculator(2)]",
            ctx = h.context(),
        )
        assertEquals(2, tool.callCount)
        assertTrue(h.llmCallCount >= 1)
    }

    @Test
    fun stress_suppress_manyDistinctCalls() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val orch = ToolInvocationOrchestrator(engine)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        val calls = (0 until 45).map { ParsedToolCall("calculator", "$it") }
        orch.runSuppressOnlyToolSequence(
            calls = calls,
            currentStage = { AgentStage.EXECUTION },
            rawModelResponse = "",
            ctx = h.context(),
        )
        assertEquals(45, tool.callCount)
        assertEquals(45, h.syncCount)
        val last = h.agent!!.messages.last { it.role == "assistant" }
        assertTrue(last.message.length > 100)
    }

    @Test
    fun stress_chain_hitsMaxIterationBoundary() = runTest {
        val tool = CountingTool("calculator")
        val engine = AgentEngine(repository, memory, listOf(tool))
        val max = 32
        val orch = ToolInvocationOrchestrator(engine, maxToolChainIterations = max)
        val assistant = ChatMessage(id = "a1", role = "assistant", message = "x", timestamp = 1L, source = SourceType.AI)
        val h = Harness(baseAgent(listOf(assistant)))
        repeat(max) { i -> h.llmQueue.add("[TOOL: calculator(${i + 1})]") }
        val collector: FlowCollector<String> = FlowCollector { }
        orch.runToolChainLoop(
            collector = collector,
            currentStage = { AgentStage.EXECUTION },
            initialResponseText = "[TOOL: calculator(0)]",
            ctx = h.context(),
        )
        assertEquals(max, tool.callCount)
        assertEquals(max, h.llmCallCount)
    }
}
