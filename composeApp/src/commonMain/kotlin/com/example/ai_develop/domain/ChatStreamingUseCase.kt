package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json

open class ChatStreamingUseCase(
    private val repository: ChatRepository,
    private val memoryManager: ChatMemoryManager
) {
    open operator fun invoke(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        provider: LLMProvider
    ): Flow<Result<String>> {
        return repository.chatStreaming(
            messages, systemPrompt, maxTokens, temperature, stopWord, isJsonMode, provider
        )
    }

    /**
     * Выполняет стриминг чата для конкретного агента, строго соблюдая его внутреннюю стратегию памяти.
     * Это «Закон»: вся логика формирования истории инкапсулирована в агенте и memoryManager.
     */
    open suspend fun invokeForAgent(
        agent: Agent,
        userMessageText: String,
        isJsonMode: Boolean = false
    ): Flow<Result<String>> {
        val lastMessage = agent.messages.lastOrNull()
        
        val userMessage = ChatMessage(
            message = userMessageText,
            role = "user",
            source = SourceType.USER,
            parentId = lastMessage?.id,
            timestamp = System.currentTimeMillis()
        )

        // Формируем историю согласно стратегии агента
        val processedHistory = memoryManager.processMessages(
            messages = agent.messages + userMessage,
            strategy = agent.memoryStrategy,
            currentBranchId = agent.currentBranchId,
            agentBranches = agent.branches
        )

        // Добавляем системные вставки из памяти (summary, facts), если они есть
        val finalMessages = mutableListOf<ChatMessage>()
        memoryManager.getShortTermMemoryMessage(agent)?.let { finalMessages.add(it) }
        finalMessages.addAll(processedHistory)

        return repository.chatStreaming(
            messages = finalMessages,
            systemPrompt = memoryManager.wrapSystemPrompt(agent),
            maxTokens = agent.maxTokens,
            temperature = agent.temperature,
            stopWord = agent.stopWord,
            isJsonMode = isJsonMode,
            provider = agent.provider
        )
    }

    // Устаревший метод, который нарушал инкапсуляцию, заменяем или помечаем к удалению
    @Deprecated("Use invokeForAgent to respect agent's memory strategy", ReplaceWith("invokeForAgent"))
    open suspend fun invokeWithState(
        agentId: String,
        userMessage: String,
        provider: LLMProvider
    ): Flow<Result<String>> {
        // Оставляем для совместимости, но логика должна быть приведена к стандарту агента
        val agent = repository.getAgentState(agentId)?.let { state ->
             // Пытаемся восстановить минимальный объект агента для работы
             Agent(id = agentId, name = "Agent", systemPrompt = "", temperature = 0.7, provider = provider, stopWord = "", maxTokens = 2000)
        } ?: return invoke(emptyList(), "", 2000, 0.7, "", false, provider)
        
        return invokeForAgent(agent, userMessage)
    }
}
