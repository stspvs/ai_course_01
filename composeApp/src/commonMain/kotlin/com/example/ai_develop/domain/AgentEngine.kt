package com.example.ai_develop.domain

import com.example.ai_develop.data.stripLeadingJsonColonLabel
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

    companion object {
        /** Нежадное тело скобок, чтобы в одном ответе было несколько `[TOOL: …][TOOL: …]`. */
        private val toolBracketRegex = "\\[TOOL: ([^\\s(]+)\\((.*?)\\)\\]".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val toolLineRegex = "TOOL_CALL: (\\S+)\\s+INPUT: (.*)".toRegex(RegexOption.DOT_MATCHES_ALL)

        /**
         * Первое вхождение любого из поддерживаемых форматов (если оба есть — по позиции в строке).
         */
        internal fun pickFirstToolMatch(text: String): MatchResult? {
            val r1 = toolBracketRegex.find(text)
            val r2 = toolLineRegex.find(text)
            return when {
                r1 == null -> r2
                r2 == null -> r1
                r1.range.first <= r2.range.first -> r1
                else -> r2
            }
        }
    }
    /**
     * Тот же запрос к LLM, что и в [streamResponse], без стриминга — для логов и отладки.
     */
    open fun prepareChatRequest(
        agent: Agent,
        stage: AgentStage,
        isJsonMode: Boolean = false,
        ragContext: String? = null,
        ragAttribution: RagAttribution? = null,
        /** Структурированный JSON-ответ RAG: без блока инструментов, схема answer/sources/quotes. */
        ragStructuredOutput: Boolean = false,
    ): PreparedLlmRequest {
        val systemPrompt = prepareSystemPrompt(agent, stage, ragContext, ragAttribution, ragStructuredOutput)
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
            stopWord = agent.stopWord,
            ragAttribution = ragAttribution,
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
        val m = pickFirstToolMatch(text) ?: return null
        return parseToolCallFromMatch(m)
    }

    /**
     * Все вызовы из одного ответа модели **по порядку** (несколько MCP в одном сообщении без промежуточного LLM).
     */
    open fun parseAllToolCalls(text: String): List<ParsedToolCall> {
        val out = mutableListOf<ParsedToolCall>()
        var remainder = text
        while (remainder.isNotBlank()) {
            val m = pickFirstToolMatch(remainder) ?: break
            out += parseToolCallFromMatch(m)
            remainder = buildString {
                append(remainder.substring(0, m.range.first))
                append(remainder.substring(m.range.last + 1))
            }
        }
        return out
    }

    private fun parseToolCallFromMatch(m: MatchResult): ParsedToolCall =
        if (m.value.startsWith("[TOOL:")) {
            ParsedToolCall(m.groupValues[1], m.groupValues[2])
        } else {
            ParsedToolCall(m.groupValues[1], m.groupValues[2].trim())
        }

    /**
     * Убирает из текста ассистента синтаксис вызова инструмента (после слияния с результатом в одном сообщении).
     */
    open fun stripToolSyntaxFromAssistantText(text: String): String {
        var t = text
        t = t.replace("\\[TOOL: ([^\\s(]+)\\((.*?)\\)\\]".toRegex(RegexOption.DOT_MATCHES_ALL), "")
        t = t.replace("TOOL_CALL: (\\S+)\\s+INPUT: (.*)".toRegex(RegexOption.DOT_MATCHES_ALL), "")
        return t.trim()
    }

    /** Удаляет первый вызов инструмента из текста (тот же порядок, что [parseAllToolCalls]). */
    open fun stripFirstToolInvocation(text: String): String {
        val m = pickFirstToolMatch(text) ?: return text
        return buildString {
            append(text.substring(0, m.range.first))
            append(text.substring(m.range.last + 1))
        }
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
        val body = stripLeadingJsonColonLabel(toolResult.trim())
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

    private fun prepareSystemPrompt(
        agent: Agent,
        stage: AgentStage,
        ragContext: String? = null,
        ragAttribution: RagAttribution? = null,
        ragStructuredOutput: Boolean = false,
    ): String {
        val basePrompt = memoryManager.wrapSystemPrompt(agent)
        val stageContext = "\n[SYSTEM INFO] CURRENT STAGE: $stage\n"

        // Любой ход с атрибуцией RAG — ответ «из базы», без смешивания с MCP/[TOOL:] (как при ragStructuredOutput).
        val suppressToolsForRagTurn = ragStructuredOutput || ragAttribution != null
        val toolsContext = if (!suppressToolsForRagTurn && tools.isNotEmpty()) {
            buildString {
                appendLine()
                appendLine("AVAILABLE TOOLS:")
                tools.forEach { appendLine("${it.name}: ${it.description}") }
                appendLine()
                appendLine("TOOL USE (mandatory when applicable):")
                appendLine("- For exchange rates, arithmetic, or any data that comes from a listed tool, you MUST call that tool. Do not invent or guess numbers.")
                appendLine("- Output one or more tool lines, each in this exact form. For several tools in one message, repeat the pattern, e.g. [TOOL: a(x)] [TOOL: b(y)]:")
                appendLine("[TOOL: toolname(input)]")
                appendLine("Use the exact tool name from the list above. Example: [TOOL: my-tool-name(input)]")
                appendLine("- If a tool expects a list of strings (see schema), put comma-separated values or a JSON array in parentheses, e.g. [TOOL: tool-name(USD,EUR)] or [TOOL: tool-name([\"USD\",\"EUR\"])].")
                appendLine("- If the user asks to fetch data AND save/export/write to a file in one request: emit ALL required [TOOL: ...] lines in this single reply (data first, then write/save). Do not stop after only the first tool.")
                appendLine("- After the last [TOOL: ...] line, add a short user-facing confirmation in the user's language (e.g. Russian): that data was saved, with file path or name when known. This text is shown in chat together with tool results; do not rely on tool JSON alone for that reassurance.")
                appendLine("- Long requests (several charts + save file, etc.) may require multiple [TOOL: ...] lines in one reply, or a second model turn after the first tool results appear — the app will continue automatically; still prefer packing all tools into one reply when possible.")
            }
        } else ""

        val ragSection = when {
            ragStructuredOutput && ragAttribution != null ->
                buildRagStructuredSystemSection(ragContext, ragAttribution)
            !ragStructuredOutput && ragAttribution != null ->
                buildRagUnstructuredKnowledgeBaseSection(ragContext, ragAttribution)
            !ragContext.isNullOrBlank() ->
                "\n\n[RELEVANT CONTEXT FROM KNOWLEDGE BASE]\n${ragContext.trim()}\n"
            else -> ""
        }

        return basePrompt + stageContext + toolsContext + ragSection
    }

    /**
     * RAG без JSON: статус базы и/или контекст — модель отвечает обычным текстом (стриминг не отключается).
     */
    private fun buildRagUnstructuredKnowledgeBaseSection(
        ragContext: String?,
        attribution: RagAttribution,
    ): String = buildString {
        val grounded = attribution.used && !attribution.insufficientRelevance &&
            !ragContext.isNullOrBlank()
        if (grounded) {
            appendLine()
            appendLine("[RELEVANT CONTEXT FROM KNOWLEDGE BASE]")
            appendLine(ragContext!!.trim())
            appendLine()
            appendLine("Опирайся на фрагменты выше. Ответь на русском обычным текстом, без JSON.")
        } else {
            appendLine()
            appendLine("[KNOWLEDGE BASE STATUS]")
            when {
                attribution.insufficientRelevance ->
                    appendLine("Релевантность найденных фрагментов ниже порога или контекст недоступен.")
                attribution.debug?.emptyReason != null ->
                    appendLine(attribution.debug?.emptyReason.orEmpty())
                else ->
                    appendLine("Контекст из базы знаний для этого запроса не использован.")
            }
            appendLine()
            appendLine("Ответь на русском обычным текстом, без JSON. Если опереться на базу нельзя — скажи честно и предложи уточнить вопрос.")
        }
        appendLine()
    }

    private fun buildRagStructuredSystemSection(
        ragContext: String?,
        attribution: RagAttribution,
    ): String = buildString {
        val grounded = attribution.used && !attribution.insufficientRelevance &&
            !ragContext.isNullOrBlank()
        if (grounded) {
            appendLine()
            appendLine("[RELEVANT CONTEXT FROM KNOWLEDGE BASE]")
            appendLine(ragContext.trim())
            appendLine()
        } else {
            appendLine()
            appendLine("[KNOWLEDGE BASE STATUS]")
            when {
                attribution.insufficientRelevance ->
                    appendLine("Релевантность найденных фрагментов ниже порога или контекст недоступен.")
                attribution.debug?.emptyReason != null ->
                    appendLine(attribution.debug?.emptyReason.orEmpty())
                else ->
                    appendLine("Контекст из базы знаний для этого запроса не использован.")
            }
            appendLine()
        }
        appendLine("[RAG OUTPUT FORMAT — REQUIRED]")
        appendLine("Ответь ОДНИМ JSON-объектом (без markdown-кодов, без текста до или после JSON).")
        appendLine("Ключи:")
        appendLine("- \"answer\": строка на русском. Если нельзя опереться на фрагменты ниже — честно скажи, что не знаешь, и попроси уточнить формулировку вопроса.")
        appendLine("- \"sources\": массив объектов { \"source\": string (имя файла), \"chunk_id\": string, \"chunk_index\": number }")
        appendLine("- \"quotes\": массив { \"text\": string, \"chunk_id\": string } — text дословно из фрагмента с этим chunk_id в блоке контекста.")
        if (grounded) {
            appendLine("При наличии контекста заполни sources и quotes по фрагментам выше; каждая цитата — подстрока соответствующего чанка.")
            appendLine(
                "Каждый chunk_id в sources и quotes скопируй из заголовка фрагмента (значение после `chunk_id=` в строке " +
                    "`--- Fragment ... ---`) символ в символ, без сокращений. Не подставляй chunk_index вместо chunk_id и не выдумывай новые идентификаторы."
            )
        } else {
            appendLine("Сейчас sources и quotes должны быть пустыми массивами []. Не выдумывай источники.")
        }
        appendLine()
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
