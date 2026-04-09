package com.example.ai_develop.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/**
 * UseCase теперь является фабрикой для AutonomousAgent.
 */
open class ChatStreamingUseCase(
    private val repository: ChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val scope: CoroutineScope
) {
    private val activeAgents = mutableMapOf<String, AutonomousAgent>()
    
    // Подключаем тестовые инструменты
    private val engine = AgentEngine(
        repository, 
        memoryManager, 
        listOf(WeatherTool(), CalculatorTool())
    )

    /**
     * Получает или создает автономного агента.
     */
    open fun getOrCreateAgent(agentId: String, taskIdForMessagePersistence: String? = null): AutonomousAgent {
        return activeAgents.getOrPut(agentId) {
            AutonomousAgent(agentId, repository, engine, scope, taskIdForMessagePersistence)
        }
    }

    /** Сбрасывает кэш агента после смены данных в БД (например сброс задачи). */
    open fun evictAgent(agentId: String) {
        activeAgents.remove(agentId)?.dispose()
    }

    /**
     * Совместимость со старым кодом.
     */
    open suspend fun invokeWithState(
        agentId: String,
        userMessage: String,
        provider: LLMProvider
    ): Flow<Result<String>> {
        val agent = getOrCreateAgent(agentId)
        
        return flow {
            agent.sendMessage(userMessage).collect { token ->
                emit(Result.success(token))
            }
        }
    }

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
