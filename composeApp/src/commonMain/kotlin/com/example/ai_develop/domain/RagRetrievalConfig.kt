package com.example.ai_develop.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RagPipelineMode {
    /** Как раньше: один отбор по косинусу, без второго этапа (recallTopK и finalTopK обычно равны). */
    Baseline,
    /** После recall — отсечение по minSimilarity, затем finalTopK. */
    Threshold,
    /** Порог + эвристический rerank (лексика). */
    Hybrid,
    /** Порог (если задан) + оценка чанков отдельной моделью Ollama. */
    LlmRerank,
}

@Serializable
enum class RagEvaluationScope {
    ALL,
    SUBSET,
}

@Serializable
data class RagEvaluationStepsEnabled(
    val threshold: Boolean = true,
    val heuristic: Boolean = true,
    val llmRerank: Boolean = true,
    val queryRewrite: Boolean = true,
)

@Serializable
data class RagRetrievalConfig(
    val pipelineMode: RagPipelineMode = RagPipelineMode.Baseline,
    val recallTopK: Int = 5,
    val finalTopK: Int = 5,
    /** Порог косинусной близости; null — не применять (кроме режимов, где порог обязателен — тогда трактуем как 0). */
    val minSimilarity: Float? = null,
    /**
     * Коэффициент w в режиме Hybrid: `combined = w * cosine + (1 - w) * jaccard`.
     * Чем больше w (ближе к 1), тем сильнее роль косинуса эмбеддингов; чем меньше — тем сильнее лексическое пересечение (Jaccard).
     */
    val hybridLexicalWeight: Float = 0.35f,
    val queryRewriteEnabled: Boolean = false,
    /** Если не пусто и провайдер Ollama — подмена имени модели для rewrite. */
    val rewriteOllamaModel: String = "",
    val llmRerankOllamaModel: String = "",
    val llmRerankMaxCandidates: Int = 10,
    val evaluationScope: RagEvaluationScope = RagEvaluationScope.ALL,
    val evaluationStepsEnabled: RagEvaluationStepsEnabled = RagEvaluationStepsEnabled(),
) {
    companion object {
        val Default = RagRetrievalConfig()
    }
}
