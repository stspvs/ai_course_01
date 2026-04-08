package com.example.ai_develop.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class SqlDelightChatRepository(
    private val db: AgentDatabase,
    private val networkRepository: ChatRepository // Для проброса вызовов к LLM
) : ChatRepository by networkRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveAgentState(state: AgentState) {
        db.agentDatabaseQueries.saveAgentState(
            agentId = state.agentId,
            name = state.name,
            systemPrompt = state.systemPrompt,
            temperature = state.temperature,
            maxTokens = state.maxTokens.toLong(),
            stopWord = state.stopWord,
            currentStage = state.currentStage,
            currentStepId = state.currentStepId,
            planJson = json.encodeToString(AgentPlan.serializer(), state.plan),
            memoryStrategyJson = json.encodeToString(ChatMemoryStrategy.serializer(), state.memoryStrategy),
            workingMemoryJson = json.encodeToString(WorkingMemory.serializer(), state.workingMemory)
        )
    }

    override suspend fun getAgentState(agentId: String): AgentState? {
        return db.agentDatabaseQueries.getAgentState(agentId).executeAsOneOrNull()?.let {
            AgentState(
                agentId = it.agentId,
                name = it.name,
                systemPrompt = it.systemPrompt,
                temperature = it.temperature,
                maxTokens = it.maxTokens.toInt(),
                stopWord = it.stopWord,
                currentStage = it.currentStage,
                currentStepId = it.currentStepId,
                plan = json.decodeFromString(AgentPlan.serializer(), it.planJson),
                memoryStrategy = if (it.memoryStrategyJson.isNotEmpty()) {
                    json.decodeFromString(ChatMemoryStrategy.serializer(), it.memoryStrategyJson)
                } else {
                    ChatMemoryStrategy.SlidingWindow(10)
                },
                workingMemory = if (it.workingMemoryJson.isNotEmpty()) {
                    json.decodeFromString(WorkingMemory.serializer(), it.workingMemoryJson)
                } else {
                    WorkingMemory()
                }
            )
        }
    }

    override suspend fun deleteAgent(agentId: String) {
        db.agentDatabaseQueries.deleteAgent(agentId)
    }

    override fun observeAgentState(agentId: String): Flow<AgentState?> {
        return db.agentDatabaseQueries.getAgentState(agentId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { entity ->
                entity?.let {
                    AgentState(
                        agentId = it.agentId,
                        name = it.name,
                        systemPrompt = it.systemPrompt,
                        temperature = it.temperature,
                        maxTokens = it.maxTokens.toInt(),
                        stopWord = it.stopWord,
                        currentStage = it.currentStage,
                        currentStepId = it.currentStepId,
                        plan = json.decodeFromString(AgentPlan.serializer(), it.planJson),
                        memoryStrategy = if (it.memoryStrategyJson.isNotEmpty()) {
                            json.decodeFromString(ChatMemoryStrategy.serializer(), it.memoryStrategyJson)
                        } else {
                            ChatMemoryStrategy.SlidingWindow(10)
                        },
                        workingMemory = if (it.workingMemoryJson.isNotEmpty()) {
                            json.decodeFromString(WorkingMemory.serializer(), it.workingMemoryJson)
                        } else {
                            WorkingMemory()
                        }
                    )
                }
            }
    }

    override suspend fun getProfile(agentId: String): UserProfile? {
        return db.agentDatabaseQueries.getProfile(agentId).executeAsOneOrNull()?.let {
            UserProfile(
                preferences = it.preferences,
                constraints = it.constraints,
                memoryModelProvider = it.memoryModelProviderJson?.let { jsonStr ->
                    json.decodeFromString<LLMProvider>(jsonStr)
                }
            )
        }
    }

    override suspend fun saveProfile(agentId: String, profile: UserProfile) {
        db.agentDatabaseQueries.saveProfile(
            agentId = agentId,
            preferences = profile.preferences,
            constraints = profile.constraints,
            memoryModelProviderJson = profile.memoryModelProvider?.let { json.encodeToString(it) }
        )
    }

    override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> {
        return db.agentDatabaseQueries.getInvariantsForStage(agentId, stage).executeAsList().map {
            Invariant(it.id, it.rule, it.stage, it.isActive)
        }
    }

    override suspend fun saveInvariant(invariant: Invariant) {
        db.agentDatabaseQueries.insertInvariant(
            id = invariant.id,
            agentId = "default", // Или передавать конкретный ID
            rule = invariant.rule,
            stage = invariant.stage,
            isActive = invariant.isActive
        )
    }
}
