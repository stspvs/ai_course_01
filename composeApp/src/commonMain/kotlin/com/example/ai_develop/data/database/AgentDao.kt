package com.example.ai_develop.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY (id = 'general_chat_id') DESC, name ASC")
    fun getAllAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getAgentById(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE id = :id")
    fun getAgentByIdFlow(id: String): Flow<AgentEntity?>

    @Upsert
    suspend fun upsertAgent(agent: AgentEntity)

    @Delete
    suspend fun deleteAgent(agentEntity: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteAgentById(id: String)

    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY timestamp ASC")
    fun getMessagesForAgent(agentId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE agentId = :agentId")
    suspend fun deleteMessagesForAgent(agentId: String)

    @Transaction
    suspend fun updateAgentWithMessages(agent: AgentEntity, messages: List<MessageEntity>) {
        upsertAgent(agent)
        deleteMessagesForAgent(agent.id)
        messages.forEach { insertMessage(it) }
    }

    @Query("UPDATE agents SET totalTokensUsed = totalTokensUsed + :tokens WHERE id = :agentId")
    suspend fun incrementTokens(agentId: String, tokens: Int)

    @Transaction
    suspend fun insertMessageAndIncrementTokens(message: MessageEntity, tokens: Int) {
        insertMessage(message)
        if (tokens > 0) {
            incrementTokens(message.agentId, tokens)
        }
    }
}
