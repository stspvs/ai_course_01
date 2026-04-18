package com.example.ai_develop.domain.chat

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.RagContextRetriever
import com.example.ai_develop.data.RagPipelineSettingsRepository
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
    private val mcpRepository: McpRepository,
    private val ragContextRetriever: RagContextRetriever? = null,
    private val ragPipelineSettingsRepository: RagPipelineSettingsRepository? = null,
) {
    private val activeAgents = mutableMapOf<String, AutonomousAgent>()

    /**
     * Увеличивается при [evictAgent] / [evictAllAgents]. UI должен снова подписаться на [getOrCreateAgent],
     * иначе остаётся отменённый [AutonomousAgent] и чат перестаёт получать сообщения.
     */
    private val _agentCacheGeneration = MutableStateFlow(0L)
    val agentCacheGeneration: StateFlow<Long> = _agentCacheGeneration.asStateFlow()

    private val engine = AgentEngine(repository, memoryManager) { agent -> agentToolRegistry.toolsFor(agent) }

    /**
     * Подгружает MCP-привязки из БД перед запросом к LLM.
     * Иначе первый ответ может уйти с пустым списком инструментов (гонка с асинхронным init).
     */
    open suspend fun ensureToolsLoaded() {
        agentToolRegistry.reloadFromDatabase()
    }

    /** Имена MCP-инструментов, доступных агентам в чате (после синхронизации с БД). */
    open suspend fun loadedMcpToolNames(): List<String> {
        ensureToolsLoaded()
        return agentToolRegistry.currentMcpToolNames()
    }

    /** Имена инструментов для [agent] (базовые + назначенные MCP). */
    open suspend fun toolNamesForAgent(agent: Agent): List<String> {
        ensureToolsLoaded()
        return agentToolRegistry.toolsFor(agent).map { it.name }.sorted()
    }

    /** Эмит при старте и при любых изменениях MCP в БД — для пересчёта списка инструментов в UI. */
    open fun observeMcpRegistryRefresh(): Flow<Unit> = merge(
        flowOf(Unit),
        mcpRepository.observeMcpRegistryChanges(),
    )

    /**
     * Получает или создает автономного агента.
     */
    open fun getOrCreateAgent(agentId: String, taskIdForMessagePersistence: String? = null): AutonomousAgent {
        return activeAgents.getOrPut(agentId) {
            AutonomousAgent(
                agentId,
                repository,
                engine,
                scope,
                taskIdForMessagePersistence,
                ragContextRetriever,
                ragPipelineSettingsRepository,
            )
        }
    }

    /** Сбрасывает кэш агента после смены данных в БД (например сброс задачи). */
    open fun evictAgent(agentId: String) {
        activeAgents.remove(agentId)?.dispose()
        _agentCacheGeneration.update { it + 1L }
    }

    /** После изменения MCP-инструментов пересоздать движки у всех активных агентов. */
    open fun evictAllAgents() {
        activeAgents.keys.toList().forEach { activeAgents.remove(it)?.dispose() }
        _agentCacheGeneration.update { it + 1L }
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
