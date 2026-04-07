@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class LLMViewModel(
    private val repository: ChatRepository,
    private val useCase: ChatStreamingUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    private val agentManager = AgentManager()
    private var currentJob: Job? = null

    val agentTemplates: List<AgentTemplate> = agentManager.templates

    init {
        // По умолчанию выбираем общий чат
        selectAgent(GENERAL_CHAT_ID)
        observeAgents()
    }

    private fun observeAgents() {
        viewModelScope.launch {
            _state.map { it.selectedAgentId }.distinctUntilChanged().collectLatest { id ->
                val targetId = id ?: GENERAL_CHAT_ID
                val autonomousAgent = useCase.getOrCreateAgent(targetId)
                
                autonomousAgent.agent.collect { snapshot ->
                    _state.update { currentState ->
                        val updatedAgent = snapshot ?: Agent(
                            id = targetId,
                            name = if (targetId == GENERAL_CHAT_ID) "Общий чат" else "Новый агент",
                            systemPrompt = "You are a helpful assistant.",
                            temperature = 0.7,
                            provider = LLMProvider.Yandex(),
                            stopWord = "",
                            maxTokens = 2000
                        )
                        
                        val updatedList = if (currentState.agents.any { it.id == targetId }) {
                            currentState.agents.map { if (it.id == targetId) updatedAgent else it }
                        } else {
                            currentState.agents + updatedAgent
                        }
                        
                        currentState.copy(
                            agents = updatedList,
                            selectedAgentId = targetId,
                            isLoading = autonomousAgent.isProcessing.value
                        )
                    }
                }
            }
        }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return
        val agentId = _state.value.selectedAgentId ?: GENERAL_CHAT_ID
        val autonomousAgent = useCase.getOrCreateAgent(agentId)

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            autonomousAgent.sendMessage(message)
        }
    }

    fun selectAgent(agentId: String?) {
        _state.update { it.copy(selectedAgentId = agentId ?: GENERAL_CHAT_ID) }
    }

    fun createAgent() {
        val newId = Uuid.random().toString()
        viewModelScope.launch {
            val newState = AgentState(agentId = newId, name = "Новый агент")
            repository.saveAgentState(newState)
            selectAgent(newId)
        }
    }

    fun deleteAgent(agentId: String) {
        viewModelScope.launch {
            // repository.deleteAgentState(agentId)
            _state.update { it.copy(agents = it.agents.filter { it.id != agentId }) }
        }
    }

    fun updateStreamingEnabled(enabled: Boolean) {
        _state.update { it.copy(isStreamingEnabled = enabled) }
    }

    fun updateSendFullHistory(enabled: Boolean) {
        _state.update { it.copy(sendFullHistory = enabled) }
    }

    fun updateMemoryStrategy(strategy: ChatMemoryStrategy) {
        val id = _state.value.selectedAgentId ?: return
        viewModelScope.launch {
            val state = repository.getAgentState(id) ?: return@launch
            repository.saveAgentState(state.copy(memoryStrategy = strategy))
            useCase.getOrCreateAgent(id).refreshAgent()
        }
    }

    fun createBranch(fromMessageId: String, branchName: String) {
        // Логика веток пока не полностью перенесена в AutonomousAgent, 
        // но мы можем обновить snapshot
    }

    fun switchBranch(branchId: String?) {
        // Аналогично
    }

    fun forceUpdateMemory() {
        // Вызов обслуживания вручную
    }

    fun clearChat() {
        val id = _state.value.selectedAgentId ?: return
        viewModelScope.launch {
            val state = repository.getAgentState(id) ?: return@launch
            repository.saveAgentState(state.copy(messages = emptyList()))
            useCase.getOrCreateAgent(id).refreshAgent()
        }
    }

    fun updateAgent(
        id: String,
        name: String,
        systemPrompt: String,
        temperature: Double,
        provider: LLMProvider,
        stopWord: String,
        maxTokens: Int,
        memoryStrategy: ChatMemoryStrategy
    ) {
        viewModelScope.launch {
            val state = repository.getAgentState(id) ?: AgentState(id)
            repository.saveAgentState(state.copy(
                name = name,
                systemPrompt = systemPrompt,
                temperature = temperature,
                maxTokens = maxTokens,
                stopWord = stopWord,
                memoryStrategy = memoryStrategy
            ))
            useCase.getOrCreateAgent(id).refreshAgent()
        }
    }

    fun updateUserProfile(id: String, profile: UserProfile) {
        viewModelScope.launch {
            repository.saveProfile(id, profile)
            useCase.getOrCreateAgent(id).refreshAgent()
        }
    }

    fun duplicateAgent(agentId: String) {
        viewModelScope.launch {
            val original = repository.getAgentState(agentId) ?: return@launch
            val newId = Uuid.random().toString()
            repository.saveAgentState(original.copy(agentId = newId, name = "${original.name} (Copy)", messages = emptyList()))
            selectAgent(newId)
        }
    }
}
