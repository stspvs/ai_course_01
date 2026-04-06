package com.example.ai_develop.data.database

import androidx.room.*
import com.example.ai_develop.domain.TaskState
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE taskId = :taskId")
    suspend fun getTaskById(taskId: String): TaskEntity?

    @Upsert
    suspend fun upsertTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("SELECT * FROM messages WHERE taskId = :taskId ORDER BY timestamp ASC")
    fun getMessagesForTask(taskId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE taskId = :taskId AND taskState = :state ORDER BY timestamp ASC")
    fun getMessagesForTaskState(taskId: String, state: TaskState): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE taskId = :taskId")
    suspend fun deleteMessagesForTask(taskId: String)
}
