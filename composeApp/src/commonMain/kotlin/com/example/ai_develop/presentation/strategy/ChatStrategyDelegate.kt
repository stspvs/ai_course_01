package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface ChatStrategyDelegate {
    fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: DatabaseChatRepository,
        onAgentUpdated: (Agent) -> Unit
    )
}

class DefaultStrategyDelegate : ChatStrategyDelegate {
    override fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: DatabaseChatRepository,
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
        repository: DatabaseChatRepository,
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

class SummarizationStrategyDelegate : ChatStrategyDelegate {
    override fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: DatabaseChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.Summarization ?: return
        
        scope.launch {
            repository.saveAgentMetadata(agent)
            
            // Здесь в будущем можно добавить автоматическую саммаризацию 
            // при достижении определенного количества сообщений
        }
    }
}

class StrategyDelegateFactory(
    private val extractFactsUseCase: ExtractFactsUseCase
) {
    fun getDelegate(strategy: ChatMemoryStrategy): ChatStrategyDelegate {
        return when (strategy) {
            is ChatMemoryStrategy.StickyFacts -> StickyFactsStrategyDelegate(extractFactsUseCase)
            is ChatMemoryStrategy.Summarization -> SummarizationStrategyDelegate()
            else -> DefaultStrategyDelegate()
        }
    }
}
