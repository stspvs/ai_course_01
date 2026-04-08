package com.example.ai_develop.domain

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
