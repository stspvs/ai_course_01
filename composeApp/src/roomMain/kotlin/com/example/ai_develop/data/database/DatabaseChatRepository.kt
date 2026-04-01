package com.example.ai_develop.data.database

import com.example.ai_develop.data.database.mappers.toDomain
import com.example.ai_develop.data.database.mappers.toEntity
import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class DatabaseChatRepository(private val db: AppDatabase) : LocalChatRepository {
    private val dao = db.agentDao()

    override fun getAgents(): Flow<List<Agent>> {
        return dao.getAllAgents().map { entities ->
            entities.map { it.toDomain(emptyList()) }
        }
    }

    override fun getAgentWithMessages(agentId: String): Flow<Agent?> {
        val agentFlow = dao.getAgentByIdFlow(agentId)
        val messagesFlow = dao.getMessagesForAgent(agentId)
        
        return agentFlow.combine(messagesFlow) { entity, messageEntities ->
            entity?.toDomain(messageEntities.map { it.toDomain() })
        }
    }

    override suspend fun saveAgent(agent: Agent) {
        dao.updateAgentWithMessages(
            agent.toEntity(),
            agent.messages.map { it.toEntity(agent.id) }
        )
    }

    override suspend fun saveAgentMetadata(agent: Agent) {
        dao.upsertAgent(agent.toEntity())
    }

    override suspend fun saveMessage(agentId: String, message: ChatMessage) {
        dao.insertMessage(message.toEntity(agentId))
        dao.getAgentById(agentId)?.let { agent ->
            dao.updateTokens(agentId, agent.totalTokensUsed + message.tokenCount)
        }
    }

    override suspend fun deleteAgent(agentId: String) {
        dao.getAgentById(agentId)?.let { dao.deleteAgent(it) }
    }
}
