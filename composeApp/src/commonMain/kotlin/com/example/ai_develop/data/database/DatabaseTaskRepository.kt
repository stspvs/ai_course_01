package com.example.ai_develop.data.database

import com.example.ai_develop.data.database.mappers.toDomain
import com.example.ai_develop.data.database.mappers.toEntity
import com.example.ai_develop.domain.TaskContext
import com.example.ai_develop.domain.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DatabaseTaskRepository(private val db: AppDatabase) : TaskRepository {
    private val taskDao = db.taskDao()

    override fun getTasks(): Flow<List<TaskContext>> {
        return taskDao.getAllTasks()
            .map { entities -> entities.map { it.toDomain() } }
            .distinctUntilChanged()
    }

    override suspend fun saveTask(task: TaskContext): Result<Unit> = runCatching {
        println("Saving task: ${task.taskId}")
        taskDao.upsertTask(task.toEntity())
    }

    override suspend fun deleteTask(task: TaskContext): Result<Unit> = runCatching {
        println("Deleting task: ${task.taskId}")
        taskDao.deleteTask(task.toEntity())
    }
}
