package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun getTasks(): Flow<List<TaskContext>>
    suspend fun getTask(taskId: String): TaskContext?
    suspend fun saveTask(task: TaskContext): Result<Unit>
    suspend fun deleteTask(task: TaskContext): Result<Unit>

    /** Холодный старт: все задачи в паузе, чтобы не показывать «в работе» до явного продолжения. */
    suspend fun pauseAllTasksOnAppLaunch(): Result<Unit>
}
