package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LLMViewModel(
    private val chatStreamingUseCase: ChatStreamingUseCase,
    private val repository: DatabaseChatRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    private val agentManager = AgentManager()
    private val memoryManager = ChatMemoryManager()
    private var currentJob: Job? = null

    val agentTemplates: List<AgentTemplate> = agentManager.templates

    init {
        observeAgents()
    }

    private fun observeAgents() {
        viewModelScope.launch {
            repository.getAgents().collect { dbAgents ->
                _state.update { currentState ->
                    val finalAgentsFromDb = if (dbAgents.isEmpty()) {
                        val general = currentState.agents.find { it.id == GENERAL_CHAT_ID }
                        if (general != null) {
                            viewModelScope.launch { repository.saveAgentMetadata(general) }
                        }
                        currentState.agents
                    } else {
                        dbAgents.map { dbAgent ->
                            val existingAgent = currentState.agents.find { it.id == dbAgent.id }
                            if (existingAgent != null) {
                                existingAgent.mergeWith(dbAgent)
                            } else {
                                dbAgent
                            }
                        }
                    }
                    currentState.copy(agents = finalAgentsFromDb)
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
        val agentId = currentAgent.id
        val activeBranchId = currentAgent.currentBranchId
        val branchKey = activeBranchId ?: "main_branch"

        val lastMessageId = if (activeBranchId != null) {
            currentAgent.branches.find { it.id == activeBranchId }?.lastMessageId
        } else {
            currentAgent.branches.find { it.id == "main_branch" }?.lastMessageId
                ?: currentAgent.messages.lastOrNull()?.id
        }

        val userMessage = createChatMessage(
            message = message,
            source = SourceType.USER,
            parentId = lastMessageId,
            branchId = branchKey
        )

        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) {
                    agent.copy(
                        messages = agent.messages + userMessage,
                        branches = agent.branches.updatePointer(branchKey, userMessage.id),
                        totalTokensUsed = agent.totalTokensUsed + userMessage.tokenCount
                    )
                } else agent
            }
            state.copy(agents = updatedAgents, isLoading = true)
        }

        viewModelScope.launch {
            repository.saveMessage(agentId, userMessage)
            _state.value.agents.find { it.id == agentId }?.let { repository.saveAgentMetadata(it) }
        }

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val botMessageId = Uuid.random().toString()
            val agentSnapshot = _state.value.agents.find { it.id == agentId } ?: currentAgent
            
            val history = memoryManager.processMessages(
                messages = agentSnapshot.messages,
                strategy = agentSnapshot.memoryStrategy,
                currentBranchId = activeBranchId,
                agentBranches = agentSnapshot.branches
            )
            
            val flow = chatStreamingUseCase(
                messages = history,
                systemPrompt = memoryManager.wrapSystemPrompt(agentSnapshot.systemPrompt, agentSnapshot.memoryStrategy),
                maxTokens = agentSnapshot.maxTokens,
                temperature = agentSnapshot.temperature,
                stopWord = agentSnapshot.stopWord,
                isJsonMode = _state.value.isJsonMode,
                provider = agentSnapshot.provider
            )

            handleStreamingResponse(agentId, botMessageId, flow, userMessage.id, branchKey)
        }
    }

    private suspend fun handleStreamingResponse(
        agentId: String, 
        botMessageId: String, 
        flow: Flow<Result<String>>,
        parentId: String,
        branchKey: String
    ) {
        var currentContent = ""
        var lastUpdateMillis = 0L

        flow.onStart {
            _state.update { it.copy(isLoading = false) }
            val initialBotMessage = createChatMessage("", SourceType.ASSISTANT, parentId, branchKey, botMessageId)
            addBotMessageLocally(agentId, initialBotMessage, branchKey)
        }
        .onCompletion {
            val finalBotMessage = createChatMessage(currentContent, SourceType.ASSISTANT, parentId, branchKey, botMessageId)
            _state.update { state ->
                val updatedAgents = state.agents.map { agent ->
                    if (agent.id == agentId) {
                        val updatedMessages = agent.messages.map { msg ->
                            if (msg.id == botMessageId) finalBotMessage else msg
                        }
                        agent.copy(
                            messages = updatedMessages,
                            branches = agent.branches.updatePointer(branchKey, finalBotMessage.id),
                            totalTokensUsed = agent.totalTokensUsed + finalBotMessage.tokenCount
                        )
                    } else agent
                }
                state.copy(agents = updatedAgents, isLoading = false)
            }
            repository.saveMessage(agentId, finalBotMessage)
            _state.value.agents.find { it.id == agentId }?.let { repository.saveAgentMetadata(it) }
        }
        .collect { result ->
            result.onSuccess { chunk ->
                currentContent += chunk
                val now = currentTimeMillis()
                if (now - lastUpdateMillis > 48) {
                    updateBotMessageLocally(agentId, botMessageId, currentContent)
                    lastUpdateMillis = now
                }
            }.onFailure { error ->
                updateBotMessageLocally(agentId, botMessageId, currentContent + "\n[Ошибка: ${error.message}]")
            }
        }
    }

    private fun addBotMessageLocally(agentId: String, message: ChatMessage, branchKey: String) {
        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) {
                    agent.copy(
                        messages = agent.messages + message, 
                        branches = agent.branches.updatePointer(branchKey, message.id)
                    )
                } else agent
            }
            state.copy(agents = updatedAgents)
        }
    }

    private fun updateBotMessageLocally(agentId: String, messageId: String, newMessage: String) {
        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) {
                    val updatedMessages = agent.messages.map { msg ->
                        if (msg.id == messageId) msg.copy(
                            message = newMessage,
                            tokenCount = estimateTokens(newMessage)
                        ) else msg
                    }
                    agent.copy(messages = updatedMessages)
                } else agent
            }
            state.copy(agents = updatedAgents)
        }
    }

    fun updateMemoryStrategy(strategy: ChatMemoryStrategy) {
        val agentId = _state.value.selectedAgentId ?: return
        val currentAgent = _state.value.agents.find { it.id == agentId } ?: return
        val updatedAgent = currentAgent.copy(memoryStrategy = strategy)
        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { if (it.id == agentId) updatedAgent else it }
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
                if (a.id == agentId) a.copy(branches = a.branches + newBranch, currentBranchId = branchId) else a
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

    fun selectAgent(agentId: String?) { _state.update { it.copy(selectedAgentId = agentId) } }
    fun updateStreamingEnabled(enabled: Boolean) { _state.update { it.copy(isStreamingEnabled = enabled) } }
    fun updateSendFullHistory(enabled: Boolean) { _state.update { it.copy(sendFullHistory = enabled) } }
    
    fun createAgent() {
        val currentProvider = _state.value.selectedAgent?.provider ?: LLMProvider.Yandex()
        val newAgent = agentManager.createDefaultAgent(currentProvider)
        _state.update { it.copy(agents = it.agents + newAgent, selectedAgentId = newAgent.id) }
        viewModelScope.launch { repository.saveAgentMetadata(newAgent) }
    }

    fun updateAgent(id: String, name: String, systemPrompt: String, temperature: Double, provider: LLMProvider, stopWord: String, maxTokens: Int, memoryStrategy: ChatMemoryStrategy) {
        val agent = _state.value.agents.find { it.id == id } ?: return
        val updatedAgent = agentManager.updateAgent(agent, name, systemPrompt, temperature, provider, stopWord, maxTokens, memoryStrategy)
        _state.update { currentState -> currentState.copy(agents = currentState.agents.map { if (it.id == id) updatedAgent else it }) }
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
        val newAgent = agent.copy(id = Uuid.random().toString(), name = "${agent.name} (Copy)", messages = emptyList(), totalTokensUsed = 0)
        _state.update { it.copy(agents = it.agents + newAgent, selectedAgentId = newAgent.id) }
        viewModelScope.launch { repository.saveAgentMetadata(newAgent) }
    }

    fun clearChat() {
        currentJob?.cancel()
        val agent = _state.value.selectedAgent ?: return
        viewModelScope.launch {
            val clearedAgent = agent.copy(messages = emptyList(), branches = emptyList(), currentBranchId = null, totalTokensUsed = 0)
            repository.saveAgent(clearedAgent)
            _state.update { state -> state.copy(agents = state.agents.map { if (it.id == agent.id) clearedAgent else it }) }
        }
    }
    
    private fun currentTimeMillis(): Long = com.example.ai_develop.util.currentTimeMillis()
}
