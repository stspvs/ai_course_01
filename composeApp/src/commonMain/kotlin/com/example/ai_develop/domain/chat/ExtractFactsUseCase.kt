package com.example.ai_develop.domain.chat

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

open class ExtractFactsUseCase(
    private val repository: ChatRepository
) {
    open suspend operator fun invoke(
        messages: List<ChatMessage>,
        currentFacts: ChatFacts,
        provider: LLMProvider,
        windowSize: Int
    ): Result<ChatFacts> {
        // Берем количество сообщений согласно настройкам стратегии или агента
        val recentMessages = messages.takeLast(windowSize)
        return repository.extractFacts(recentMessages, currentFacts, provider)
    }
}
