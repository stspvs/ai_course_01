package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow

class GetTasksUseCase(private val repository: TaskRepository) {
    operator fun invoke(): Flow<List<TaskContext>> = repository.getTasks()
}

class GetTaskUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(taskId: String): TaskContext? = repository.getTask(taskId)
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

class PauseAllTasksOnAppLaunchUseCase(private val repository: TaskRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.pauseAllTasksOnAppLaunch()
}

class ResetTaskUseCase(
    private val chatRepository: ChatRepository,
    private val chatStreamingUseCase: ChatStreamingUseCase
) {
    suspend operator fun invoke(taskId: String): Result<Unit> {
        chatRepository.resetTaskConversation(taskId).getOrThrow()
        chatStreamingUseCase.evictAgent(taskId)
        return Result.success(Unit)
    }
}

class GetMessagesUseCase(private val repository: MessageRepository) {
    operator fun invoke(taskId: String): Flow<List<ChatMessage>> = repository.getMessagesForTask(taskId)
}

class GetAgentsUseCase(private val agentRepository: AgentRepository) {
    operator fun invoke(): Flow<List<Agent>> = agentRepository.getAgents()
}
