package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.CoroutineScope

interface StrategyDelegate {
    suspend fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    )
    
    suspend fun forceUpdate(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    )
}

class DefaultStrategyDelegate : StrategyDelegate {
    override suspend fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        onAgentUpdated(agent)
    }

    override suspend fun forceUpdate(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        onAgentUpdated(agent)
    }
}

class SummarizationStrategyDelegate(
    private val summarizeChatUseCase: SummarizeChatUseCase
) : StrategyDelegate {
    override suspend fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.Summarization ?: return
        
        val assistantMessagesCount = agent.messages.count { it.source == SourceType.ASSISTANT }
        
        if (assistantMessagesCount >= strategy.windowSize) {
            forceUpdate(scope, agent, repository, onAgentUpdated)
        }
    }

    override suspend fun forceUpdate(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.Summarization ?: return
        
        val result = summarizeChatUseCase(
            messages = agent.messages,
            previousSummary = strategy.summary,
            instruction = strategy.summaryPrompt,
            provider = agent.provider
        )
        
        result.onSuccess { newSummary ->
            val updatedStrategy = strategy.copy(summary = newSummary)
            val updatedAgent = agent.copy(memoryStrategy = updatedStrategy)
            onAgentUpdated(updatedAgent)
        }
    }
}

class StickyFactsStrategyDelegate(
    private val extractFactsUseCase: ExtractFactsUseCase
) : StrategyDelegate {
    override suspend fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.StickyFacts ?: return
        
        val assistantMessagesCount = agent.messages.count { it.source == SourceType.ASSISTANT }
        
        if (assistantMessagesCount > 0 && assistantMessagesCount % strategy.updateInterval == 0) {
            forceUpdate(scope, agent, repository, onAgentUpdated)
        }
    }

    override suspend fun forceUpdate(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.StickyFacts ?: return
        
        val result = extractFactsUseCase(
            messages = agent.messages,
            currentFacts = strategy.facts,
            provider = agent.provider,
            windowSize = strategy.windowSize
        )
        
        result.onSuccess { newFacts ->
            val updatedStrategy = strategy.copy(facts = newFacts)
            val updatedAgent = agent.copy(memoryStrategy = updatedStrategy)
            onAgentUpdated(updatedAgent)
        }
    }
}
