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
    private val agentManager: AgentManager
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    val agentTemplates: List<AgentTemplate> = agentManager.templates

    init {
        // Инициализируем выбор агента по умолчанию
        onEvent(LLMEvent.SelectAgent(GENERAL_CHAT_ID))
        observeAgents()
    }

    /**
     * Логика наблюдения за агентами:
     * - flatMapLatest предотвращает утечки при смене агента.
     * - combine делает isLoading реактивным.
     */
    private fun observeAgents() {
        viewModelScope.launch {
            _state
                .map { it.selectedAgentId ?: GENERAL_CHAT_ID }
                .distinctUntilChanged()
                .flatMapLatest { id ->
                    val autonomousAgent = chatStreamingUseCase.getOrCreateAgent(id)
                    combine(
                        autonomousAgent.agent,
                        autonomousAgent.isProcessing
                    ) { snapshot, loading ->
                        Triple(id, snapshot, loading)
                    }
                }
                .collect { (targetId, snapshot, loading) ->
                    _state.updateIfChanged { currentState ->
                        val updatedAgent = snapshot ?: createDefaultAgent(targetId)
                        val agents = currentState.agents

                        val updatedList = agents.updateAgent(targetId) { updatedAgent }
                        val finalAgents = if (updatedList === agents && agents.none { it.id == targetId }) {
                            agents + updatedAgent
                        } else {
                            updatedList
                        }

                        currentState.copy(
                            agents = finalAgents,
                            selectedAgentId = targetId,
                            isLoading = loading
                        )
                    }
                }
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
        val autonomousAgent = chatStreamingUseCase.getOrCreateAgent(agentId)

        autonomousAgent.sendMessage(message)
            .launchIn(viewModelScope)
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
        viewModelScope.launch {
            agentManagementUseCase.deleteAgent(agentId)
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
        val id = _state.value.selectedAgentId ?: return
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
