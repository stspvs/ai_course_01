package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

class GetAgentsUseCase(private val repository: ChatRepository) {
    operator fun invoke(): Flow<List<Agent>> {
        return repository.observeAllAgents().map { states ->
            states.map { state ->
                Agent(
                    id = state.agentId,
                    name = state.name,
                    systemPrompt = state.systemPrompt,
                    temperature = state.temperature,
                    provider = LLMProvider.Yandex(), // Можно расширить в AgentState
                    stopWord = state.stopWord,
                    maxTokens = state.maxTokens,
                    memoryStrategy = state.memoryStrategy,
                    workingMemory = state.workingMemory,
                    messages = emptyList() // Сообщения подгружаются отдельно при необходимости
                )
            }
        }
    }
}
