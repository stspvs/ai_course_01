package com.example.ai_develop.data.database

import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.Flow

interface LocalChatRepository {
    fun getAgents(): Flow<List<Agent>>
    fun getAgentWithMessages(agentId: String): Flow<Agent?>
    suspend fun saveAgent(agent: Agent)
    suspend fun saveAgentMetadata(agent: Agent)
    suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String? = null, taskState: TaskState? = null)
    suspend fun deleteAgent(agentId: String)
    
    // Task operations
    fun getTasks(): Flow<List<TaskContext>>
    suspend fun saveTask(task: TaskContext)
    suspend fun deleteTask(task: TaskContext)
    fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>>
    suspend fun deleteMessagesForTask(taskId: String)
}
