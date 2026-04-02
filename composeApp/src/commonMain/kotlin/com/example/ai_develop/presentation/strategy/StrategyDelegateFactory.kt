package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.ChatMemoryStrategy
import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.domain.ExtractFactsUseCase
import com.example.ai_develop.domain.SummarizeChatUseCase
import com.example.ai_develop.domain.UpdateWorkingMemoryUseCase

class StrategyDelegateFactory(
    private val extractFactsUseCase: ExtractFactsUseCase,
    private val summarizeChatUseCase: SummarizeChatUseCase,
    private val updateWorkingMemoryUseCase: UpdateWorkingMemoryUseCase,
    private val repository: ChatRepository
) {
    fun getDelegate(strategy: ChatMemoryStrategy): StrategyDelegate {
        return when (strategy) {
            is ChatMemoryStrategy.Summarization -> SummarizationStrategyDelegate(summarizeChatUseCase, updateWorkingMemoryUseCase)
            is ChatMemoryStrategy.StickyFacts -> StickyFactsStrategyDelegate(extractFactsUseCase, updateWorkingMemoryUseCase)
            else -> DefaultStrategyDelegate(updateWorkingMemoryUseCase, extractFactsUseCase)
        }
    }
}
