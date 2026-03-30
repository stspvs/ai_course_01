package com.example.ai_develop.domain

class ExtractFactsUseCase(
    private val repository: ChatRepository
) {
    suspend operator fun invoke(
        messages: List<ChatMessage>,
        currentFacts: ChatFacts,
        provider: LLMProvider
    ): Result<ChatFacts> {
        // Мы берем только последние сообщения для анализа, чтобы не перегружать контекст
        // и отправляем их в репозиторий для извлечения новых фактов или обновления старых
        val recentMessages = messages.takeLast(10)
        return repository.extractFacts(recentMessages, currentFacts, provider)
    }
}
