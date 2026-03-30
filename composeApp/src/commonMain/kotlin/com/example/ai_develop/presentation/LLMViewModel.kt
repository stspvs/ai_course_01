package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.domain.*
import com.example.ai_develop.util.currentTimeMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LLMViewModel(
    private val chatStreamingUseCase: ChatStreamingUseCase,
    private val extractFactsUseCase: ExtractFactsUseCase,
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
                    if (dbAgents.isEmpty()) {
                        val defaultAgent = currentState.agents.first()
                        viewModelScope.launch { repository.saveAgent(defaultAgent) }
                        currentState
                    } else {
                        val updatedAgents = dbAgents.map { dbAgent ->
                            val existingAgent = currentState.agents.find { it.id == dbAgent.id }
                            if (existingAgent != null) {
                                dbAgent.copy(messages = existingAgent.messages)
                            } else {
                                dbAgent
                            }
                        }
                        currentState.copy(agents = updatedAgents)
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
                                val updatedAgents = currentState.agents.map {
                                    if (it.id == id) fullAgent else it
                                }
                                currentState.copy(agents = updatedAgents)
                            }
                        }
                    }
                }
            }
        }
    }

    fun createAgent() {
        val currentProvider = _state.value.selectedAgent?.provider ?: LLMProvider.Yandex()
        val newAgent = agentManager.createDefaultAgent(currentProvider)
        _state.update { it.copy(
            agents = it.agents + newAgent,
            selectedAgentId = newAgent.id 
        ) }
        viewModelScope.launch { repository.saveAgentMetadata(newAgent) }
    }

    fun updateMemoryStrategy(strategy: ChatMemoryStrategy) {
        val agentId = _state.value.selectedAgentId ?: return
        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { 
                if (it.id == agentId) it.copy(memoryStrategy = strategy) else it 
            }
            currentState.copy(agents = updatedAgents)
        }
        val updatedAgent = _state.value.agents.find { it.id == agentId }
        if (updatedAgent != null) {
            viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
        }
    }

    fun createBranch(fromMessageId: String, branchName: String) {
        val agentId = _state.value.selectedAgentId ?: return
        val branchId = Uuid.random().toString()
        val newBranch = ChatBranch(id = branchId, name = branchName, lastMessageId = fromMessageId)
        
        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { agent ->
                if (agent.id == agentId) {
                    agent.copy(
                        branches = agent.branches + newBranch,
                        currentBranchId = branchId
                    )
                } else agent
            }
            currentState.copy(agents = updatedAgents)
        }
        
        val updatedAgent = _state.value.agents.find { it.id == agentId }
        if (updatedAgent != null) {
            viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
        }
    }

    fun switchBranch(branchId: String?) {
        val agentId = _state.value.selectedAgentId ?: return
        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { agent ->
                if (agent.id == agentId) agent.copy(currentBranchId = branchId) else agent
            }
            currentState.copy(agents = updatedAgents)
        }
        val updatedAgent = _state.value.agents.find { it.id == agentId }
        if (updatedAgent != null) {
            viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
        }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        val tokenCount = (message.length / 4).coerceAtLeast(1)
        val currentAgent = _state.value.selectedAgent ?: return
        val agentId = currentAgent.id
        
        val userMessage = ChatMessage(
            parentId = currentAgent.currentBranchId, // Привязываем к текущей ветке
            message = message, 
            source = SourceType.USER, 
            tokenCount = tokenCount,
            timestamp = currentTimeMillis()
        )

        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) agent.copy(
                    messages = agent.messages + userMessage,
                    totalTokensUsed = agent.totalTokensUsed + tokenCount
                ) else agent
            }
            state.copy(agents = updatedAgents, isLoading = true)
        }

        viewModelScope.launch {
            repository.saveMessage(agentId, userMessage)
            
            // Если включена стратегия StickyFacts, обновляем факты
            if (currentAgent.memoryStrategy is ChatMemoryStrategy.StickyFacts) {
                updateFacts(agentId)
            }
        }

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val botMessageId = Uuid.random().toString()
            var currentContent = ""

            val agentSnapshot = _state.value.agents.find { it.id == agentId } ?: currentAgent
            
            // Используем MemoryManager для подготовки истории
            val history = memoryManager.processMessages(
                messages = agentSnapshot.messages,
                strategy = agentSnapshot.memoryStrategy,
                currentBranchId = agentSnapshot.currentBranchId
            )
            
            val systemPrompt = memoryManager.wrapSystemPrompt(
                agentSnapshot.systemPrompt,
                agentSnapshot.memoryStrategy
            )

            val flow = chatStreamingUseCase(
                messages = history,
                systemPrompt = systemPrompt,
                maxTokens = agentSnapshot.maxTokens,
                temperature = agentSnapshot.temperature,
                stopWord = agentSnapshot.stopWord,
                isJsonMode = _state.value.isJsonMode,
                provider = agentSnapshot.provider
            )

            // Логика стриминга (аналогично предыдущей, но с поддержкой parentId)
            handleStreamingResponse(agentId, botMessageId, flow, userMessage.id)
        }
    }

    private suspend fun updateFacts(agentId: String) {
        val agent = _state.value.agents.find { it.id == agentId } ?: return
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.StickyFacts ?: return
        
        val result = extractFactsUseCase(agent.messages, strategy.facts, agent.provider)
        result.onSuccess { newFacts ->
            _state.update { currentState ->
                val updatedAgents = currentState.agents.map { a ->
                    if (a.id == agentId) a.copy(memoryStrategy = strategy.copy(facts = newFacts)) else a
                }
                currentState.copy(agents = updatedAgents)
            }
            val finalAgent = _state.value.agents.find { it.id == agentId }
            if (finalAgent != null) repository.saveAgentMetadata(finalAgent)
        }
    }

    private suspend fun handleStreamingResponse(
        agentId: String, 
        botMessageId: String, 
        flow: Flow<Result<String>>,
        parentId: String
    ) {
        var currentContent = ""
        var lastUpdateMillis = 0L

        flow.onStart {
            _state.update { it.copy(isLoading = false) }
            val initialBotMessage = ChatMessage(
                id = botMessageId,
                parentId = parentId,
                message = "",
                source = SourceType.ASSISTANT,
                timestamp = currentTimeMillis()
            )
            addBotMessageLocally(agentId, initialBotMessage)
        }
        .onCompletion {
            val finalTokens = (currentContent.length / 4).coerceAtLeast(1)
            val finalBotMessage = ChatMessage(
                id = botMessageId,
                parentId = parentId,
                message = currentContent,
                source = SourceType.ASSISTANT,
                tokenCount = finalTokens,
                timestamp = currentTimeMillis()
            )
            _state.update { state ->
                val updatedAgents = state.agents.map { agent ->
                    if (agent.id == agentId) {
                        val updatedMessages = agent.messages.map { msg ->
                            if (msg.id == botMessageId) finalBotMessage else msg
                        }
                        agent.copy(
                            messages = updatedMessages,
                            totalTokensUsed = agent.totalTokensUsed + finalTokens
                        )
                    } else agent
                }
                state.copy(agents = updatedAgents, isLoading = false)
            }
            repository.saveMessage(agentId, finalBotMessage)
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

    private fun addBotMessageLocally(agentId: String, message: ChatMessage) {
        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) agent.copy(messages = agent.messages + message) else agent
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
                            tokenCount = (newMessage.length / 4).coerceAtLeast(1)
                        ) else msg
                    }
                    agent.copy(messages = updatedMessages)
                } else agent
            }
            state.copy(agents = updatedAgents)
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

    fun updateAgent(
        id: String,
        name: String,
        systemPrompt: String,
        temperature: Double,
        provider: LLMProvider,
        stopWord: String,
        maxTokens: Int,
        keepLastMessagesCount: Int,
        summaryPrompt: String,
        summaryDepth: SummaryDepth,
        memoryStrategy: ChatMemoryStrategy
    ) {
        val agent = _state.value.agents.find { it.id == id } ?: return
        val updatedAgent = agentManager.updateAgent(
            agent, name, systemPrompt, temperature, provider, stopWord, maxTokens, keepLastMessagesCount, summaryPrompt, summaryDepth, memoryStrategy
        )
        
        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { if (it.id == id) updatedAgent else it }
            currentState.copy(agents = updatedAgents)
        }
        
        viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
    }

    fun deleteAgent(agentId: String) {
        viewModelScope.launch {
            repository.deleteAgent(agentId)
            _state.update { currentState ->
                val updatedAgents = currentState.agents.filter { it.id != agentId }
                val nextSelectedId = if (currentState.selectedAgentId == agentId) {
                    updatedAgents.firstOrNull()?.id ?: GENERAL_CHAT_ID
                } else {
                    currentState.selectedAgentId
                }
                currentState.copy(agents = updatedAgents, selectedAgentId = nextSelectedId)
            }
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
        _state.update { it.copy(agents = it.agents + newAgent) }
        viewModelScope.launch { repository.saveAgentMetadata(newAgent) }
    }

    fun clearChat() {
        currentJob?.cancel()
        val agent = _state.value.selectedAgent ?: return
        viewModelScope.launch {
            repository.saveAgent(agent.copy(
                messages = emptyList(), 
                branches = emptyList(),
                currentBranchId = null,
                totalTokensUsed = 0, 
                summary = null
            ))
        }
    }

    companion object {
        const val GENERAL_CHAT_ID = "general_chat"
    }
}
