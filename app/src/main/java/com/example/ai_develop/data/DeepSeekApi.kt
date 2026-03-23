package com.example.ai_develop.data

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming

internal interface DeepSeekApi {
    @Streaming
    @POST("v1/chat/completions")
    suspend fun chatStreaming(@Body request: ChatRequest): Response<ResponseBody>
}
