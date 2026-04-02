package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.ChatMemoryStrategy
import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.domain.ExtractFactsUseCase
import com.example.ai_develop.domain.SummarizeChatUseCase

class StrategyDelegateFactory(
    private val extractFactsUseCase: ExtractFactsUseCase,
    private val summarizeChatUseCase: SummarizeChatUseCase,
    private val repository: ChatRepository
) {
    fun getDelegate(strategy: ChatMemoryStrategy): StrategyDelegate {
        return when (strategy) {
            is ChatMemoryStrategy.Summarization -> SummarizationStrategyDelegate(summarizeChatUseCase)
            is ChatMemoryStrategy.StickyFacts -> StickyFactsStrategyDelegate(extractFactsUseCase)
            else -> DefaultStrategyDelegate()
        }
    }
}
