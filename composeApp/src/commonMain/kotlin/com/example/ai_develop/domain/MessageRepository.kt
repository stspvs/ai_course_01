package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>>
    suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?): Result<Unit>
    suspend fun deleteMessagesForTask(taskId: String): Result<Unit>
}
