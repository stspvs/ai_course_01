package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DeepSeekRepository @Inject constructor(
    private val deepSeekClient: DeepSeekClientAPI
) : ChatRepository {
    override suspend fun sendMessage(message: String): Result<String> {
        return deepSeekClient.sendMessage(message)
    }
}
