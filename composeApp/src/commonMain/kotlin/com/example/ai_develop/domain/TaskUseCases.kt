package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow

class GetTasksUseCase(private val repository: TaskRepository) {
    operator fun invoke(): Flow<List<TaskContext>> = repository.getTasks()
}

class CreateTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(task: TaskContext): Result<Unit> = repository.saveTask(task)
}

class UpdateTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(task: TaskContext): Result<Unit> = repository.saveTask(task)
}

class DeleteTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(task: TaskContext): Result<Unit> = repository.deleteTask(task)
}

class ResetTaskUseCase(private val repository: MessageRepository) {
    suspend operator fun invoke(taskId: String): Result<Unit> = repository.deleteMessagesForTask(taskId)
}

class GetMessagesUseCase(private val repository: MessageRepository) {
    operator fun invoke(taskId: String): Flow<List<ChatMessage>> = repository.getMessagesForTask(taskId)
}

class GetAgentsUseCase(private val repository: AgentRepository) {
    operator fun invoke(): Flow<List<Agent>> = repository.getAgents()
}
