package com.example.ai_develop.domain

class ExtractFactsUseCase(
    private val repository: ChatRepository
) {
    suspend operator fun invoke(
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
