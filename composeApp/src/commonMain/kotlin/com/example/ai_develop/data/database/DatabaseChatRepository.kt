package com.example.ai_develop.data.database

import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.presentation.Agent
import com.example.ai_develop.presentation.ChatMessage
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
        val agentFlow = dao.getAgentByIdFlow(agentId)
        val messagesFlow = dao.getMessagesForAgent(agentId)

        return agentFlow.combine(messagesFlow) { entity, messageEntities ->
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
        dao.insertAgent(agent.toEntity())
    }

    suspend fun saveMessage(agentId: String, message: ChatMessage) {
        dao.insertMessage(message.toEntity(agentId))
        // Обновляем общее количество токенов в агенте
        val agent = dao.getAgentById(agentId)
        if (agent != null) {
            dao.updateTokens(agentId, agent.totalTokensUsed + message.tokenCount)
        }
    }

    suspend fun clearChatTokens(agentId: String) {
        dao.updateTokens(agentId, 0)
    }

    suspend fun deleteAgent(agentId: String) {
        val agent = dao.getAgentById(agentId)
        if (agent != null) {
            dao.deleteAgent(agent)
        }
    }

    // Mappers
    private fun AgentEntity.toDomain(messages: List<ChatMessage>) = Agent(
        id = id,
        name = name,
        systemPrompt = systemPrompt,
        temperature = temperature,
        provider = provider,
        stopWord = stopWord,
        maxTokens = maxTokens,
        messages = messages,
        totalTokensUsed = totalTokensUsed,
        summary = summary,
        keepLastMessagesCount = keepLastMessagesCount,
        summaryPrompt = summaryPrompt,
        summaryDepth = summaryDepth
    )

    private fun Agent.toEntity() = AgentEntity(
        id = id,
        name = name,
        systemPrompt = systemPrompt,
        temperature = temperature,
        provider = provider,
        stopWord = stopWord,
        maxTokens = maxTokens,
        totalTokensUsed = totalTokensUsed,
        summary = summary,
        keepLastMessagesCount = keepLastMessagesCount,
        summaryPrompt = summaryPrompt,
        summaryDepth = summaryDepth
    )

    private fun MessageEntity.toDomain() = ChatMessage(
        id = id,
        message = message,
        source = source,
        tokenCount = tokenCount,
        timestamp = timestamp,
        isSystemNotification = isSystemNotification
    )

    private fun ChatMessage.toEntity(agentId: String) = MessageEntity(
        id = id,
        agentId = agentId,
        message = message,
        source = source,
        tokenCount = tokenCount,
        timestamp = timestamp,
        isSystemNotification = isSystemNotification
    )
}
