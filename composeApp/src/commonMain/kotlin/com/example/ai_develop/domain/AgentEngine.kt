package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.uuid.ExperimentalUuidApi

/**
 * Интерфейс для инструментов агента.
 */
interface AgentTool {
    val name: String
    val description: String
    suspend fun execute(input: String): String

    /**
     * Если true — после выполнения этого инструмента повторный вызов LLM для того же пользовательского сообщения
     * не выполняется (ответ считается полностью заданным инструментом, например MCP).
     */
    val suppressLlmFollowUp: Boolean get() = false
}

/** Распознанный вызов инструмента из ответа модели ([parseToolCall]). */
data class ParsedToolCall(val toolName: String, val input: String)

data class PreparedLlmRequest(
    val systemPrompt: String,
    val inputMessages: List<ChatMessage>,
    val snapshot: LlmRequestSnapshot
)

/**
 * Движок агента — инкапсулирует логику взаимодействия с LLM и выполнение инструментов.
 * Он не хранит состояние, а только обрабатывает переданного агента.
 */
@OptIn(ExperimentalUuidApi::class)
open class AgentEngine(
    private val repository: ChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val toolsProvider: () -> List<AgentTool> = { emptyList() }
) {
    constructor(
        repository: ChatRepository,
        memoryManager: ChatMemoryManager,
        tools: List<AgentTool>
    ) : this(repository, memoryManager, { tools })

    private val tools: List<AgentTool> get() = toolsProvider()
    /**
     * Тот же запрос к LLM, что и в [streamResponse], без стриминга — для логов и отладки.
     */
    open fun prepareChatRequest(
        agent: Agent,
        stage: AgentStage,
        isJsonMode: Boolean = false
    ): PreparedLlmRequest {
        val systemPrompt = prepareSystemPrompt(agent, stage)
        val inputMessages = prepareInputMessages(agent)
        val snapshot = LlmRequestSnapshot(
            effectiveSystemPrompt = systemPrompt,
            inputMessagesText = formatLlmInputMessagesText(inputMessages),
            providerName = agent.provider.name,
            model = agent.provider.model,
            agentStage = stage.toString(),
            temperature = agent.temperature,
            maxTokens = agent.maxTokens,
            isJsonMode = isJsonMode,
            stopWord = agent.stopWord
        )
        return PreparedLlmRequest(systemPrompt, inputMessages, snapshot)
    }

    /**
     * Стриминг по уже подготовленному запросу (тот же [PreparedLlmRequest], что ушёл в лог).
     */
    open fun streamFromPrepared(
        agent: Agent,
        prepared: PreparedLlmRequest
    ): Flow<String> = flow {
        repository.chatStreaming(
            messages = prepared.inputMessages,
            systemPrompt = prepared.systemPrompt,
            maxTokens = agent.maxTokens,
            temperature = agent.temperature,
            stopWord = agent.stopWord,
            isJsonMode = prepared.snapshot.isJsonMode,
            provider = agent.provider
        ).collect { result ->
            result.onSuccess { chunk ->
                emit(chunk)
            }.onFailure { throw it }
        }
    }

    /**
     * Формирует поток токенов и возвращает финальное сообщение AI.
     */
    open fun streamResponse(
        agent: Agent,
        stage: AgentStage
    ): Flow<String> = streamFromPrepared(agent, prepareChatRequest(agent, stage, isJsonMode = false))

    /**
     * Извлекает первый вызов инструмента из текста модели (два поддерживаемых формата).
     */
    open fun parseToolCall(text: String): ParsedToolCall? {
        val regex1 = "\\[TOOL: (\\w+)\\((.*)\\)\\]".toRegex()
        regex1.find(text)?.let { m ->
            return ParsedToolCall(m.groupValues[1], m.groupValues[2])
        }
        val regex2 = "TOOL_CALL: (\\w+)\\s+INPUT: (.*)".toRegex(RegexOption.DOT_MATCHES_ALL)
        regex2.find(text)?.let { m ->
            return ParsedToolCall(m.groupValues[1], m.groupValues[2].trim())
        }
        return null
    }

    /**
     * Убирает из текста ассистента синтаксис вызова инструмента (после слияния с результатом в одном сообщении).
     */
    open fun stripToolSyntaxFromAssistantText(text: String): String {
        var t = text
        t = t.replace("\\[TOOL: (\\w+)\\((.*)\\)\\]".toRegex(RegexOption.DOT_MATCHES_ALL), "")
        t = t.replace("TOOL_CALL: (\\w+)\\s+INPUT: (.*)".toRegex(RegexOption.DOT_MATCHES_ALL), "")
        return t.trim()
    }

    /**
     * Один блок ответа для UI/истории: опционально преамбула модели, затем пометка инструмента и результат.
     * В [AutonomousAgent] преамбула не используется — ответ формирует только инструмент.
     */
    open fun formatMergedAssistantWithToolResult(
        strippedPreamble: String,
        toolName: String,
        toolResult: String
    ): String {
        val prefix = strippedPreamble.trim()
        val body = toolResult.trim()
        return buildString {
            if (prefix.isNotEmpty()) {
                append(prefix)
                append("\n\n")
            }
            append("— Инструмент: $toolName —\n")
            append(body)
        }
    }

    open suspend fun executeToolCall(call: ParsedToolCall): String? =
        tools.find { it.name == call.toolName }?.execute(call.input)

    /** Список имён зарегистрированных инструментов (для сообщений об ошибках). */
    open fun registeredToolNames(): List<String> = tools.map { it.name }

    /** После такого инструмента не вызывать LLM повторно (см. [AgentTool.suppressLlmFollowUp]). */
    open fun toolSuppressesLlmFollowUp(toolName: String): Boolean =
        tools.find { it.name == toolName }?.suppressLlmFollowUp == true

    /**
     * Анализирует сообщение на наличие вызовов инструментов (Tool Calling).
     * В реальном мире тут был бы парсинг JSON или специальных тегов.
     */
    open suspend fun processTools(text: String): String? {
        val call = parseToolCall(text) ?: return null
        return executeToolCall(call)
    }

    private fun prepareSystemPrompt(agent: Agent, stage: AgentStage): String {
        val basePrompt = memoryManager.wrapSystemPrompt(agent)
        val stageContext = "\n[SYSTEM INFO] CURRENT STAGE: $stage\n"

        val toolsContext = if (tools.isNotEmpty()) {
            buildString {
                appendLine()
                appendLine("AVAILABLE TOOLS:")
                tools.forEach { appendLine("${it.name}: ${it.description}") }
                appendLine()
                appendLine("TOOL USE (mandatory when applicable):")
                appendLine("- For current news, weather, or arithmetic you MUST call the matching tool. Do not invent headlines or facts.")
                appendLine("- Output a single line only, in this exact form (then stop):")
                appendLine("[TOOL: toolname(input)]")
                appendLine("Examples: [TOOL: news_search(world news)]  [TOOL: weather(Paris)]  [TOOL: calculator(12*34)]")
            }.toString()
        } else ""

        return basePrompt + stageContext + toolsContext
    }

    private fun prepareInputMessages(agent: Agent): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        memoryManager.getShortTermMemoryMessage(agent)?.let { messages.add(it) }

        messages.addAll(
            memoryManager.processMessages(
                agent.messages,
                agent.memoryStrategy,
                agent.currentBranchId,
                agent.branches
            )
        )
        return messages
    }

    open suspend fun performMaintenance(agent: Agent): WorkingMemory {
        if (!agent.workingMemory.isAutoUpdateEnabled) return agent.workingMemory

        val result = repository.extractFacts(
            messages = agent.messages.takeLast(agent.workingMemory.analysisWindowSize),
            currentFacts = agent.workingMemory.extractedFacts,
            provider = agent.provider
        )

        return agent.workingMemory.copy(
            extractedFacts = result.getOrDefault(agent.workingMemory.extractedFacts)
        )
    }
}
