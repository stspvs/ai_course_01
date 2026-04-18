@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.McpToolBindingRecord
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val agentChatSessionPort: AgentChatSessionPort,
    private val agentManager: AgentManager,
    private val getAgentsUseCase: GetAgentsUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    private val uiMutex = Mutex()

    private val chatAgentsSessionFlow = ChatAgentsSessionFlow(getAgentsUseCase, agentChatSessionPort)

    /** Скрывает агента в списке до завершения удаления в БД (мгновенный отклик UI). */
    private val pendingDeletedAgentIds = MutableStateFlow<Set<String>>(emptySet())

    val agentTemplates: List<AgentTemplate> = agentManager.templates

    init {
        onEvent(LLMEvent.SelectAgent(GENERAL_CHAT_ID))
        observeSessionSlice()
    }

    private suspend fun applyUiResult(result: LlmUiResult) {
        uiMutex.withLock {
            _state.updateIfChanged { reduceLlmState(it, result) }
        }
    }

    private fun postUiResult(result: LlmUiResult) {
        viewModelScope.launch {
            applyUiResult(result)
        }
    }

    private fun observeSessionSlice() {
        viewModelScope.launch {
            chatAgentsSessionFlow.observe(
                pendingDeletedAgentIds,
                state.map { it.selectedAgentId ?: GENERAL_CHAT_ID }.distinctUntilChanged(),
            ).collect { slice ->
                applyUiResult(slice)
            }
        }
    }

    /** Пересчитать имена инструментов для текущего выбранного агента. */
    fun refreshAvailableTools() {
        viewModelScope.launch {
            val sel = _state.value.selectedAgentId ?: GENERAL_CHAT_ID
            val agent = _state.value.agents.find { it.id == sel } ?: defaultAgentForToolbar(sel)
            val names = agentChatSessionPort.toolNamesForAgent(agent)
            applyUiResult(LlmUiResult.ToolsOnlyUpdated(names))
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
            is LLMEvent.UpdateStreamingEnabled -> postUiResult(LlmUiResult.StreamingEnabledChanged(event.enabled))
            is LLMEvent.UpdateSendFullHistory -> postUiResult(LlmUiResult.SendFullHistoryChanged(event.enabled))
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
            agentChatSessionPort.ensureToolsLoaded()
            agentChatSessionPort.getOrCreateAgent(agentId, null).sendMessage(message).collect()
        }
    }

    fun selectAgent(agentId: String?) {
        val id = agentId ?: GENERAL_CHAT_ID
        if (_state.value.selectedAgentId == id) return
        postUiResult(LlmUiResult.SelectionChanged(id))
    }

    fun createAgent() {
        viewModelScope.launch {
            val newId = agentManagementUseCase.createAgent()
            applyUiResult(LlmUiResult.SelectionChanged(newId))
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
                agentChatSessionPort.evictAgent(agentId)
                agentManagementUseCase.deleteAgent(agentId)
            } finally {
                pendingDeletedAgentIds.update { it - agentId }
            }
        }
    }

    fun updateStreamingEnabled(enabled: Boolean) {
        postUiResult(LlmUiResult.StreamingEnabledChanged(enabled))
    }

    fun updateSendFullHistory(enabled: Boolean) {
        postUiResult(LlmUiResult.SendFullHistoryChanged(enabled))
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
        provider: LLMProvider, stopWord: String, maxTokens: Int, memoryStrategy: ChatMemoryStrategy,
        ragEnabled: Boolean = true,
    ) {
        onEvent(LLMEvent.UpdateAgent(UpdateAgentParams(
            id, name, systemPrompt, temperature, provider, stopWord, maxTokens, memoryStrategy, ragEnabled
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
                applyUiResult(LlmUiResult.SelectionChanged(newId))
            }
        }
    }

    suspend fun loadMcpAssignmentCatalog(): List<Pair<String, McpToolBindingRecord>> =
        agentManagementUseCase.mcpAssignmentCatalogRows()

    fun setMcpAllowedBindingIds(agentId: String, ids: List<String>) {
        viewModelScope.launch {
            agentManagementUseCase.setMcpAllowedBindingIds(agentId, ids)
        }
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

    fun forceUpdateMemory() {
        val agentId = _state.value.selectedAgentId ?: return

        viewModelScope.launch {
            agentManagementUseCase.forceUpdateMemory(agentId)
                .collect { update ->
                    update.agentUpdate?.let { (id, transform) ->
                        applyUiResult(LlmUiResult.MemoryAgentPatched(id, transform))
                    }
                    applyUiResult(LlmUiResult.MemoryLoading(update.isLoading))
                }
        }
    }
}

private fun defaultAgentForToolbar(targetId: String): Agent {
    return Agent(
        id = targetId,
        name = if (targetId == GENERAL_CHAT_ID) "Общий чат" else "Новый агент",
        systemPrompt = "You are a helpful assistant.",
        temperature = 0.7,
        provider = LLMProvider.Yandex(),
        stopWord = "",
        maxTokens = 2000,
        mcpAllowedBindingIds = emptyList(),
    )
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
