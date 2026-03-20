package com.example.ai_develop.data

data class ChatRequest(
    val model: String = "deepseek-chat",
    val messages: List<Message>,
    val maxTokens: Int = 300,
    val stream: Boolean = false
)

data class Message(
    val role: String,
    val content: String
)

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: Message
)