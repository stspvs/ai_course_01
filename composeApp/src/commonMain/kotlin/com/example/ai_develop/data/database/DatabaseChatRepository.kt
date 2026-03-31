package com.example.ai_develop.data.database

import com.example.ai_develop.data.database.mappers.toDomain
import com.example.ai_develop.data.database.mappers.toEntity
import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class DatabaseChatRepository(private val db: AppDatabase) {
    private val dao = db.agentDao()

    fun getAgents(): Flow<List<Agent>> {
        return dao.getAllAgents().map { entities ->
            entities.map { it.toDomain(emptyList()) }
        }
    }

    fun getAgentWithMessages(agentId: String): Flow<Agent?> {
        return dao.getAgentByIdFlow(agentId).combine(dao.getMessagesForAgent(agentId)) { entity, messageEntities ->
            entity?.toDomain(messageEntities.map { it.toDomain() })
        }
    }

    suspend fun saveAgent(agent: Agent) {
        dao.updateAgentWithMessages(
            agent.toEntity(),
            agent.messages.map { it.toEntity(agent.id) }
        )
    }

    suspend fun saveAgentMetadata(agent: Agent) {
        dao.upsertAgent(agent.toEntity())
    }

    suspend fun saveMessage(agentId: String, message: ChatMessage) {
        dao.insertMessage(message.toEntity(agentId))
        dao.getAgentById(agentId)?.let { agent ->
            dao.updateTokens(agentId, agent.totalTokensUsed + message.tokenCount)
        }
    }

    suspend fun deleteAgent(agentId: String) {
        dao.getAgentById(agentId)?.let { dao.deleteAgent(it) }
    }
}
