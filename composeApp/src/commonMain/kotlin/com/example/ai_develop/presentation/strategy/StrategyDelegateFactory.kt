package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.ChatMemoryStrategy
import com.example.ai_develop.domain.ExtractFactsUseCase
import com.example.ai_develop.domain.SummarizeChatUseCase
import com.example.ai_develop.domain.UpdateWorkingMemoryUseCase

class StrategyDelegateFactory(
    private val extractFactsUseCase: ExtractFactsUseCase,
    private val summarizeChatUseCase: SummarizeChatUseCase,
    private val updateWorkingMemoryUseCase: UpdateWorkingMemoryUseCase
) {
    private val defaultDelegate by lazy { DefaultStrategyDelegate(updateWorkingMemoryUseCase, extractFactsUseCase) }
    private val summarizationDelegate by lazy { SummarizationStrategyDelegate(summarizeChatUseCase, updateWorkingMemoryUseCase) }
    private val stickyFactsDelegate by lazy { StickyFactsStrategyDelegate(extractFactsUseCase, updateWorkingMemoryUseCase) }

    fun getDelegate(strategy: ChatMemoryStrategy): StrategyDelegate {
        return when (strategy) {
            is ChatMemoryStrategy.Summarization -> summarizationDelegate
            is ChatMemoryStrategy.StickyFacts -> stickyFactsDelegate
            else -> defaultDelegate
        }
    }
}
