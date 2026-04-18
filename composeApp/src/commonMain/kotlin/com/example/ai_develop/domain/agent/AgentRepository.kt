package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.coroutines.flow.Flow

interface AgentRepository {
    fun getAgents(): Flow<List<Agent>>
    fun getAgentWithMessages(agentId: String): Flow<Agent?>
    suspend fun saveAgent(agent: Agent): Result<Unit>
    suspend fun saveAgentMetadata(agent: Agent): Result<Unit>
    suspend fun deleteAgent(agentId: String)
}
