package com.example.ai_develop.data.database.mappers

import com.example.ai_develop.data.database.AgentEntity
import com.example.ai_develop.data.database.MessageEntity
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

fun AgentEntity.toDomain(messages: List<ChatMessage>) = Agent(
    id = id,
    name = name,
    systemPrompt = systemPrompt,
    temperature = temperature,
    provider = provider,
    stopWord = stopWord,
    maxTokens = maxTokens,
    messages = messages,
    totalTokensUsed = totalTokensUsed,
    memoryStrategy = memoryStrategy,
    branches = branches,
    currentBranchId = currentBranchId,
    userProfile = userProfile,
    workingMemory = workingMemory
)

private val snapshotCodec = Json { ignoreUnknownKeys = true }

fun Agent.toEntity() = AgentEntity(
    id = id,
    name = name,
    systemPrompt = systemPrompt,
    temperature = temperature,
    provider = provider,
    stopWord = stopWord,
    maxTokens = maxTokens,
    totalTokensUsed = totalTokensUsed,
    memoryStrategy = memoryStrategy,
    branches = branches,
    currentBranchId = currentBranchId,
    userProfile = userProfile,
    workingMemory = workingMemory
)

fun MessageEntity.toDomain() = ChatMessage(
    id = id,
    parentId = parentId,
    branchId = branchId,
    message = message,
    role = when(source) {
        SourceType.USER -> "user"
        SourceType.AI, SourceType.ASSISTANT -> "assistant"
        else -> "system"
    },
    tokensUsed = tokenCount,
    timestamp = timestamp,
    source = source,
    isSystemNotification = isSystemNotification,
    taskId = taskId,
    taskState = taskState,
    llmRequestSnapshot = llmSnapshotJson?.let { raw ->
        try {
            snapshotCodec.decodeFromString(LlmRequestSnapshot.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }
)

fun ChatMessage.toEntity(agentId: String) = MessageEntity(
    id = id,
    agentId = agentId,
    parentId = parentId,
    branchId = branchId,
    message = message,
    source = source,
    tokenCount = tokensUsed ?: 0,
    timestamp = timestamp,
    isSystemNotification = isSystemNotification,
    taskId = taskId,
    taskState = taskState,
    llmSnapshotJson = llmRequestSnapshot?.let {
        snapshotCodec.encodeToString(LlmRequestSnapshot.serializer(), it)
    }
)
