package com.example.ai_develop.data

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

internal interface YandexApi {
    @Streaming
    @POST("foundationModels/v1/completion")
    suspend fun chatStreaming(
        @Header("Authorization") auth: String,
        @Header("x-folder-id") folderId: String,
        @Body request: YandexChatRequest
    ): Response<ResponseBody>
}
