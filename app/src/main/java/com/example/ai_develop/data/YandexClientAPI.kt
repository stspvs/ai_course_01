package com.example.ai_develop.data

import com.example.ai_develop.presentation.ChatMessage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class YandexClientAPI @Inject constructor(
    private val apiKey: String,
    private val folderId: String
) {
    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val api: YandexApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://llm.api.cloud.yandex.net/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YandexApi::class.java)
    }

    fun chatStreaming(
        chatHistory: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        model: String
    ): Flow<Result<String>> = flow {
        try {
            val yandexMessages = mutableListOf<YandexMessage>()
            if (systemPrompt.isNotBlank()) {
                yandexMessages.add(YandexMessage(role = "system", text = systemPrompt))
            }

            chatHistory.forEach { msg ->
                yandexMessages.add(YandexMessage(role = msg.source.role, text = msg.message))
            }

            // Формируем modelUri напрямую, используя folderId
            val modelPath = if (model.contains("/")) model else "$model/latest"
            val fullModelUri = "gpt://$folderId/$modelPath"

            val request = YandexChatRequest(
                modelUri = fullModelUri,
                completionOptions = YandexCompletionOptions(
                    stream = true,
                    temperature = temperature,
                    maxTokens = maxTokens.toLong()
                ),
                messages = yandexMessages
            )

            val response = api.chatStreaming("Api-Key $apiKey", folderId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    body.source().use { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            
                            try {
                                val chunk = gson.fromJson(line, YandexStreamResponse::class.java)
                                val content = chunk.result.alternatives.firstOrNull()?.message?.text
                                if (content != null) {
                                    emit(Result.success(content))
                                }
                            } catch (e: Exception) {
                                // Skip invalid chunks or partial JSON
                            }
                        }
                    }
                } else {
                    emit(Result.failure(Exception("Empty response body")))
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                emit(Result.failure(Exception("HTTP Error ${response.code()}: $errorBody")))
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)
}
