package com.example.ai_develop.data.database

import com.example.ai_develop.data.database.mappers.toAgentDomain
import com.example.ai_develop.data.database.mappers.toMessageDomain
import com.example.ai_develop.data.database.mappers.toDomain
import com.example.ai_develop.data.database.mappers.toEntity
import com.example.ai_develop.domain.Agent
import com.example.ai_develop.domain.AgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DatabaseAgentRepository(private val db: AppDatabase) : AgentRepository {
    private val agentDao = db.agentDao()

    override fun getAgents(): Flow<List<Agent>> {
        return agentDao.getAllAgents()
            .map { it.toAgentDomain() }
            .distinctUntilChanged()
    }

    override fun getAgentWithMessages(agentId: String): Flow<Agent?> {
        val agentFlow = agentDao.getAgentByIdFlow(agentId).distinctUntilChanged()
        val messagesFlow = agentDao.getMessagesForAgent(agentId).distinctUntilChanged()
        
        return agentFlow.combine(messagesFlow) { entity, messageEntities ->
            entity?.toDomain(messageEntities.toMessageDomain())
        }.distinctUntilChanged()
    }

    override suspend fun saveAgent(agent: Agent): Result<Unit> = runCatching {
        println("Saving full agent: ${agent.id}")
        agentDao.updateAgentWithMessages(
            agent.toEntity(),
            agent.messages.map { it.toEntity(agent.id) }
        )
    }

    override suspend fun saveAgentMetadata(agent: Agent): Result<Unit> = runCatching {
        println("Saving agent metadata: ${agent.id}")
        agentDao.upsertAgent(agent.toEntity())
    }

    override suspend fun deleteAgent(agentId: String): Result<Unit> = runCatching {
        println("Deleting agent: $agentId")
        agentDao.deleteAgentById(agentId)
    }
}
