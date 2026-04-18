package com.example.ai_develop.domain.chat

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>>
    suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?): Result<Unit>
    suspend fun deleteMessagesForTask(taskId: String): Result<Unit>
}
