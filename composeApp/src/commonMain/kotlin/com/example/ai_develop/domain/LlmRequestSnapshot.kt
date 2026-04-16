package com.example.ai_develop.domain

import kotlinx.serialization.Serializable

/**
 * Источник фрагмента, подмешанного в промпт из RAG.
 */
@Serializable
data class RagSourceRef(
    val documentTitle: String,
    val sourceFileName: String,
    val chunkIndex: Long,
    val score: Double? = null,
    /** Итоговый скор после rerank (Hybrid / LLM), если отличается от косинуса. */
    val finalScore: Double? = null,
    /** Идентификатор чанка в БД (для отладки / дозагрузки). */
    val chunkId: String = "",
    /** Текст чанка, подмешанного в промпт (для просмотра по нажатию на сообщение). */
    val chunkText: String = "",
)

/**
 * Краткая сводка пайплайна RAG для UI (чат, dry-run).
 */
@Serializable
data class RagRetrievalDebug(
    val pipelineMode: RagPipelineMode = RagPipelineMode.Baseline,
    val originalQuery: String = "",
    val retrievalQuery: String = "",
    val rewriteApplied: Boolean = false,
    val recallTopK: Int = 0,
    val finalTopK: Int = 0,
    val candidatesAfterRecall: Int = 0,
    val candidatesAfterThreshold: Int = 0,
    val candidatesAfterRerank: Int = 0,
    val minSimilarity: Float? = null,
    val hybridLexicalWeight: Float? = null,
    val llmRerankModel: String? = null,
    /** Причина пустого контекста: отсечены по порогу, нет чанков в БД и т.д. */
    val emptyReason: String? = null,
)

/**
 * Был ли использован RAG для этого ответа и какие чанки попали в контекст.
 */
@Serializable
data class RagAttribution(
    val used: Boolean = false,
    val sources: List<RagSourceRef> = emptyList(),
    val debug: RagRetrievalDebug? = null,
)

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
    val stopWord: String = "",
    /** PLANNING: в системном промпте есть блок замечаний инспектора плана (после отката из PLAN_VERIFICATION). */
    val planningInspectorRevisionMode: Boolean = false,
    val ragAttribution: RagAttribution? = null,
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
    isJsonMode: Boolean,
    planningInspectorRevisionMode: Boolean = false
): LlmRequestSnapshot = LlmRequestSnapshot(
    effectiveSystemPrompt = effectiveSystemPrompt,
    inputMessagesText = formatLlmInputMessagesText(inputMessages),
    providerName = agent.provider.name,
    model = agent.provider.model,
    agentStage = agentStageLabel,
    temperature = agent.temperature,
    maxTokens = agent.maxTokens,
    isJsonMode = isJsonMode,
    stopWord = agent.stopWord,
    planningInspectorRevisionMode = planningInspectorRevisionMode,
    ragAttribution = null,
)
