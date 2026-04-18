package com.example.ai_develop.data

import com.example.ai_develop.domain.rag.RagAttribution
import com.example.ai_develop.domain.rag.RagPipelineMode
import com.example.ai_develop.domain.rag.RagRetrievalConfig
import com.example.ai_develop.domain.rag.RagRetrievalDebug
import com.example.ai_develop.domain.rag.RagSourceRef

/**
 * Результат поиска: текст для системного промпта и атрибуция для UI.
 */
data class RagRetrievalResult(
    val contextText: String,
    val attribution: RagAttribution,
)

private data class RetrievalStageCounts(
    val recallTopK: Int,
    val finalTopK: Int,
    val candidatesAfterRecall: Int,
    val candidatesAfterThreshold: Int,
    val candidatesAfterRerank: Int,
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
            return unusedResult(config, originalQuery, q, rewriteApplied, "В базе нет проиндексированных чанков")
        }

        val scored = scoreChunksByEmbeddingModels(chunks, q) { model, query ->
            ollamaEmbeddingClient.embed(model, query)
        }
        if (scored.isEmpty()) {
            return unusedResult(
                config,
                originalQuery,
                q,
                rewriteApplied,
                "Не удалось получить эмбеддинг запроса (Ollama или размерность)",
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

        val ranked = applyPipelineMode(q, ordered, config, ollamaRerankClient)
        ordered = ranked.ordered
        val scoreMap = ranked.finalScoreByChunkId

        val candidatesAfterRerank = ordered.size
        val finalK = effectiveFinalTopK(config, ordered.size)
        val top = ordered.take(finalK)

        val counts = RetrievalStageCounts(
            recallTopK = recallK,
            finalTopK = finalK,
            candidatesAfterRecall = candidatesAfterRecall,
            candidatesAfterThreshold = candidatesAfterThreshold,
            candidatesAfterRerank = candidatesAfterRerank,
        )

        if (top.isEmpty()) {
            return unusedResultWithSources(
                config = config,
                originalQuery = originalQuery,
                retrievalQuery = q,
                rewriteApplied = rewriteApplied,
                counts = counts,
                minSimilarity = min,
                emptyReason = "После фильтрации не осталось релевантных чанков",
            )
        }

        val bestScore = top.maxOf { sc ->
            scoreMap[sc.chunk.chunkId] ?: sc.cosine.toDouble()
        }
        val answerTh = config.answerRelevanceThreshold
        if (answerTh != null && bestScore < answerTh) {
            return unusedResultWithSources(
                config = config,
                originalQuery = originalQuery,
                retrievalQuery = q,
                rewriteApplied = rewriteApplied,
                counts = counts,
                minSimilarity = min,
                emptyReason = "Релевантность ниже порога для ответа (answerRelevanceThreshold)",
                insufficientRelevance = true,
                answerRelevanceThreshold = answerTh,
                bestRetrievalScore = bestScore,
            )
        }

        val sources = buildRagSources(top, scoreMap, config.pipelineMode)
        val contextText = buildContextText(top)

        return RagRetrievalResult(
            contextText = contextText,
            attribution = RagAttribution(
                used = true,
                sources = sources,
                debug = debugBlock(
                    config = config,
                    originalQuery = originalQuery,
                    retrievalQuery = q,
                    rewriteApplied = rewriteApplied,
                    counts = counts,
                    minSimilarity = min,
                    emptyReason = null,
                ),
            ),
        )
    }

    private fun unusedResult(
        config: RagRetrievalConfig,
        originalQuery: String,
        retrievalQuery: String,
        rewriteApplied: Boolean,
        emptyReason: String,
    ) = RagRetrievalResult(
        contextText = "",
        attribution = RagAttribution(
            used = false,
            debug = RagRetrievalDebug(
                pipelineMode = config.pipelineMode,
                originalQuery = originalQuery,
                retrievalQuery = retrievalQuery,
                rewriteApplied = rewriteApplied,
                emptyReason = emptyReason,
            ),
        ),
    )

    private fun unusedResultWithSources(
        config: RagRetrievalConfig,
        originalQuery: String,
        retrievalQuery: String,
        rewriteApplied: Boolean,
        counts: RetrievalStageCounts,
        minSimilarity: Float?,
        emptyReason: String,
        insufficientRelevance: Boolean = false,
        answerRelevanceThreshold: Float? = null,
        bestRetrievalScore: Double? = null,
    ) = RagRetrievalResult(
        contextText = "",
        attribution = RagAttribution(
            used = false,
            insufficientRelevance = insufficientRelevance,
            sources = emptyList(),
            debug = debugBlock(
                config = config,
                originalQuery = originalQuery,
                retrievalQuery = retrievalQuery,
                rewriteApplied = rewriteApplied,
                counts = counts,
                minSimilarity = minSimilarity,
                emptyReason = emptyReason,
                answerRelevanceThreshold = answerRelevanceThreshold,
                bestRetrievalScore = bestRetrievalScore,
            ),
        ),
    )

    private fun buildRagSources(
        top: List<ScoredChunk>,
        finalScoreByChunkId: Map<String, Double>,
        pipelineMode: RagPipelineMode,
    ): List<RagSourceRef> {
        val baselineLike =
            pipelineMode == RagPipelineMode.Baseline || pipelineMode == RagPipelineMode.Threshold
        return top.map { sc ->
            val fs = finalScoreByChunkId[sc.chunk.chunkId]
            RagSourceRef(
                documentTitle = sc.chunk.documentTitle,
                sourceFileName = sc.chunk.sourceFileName,
                chunkIndex = sc.chunk.chunkIndex,
                score = sc.cosine.toDouble(),
                finalScore = if (baselineLike) null else fs,
                chunkId = sc.chunk.chunkId,
                chunkText = sc.chunk.text.trim(),
            )
        }
    }

    private fun buildContextText(top: List<ScoredChunk>): String =
        buildString {
            top.forEachIndexed { i, sc ->
                val ch = sc.chunk
                if (i > 0) appendLine()
                appendLine(
                    "--- Fragment ${i + 1} | chunk_id=${ch.chunkId} | chunk_index=${ch.chunkIndex} | " +
                        "${ch.documentTitle} / ${ch.sourceFileName} ---"
                )
                appendLine(ch.text.trim())
            }
        }.trim()

    private fun debugBlock(
        config: RagRetrievalConfig,
        originalQuery: String,
        retrievalQuery: String,
        rewriteApplied: Boolean,
        counts: RetrievalStageCounts,
        minSimilarity: Float?,
        emptyReason: String?,
        answerRelevanceThreshold: Float? = null,
        bestRetrievalScore: Double? = null,
    ) = RagRetrievalDebug(
        pipelineMode = config.pipelineMode,
        originalQuery = originalQuery,
        retrievalQuery = retrievalQuery,
        rewriteApplied = rewriteApplied,
        recallTopK = counts.recallTopK,
        finalTopK = counts.finalTopK,
        candidatesAfterRecall = counts.candidatesAfterRecall,
        candidatesAfterThreshold = counts.candidatesAfterThreshold,
        candidatesAfterRerank = counts.candidatesAfterRerank,
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
