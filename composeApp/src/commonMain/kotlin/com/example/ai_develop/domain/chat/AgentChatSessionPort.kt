package com.example.ai_develop.domain.chat

import com.example.ai_develop.domain.agent.AutonomousAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Узкий контракт сессии чата с [AutonomousAgent] для ViewModel и координаторов.
 */
interface AgentChatSessionPort {
    val agentCacheGeneration: StateFlow<Long>

    suspend fun ensureToolsLoaded()

    suspend fun toolNamesForAgent(agent: Agent): List<String>

    fun observeMcpRegistryRefresh(): Flow<Unit>

    fun getOrCreateAgent(agentId: String, taskIdForMessagePersistence: String?): AutonomousAgent

    fun evictAgent(agentId: String)
}
