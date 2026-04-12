package com.example.ai_develop.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/**
 * UseCase теперь является фабрикой для AutonomousAgent.
 */
open class ChatStreamingUseCase(
    private val repository: ChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val scope: CoroutineScope,
    private val agentToolRegistry: AgentToolRegistry,
) {
    private val activeAgents = mutableMapOf<String, AutonomousAgent>()

    private val engine = AgentEngine(repository, memoryManager) { agentToolRegistry.currentTools() }

    /**
     * Подгружает MCP-привязки из БД перед запросом к LLM.
     * Иначе первый ответ может уйти с пустым списком инструментов (гонка с асинхронным init).
     */
    open suspend fun ensureToolsLoaded() {
        agentToolRegistry.reloadFromDatabase()
    }

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

    /** После изменения MCP-инструментов пересоздать движки у всех активных агентов. */
    open fun evictAllAgents() {
        val ids = activeAgents.keys.toList()
        ids.forEach { evictAgent(it) }
    }

    /**
     * Совместимость со старым кодом.
     */
    open suspend fun invokeWithState(
        agentId: String,
        userMessage: String,
        provider: LLMProvider
    ): Flow<Result<String>> {
        ensureToolsLoaded()
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
