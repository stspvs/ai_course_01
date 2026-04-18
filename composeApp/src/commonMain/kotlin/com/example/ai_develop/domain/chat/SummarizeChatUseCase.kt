package com.example.ai_develop.domain.chat

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

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
