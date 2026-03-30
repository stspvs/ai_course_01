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
        // Наблюдаем за списком агентов из БД
        viewModelScope.launch {
            repository.getAgents().collect { dbAgents ->
                _state.update { currentState ->
                    val finalAgentsFromDb = if (dbAgents.isEmpty()) {
                        // Если БД пуста, сохраняем Общий чат
                        val general = currentState.agents.find { it.id == GENERAL_CHAT_ID }
                        if (general != null) {
                            viewModelScope.launch { repository.saveAgentMetadata(general) }
                        }
                        currentState.agents
                    } else {
                        // Мапим агентов из БД, сохраняя сообщения в памяти (getAgents их не грузит)
                        val updated = dbAgents.map { dbAgent ->
                            val existingAgent = currentState.agents.find { it.id == dbAgent.id }
                            if (existingAgent != null && existingAgent.messages.isNotEmpty()) {
                                dbAgent.copy(messages = existingAgent.messages)
                            } else {
                                dbAgent
                            }
                        }
                        
                        // Если в БД нет Общего чата, добавляем его и сохраняем
                        if (updated.none { it.id == GENERAL_CHAT_ID }) {
                            val general = currentState.agents.find { it.id == GENERAL_CHAT_ID }
                            if (general != null) {
                                viewModelScope.launch { repository.saveAgentMetadata(general) }
                                listOf(general) + updated
                            } else updated
                        } else {
                            updated
                        }
                    }

                    currentState.copy(agents = finalAgentsFromDb)
                }
            }
        }
        
        // Наблюдаем за сообщениями выбранного агента
        viewModelScope.launch {
            _state.map { it.selectedAgentId }.distinctUntilChanged().collectLatest { id ->
                if (id != null) {
                    repository.getAgentWithMessages(id).collect { fullAgent ->
                        if (fullAgent != null) {
                            _state.update { currentState ->
                                val updatedAgents = currentState.agents.map {
                                    if (it.id == id) {
                                        it.copy(
                                            messages = fullAgent.messages,
                                            totalTokensUsed = fullAgent.totalTokensUsed,
                                            summary = fullAgent.summary,
                                            branches = fullAgent.branches,
                                            currentBranchId = fullAgent.currentBranchId
                                        )
                                    } else it
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
        
        // Обновляем стейт сразу для мгновенной реакции UI
        _state.update { it.copy(
            agents = it.agents + newAgent,
            selectedAgentId = newAgent.id 
        ) }

        viewModelScope.launch { 
            repository.saveAgentMetadata(newAgent)
        }
    }

    fun updateMemoryStrategy(strategy: ChatMemoryStrategy) {
        val agentId = _state.value.selectedAgentId ?: return
        val currentAgent = _state.value.agents.find { it.id == agentId } ?: return
        val updatedAgent = currentAgent.copy(memoryStrategy = strategy)
        
        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { 
                if (it.id == agentId) updatedAgent else it 
            }
            currentState.copy(agents = updatedAgents)
        }
        viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
    }

    fun createBranch(fromMessageId: String, branchName: String) {
        val agentId = _state.value.selectedAgentId ?: return
        val branchId = Uuid.random().toString()
        val newBranch = ChatBranch(id = branchId, name = branchName, lastMessageId = fromMessageId)
        
        val agent = _state.value.agents.find { it.id == agentId } ?: return
        val updatedAgent = agent.copy(
            branches = agent.branches + newBranch,
            currentBranchId = branchId
        )

        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { if (it.id == agentId) updatedAgent else it }
            currentState.copy(agents = updatedAgents)
        }
        viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
    }

    fun switchBranch(branchId: String?) {
        val agentId = _state.value.selectedAgentId ?: return
        val agent = _state.value.agents.find { it.id == agentId } ?: return
        val updatedAgent = agent.copy(currentBranchId = branchId)

        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { if (it.id == agentId) updatedAgent else it }
            currentState.copy(agents = updatedAgents)
        }
        viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        val tokenCount = (message.length / 4).coerceAtLeast(1)
        val currentAgent = _state.value.selectedAgent ?: return
        val agentId = currentAgent.id
        
        val userMessage = ChatMessage(
            parentId = currentAgent.currentBranchId,
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
            if (currentAgent.memoryStrategy is ChatMemoryStrategy.StickyFacts) {
                updateFacts(agentId)
            }
        }

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val botMessageId = Uuid.random().toString()
            val agentSnapshot = _state.value.agents.find { it.id == agentId } ?: currentAgent
            
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

            handleStreamingResponse(agentId, botMessageId, flow, userMessage.id)
        }
    }

    private suspend fun updateFacts(agentId: String) {
        val agent = _state.value.agents.find { it.id == agentId } ?: return
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.StickyFacts ?: return
        
        val result = extractFactsUseCase(agent.messages, strategy.facts, agent.provider)
        result.onSuccess { newFacts ->
            val updatedAgent = agent.copy(memoryStrategy = strategy.copy(facts = newFacts))
            _state.update { currentState ->
                val updatedAgents = currentState.agents.map { a ->
                    if (a.id == agentId) updatedAgent else a
                }
                currentState.copy(agents = updatedAgents)
            }
            repository.saveAgentMetadata(updatedAgent)
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
        // Обновляем локально и сохраняем
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
                totalTokensUsed = 0, 
                summary = null
            )
            repository.saveAgent(clearedAgent)
            _state.update { currentState ->
                val updatedAgents = currentState.agents.map { if (it.id == agent.id) clearedAgent else it }
                currentState.copy(agents = updatedAgents)
            }
        }
    }
}
