package com.example.ai_develop.data.database

import com.example.ai_develop.data.database.mappers.toDomain
import com.example.ai_develop.data.database.mappers.toEntity
import com.example.ai_develop.domain.Agent
import com.example.ai_develop.domain.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomLocalChatRepository(
    private val agentDao: AgentDao
) : LocalChatRepository {

    override fun getAgents(): Flow<List<Agent>> {
        return agentDao.getAllAgents().map { entities ->
            entities.map { it.toDomain(emptyList()) }
        }
    }

    override fun getAgentWithMessages(agentId: String): Flow<Agent?> {
        val agentFlow = agentDao.getAgentByIdFlow(agentId)
        val messagesFlow = agentDao.getMessagesForAgent(agentId)

        return agentFlow.combine(messagesFlow) { agentEntity, messageEntities ->
            agentEntity?.toDomain(messageEntities.map { it.toDomain() })
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

    override suspend fun saveMessage(agentId: String, message: ChatMessage) {
        agentDao.insertMessage(message.toEntity(agentId))
    }

    override suspend fun deleteAgent(agentId: String) {
        val entity = agentDao.getAgentById(agentId)
        if (entity != null) {
            agentDao.deleteAgent(entity)
        }
    }
}
