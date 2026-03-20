package com.example.ai_develop.data

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal class DeepSeekClientAPI(private val apiKey: String) {

    companion object {
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    // suspend функция для работы с coroutine
    suspend fun sendMessage(userMessage: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("model", "deepseek-chat")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    })
                })
                put("max_tokens", 300)
                put("stream", false)
            }.toString()

            val request = Request.Builder()
                .url("https://api.deepseek.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val bodyString = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            val json = try {
                JSONObject(bodyString)
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Invalid JSON"))
            }

            val message = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?: return@withContext Result.failure(Exception("Invalid response structure"))

            Result.success(message)

        } catch (e: IOException) {
            Result.failure(Exception("Network error: ${e.localizedMessage}", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}