package com.example.ai_develop.data.database.mappers

import com.example.ai_develop.data.database.AgentEntity
import com.example.ai_develop.data.database.MessageEntity
import com.example.ai_develop.domain.Agent
import com.example.ai_develop.domain.ChatMessage

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
    userProfile = userProfile
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
    userProfile = userProfile
)

fun MessageEntity.toDomain() = ChatMessage(
    id = id,
    parentId = parentId,
    branchId = branchId,
    message = message,
    source = source,
    tokenCount = tokenCount,
    timestamp = timestamp,
    isSystemNotification = isSystemNotification
)

fun ChatMessage.toEntity(agentId: String) = MessageEntity(
    id = id,
    agentId = agentId,
    parentId = parentId,
    branchId = branchId,
    message = message,
    source = source,
    tokenCount = tokenCount,
    timestamp = timestamp,
    isSystemNotification = isSystemNotification
)
