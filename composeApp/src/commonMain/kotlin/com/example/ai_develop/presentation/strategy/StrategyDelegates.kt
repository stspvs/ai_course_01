package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.*

sealed interface StrategyDelegate {
    suspend fun onMessageReceived(
        agent: Agent,
        onAgentUpdated: (Agent) -> Unit
    )
    
    suspend fun forceUpdate(
        agent: Agent,
        onAgentUpdated: (Agent) -> Unit
    )
}

abstract class BaseStrategyDelegate : StrategyDelegate {

    protected suspend fun updateAgent(
        agent: Agent,
        onAgentUpdated: (Agent) -> Unit,
        block: suspend (Agent) -> AgentUpdate
    ) {
        val update = block(agent)
        val updatedAgent = agent.applyUpdate(update)
        if (updatedAgent != agent) {
            onAgentUpdated(updatedAgent)
        }
    }

    protected suspend fun updateWorkingMemory(
        agent: Agent,
        useCase: UpdateWorkingMemoryUseCase
    ): Result<WorkingMemory> {
        return useCase.update(agent)
    }
}

class DefaultStrategyDelegate(
    private val updateWorkingMemoryUseCase: UpdateWorkingMemoryUseCase,
    private val extractFactsUseCase: ExtractFactsUseCase
) : BaseStrategyDelegate() {
    override suspend fun onMessageReceived(
        agent: Agent,
        onAgentUpdated: (Agent) -> Unit
    ) {
        if (agent.assistantMessagesCount() > 0 && agent.assistantMessagesCount() % 5 == 0) {
            forceUpdate(agent, onAgentUpdated)
        }
    }

    override suspend fun forceUpdate(
        agent: Agent,
        onAgentUpdated: (Agent) -> Unit
    ) {
        updateAgent(agent, onAgentUpdated) { current ->
            val wmResult = updateWorkingMemory(current, updateWorkingMemoryUseCase)
            val factsResult = extractFactsUseCase(
                messages = current.messages, 
                currentFacts = current.workingMemory.extractedFacts, 
                provider = current.memoryProvider, 
                windowSize = 20
            )
            
            var newWM = current.workingMemory
            wmResult.onSuccess { newWM = it }
            factsResult.onSuccess { newFacts ->
                newWM = newWM.copy(extractedFacts = newFacts)
            }
            
            AgentUpdate(workingMemory = newWM)
        }
    }
}

class SummarizationStrategyDelegate(
    private val summarizeChatUseCase: SummarizeChatUseCase,
    private val updateWorkingMemoryUseCase: UpdateWorkingMemoryUseCase
) : BaseStrategyDelegate() {
    override suspend fun onMessageReceived(
        agent: Agent,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.Summarization ?: return
        if (agent.assistantMessagesCount() >= strategy.windowSize) {
            forceUpdate(agent, onAgentUpdated)
        }
    }

    override suspend fun forceUpdate(
        agent: Agent,
        onAgentUpdated: (Agent) -> Unit
    ) {
        updateAgent(agent, onAgentUpdated) { current ->
            val strategy = current.memoryStrategy as? ChatMemoryStrategy.Summarization ?: return@updateAgent AgentUpdate()
            
            val summaryResult = summarizeChatUseCase(
                messages = current.messages,
                previousSummary = strategy.summary,
                instruction = strategy.summaryPrompt,
                provider = current.memoryProvider
            )
            
            val wmResult = updateWorkingMemory(current, updateWorkingMemoryUseCase)
            
            var newStrategy: ChatMemoryStrategy? = null
            summaryResult.onSuccess { newSummary ->
                newStrategy = strategy.copy(summary = newSummary)
            }
            
            var newWM: WorkingMemory? = null
            wmResult.onSuccess { newWM = it }
            
            AgentUpdate(workingMemory = newWM, memoryStrategy = newStrategy)
        }
    }
}

class StickyFactsStrategyDelegate(
    private val extractFactsUseCase: ExtractFactsUseCase,
    private val updateWorkingMemoryUseCase: UpdateWorkingMemoryUseCase
) : BaseStrategyDelegate() {
    override suspend fun onMessageReceived(
        agent: Agent,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.StickyFacts ?: return
        if (agent.assistantMessagesCount() > 0 && agent.assistantMessagesCount() % strategy.updateInterval == 0) {
            forceUpdate(agent, onAgentUpdated)
        }
    }

    override suspend fun forceUpdate(
        agent: Agent,
        onAgentUpdated: (Agent) -> Unit
    ) {
        updateAgent(agent, onAgentUpdated) { current ->
            val strategy = current.memoryStrategy as? ChatMemoryStrategy.StickyFacts ?: return@updateAgent AgentUpdate()
            
            val factsResult = extractFactsUseCase(
                messages = current.messages,
                currentFacts = strategy.facts,
                provider = current.memoryProvider,
                windowSize = strategy.windowSize
            )
            
            val wmResult = updateWorkingMemory(current, updateWorkingMemoryUseCase)
            
            var newStrategy: ChatMemoryStrategy? = null
            var newWM = current.workingMemory
            
            factsResult.onSuccess { newFacts ->
                newStrategy = strategy.copy(facts = newFacts)
                newWM = newWM.copy(extractedFacts = newFacts)
            }
            
            wmResult.onSuccess { 
                // Сохраняем извлеченные факты при обновлении WM
                newWM = it.copy(extractedFacts = newWM.extractedFacts)
            }
            
            AgentUpdate(workingMemory = newWM, memoryStrategy = newStrategy)
        }
    }
}
