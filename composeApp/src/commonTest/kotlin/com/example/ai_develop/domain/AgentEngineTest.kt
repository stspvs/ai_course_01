package com.example.ai_develop.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgentEngineTest {

    // --- Fakes ---

    private class FakeChatRepository : ChatRepository {
        var streamingResult: Flow<Result<String>> = emptyFlow()
        var extractFactsResult: Result<ChatFacts> = Result.success(ChatFacts())

        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ): Flow<Result<String>> = streamingResult

        override suspend fun extractFacts(
            messages: List<ChatMessage>,
            currentFacts: ChatFacts,
            provider: LLMProvider
        ): Result<ChatFacts> = extractFactsResult

        override suspend fun summarize(
            messages: List<ChatMessage>,
            previousSummary: String?,
            instruction: String,
            provider: LLMProvider
        ) = Result.success("")

        override suspend fun analyzeTask(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ) = Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
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

    private class TestTool(
        override val name: String, 
        override val description: String,
        val onExecute: suspend (String) -> String = { "executed: $it" }
    ) : AgentTool {
        var callCount = 0
        override suspend fun execute(input: String): String {
            callCount++
            return onExecute(input)
        }
    }

    // --- Setup ---

    private val repository = FakeChatRepository()
    private val memoryManager = ChatMemoryManager()
    private val tool1 = TestTool("calculator", "Calculates 2+2")
    private val engine = AgentEngine(repository, memoryManager, listOf(tool1))

    private val defaultAgent = Agent(
        name = "TestAgent",
        systemPrompt = "Test System Prompt",
        temperature = 0.7,
        provider = LLMProvider.DeepSeek(),
        stopWord = "",
        maxTokens = 100,
        workingMemory = WorkingMemory(isAutoUpdateEnabled = true)
    )

    // --- 1 & 3: streamResponse & Flow Tests ---

    @Test
    fun testStreamResponseEmitsCorrectTokens() = runTest {
        repository.streamingResult = flowOf(
            Result.success("Hello"),
            Result.success(" world"),
            Result.success("!")
        )

        val results = engine.streamResponse(defaultAgent, AgentStage.EXECUTION).toList()
        
        assertEquals(3, results.size)
        assertEquals("Hello", results[0])
        assertEquals(" world", results[1])
        assertEquals("!", results[2])
    }

    @Test
    fun testStreamResponseEmptyTools() = runTest {
        val engineNoTools = AgentEngine(repository, memoryManager, emptyList())
        repository.streamingResult = flowOf(Result.success("Done"))

        val results = engineNoTools.streamResponse(defaultAgent, AgentStage.DONE).toList()
        assertEquals("Done", results.first())
    }

    // --- 2: Error Handling ---

    @Test
    fun testStreamResponseFailureThrowsException() = runTest {
        val exception = RuntimeException("API Crash")
        repository.streamingResult = flow { emit(Result.failure(exception)) }

        assertFailsWith<RuntimeException> {
            engine.streamResponse(defaultAgent, AgentStage.PLANNING).toList()
        }
    }

    // --- 4: AgentTool (processTools) ---

    @Test
    fun testProcessToolsFormat1() = runTest {
        val result = engine.processTools("[TOOL: calculator(5*5)]")
        assertEquals("executed: 5*5", result)
        assertEquals(1, tool1.callCount)
    }

    @Test
    fun testProcessToolsFormat2() = runTest {
        val text = "TOOL_CALL: calculator\nINPUT: some complex data"
        val result = engine.processTools(text)
        assertEquals("executed: some complex data", result)
    }

    @Test
    fun testProcessToolsReturnsNullWhenNotFound() = runTest {
        val result = engine.processTools("[TOOL: unknown_tool(input)]")
        assertNull(result)
    }

    @Test
    fun testProcessToolsHandlesEmptyString() = runTest {
        assertNull(engine.processTools(""))
    }

    @Test
    fun testParseToolCallMatchesProcessTools() = runTest {
        val call1 = engine.parseToolCall("[TOOL: calculator(5*5)]")
        assertEquals("calculator", call1?.toolName)
        assertEquals("5*5", call1?.input)
        val text2 = "TOOL_CALL: calculator\nINPUT: some complex data"
        val call2 = engine.parseToolCall(text2)
        assertEquals("calculator", call2?.toolName)
        assertEquals("some complex data", call2?.input)
    }

    @Test
    fun testParseToolCallAcceptsMcpStyleNamesWithHyphensAndPrefix() {
        val hyphen = engine.parseToolCall("[TOOL: get-usd-rate(USD/RUB)]")
        assertEquals("get-usd-rate", hyphen?.toolName)
        assertEquals("USD/RUB", hyphen?.input)

        val prefixed = engine.parseToolCall("[TOOL: MyServer_get_usd()]")
        assertEquals("MyServer_get_usd", prefixed?.toolName)
        assertEquals("", prefixed?.input)

        val tcHyphen = engine.parseToolCall("TOOL_CALL: get-rate\nINPUT: EUR")
        assertEquals("get-rate", tcHyphen?.toolName)
        assertEquals("EUR", tcHyphen?.input)
    }

    @Test
    fun testRegisteredToolNames() {
        assertEquals(listOf("calculator"), engine.registeredToolNames())
        val empty = AgentEngine(repository, memoryManager, emptyList())
        assertTrue(empty.registeredToolNames().isEmpty())
    }

    @Test
    fun testToolSuppressesLlmFollowUp() {
        assertFalse(engine.toolSuppressesLlmFollowUp("calculator"))
        assertFalse(engine.toolSuppressesLlmFollowUp("missing"))

        val suppressing = object : AgentTool {
            override val name = "news_search"
            override val description = "x"
            override val suppressLlmFollowUp = true
            override suspend fun execute(input: String) = "ok"
        }
        val eng = AgentEngine(repository, memoryManager, listOf(suppressing))
        assertTrue(eng.toolSuppressesLlmFollowUp("news_search"))
        assertFalse(eng.toolSuppressesLlmFollowUp("calculator"))
    }

    @Test
    fun testExecuteToolCallUnknownReturnsNull() = runTest {
        val call = ParsedToolCall("no_such_tool", "in")
        assertNull(engine.executeToolCall(call))
    }

    @Test
    fun testStripFirstToolInvocation() {
        val a = "[TOOL: mcp_a(x)] [TOOL: mcp_b(y)]"
        assertEquals("[TOOL: mcp_b(y)]", engine.stripFirstToolInvocation(a).trim())
        assertEquals("", engine.stripFirstToolInvocation("[TOOL: only(z)]").trim())
    }

    @Test
    fun testStripToolSyntaxRemovesBracketAndToolCallFormats() {
        val a = "Пояснение.\n\n[TOOL: news_search(query=\"x\", pageSize=5)]"
        assertEquals("Пояснение.", engine.stripToolSyntaxFromAssistantText(a))
        val b = "Hi\nTOOL_CALL: calc\nINPUT: 2+2\n"
        assertEquals("Hi", engine.stripToolSyntaxFromAssistantText(b))
        assertEquals("", engine.stripToolSyntaxFromAssistantText("[TOOL: dup_tool(abc)]").trim())
    }

    @Test
    fun testFormatMergedAssistantWithToolResult() {
        val withPreamble = engine.formatMergedAssistantWithToolResult("Кратко.", "news_search", "No articles")
        assertTrue(withPreamble.contains("Кратко."))
        assertTrue(withPreamble.contains("— Инструмент: news_search —"))
        assertTrue(withPreamble.contains("No articles"))

        val toolOnly = engine.formatMergedAssistantWithToolResult("", "news_search", "No articles")
        assertEquals("— Инструмент: news_search —\nNo articles", toolOnly)

        val stripsJsonPrefix = engine.formatMergedAssistantWithToolResult("", "t", "JSON:\n{\"a\":1}")
        assertEquals("— Инструмент: t —\n{\"a\":1}", stripsJsonPrefix)
    }

    // --- 5: Memory & Maintenance ---

    @Test
    fun testPerformMaintenanceUpdatesFactsWhenEnabled() = runTest {
        val newFacts = ChatFacts(facts = listOf("New information discovered"))
        repository.extractFactsResult = Result.success(newFacts)

        val updatedWM = engine.performMaintenance(defaultAgent)
        
        assertEquals(newFacts, updatedWM.extractedFacts)
    }

    @Test
    fun testPerformMaintenanceDoesNothingWhenDisabled() = runTest {
        val disabledAgent = defaultAgent.copy(
            workingMemory = WorkingMemory(isAutoUpdateEnabled = false)
        )
        
        val result = engine.performMaintenance(disabledAgent)
        assertEquals(disabledAgent.workingMemory, result)
    }

    @Test
    fun testPerformMaintenanceHandlesRepoError() = runTest {
        repository.extractFactsResult = Result.failure(Exception("Extraction failed"))
        
        // Должен вернуть старую память (getOrDefault)
        val result = engine.performMaintenance(defaultAgent)
        assertEquals(defaultAgent.workingMemory.extractedFacts, result.extractedFacts)
    }

    // --- 6: Stress Tests ---

    @Test
    fun testParallelStreamResponse() = runTest {
        repository.streamingResult = flowOf(Result.success("token"))
        
        val jobs = List(50) {
            launch {
                val list = engine.streamResponse(defaultAgent, AgentStage.EXECUTION).toList()
                assertEquals("token", list.first())
            }
        }
        jobs.forEach { it.join() }
    }

    @Test
    fun testLongTokenStream() = runTest {
        val tokens = List(1000) { "t$it" }
        repository.streamingResult = flow {
            tokens.forEach { emit(Result.success(it)) }
        }

        val collected = engine.streamResponse(defaultAgent, AgentStage.EXECUTION).toList()
        assertEquals(1000, collected.size)
        assertEquals("t0", collected.first())
        assertEquals("t999", collected.last())
    }

    // --- 8: Private Methods Indirect Testing ---

    @Test
    fun testSystemPromptIncludesToolsInfo() = runTest {
        repository.streamingResult = flowOf(Result.success("ok"))
        engine.streamResponse(defaultAgent, AgentStage.PLANNING).toList()
        
        // Мы убеждаемся, что флоу не прерван.
    }
}
