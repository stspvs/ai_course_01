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
}

/**
 * Движок агента — инкапсулирует логику взаимодействия с LLM и выполнение инструментов.
 * Он не хранит состояние, а только обрабатывает переданного агента.
 */
@OptIn(ExperimentalUuidApi::class)
open class AgentEngine(
    private val repository: ChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val tools: List<AgentTool> = emptyList()
) {
    /**
     * Формирует поток токенов и возвращает финальное сообщение AI.
     */
    open fun streamResponse(
        agent: Agent,
        stage: AgentStage
    ): Flow<String> = flow {
        val systemPrompt = prepareSystemPrompt(agent, stage)
        val inputMessages = prepareInputMessages(agent)

        val fullResponse = StringBuilder()

        repository.chatStreaming(
            messages = inputMessages,
            systemPrompt = systemPrompt,
            maxTokens = agent.maxTokens,
            temperature = agent.temperature,
            stopWord = agent.stopWord,
            isJsonMode = false,
            provider = agent.provider
        ).collect { result ->
            result.onSuccess { chunk ->
                fullResponse.append(chunk)
                emit(chunk)
            }.onFailure { throw it }
        }
    }

    /**
     * Анализирует сообщение на наличие вызовов инструментов (Tool Calling).
     * В реальном мире тут был бы парсинг JSON или специальных тегов.
     */
    open suspend fun processTools(text: String): String? {
        // Поддержка двух форматов:
        // 1. [TOOL: name(input)]
        // 2. TOOL_CALL: name\nINPUT: input (из промпта пользователя)

        val regex1 = "\\[TOOL: (\\w+)\\((.*)\\)\\]".toRegex()
        val match1 = regex1.find(text)
        if (match1 != null) {
            val toolName = match1.groupValues[1]
            val input = match1.groupValues[2]
            return tools.find { it.name == toolName }?.execute(input)
        }

        val regex2 = "TOOL_CALL: (\\w+)\\s+INPUT: (.*)".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match2 = regex2.find(text)
        if (match2 != null) {
            val toolName = match2.groupValues[1]
            val input = match2.groupValues[2].trim()
            return tools.find { it.name == toolName }?.execute(input)
        }

        return null
    }

    private fun prepareSystemPrompt(agent: Agent, stage: AgentStage): String {
        val basePrompt = memoryManager.wrapSystemPrompt(agent)
        val stageContext = "\n[SYSTEM INFO] CURRENT STAGE: $stage\n"

        val toolsContext = if (tools.isNotEmpty()) {
            "\nAVAILABLE TOOLS:\n" + tools.joinToString("\n") { "${it.name}: ${it.description}" } +
                    "\nTo use a tool, output: [TOOL: name(input)]\n"
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
