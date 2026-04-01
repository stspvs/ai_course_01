package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface ChatStrategyDelegate {
    fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    )
}

class DefaultStrategyDelegate : ChatStrategyDelegate {
    override fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        scope.launch {
            repository.saveAgentMetadata(agent)
        }
    }
}

class StickyFactsStrategyDelegate(
    private val extractFactsUseCase: ExtractFactsUseCase
) : ChatStrategyDelegate {
    override fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.StickyFacts ?: return
        
        scope.launch {
            repository.saveAgentMetadata(agent)
            
            val assistantMessagesCount = agent.messages.count { it.source == SourceType.ASSISTANT }
            if (assistantMessagesCount > 0 && assistantMessagesCount % strategy.updateInterval == 0) {
                extractFactsUseCase(
                    messages = agent.messages,
                    currentFacts = strategy.facts,
                    provider = agent.provider,
                    windowSize = strategy.windowSize
                ).onSuccess { newFacts ->
                    val updatedAgent = agent.copy(
                        memoryStrategy = strategy.copy(facts = newFacts)
                    )
                    onAgentUpdated(updatedAgent)
                    repository.saveAgentMetadata(updatedAgent)
                }
            }
        }
    }
}

class SummarizationStrategyDelegate(
    private val summarizeChatUseCase: SummarizeChatUseCase
) : ChatStrategyDelegate {
    override fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.Summarization ?: return
        
        scope.launch {
            repository.saveAgentMetadata(agent)
            
            val assistantMessagesCount = agent.messages.count { it.source == SourceType.ASSISTANT }
            // Саммаризируем каждые windowSize сообщений ассистента, чтобы освежить контекст
            if (assistantMessagesCount > 0 && assistantMessagesCount % strategy.windowSize == 0) {
                summarizeChatUseCase(
                    messages = agent.messages,
                    previousSummary = strategy.summary,
                    instruction = strategy.summaryPrompt,
                    provider = agent.provider
                ).onSuccess { newSummary ->
                    val updatedAgent = agent.copy(
                        memoryStrategy = strategy.copy(summary = newSummary)
                    )
                    onAgentUpdated(updatedAgent)
                    repository.saveAgentMetadata(updatedAgent)
                }
            }
        }
    }
}

class StrategyDelegateFactory(
    private val extractFactsUseCase: ExtractFactsUseCase,
    private val summarizeChatUseCase: SummarizeChatUseCase
) {
    fun getDelegate(strategy: ChatMemoryStrategy): ChatStrategyDelegate {
        return when (strategy) {
            is ChatMemoryStrategy.StickyFacts -> StickyFactsStrategyDelegate(extractFactsUseCase)
            is ChatMemoryStrategy.Summarization -> SummarizationStrategyDelegate(summarizeChatUseCase)
            else -> DefaultStrategyDelegate()
        }
    }
}
