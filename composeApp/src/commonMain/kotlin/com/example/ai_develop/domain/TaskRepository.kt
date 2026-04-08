package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun getTasks(): Flow<List<TaskContext>>
    suspend fun saveTask(task: TaskContext): Result<Unit>
    suspend fun deleteTask(task: TaskContext): Result<Unit>
}
