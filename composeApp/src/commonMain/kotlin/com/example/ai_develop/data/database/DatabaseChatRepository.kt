package com.example.ai_develop.data.database

import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.Flow

class DatabaseChatRepository(
    private val agentRepository: AgentRepository,
    private val taskRepository: TaskRepository,
    private val messageRepository: MessageRepository
) : LocalChatRepository, 
    AgentRepository by agentRepository,
    TaskRepository by taskRepository,
    MessageRepository by messageRepository {

    // Переопределяем методы LocalChatRepository, чтобы они возвращали Result,
    // делегируя вызовы в TaskRepository / MessageRepository (и save-операции агента).

    override suspend fun saveAgent(agent: Agent): Result<Unit> = 
        agentRepository.saveAgent(agent)

    override suspend fun saveAgentMetadata(agent: Agent): Result<Unit> = 
        agentRepository.saveAgentMetadata(agent)

    override suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?): Result<Unit> = 
        messageRepository.saveMessage(agentId, message, taskId, taskState)

    override suspend fun saveTask(task: TaskContext): Result<Unit> = 
        taskRepository.saveTask(task)

    override suspend fun deleteTask(task: TaskContext): Result<Unit> = 
        taskRepository.deleteTask(task)

    override suspend fun deleteMessagesForTask(taskId: String): Result<Unit> = 
        messageRepository.deleteMessagesForTask(taskId)
}
