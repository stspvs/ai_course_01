package com.example.ai_develop.presentation

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.strategy.StrategyDelegateFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ChatInteractor(
    private val chatStreamingUseCase: ChatStreamingUseCase,
    private val repository: LocalChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val strategyFactory: StrategyDelegateFactory
) {

    fun sendMessage(
        scope: CoroutineScope,
        agent: Agent,
        messageText: String,
        isJsonMode: Boolean,
        onAgentUpdate: (String, (Agent) -> Agent) -> Unit,
        onLoadingStatus: (Boolean) -> Unit
    ): Job {
        val agentId = agent.id
        val branchKey = agent.currentBranchId ?: "main_branch"
        
        val historyBeforeNewMessage = memoryManager.getBranchHistory(
            messages = agent.messages,
            currentBranchId = agent.currentBranchId,
            agentBranches = agent.branches
        )
        
        val lastMessageId = historyBeforeNewMessage.lastOrNull()?.id

        val userMessage = createChatMessage(
            message = messageText,
            source = SourceType.USER,
            parentId = lastMessageId,
            branchId = branchKey
        )

        updateLocalAndDb(agentId, userMessage, branchKey, onAgentUpdate, onLoadingStatus, scope)

        return scope.launch {
            val agentSnapshot = repository.getAgentWithMessages(agentId).firstOrNull() ?: agent
            
            val updatedBranches = agentSnapshot.branches.updatePointer(branchKey, userMessage.id)
            val updatedMessages = agentSnapshot.messages.toMutableList().apply {
                if (none { it.id == userMessage.id }) add(userMessage)
            }

            val history = memoryManager.processMessages(
                messages = updatedMessages,
                strategy = agentSnapshot.memoryStrategy,
                currentBranchId = agentSnapshot.currentBranchId,
                agentBranches = updatedBranches
            )

            val flow = chatStreamingUseCase(
                messages = history,
                systemPrompt = memoryManager.wrapSystemPrompt(agentSnapshot),
                maxTokens = agentSnapshot.maxTokens,
                temperature = agentSnapshot.temperature,
                stopWord = agentSnapshot.stopWord,
                isJsonMode = isJsonMode,
                provider = agentSnapshot.provider
            )

            handleStreamingResponse(
                scope = scope,
                agentId = agentId,
                flow = flow,
                parentId = userMessage.id,
                branchKey = branchKey,
                onAgentUpdate = onAgentUpdate,
                onLoadingStatus = onLoadingStatus
            )
        }
    }

    fun forceUpdateMemory(
        scope: CoroutineScope,
        agent: Agent,
        onAgentUpdate: (String, (Agent) -> Agent) -> Unit,
        onLoadingStatus: (Boolean) -> Unit
    ) {
        onLoadingStatus(true)
        scope.launch {
            try {
                strategyFactory.getDelegate(agent.memoryStrategy).forceUpdate(
                    scope = scope,
                    agent = agent,
                    repository = repository,
                    onAgentUpdated = { updated -> 
                        onAgentUpdate(agent.id) { updated }
                        scope.launch { repository.saveAgentMetadata(updated) }
                    }
                )
            } finally {
                onLoadingStatus(false)
            }
        }
    }

    private fun updateLocalAndDb(
        agentId: String,
        message: ChatMessage,
        branchKey: String,
        onAgentUpdate: (String, (Agent) -> Agent) -> Unit,
        onLoadingStatus: (Boolean) -> Unit,
        scope: CoroutineScope
    ) {
        var agentToSave: Agent? = null
        onAgentUpdate(agentId) { current ->
            val updated = current.copy(
                messages = current.messages + message,
                branches = current.branches.updatePointer(branchKey, message.id),
                totalTokensUsed = current.totalTokensUsed + message.tokenCount
            )
            agentToSave = updated
            updated
        }
        onLoadingStatus(true)

        scope.launch {
            repository.saveMessage(agentId, message)
            agentToSave?.let { repository.saveAgentMetadata(it) }
        }
    }

    private suspend fun handleStreamingResponse(
        scope: CoroutineScope,
        agentId: String,
        flow: Flow<Result<String>>,
        parentId: String,
        branchKey: String,
        onAgentUpdate: (String, (Agent) -> Agent) -> Unit,
        onLoadingStatus: (Boolean) -> Unit
    ) {
        val botMessageId = Uuid.random().toString()
        var currentContent = ""
        var lastUpdateMillis = 0L

        flow.onStart {
            onLoadingStatus(false)
            val initialBotMessage = createChatMessage("", SourceType.ASSISTANT, parentId, branchKey, botMessageId)
            onAgentUpdate(agentId) { agent ->
                agent.copy(
                    messages = agent.messages + initialBotMessage,
                    branches = agent.branches.updatePointer(branchKey, initialBotMessage.id)
                )
            }
        }
        .onCompletion {
            finalizeResponse(agentId, botMessageId, currentContent, parentId, branchKey, onAgentUpdate, scope)
        }
        .collect { result ->
            result.onSuccess { chunk ->
                currentContent += chunk
                val now = currentTimeMillis()
                if (now - lastUpdateMillis > 48) {
                    updateStreamingContent(agentId, botMessageId, currentContent, onAgentUpdate)
                    lastUpdateMillis = now
                }
            }.onFailure { error ->
                handleStreamingError(agentId, botMessageId, currentContent, error, onAgentUpdate)
            }
        }
    }

    private fun updateStreamingContent(
        agentId: String,
        botMessageId: String,
        content: String,
        onAgentUpdate: (String, (Agent) -> Agent) -> Unit
    ) {
        onAgentUpdate(agentId) { agent ->
            val updatedMessages = agent.messages.map { msg ->
                if (msg.id == botMessageId) msg.copy(
                    message = content,
                    tokensUsed = estimateTokens(content)
                ) else msg
            }
            agent.copy(messages = updatedMessages)
        }
    }

    private fun handleStreamingError(
        agentId: String,
        botMessageId: String,
        content: String,
        error: Throwable,
        onAgentUpdate: (String, (Agent) -> Agent) -> Unit
    ) {
        onAgentUpdate(agentId) { agent ->
            val updatedMessages = agent.messages.map { msg ->
                if (msg.id == botMessageId) msg.copy(
                    message = content + "\n[Ошибка: ${error.message}]"
                ) else msg
            }
            agent.copy(messages = updatedMessages)
        }
    }

    private fun finalizeResponse(
        agentId: String,
        botMessageId: String,
        content: String,
        parentId: String,
        branchKey: String,
        onAgentUpdate: (String, (Agent) -> Agent) -> Unit,
        scope: CoroutineScope
    ) {
        val finalBotMessage = createChatMessage(content, SourceType.ASSISTANT, parentId, branchKey, botMessageId)
        
        var agentToSave: Agent? = null
        onAgentUpdate(agentId) { agent ->
            val updatedMessages = agent.messages.map { if (it.id == botMessageId) finalBotMessage else it }
            val updated = agent.copy(
                messages = updatedMessages,
                branches = agent.branches.updatePointer(branchKey, finalBotMessage.id),
                totalTokensUsed = agent.totalTokensUsed + finalBotMessage.tokenCount
            )
            agentToSave = updated
            updated
        }
        
        scope.launch {
            repository.saveMessage(agentId, finalBotMessage)
            agentToSave?.let { repository.saveAgentMetadata(it) }
            
            repository.getAgentWithMessages(agentId).firstOrNull()?.let { latestAgent ->
                strategyFactory.getDelegate(latestAgent.memoryStrategy).onMessageReceived(
                    scope = scope,
                    agent = latestAgent,
                    repository = repository,
                    onAgentUpdated = { updated -> 
                        onAgentUpdate(agentId) { updated }
                        scope.launch { repository.saveAgentMetadata(updated) }
                    }
                )
            }
        }
    }
    
    private fun currentTimeMillis(): Long = com.example.ai_develop.util.currentTimeMillis()
}
