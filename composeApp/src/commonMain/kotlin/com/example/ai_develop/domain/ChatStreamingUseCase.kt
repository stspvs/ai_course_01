package com.example.ai_develop.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * UseCase теперь является фабрикой/мостом для AutonomousAgent.
 * Он обеспечивает плавный переход от старой модели к новой.
 */
open class ChatStreamingUseCase(
    private val repository: ChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val scope: CoroutineScope
) {
    private val activeAgents = mutableMapOf<String, AutonomousAgent>()

    /**
     * Получает или создает автономного агента для выполнения запроса.
     */
    fun getOrCreateAgent(agentId: String): AutonomousAgent {
        return activeAgents.getOrPut(agentId) {
            AutonomousAgent(agentId, repository, memoryManager, scope)
        }
    }

    /**
     * Совместимость со старым кодом: выполняет стриминг, используя AutonomousAgent.
     */
    open suspend fun invokeWithState(
        agentId: String,
        userMessage: String,
        provider: LLMProvider // Провайдер теперь берется из настроек агента, но оставляем для совместимости
    ): Flow<Result<String>> {
        val agent = getOrCreateAgent(agentId)
        
        return flow {
            // Запускаем процесс отправки сообщения в scope агента
            val job = scope.launch {
                agent.sendMessage(userMessage)
            }
            
            // Подписываемся на поток токенов из агента
            // Используем transformWhile чтобы завершить поток когда обработка закончена
            agent.partialResponse.collect { token ->
                emit(Result.success(token))
            }
        }.takeWhile { 
            agent.isProcessing.value 
        }
    }

    /**
     * Прямой вызов стриминга (без сохранения состояния агента)
     */
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
}
