package com.example.ai_develop.domain

import kotlinx.serialization.Serializable

/**
 * Снимок фактического запроса к LLM на момент генерации ответа (для отладки в UI).
 */
@Serializable
data class LlmRequestSnapshot(
    val effectiveSystemPrompt: String,
    val inputMessagesText: String,
    val providerName: String,
    val model: String,
    val agentStage: String,
    val temperature: Double,
    val maxTokens: Int,
    val isJsonMode: Boolean,
    val stopWord: String = ""
)

fun formatLlmInputMessagesText(messages: List<ChatMessage>): String =
    messages.joinToString(separator = "\n\n---\n\n") { msg ->
        "[${msg.role}]\n${msg.content}"
    }

/** Снимок для произвольного вызова (например TaskSaga), когда сбор промпта не через [AgentEngine]. */
fun buildLlmRequestSnapshot(
    effectiveSystemPrompt: String,
    inputMessages: List<ChatMessage>,
    agent: Agent,
    agentStageLabel: String,
    isJsonMode: Boolean
): LlmRequestSnapshot = LlmRequestSnapshot(
    effectiveSystemPrompt = effectiveSystemPrompt,
    inputMessagesText = formatLlmInputMessagesText(inputMessages),
    providerName = agent.provider.name,
    model = agent.provider.model,
    agentStage = agentStageLabel,
    temperature = agent.temperature,
    maxTokens = agent.maxTokens,
    isJsonMode = isJsonMode,
    stopWord = agent.stopWord
)
