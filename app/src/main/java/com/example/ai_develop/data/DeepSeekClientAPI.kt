package com.example.ai_develop.data

import com.example.ai_develop.presentation.ChatMessage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DeepSeekClientAPI @Inject constructor(
    private val apiKey: String
) {
    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val api: DeepSeekApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeepSeekApi::class.java)
    }

    suspend fun sendMessage(
        chatHistory: List<ChatMessage>, 
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiMessages = mutableListOf<Message>()
            if (systemPrompt.isNotBlank()) {
                apiMessages.add(Message(role = "system", content = systemPrompt))
            }
            
            // Добавляем всю историю чата
            chatHistory.forEach { msg ->
                apiMessages.add(Message(role = msg.source.role, content = msg.message))
            }

            val shouldEnableJson = isJsonMode && systemPrompt.contains("json", ignoreCase = true)

            val request = ChatRequest(
                messages = apiMessages,
                maxTokens = maxTokens,
                temperature = temperature,
                stream = false,
                responseFormat = if (shouldEnableJson) ResponseFormat("json_object") else null,
                stop = if (stopWord.isNotBlank()) listOf(stopWord) else null
            )
            
            val response = api.sendMessage(request)

            if (response.isSuccessful) {
                val content = response.body()?.choices?.firstOrNull()?.message?.content
                if (content != null) {
                    Result.success(content)
                } else {
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("HTTP Error ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun chatStreaming(
        chatHistory: List<ChatMessage>, 
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean
    ): Flow<Result<String>> = flow {
        try {
            val apiMessages = mutableListOf<Message>()
            if (systemPrompt.isNotBlank()) {
                apiMessages.add(Message(role = "system", content = systemPrompt))
            }
            
            // Добавляем всю историю чата
            chatHistory.forEach { msg ->
                apiMessages.add(Message(role = msg.source.role, content = msg.message))
            }

            val shouldEnableJson = isJsonMode && systemPrompt.contains("json", ignoreCase = true)

            val request = ChatRequest(
                messages = apiMessages,
                maxTokens = maxTokens,
                temperature = temperature,
                stream = true,
                responseFormat = if (shouldEnableJson) ResponseFormat("json_object") else null,
                stop = if (stopWord.isNotBlank()) listOf(stopWord) else null
            )
            
            val response = api.chatStreaming(request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    body.source().use { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data: ")) {
                                val data = line.substring(6)
                                if (data == "[DONE]") break
                                
                                try {
                                    val chunk = gson.fromJson(data, ChatStreamResponse::class.java)
                                    val content = chunk.choices.firstOrNull()?.delta?.content
                                    if (content != null) {
                                        emit(Result.success(content))
                                    }
                                } catch (e: Exception) {
                                    // Skip invalid chunks
                                }
                            }
                        }
                    }
                } else {
                    emit(Result.failure(Exception("Empty response body")))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                emit(Result.failure(Exception("HTTP Error ${response.code()}: $errorBody")))
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)
}
