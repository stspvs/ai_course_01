@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.Agent
import com.example.ai_develop.domain.AgentProfile
import com.example.ai_develop.domain.AgentTemplate
import com.example.ai_develop.domain.ChatBranch
import com.example.ai_develop.domain.ChatMemoryStrategy
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.GENERAL_CHAT_ID
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.domain.LLMStateModel
import com.example.ai_develop.domain.mergeWith
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class LLMViewModel(
    private val repository: LocalChatRepository,
    private val interactor: ChatInteractor
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    private val agentManager = AgentManager()
    private var currentJob: Job? = null

    val agentTemplates: List<AgentTemplate> = agentManager.templates

    init {
        observeAgents()
    }

    private fun observeAgents() {
        viewModelScope.launch {
            repository.getAgents().collect { dbAgents ->
                _state.update { currentState ->
                    if (dbAgents.isEmpty()) {
                        // Если БД пуста, пытаемся сохранить дефолтный чат
                        val general = currentState.agents.find { it.id == GENERAL_CHAT_ID }
                        if (general != null) {
                            viewModelScope.launch { repository.saveAgentMetadata(general) }
                        }
                        currentState
                    } else {
                        val dbIds = dbAgents.map { it.id }.toSet()
                        // Сохраняем агентов, которые есть в памяти, но еще не долетели до БД
                        val pendingAgents = currentState.agents.filter { it.id !in dbIds }
                        
                        val mergedAgents = dbAgents.map { dbAgent ->
                            val existingAgent = currentState.agents.find { it.id == dbAgent.id }
                            if (existingAgent != null) {
                                existingAgent.mergeWith(dbAgent)
                            } else {
                                dbAgent
                            }
                        }
                        currentState.copy(agents = mergedAgents + pendingAgents)
                    }
                }
            }
        }

        viewModelScope.launch {
            _state.map { it.selectedAgentId }.distinctUntilChanged().collectLatest { id ->
                if (id != null) {
                    repository.getAgentWithMessages(id).collect { fullAgent ->
                        if (fullAgent != null) {
                            _state.update { currentState ->
                                val updatedAgents = currentState.agents.map { existing ->
                                    if (existing.id == id) {
                                        existing.mergeWith(fullAgent)
                                    } else existing
                                }
                                currentState.copy(agents = updatedAgents)
                            }
                        }
                    }
                }
            }
        }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        val currentAgent = _state.value.selectedAgent ?: return

        currentJob?.cancel()
        currentJob = interactor.sendMessage(
            scope = viewModelScope,
            agent = currentAgent,
            messageText = message,
            isJsonMode = _state.value.isJsonMode,
            onAgentUpdate = { agentId, update ->
                _state.update { state ->
                    val updatedAgents = state.agents.map { agent ->
                        if (agent.id == agentId) update(agent) else agent
                    }
                    state.copy(agents = updatedAgents)
                }
            },
            onLoadingStatus = { isLoading ->
                _state.update { it.copy(isLoading = isLoading) }
            }
        )
    }

    fun forceUpdateMemory() {
        val agent = _state.value.selectedAgent ?: return
        interactor.forceUpdateMemory(
            scope = viewModelScope,
            agent = agent,
            onAgentUpdate = { agentId, update ->
                _state.update { state ->
                    val updatedAgents =
                        state.agents.map { if (it.id == agentId) update(it) else it }
                    state.copy(agents = updatedAgents)
                }
            },
            onLoadingStatus = { isLoading ->
                _state.update { it.copy(isLoading = isLoading) }
            }
        )
    }

    fun updateMemoryStrategy(strategy: ChatMemoryStrategy) {
        val agentId = _state.value.selectedAgentId ?: return
        val currentAgent = _state.value.agents.find { it.id == agentId } ?: return
        val updatedAgent = currentAgent.copy(memoryStrategy = strategy)
        _state.update { currentState ->
            val updatedAgents =
                currentState.agents.map { if (it.id == agentId) updatedAgent else it }
            currentState.copy(agents = updatedAgents)
        }
        viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
    }

    fun createBranch(fromMessageId: String, branchName: String) {
        val agentId = _state.value.selectedAgentId ?: return
        val branchId = Uuid.random().toString()
        val newBranch = ChatBranch(id = branchId, name = branchName, lastMessageId = fromMessageId)
        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { a ->
                if (a.id == agentId) a.copy(
                    branches = a.branches + newBranch,
                    currentBranchId = branchId
                ) else a
            }
            currentState.copy(agents = updatedAgents)
        }
        viewModelScope.launch {
            _state.value.agents.find { it.id == agentId }?.let { repository.saveAgentMetadata(it) }
        }
    }

    fun switchBranch(branchId: String?) {
        val normalizedId = if (branchId == "main_branch") null else branchId
        val agentId = _state.value.selectedAgentId ?: return
        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { a ->
                if (a.id == agentId) a.copy(currentBranchId = normalizedId) else a
            }
            currentState.copy(agents = updatedAgents)
        }
        viewModelScope.launch {
            _state.value.agents.find { it.id == agentId }?.let { repository.saveAgentMetadata(it) }
        }
    }

    fun selectAgent(agentId: String?) {
        _state.update { it.copy(selectedAgentId = agentId) }
    }

    fun updateStreamingEnabled(enabled: Boolean) {
        _state.update { it.copy(isStreamingEnabled = enabled) }
    }

    fun updateSendFullHistory(enabled: Boolean) {
        _state.update { it.copy(sendFullHistory = enabled) }
    }

    fun createAgent() {
        val currentProvider = _state.value.selectedAgent?.provider ?: LLMProvider.Yandex()
        val newAgent = agentManager.createDefaultAgent(currentProvider)
        _state.update { it.copy(agents = it.agents + newAgent, selectedAgentId = newAgent.id) }
        viewModelScope.launch { repository.saveAgentMetadata(newAgent) }
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
        val agent = _state.value.agents.find { it.id == id } ?: return
        val updatedAgent = agentManager.updateAgent(
            agent,
            name,
            systemPrompt,
            temperature,
            provider,
            stopWord,
            maxTokens,
            memoryStrategy
        )
        _state.update { currentState -> currentState.copy(agents = currentState.agents.map { if (it.id == id) updatedAgent else it }) }
        viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
    }

    fun updateAgentWithProfile(id: String, profile: AgentProfile) {
        val agent = _state.value.agents.find { it.id == id } ?: return
        val updatedAgent = agent.copy(agentProfile = profile)
        _state.update { currentState ->
            currentState.copy(agents = currentState.agents.map { if (it.id == id) updatedAgent else it })
        }
        viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
    }

    fun deleteAgent(agentId: String) {
        viewModelScope.launch {
            repository.deleteAgent(agentId)
            _state.update { it.copy(agents = it.agents.filter { it.id != agentId }) }
        }
    }

    fun duplicateAgent(agentId: String) {
        val agent = _state.value.agents.find { it.id == agentId } ?: return
        val newAgent = agent.copy(
            id = Uuid.random().toString(),
            name = "${agent.name} (Copy)",
            messages = emptyList(),
            totalTokensUsed = 0
        )
        _state.update { it.copy(agents = it.agents + newAgent, selectedAgentId = newAgent.id) }
        viewModelScope.launch { repository.saveAgentMetadata(newAgent) }
    }

    fun clearChat() {
        currentJob?.cancel()
        val agent = _state.value.selectedAgent ?: return
        viewModelScope.launch {
            val clearedAgent = agent.copy(
                messages = emptyList(),
                branches = emptyList(),
                currentBranchId = null,
                totalTokensUsed = 0
            )
            repository.saveAgent(clearedAgent)
            _state.update { state -> state.copy(agents = state.agents.map { if (it.id == agent.id) clearedAgent else it }) }
        }
    }
}
