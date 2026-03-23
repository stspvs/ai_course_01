package com.example.ai_develop.data

import com.google.gson.annotations.SerializedName

data class YandexChatRequest(
    @SerializedName("modelUri")
    val modelUri: String,
    @SerializedName("completionOptions")
    val completionOptions: YandexCompletionOptions,
    @SerializedName("messages")
    val messages: List<YandexMessage>
)

data class YandexCompletionOptions(
    @SerializedName("stream")
    val stream: Boolean,
    @SerializedName("temperature")
    val temperature: Double,
    @SerializedName("maxTokens")
    val maxTokens: Long
)

data class YandexMessage(
    @SerializedName("role")
    val role: String,
    @SerializedName("text")
    val text: String
)

data class YandexChatResponse(
    @SerializedName("result")
    val result: YandexResult
)

data class YandexResult(
    @SerializedName("alternatives")
    val alternatives: List<YandexAlternative>
)

data class YandexAlternative(
    @SerializedName("message")
    val message: YandexMessage,
    @SerializedName("status")
    val status: String
)

data class YandexStreamResponse(
    @SerializedName("result")
    val result: YandexResult
)
