package com.example.ai_develop.data.database

import com.example.ai_develop.data.database.mappers.toMessageDomain
import com.example.ai_develop.data.database.mappers.toDomain
import com.example.ai_develop.data.database.mappers.toEntity
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.MessageRepository
import com.example.ai_develop.domain.TaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DatabaseMessageRepository(private val db: AppDatabase) : MessageRepository {
    private val agentDao = db.agentDao()
    private val taskDao = db.taskDao()

    override suspend fun saveMessage(
        agentId: String,
        message: ChatMessage,
        taskId: String?,
        taskState: TaskState?
    ): Result<Unit> = runCatching {
        println("Saving message for agent $agentId, task $taskId")
        val entity = message.toEntity(agentId).copy(taskId = taskId, taskState = taskState)
        val tokens = message.tokensUsed ?: 0
        agentDao.insertMessageAndIncrementTokens(entity, tokens)
    }

    override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> {
        return taskDao.getMessagesForTask(taskId)
            .map { it.toMessageDomain() }
            .distinctUntilChanged()
    }

    override suspend fun deleteMessagesForTask(taskId: String): Result<Unit> = runCatching {
        println("Deleting messages for task $taskId")
        taskDao.deleteMessagesForTask(taskId)
    }
}
