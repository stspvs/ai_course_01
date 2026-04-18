package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.chat.ChatMemoryStrategy
import com.example.ai_develop.domain.chat.ExtractFactsUseCase
import com.example.ai_develop.domain.chat.SummarizeChatUseCase
import com.example.ai_develop.domain.chat.UpdateWorkingMemoryUseCase

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
