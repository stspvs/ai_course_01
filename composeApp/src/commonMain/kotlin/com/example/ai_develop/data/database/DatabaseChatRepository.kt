package com.example.ai_develop.data.database

import com.example.ai_develop.data.database.mappers.toDomain
import com.example.ai_develop.data.database.mappers.toEntity
import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class DatabaseChatRepository(private val db: AppDatabase) : LocalChatRepository {
    private val agentDao = db.agentDao()
    private val taskDao = db.taskDao()

    override fun getAgents(): Flow<List<Agent>> {
        return agentDao.getAllAgents().map { entities ->
            entities.map { it.toDomain(emptyList()) }
        }
    }

    override fun getAgentWithMessages(agentId: String): Flow<Agent?> {
        val agentFlow = agentDao.getAgentByIdFlow(agentId)
        val messagesFlow = agentDao.getMessagesForAgent(agentId)
        
        return agentFlow.combine(messagesFlow) { entity, messageEntities ->
            entity?.toDomain(messageEntities.map { it.toDomain() })
        }
    }

    override suspend fun saveAgent(agent: Agent) {
        agentDao.updateAgentWithMessages(
            agent.toEntity(),
            agent.messages.map { it.toEntity(agent.id) }
        )
    }

    override suspend fun saveAgentMetadata(agent: Agent) {
        agentDao.upsertAgent(agent.toEntity())
    }

    override suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?) {
        val entity = message.toEntity(agentId).copy(taskId = taskId, taskState = taskState)
        agentDao.insertMessage(entity)
        agentDao.getAgentById(agentId)?.let { agent ->
            agentDao.updateTokens(agentId, agent.totalTokensUsed + (message.tokensUsed ?: 0))
        }
    }

    override suspend fun deleteAgent(agentId: String) {
        agentDao.getAgentById(agentId)?.let { agentDao.deleteAgent(it) }
    }

    // Task operations
    override fun getTasks(): Flow<List<TaskContext>> {
        return taskDao.getAllTasks().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveTask(task: TaskContext) {
        taskDao.upsertTask(task.toEntity())
    }

    override suspend fun deleteTask(task: TaskContext) {
        taskDao.deleteTask(task.toEntity())
    }

    override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> {
        return taskDao.getMessagesForTask(taskId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun deleteMessagesForTask(taskId: String) {
        taskDao.deleteMessagesForTask(taskId)
    }
}
