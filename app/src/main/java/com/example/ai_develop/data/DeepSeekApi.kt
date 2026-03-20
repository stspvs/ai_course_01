package com.example.ai_develop.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

internal interface DeepSeekApi {
    @POST("v1/chat/completions")
    suspend fun sendMessage(@Body request: ChatRequest): Response<ChatResponse>
}
