package com.example.ai_develop.data.database

import com.example.ai_develop.data.database.mappers.toDomain
import com.example.ai_develop.data.database.mappers.toEntity
import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

open class DatabaseChatRepository(private val db: AppDatabase?) {
    private val dao = db?.agentDao()

    open fun getAgents(): Flow<List<Agent>> {
        return dao?.getAllAgents()?.map { entities ->
            entities.map { it.toDomain(emptyList()) }
        } ?: kotlinx.coroutines.flow.emptyFlow()
    }

    open fun getAgentWithMessages(agentId: String): Flow<Agent?> {
        val agentFlow = dao?.getAgentByIdFlow(agentId) ?: kotlinx.coroutines.flow.flowOf(null)
        val messagesFlow = dao?.getMessagesForAgent(agentId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
        
        return agentFlow.combine(messagesFlow) { entity, messageEntities ->
            entity?.toDomain(messageEntities.map { it.toDomain() })
        }
    }

    open suspend fun saveAgent(agent: Agent) {
        dao?.updateAgentWithMessages(
            agent.toEntity(),
            agent.messages.map { it.toEntity(agent.id) }
        )
    }

    open suspend fun saveAgentMetadata(agent: Agent) {
        dao?.upsertAgent(agent.toEntity())
    }

    open suspend fun saveMessage(agentId: String, message: ChatMessage) {
        dao?.insertMessage(message.toEntity(agentId))
        dao?.getAgentById(agentId)?.let { agent ->
            dao?.updateTokens(agentId, agent.totalTokensUsed + message.tokenCount)
        }
    }

    open suspend fun deleteAgent(agentId: String) {
        dao?.getAgentById(agentId)?.let { dao.deleteAgent(it) }
    }
}
