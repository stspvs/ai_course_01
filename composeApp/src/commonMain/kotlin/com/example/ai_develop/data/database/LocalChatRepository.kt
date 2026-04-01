package com.example.ai_develop.data.database

import com.example.ai_develop.domain.Agent
import com.example.ai_develop.domain.ChatMessage
import kotlinx.coroutines.flow.Flow

interface LocalChatRepository {
    fun getAgents(): Flow<List<Agent>>
    fun getAgentWithMessages(agentId: String): Flow<Agent?>
    suspend fun saveAgent(agent: Agent)
    suspend fun saveAgentMetadata(agent: Agent)
    suspend fun saveMessage(agentId: String, message: ChatMessage)
    suspend fun deleteAgent(agentId: String)
}
