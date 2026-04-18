package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

class DefaultAgentFactory {
    fun create(): Agent = Agent(
        name = "Default",
        systemPrompt = "You are a helpful assistant.",
        temperature = 0.7,
        provider = LLMProvider.Yandex(),
        stopWord = "",
        maxTokens = 2000
    )
}
