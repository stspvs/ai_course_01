package com.example.ai_develop.data

import com.example.ai_develop.domain.RagAttribution
import com.example.ai_develop.domain.RagPipelineMode
import com.example.ai_develop.domain.RagRetrievalConfig
import com.example.ai_develop.domain.RagRetrievalDebug
import com.example.ai_develop.domain.RagSourceRef

/**
 * Результат поиска: текст для системного промпта и атрибуция для UI.
 */
data class RagRetrievalResult(
    val contextText: String,
    val attribution: RagAttribution,
)

class RagContextRetriever(
    private val ollamaEmbeddingClient: OllamaEmbeddingClient,
    private val ragEmbeddingRepository: RagEmbeddingRepository,
    private val ollamaRerankClient: OllamaRagRerankClient,
) {

    /**
     * @param originalQuery исходный текст пользователя (для debug)
     * @param retrievalQuery строка для эмбеддинга (после optional rewrite)
     */
    suspend fun retrieve(
        originalQuery: String,
        retrievalQuery: String,
        config: RagRetrievalConfig,
        rewriteApplied: Boolean,
    ): RagRetrievalResult? {
        val q = retrievalQuery.trim()
        if (q.isEmpty()) return null

        val chunks = ragEmbeddingRepository.loadAllChunksForRetrieval()
        if (chunks.isEmpty()) {
            return RagRetrievalResult(
                contextText = "",
                attribution = RagAttribution(
                    used = false,
                    debug = RagRetrievalDebug(
                        pipelineMode = config.pipelineMode,
                        originalQuery = originalQuery,
                        retrievalQuery = q,
                        rewriteApplied = rewriteApplied,
                        emptyReason = "В базе нет проиндексированных чанков",
                    ),
                ),
            )
        }

        val byModel = chunks.groupBy { it.ollamaModel.trim() }
        val scored = mutableListOf<ScoredChunk>()
        for ((model, list) in byModel) {
            val qVec = runCatching { ollamaEmbeddingClient.embed(model, q) }.getOrNull() ?: continue
            for (ch in list) {
                if (ch.embedding.size != qVec.size) continue
                val s = dotProduct(qVec, ch.embedding)
                scored.add(ScoredChunk(ch, s))
            }
        }
        if (scored.isEmpty()) {
            return RagRetrievalResult(
                contextText = "",
                attribution = RagAttribution(
                    used = false,
                    debug = RagRetrievalDebug(
                        pipelineMode = config.pipelineMode,
                        originalQuery = originalQuery,
                        retrievalQuery = q,
                        rewriteApplied = rewriteApplied,
                        emptyReason = "Не удалось получить эмбеддинг запроса (Ollama или размерность)",
                    ),
                ),
            )
        }

        val recallK = effectiveRecallTopK(config, scored.size)
        var ordered = scored
            .sortedByDescending { it.cosine }
            .distinctBy { it.chunk.chunkId }
            .take(recallK)

        val candidatesAfterRecall = ordered.size
        val min = config.minSimilarity

        if (min != null && config.pipelineMode != RagPipelineMode.Baseline) {
            ordered = applyCosineThreshold(ordered, min)
        }
        val candidatesAfterThreshold = ordered.size

        var finalScores: List<Pair<ScoredChunk, Double>> = ordered.map { it to it.cosine.toDouble() }

        when (config.pipelineMode) {
            RagPipelineMode.Baseline, RagPipelineMode.Threshold -> { /* finalScores = cosine */ }
            RagPipelineMode.Hybrid -> {
                ordered = rerankHybrid(q, ordered, config.hybridLexicalWeight)
                finalScores = ordered.map { sc ->
                    val lex = lexicalJaccard(q, sc.chunk.text)
                    val comb = hybridCombinedScore(sc.cosine, lex, config.hybridLexicalWeight).toDouble()
                    sc to comb
                }
            }
            RagPipelineMode.LlmRerank -> {
                val model = config.llmRerankOllamaModel.trim().ifBlank { null }
                val limit = config.llmRerankMaxCandidates.coerceAtLeast(1)
                val slice = ordered.take(limit)
                if (model != null) {
                    val scoredList = slice.map { sc ->
                        val llm = ollamaRerankClient.scoreRelevance(model, q, sc.chunk.text)
                        val final = (llm ?: sc.cosine).toDouble()
                        sc to final
                    }.sortedByDescending { it.second }
                    val rerankedHead = scoredList.map { it.first }
                    val tail = ordered.drop(limit)
                    ordered = rerankedHead + tail
                    finalScores = scoredList + tail.map { it to it.cosine.toDouble() }
                } else {
                    finalScores = ordered.map { it to it.cosine.toDouble() }
                }
            }
        }

        val candidatesAfterRerank = ordered.size
        val scoreMap = finalScores.associate { (sc, fs) -> sc.chunk.chunkId to fs }

        val finalK = effectiveFinalTopK(config, ordered.size)
        val top = ordered.take(finalK)

        if (top.isEmpty()) {
            return RagRetrievalResult(
                contextText = "",
                attribution = RagAttribution(
                    used = false,
                    sources = emptyList(),
                    debug = debugBlock(
                        config, originalQuery, q, rewriteApplied,
                        recallK, finalK, candidatesAfterRecall, candidatesAfterThreshold, candidatesAfterRerank, min,
                        emptyReason = "После фильтрации не осталось релевантных чанков",
                    ),
                ),
            )
        }

        val bestScore = top.maxOf { sc ->
            scoreMap[sc.chunk.chunkId] ?: sc.cosine.toDouble()
        }
        val answerTh = config.answerRelevanceThreshold
        if (answerTh != null && bestScore < answerTh) {
            return RagRetrievalResult(
                contextText = "",
                attribution = RagAttribution(
                    used = false,
                    insufficientRelevance = true,
                    sources = emptyList(),
                    debug = debugBlock(
                        config, originalQuery, q, rewriteApplied,
                        recallK, finalK, candidatesAfterRecall, candidatesAfterThreshold, candidatesAfterRerank, min,
                        emptyReason = "Релевантность ниже порога для ответа (answerRelevanceThreshold)",
                        answerRelevanceThreshold = answerTh,
                        bestRetrievalScore = bestScore,
                    ),
                ),
            )
        }

        val sources = top.map { sc ->
            val fs = scoreMap[sc.chunk.chunkId]
            RagSourceRef(
                documentTitle = sc.chunk.documentTitle,
                sourceFileName = sc.chunk.sourceFileName,
                chunkIndex = sc.chunk.chunkIndex,
                score = sc.cosine.toDouble(),
                finalScore = if (config.pipelineMode == RagPipelineMode.Baseline ||
                    config.pipelineMode == RagPipelineMode.Threshold
                ) {
                    null
                } else {
                    fs
                },
                chunkId = sc.chunk.chunkId,
                chunkText = sc.chunk.text.trim(),
            )
        }

        val contextText = buildString {
            top.forEachIndexed { i, sc ->
                val ch = sc.chunk
                if (i > 0) appendLine()
                appendLine(
                    "--- Fragment ${i + 1} | chunk_id=${ch.chunkId} | chunk_index=${ch.chunkIndex} | " +
                        "${ch.documentTitle} / ${ch.sourceFileName} ---"
                )
                appendLine(ch.text.trim())
            }
        }

        return RagRetrievalResult(
            contextText = contextText.trim(),
            attribution = RagAttribution(
                used = true,
                sources = sources,
                debug = debugBlock(
                    config, originalQuery, q, rewriteApplied,
                    recallK, finalK, candidatesAfterRecall, candidatesAfterThreshold, candidatesAfterRerank, min,
                    emptyReason = null,
                ),
            ),
        )
    }

    private fun debugBlock(
        config: RagRetrievalConfig,
        originalQuery: String,
        retrievalQuery: String,
        rewriteApplied: Boolean,
        recallTopK: Int,
        finalTopK: Int,
        candidatesAfterRecall: Int,
        candidatesAfterThreshold: Int,
        candidatesAfterRerank: Int,
        minSimilarity: Float?,
        emptyReason: String?,
        answerRelevanceThreshold: Float? = null,
        bestRetrievalScore: Double? = null,
    ) = RagRetrievalDebug(
        pipelineMode = config.pipelineMode,
        originalQuery = originalQuery,
        retrievalQuery = retrievalQuery,
        rewriteApplied = rewriteApplied,
        recallTopK = recallTopK,
        finalTopK = finalTopK,
        candidatesAfterRecall = candidatesAfterRecall,
        candidatesAfterThreshold = candidatesAfterThreshold,
        candidatesAfterRerank = candidatesAfterRerank,
        minSimilarity = minSimilarity,
        hybridLexicalWeight = if (config.pipelineMode == RagPipelineMode.Hybrid) config.hybridLexicalWeight else null,
        llmRerankModel = if (config.pipelineMode == RagPipelineMode.LlmRerank) {
            config.llmRerankOllamaModel.trim().ifBlank { null }
        } else {
            null
        },
        emptyReason = emptyReason,
        answerRelevanceThreshold = answerRelevanceThreshold,
        bestRetrievalScore = bestRetrievalScore,
    )

}
