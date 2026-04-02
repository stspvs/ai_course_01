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

class DefaultStrategyDelegate(
    private val updateWorkingMemoryUseCase: UpdateWorkingMemoryUseCase,
    private val extractFactsUseCase: ExtractFactsUseCase
) : StrategyDelegate {
    override suspend fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        // По умолчанию просто обновляем рабочую память каждые 5 сообщений
        val assistantMessagesCount = agent.messages.count { it.source == SourceType.ASSISTANT }
        if (assistantMessagesCount > 0 && assistantMessagesCount % 5 == 0) {
            forceUpdate(scope, agent, repository, onAgentUpdated)
        }
    }

    override suspend fun forceUpdate(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        // Обновляем и рабочую память (задачу), и факты (долгую память)
        val wmResult = updateWorkingMemoryUseCase.update(agent)
        val factsResult = extractFactsUseCase(
            messages = agent.messages, 
            currentFacts = agent.workingMemory.extractedFacts, 
            provider = agent.memoryProvider, 
            windowSize = 20
        )
        
        var updatedAgent = agent
        wmResult.onSuccess { newWm ->
            updatedAgent = updatedAgent.copy(workingMemory = newWm)
        }
        factsResult.onSuccess { newFacts ->
            updatedAgent = updatedAgent.copy(
                workingMemory = updatedAgent.workingMemory.copy(extractedFacts = newFacts)
            )
        }
        
        if (updatedAgent != agent) {
            onAgentUpdated(updatedAgent)
        }
    }
}

class SummarizationStrategyDelegate(
    private val summarizeChatUseCase: SummarizeChatUseCase,
    private val updateWorkingMemoryUseCase: UpdateWorkingMemoryUseCase
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
        
        val summaryResult = summarizeChatUseCase(
            messages = agent.messages,
            previousSummary = strategy.summary,
            instruction = strategy.summaryPrompt,
            provider = agent.memoryProvider
        )
        
        val wmResult = updateWorkingMemoryUseCase.update(agent)
        
        var updatedAgent = agent
        summaryResult.onSuccess { newSummary ->
            val updatedStrategy = strategy.copy(summary = newSummary)
            updatedAgent = updatedAgent.copy(memoryStrategy = updatedStrategy)
        }
        wmResult.onSuccess { newWm ->
            updatedAgent = updatedAgent.copy(workingMemory = newWm)
        }
        
        onAgentUpdated(updatedAgent)
    }
}

class StickyFactsStrategyDelegate(
    private val extractFactsUseCase: ExtractFactsUseCase,
    private val updateWorkingMemoryUseCase: UpdateWorkingMemoryUseCase
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
        
        val factsResult = extractFactsUseCase(
            messages = agent.messages,
            currentFacts = strategy.facts,
            provider = agent.memoryProvider,
            windowSize = strategy.windowSize
        )
        
        val wmResult = updateWorkingMemoryUseCase.update(agent)
        
        var updatedAgent = agent
        factsResult.onSuccess { newFacts ->
            val updatedStrategy = strategy.copy(facts = newFacts)
            updatedAgent = updatedAgent.copy(
                memoryStrategy = updatedStrategy,
                workingMemory = updatedAgent.workingMemory.copy(extractedFacts = newFacts)
            )
        }
        wmResult.onSuccess { newWm ->
            updatedAgent = updatedAgent.copy(workingMemory = newWm)
        }
        
        onAgentUpdated(updatedAgent)
    }
}
