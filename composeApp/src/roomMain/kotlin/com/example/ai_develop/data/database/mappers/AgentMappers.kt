package com.example.ai_develop.data.database.mappers

import com.example.ai_develop.data.database.AgentEntity
import com.example.ai_develop.data.database.MessageEntity
import com.example.ai_develop.domain.*

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
    agentProfile = agentProfile,
    workingMemory = workingMemory
)

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
    agentProfile = agentProfile,
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
    source = source
)

fun ChatMessage.toEntity(agentId: String) = MessageEntity(
    id = id,
    agentId = agentId,
    parentId = parentId,
    branchId = branchId,
    message = message,
    source = source,
    tokenCount = tokensUsed ?: 0,
    timestamp = timestamp
)
