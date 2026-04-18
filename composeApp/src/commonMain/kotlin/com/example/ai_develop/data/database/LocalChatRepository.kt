package com.example.ai_develop.data.database

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import kotlinx.coroutines.flow.Flow

interface LocalChatRepository : AgentRepository {
    suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String? = null, taskState: TaskState? = null): Result<Unit>

    // Task operations
    fun getTasks(): Flow<List<TaskContext>>
    suspend fun saveTask(task: TaskContext): Result<Unit>
    suspend fun deleteTask(task: TaskContext): Result<Unit>
    fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>>
    suspend fun deleteMessagesForTask(taskId: String): Result<Unit>
}
