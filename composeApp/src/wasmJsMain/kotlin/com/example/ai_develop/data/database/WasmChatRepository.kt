package com.example.ai_develop.data.database

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import kotlinx.browser.window
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WasmChatRepository(private val json: Json) : LocalChatRepository {
    private val AGENTS_LIST_KEY = "chat_agents_ids"
    private val GENERAL_CHAT_ID = "general_chat_id"
    private fun getAgentKey(id: String) = "agent_data_$id"

    private val refreshSignal = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    override fun getAgents(): Flow<List<Agent>> = refreshSignal.map {
        val idsJson = window.localStorage.getItem(AGENTS_LIST_KEY) ?: "[]"
        val ids = try {
            json.decodeFromString<List<String>>(idsJson)
        } catch (e: Exception) {
            emptyList()
        }
        ids.mapNotNull { id ->
            window.localStorage.getItem(getAgentKey(id))?.let {
                try {
                    json.decodeFromString<Agent>(it)
                } catch (e: Exception) {
                    null
                }
            }
        }.sortedWith(
            compareByDescending<Agent> { it.id == GENERAL_CHAT_ID }
                .thenBy { it.name }
        )
    }

    override fun getAgentWithMessages(agentId: String): Flow<Agent?> = refreshSignal.map {
        window.localStorage.getItem(getAgentKey(agentId))?.let {
            try {
                json.decodeFromString<Agent>(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun saveAgent(agent: Agent) {
        window.localStorage.setItem(getAgentKey(agent.id), json.encodeToString(agent))
        
        val idsJson = window.localStorage.getItem(AGENTS_LIST_KEY) ?: "[]"
        val ids = try {
            json.decodeFromString<List<String>>(idsJson).toMutableSet()
        } catch (e: Exception) {
            mutableSetOf()
        }
        
        if (ids.add(agent.id)) {
            window.localStorage.setItem(AGENTS_LIST_KEY, json.encodeToString(ids.toList()))
        }
        refreshSignal.emit(Unit)
    }

    override suspend fun saveAgentMetadata(agent: Agent) {
        val existing = window.localStorage.getItem(getAgentKey(agent.id))?.let {
            try {
                json.decodeFromString<Agent>(it)
            } catch (e: Exception) {
                null
            }
        }
        
        val toSave = existing?.copy(
            name = agent.name,
            systemPrompt = agent.systemPrompt,
            temperature = agent.temperature,
            provider = agent.provider,
            stopWord = agent.stopWord,
            maxTokens = agent.maxTokens,
            memoryStrategy = agent.memoryStrategy,
            currentBranchId = agent.currentBranchId,
            branches = agent.branches,
            ragEnabled = agent.ragEnabled,
            mcpAllowedBindingIds = agent.mcpAllowedBindingIds,
        ) ?: agent
        
        saveAgent(toSave)
    }

    override suspend fun saveMessage(agentId: String, message: ChatMessage) {
        val existing = window.localStorage.getItem(getAgentKey(agentId))?.let {
            try {
                json.decodeFromString<Agent>(it)
            } catch (e: Exception) {
                null
            }
        } ?: return
        
        val updated = existing.copy(
            messages = existing.messages + message,
            totalTokensUsed = existing.totalTokensUsed + message.tokenCount
        )
        saveAgent(updated)
    }

    override suspend fun deleteAgent(agentId: String) {
        window.localStorage.removeItem(getAgentKey(agentId))
        val idsJson = window.localStorage.getItem(AGENTS_LIST_KEY) ?: "[]"
        val ids = try {
            json.decodeFromString<List<String>>(idsJson).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
        
        if (ids.remove(agentId)) {
            window.localStorage.setItem(AGENTS_LIST_KEY, json.encodeToString(ids))
        }
        refreshSignal.emit(Unit)
    }
}
