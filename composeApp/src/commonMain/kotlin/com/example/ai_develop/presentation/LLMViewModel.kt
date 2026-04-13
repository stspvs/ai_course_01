@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * События пользовательского интерфейса для LLMViewModel.
 */
sealed interface LLMEvent {
    data class SendMessage(val message: String) : LLMEvent
    data class SelectAgent(val agentId: String?) : LLMEvent
    data object CreateAgent : LLMEvent
    data class DeleteAgent(val agentId: String) : LLMEvent
    data class UpdateStreamingEnabled(val enabled: Boolean) : LLMEvent
    data class UpdateSendFullHistory(val enabled: Boolean) : LLMEvent
    data class UpdateMemoryStrategy(val strategy: ChatMemoryStrategy) : LLMEvent
    data object ClearChat : LLMEvent
    data class UpdateAgent(val params: UpdateAgentParams) : LLMEvent
    data class UpdateUserProfile(val id: String, val profile: UserProfile) : LLMEvent
    data class DuplicateAgent(val agentId: String) : LLMEvent
    data class CreateBranch(val fromMessageId: String, val branchName: String) : LLMEvent
    data class SwitchBranch(val branchId: String?) : LLMEvent
    data object ForceUpdateMemory : LLMEvent
}

class LLMViewModel(
    private val agentManagementUseCase: AgentManagementUseCase,
    private val chatStreamingUseCase: ChatStreamingUseCase,
    private val agentManager: AgentManager,
    private val getAgentsUseCase: GetAgentsUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    /** Скрывает агента в списке до завершения удаления в БД (мгновенный отклик UI). */
    private val pendingDeletedAgentIds = MutableStateFlow<Set<String>>(emptySet())

    val agentTemplates: List<AgentTemplate> = agentManager.templates

    init {
        // Инициализируем выбор агента по умолчанию
        onEvent(LLMEvent.SelectAgent(GENERAL_CHAT_ID))
        observeAgents()
    }

    /**
     * Логика наблюдения за агентами:
     * - Список агентов берётся из БД ([GetAgentsUseCase]), чтобы после перезапуска в чате были все сохранённые агенты.
     * - Для выбранного агента подставляется полный снимок из [AutonomousAgent] (сообщения, ветки и т.д.).
     * - flatMapLatest предотвращает утечки при смене агента.
     */
    private fun observeAgents() {
        viewModelScope.launch {
            combine(
                getAgentsUseCase(),
                pendingDeletedAgentIds,
                _state
                    .map { it.selectedAgentId ?: GENERAL_CHAT_ID }
                    .distinctUntilChanged()
                    .flatMapLatest { id ->
                        chatStreamingUseCase.ensureToolsLoaded()
                        val autonomousAgent = chatStreamingUseCase.getOrCreateAgent(id)
                        combine(
                            autonomousAgent.agent,
                            autonomousAgent.isProcessing
                        ) { snapshot, loading ->
                            Triple(id, snapshot, loading)
                        }
                    },
                chatStreamingUseCase.observeAvailableToolNames(),
            ) { agentsFromDb, pending, triple, toolNames ->
                val (targetId, snapshot, loading) = triple
                val visibleFromDb = agentsFromDb.filter { it.id !in pending }
                val updatedAgent = snapshot
                    ?: visibleFromDb.find { it.id == targetId }
                    ?: createDefaultAgent(targetId)
                val merged = mergeAgentsFromDbWithSelection(visibleFromDb, targetId, updatedAgent)
                Triple(merged, loading, toolNames)
            }.collect { (finalAgents, loading, toolNames) ->
                _state.updateIfChanged { currentState ->
                    currentState.copy(
                        agents = finalAgents,
                        isLoading = loading,
                        availableToolNames = toolNames,
                    )
                }
            }
        }
    }

    /** Принудительно перечитать инструменты из БД (редко нужно: список и так обновляется по [observeMcpRegistryChanges]). */
    fun refreshAvailableTools() {
        viewModelScope.launch {
            val names = chatStreamingUseCase.loadedAllToolNames()
            _state.updateIfChanged { it.copy(availableToolNames = names) }
        }
    }

    /**
     * Объединяет полный список агентов из БД с детальным состоянием текущего выбранного агента.
     */
    private fun mergeAgentsFromDbWithSelection(
        agentsFromDb: List<Agent>,
        targetId: String,
        selectedDetail: Agent
    ): List<Agent> {
        if (agentsFromDb.isEmpty()) {
            return listOf(selectedDetail)
        }
        val merged = agentsFromDb.map { agent ->
            if (agent.id == targetId) selectedDetail else agent
        }
        return if (merged.none { it.id == targetId }) {
            merged + selectedDetail
        } else {
            merged
        }
    }

    /**
     * Единая точка входа для событий UI.
     */
    fun onEvent(event: LLMEvent) {
        when (event) {
            is LLMEvent.SendMessage -> sendMessage(event.message)
            is LLMEvent.SelectAgent -> selectAgent(event.agentId)
            LLMEvent.CreateAgent -> createAgent()
            is LLMEvent.DeleteAgent -> deleteAgent(event.agentId)
            is LLMEvent.UpdateStreamingEnabled -> updateStreamingEnabled(event.enabled)
            is LLMEvent.UpdateSendFullHistory -> updateSendFullHistory(event.enabled)
            is LLMEvent.UpdateMemoryStrategy -> updateMemoryStrategy(event.strategy)
            LLMEvent.ClearChat -> clearChat()
            is LLMEvent.UpdateAgent -> updateAgent(event.params)
            is LLMEvent.UpdateUserProfile -> updateUserProfile(event.id, event.profile)
            is LLMEvent.DuplicateAgent -> duplicateAgent(event.agentId)
            is LLMEvent.CreateBranch -> createBranch(event.fromMessageId, event.branchName)
            is LLMEvent.SwitchBranch -> switchBranch(event.branchId)
            LLMEvent.ForceUpdateMemory -> forceUpdateMemory()
        }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return
        val agentId = _state.value.selectedAgentId ?: GENERAL_CHAT_ID
        viewModelScope.launch {
            chatStreamingUseCase.ensureToolsLoaded()
            chatStreamingUseCase.getOrCreateAgent(agentId).sendMessage(message).collect()
        }
    }

    fun selectAgent(agentId: String?) {
        val id = agentId ?: GENERAL_CHAT_ID
        if (_state.value.selectedAgentId == id) return
        _state.updateIfChanged { it.copy(selectedAgentId = id) }
    }

    fun createAgent() {
        viewModelScope.launch {
            val newId = agentManagementUseCase.createAgent()
            onEvent(LLMEvent.SelectAgent(newId))
        }
    }

    fun deleteAgent(agentId: String) {
        if (agentId == GENERAL_CHAT_ID) return
        val currentAgents = _state.value.agents
        val idx = currentAgents.indexOfFirst { it.id == agentId }
        if (idx < 0) return
        val newSelectionId = when {
            idx > 0 -> currentAgents[idx - 1].id
            currentAgents.size > idx + 1 -> currentAgents[idx + 1].id
            else -> GENERAL_CHAT_ID
        }
        pendingDeletedAgentIds.update { it + agentId }
        selectAgent(newSelectionId)
        viewModelScope.launch {
            try {
                chatStreamingUseCase.evictAgent(agentId)
                agentManagementUseCase.deleteAgent(agentId)
            } finally {
                pendingDeletedAgentIds.update { it - agentId }
            }
        }
    }

    fun updateStreamingEnabled(enabled: Boolean) {
        _state.updateIfChanged { it.copy(isStreamingEnabled = enabled) }
    }

    fun updateSendFullHistory(enabled: Boolean) {
        _state.updateIfChanged { it.copy(sendFullHistory = enabled) }
    }

    fun updateMemoryStrategy(strategy: ChatMemoryStrategy) {
        val id = _state.value.selectedAgentId ?: return
        viewModelScope.launch {
            agentManagementUseCase.updateMemoryStrategy(id, strategy)
        }
    }

    fun clearChat() {
        val id = _state.value.selectedAgentId ?: GENERAL_CHAT_ID
        viewModelScope.launch {
            agentManagementUseCase.clearChat(id)
        }
    }

    fun updateAgent(params: UpdateAgentParams) {
        viewModelScope.launch {
            agentManagementUseCase.updateAgent(params)
        }
    }

    fun updateAgent(
        id: String, name: String, systemPrompt: String, temperature: Double,
        provider: LLMProvider, stopWord: String, maxTokens: Int, memoryStrategy: ChatMemoryStrategy
    ) {
        onEvent(LLMEvent.UpdateAgent(UpdateAgentParams(
            id, name, systemPrompt, temperature, provider, stopWord, maxTokens, memoryStrategy
        )))
    }

    fun updateUserProfile(id: String, profile: UserProfile) {
        viewModelScope.launch {
            agentManagementUseCase.saveProfile(id, profile)
        }
    }

    fun duplicateAgent(agentId: String) {
        viewModelScope.launch {
            val newId = agentManagementUseCase.duplicateAgent(agentId)
            if (newId != null) {
                onEvent(LLMEvent.SelectAgent(newId))
            }
        }
    }

    private fun createDefaultAgent(targetId: String): Agent {
        return Agent(
            id = targetId,
            name = if (targetId == GENERAL_CHAT_ID) "Общий чат" else "Новый агент",
            systemPrompt = "You are a helpful assistant.",
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 2000
        )
    }

    fun createBranch(fromMessageId: String, branchName: String) {
        val id = _state.value.selectedAgentId ?: return
        viewModelScope.launch {
            agentManagementUseCase.createBranch(id, fromMessageId, branchName)
        }
    }

    fun switchBranch(branchId: String?) {
        val id = _state.value.selectedAgentId ?: return
        viewModelScope.launch {
            agentManagementUseCase.switchBranch(id, branchId)
        }
    }

    /**
     * Принудительное обновление памяти.
     * Использует Flow подход (Проблема №1) и защищено от race condition (Проблема №2).
     */
    fun forceUpdateMemory() {
        val agentId = _state.value.selectedAgentId ?: return
        
        viewModelScope.launch {
            agentManagementUseCase.forceUpdateMemory(agentId)
                .collect { update ->
                    update.agentUpdate?.let { (id, transform) ->
                        _state.updateIfChanged { state ->
                            state.copy(
                                agents = state.agents.updateAgent(id, transform)
                            )
                        }
                    }
                    _state.updateIfChanged { it.copy(isLoading = update.isLoading) }
                }
        }
    }
}

/**
 * Extension для MutableStateFlow, чтобы обновлять значение только при его изменении.
 */
inline fun <T> MutableStateFlow<T>.updateIfChanged(
    transform: (T) -> T
) {
    val new = transform(value)
    if (new != value) value = new
}
