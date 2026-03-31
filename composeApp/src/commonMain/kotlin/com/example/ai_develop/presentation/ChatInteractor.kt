package com.example.ai_develop.presentation

import com.example.ai_develop.data.database.DatabaseChatRepository
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
    private val repository: DatabaseChatRepository,
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
        val lastMessageId = agent.branches.find { it.id == branchKey }?.lastMessageId ?: agent.messages.lastOrNull()?.id

        val userMessage = createChatMessage(
            message = messageText,
            source = SourceType.USER,
            parentId = lastMessageId,
            branchId = branchKey
        )

        updateLocalAndDb(agentId, userMessage, branchKey, onAgentUpdate, onLoadingStatus, scope)

        return scope.launch {
            val history = memoryManager.processMessages(
                messages = agent.messages + userMessage,
                strategy = agent.memoryStrategy,
                currentBranchId = agent.currentBranchId,
                agentBranches = agent.branches
            )

            val flow = chatStreamingUseCase(
                messages = history,
                systemPrompt = memoryManager.wrapSystemPrompt(agent.systemPrompt, agent.memoryStrategy),
                maxTokens = agent.maxTokens,
                temperature = agent.temperature,
                stopWord = agent.stopWord,
                isJsonMode = isJsonMode,
                provider = agent.provider
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

    private fun updateLocalAndDb(
        agentId: String,
        message: ChatMessage,
        branchKey: String,
        onAgentUpdate: (String, (Agent) -> Agent) -> Unit,
        onLoadingStatus: (Boolean) -> Unit,
        scope: CoroutineScope
    ) {
        onAgentUpdate(agentId) { current ->
            current.copy(
                messages = current.messages + message,
                branches = current.branches.updatePointer(branchKey, message.id),
                totalTokensUsed = current.totalTokensUsed + message.tokenCount
            )
        }
        onLoadingStatus(true)

        scope.launch {
            repository.saveMessage(agentId, message)
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
                    tokenCount = estimateTokens(content)
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
        
        onAgentUpdate(agentId) { agent ->
            val updatedMessages = agent.messages.map { if (it.id == botMessageId) finalBotMessage else it }
            agent.copy(
                messages = updatedMessages,
                branches = agent.branches.updatePointer(branchKey, finalBotMessage.id),
                totalTokensUsed = agent.totalTokensUsed + finalBotMessage.tokenCount
            )
        }
        
        scope.launch {
            repository.saveMessage(agentId, finalBotMessage)
            repository.getAgentWithMessages(agentId).firstOrNull()?.let { latestAgent ->
                strategyFactory.getDelegate(latestAgent.memoryStrategy).onMessageReceived(
                    scope = scope,
                    agent = latestAgent,
                    repository = repository,
                    onAgentUpdated = { updated -> onAgentUpdate(agentId) { updated } }
                )
            }
        }
    }
    
    private fun currentTimeMillis(): Long = com.example.ai_develop.util.currentTimeMillis()
}
