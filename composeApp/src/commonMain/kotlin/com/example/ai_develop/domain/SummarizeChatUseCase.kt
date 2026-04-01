package com.example.ai_develop.domain

open class SummarizeChatUseCase(
    private val repository: ChatRepository
) {
    open suspend operator fun invoke(
        messages: List<ChatMessage>,
        previousSummary: String?,
        instruction: String,
        provider: LLMProvider
    ): Result<String> {
        return repository.summarize(messages, previousSummary, instruction, provider)
    }
}
