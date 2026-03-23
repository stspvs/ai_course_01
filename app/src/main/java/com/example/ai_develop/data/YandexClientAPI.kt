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
            // Если модель — одна из Qwen/QWQ, используем OpenAI-совместимый эндпоинт
            if (!model.contains("yandex")) {
                val openAiMessages = mutableListOf<OpenAiMessage>()
                if (systemPrompt.isNotBlank()) {
                    openAiMessages.add(OpenAiMessage(role = "system", content = systemPrompt))
                }
                chatHistory.forEach { msg ->
                    openAiMessages.add(OpenAiMessage(role = msg.source.role, content = msg.message))
                }

                // Извлекаем короткое имя модели
                val shortModelName = if (model.startsWith("gpt://")) {
                    model.substringBeforeLast("/").substringAfterLast("/")
                } else {
                    model
                }

                val request = OpenAiChatRequest(
                    model = shortModelName,
                    messages = openAiMessages,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    //stream = true
                )

                val response = api.openAiChatStreaming("Api-Key $apiKey", folderId, request)
                handleStreamResponse(response, this, true)
            } else {
                // Стандартный путь для YandexGPT
                val yandexMessages = mutableListOf<YandexMessage>()
                if (systemPrompt.isNotBlank()) {
                    yandexMessages.add(YandexMessage(role = "system", text = systemPrompt))
                }
                chatHistory.forEach { msg ->
                    yandexMessages.add(YandexMessage(role = msg.source.role, text = msg.message))
                }

                val fullModelUri = if (model.startsWith("gpt://")) {
                    model
                } else {
                    val modelPath = if (model.contains("/")) model else "$model/latest"
                    "gpt://$folderId/$modelPath"
                }

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
                handleStreamResponse(response, this, false)
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun handleStreamResponse(
        response: retrofit2.Response<okhttp3.ResponseBody>,
        collector: kotlinx.coroutines.flow.FlowCollector<Result<String>>,
        isOpenAiFormat: Boolean
    ) {
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                body.source().use { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        
                        try {
                            if (isOpenAiFormat) {
                                if (line.startsWith("data: ")) {
                                    val data = line.substring(6)
                                    if (data.trim() == "[DONE]") break
                                    
                                    val chunk = gson.fromJson(data, ChatStreamResponse::class.java)
                                    val content = chunk.choices.firstOrNull()?.delta?.content
                                    if (content != null) {
                                        collector.emit(Result.success(content))
                                    }
                                }
                            } else {
                                val chunk = gson.fromJson(line, YandexStreamResponse::class.java)
                                val content = chunk.result.alternatives.firstOrNull()?.message?.text
                                if (content != null) {
                                    collector.emit(Result.success(content))
                                }
                            }
                        } catch (e: Exception) {
                            // Skip invalid chunks
                        }
                    }
                }
            } else {
                collector.emit(Result.failure(Exception("Empty response body")))
            }
        } else {
            val errorBody = response.errorBody()?.string() ?: "Unknown error"
            collector.emit(Result.failure(Exception("HTTP Error ${response.code()}: $errorBody")))
        }
    }
}
