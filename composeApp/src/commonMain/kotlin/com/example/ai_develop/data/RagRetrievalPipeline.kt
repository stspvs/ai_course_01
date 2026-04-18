package com.example.ai_develop.data

import com.example.ai_develop.domain.rag.RagPipelineMode
import com.example.ai_develop.domain.rag.RagRetrievalConfig

/**
 * Лексическое пересечение (Jaccard) по токенам; для скоринга в [0, 1].
 */
fun lexicalJaccard(query: String, text: String): Float {
    val a = tokenizeForOverlap(query)
    val b = tokenizeForOverlap(text)
    if (a.isEmpty() && b.isEmpty()) return 1f
    if (a.isEmpty() || b.isEmpty()) return 0f
    val inter = a.intersect(b).size.toFloat()
    val union = a.union(b).size.toFloat()
    return if (union <= 0f) 0f else inter / union
}

private val tokenSplitRegex = Regex("[\\s\\p{Punct}]+")

private fun tokenizeForOverlap(s: String): Set<String> =
    tokenSplitRegex.split(s.lowercase())
        .map { it.trim() }
        .filter { it.length > 1 }
        .toSet()

fun hybridCombinedScore(cosine: Float, lexical: Float, lexicalWeight: Float): Float {
    val w = lexicalWeight.coerceIn(0f, 1f)
    return w * cosine + (1f - w) * lexical
}

data class ScoredChunk(
    val chunk: RagIndexedChunk,
    val cosine: Float,
)

/**
 * Упорядоченные кандидаты после этапа rerank и итоговые скоры по [RagIndexedChunk.chunkId].
 */
data class RankedCandidates(
    val ordered: List<ScoredChunk>,
    val finalScoreByChunkId: Map<String, Double>,
)

/**
 * Глобальный косинусный скоринг: один эмбеддинг запроса на модель Ollama, dot product с чанками той же размерности.
 */
suspend fun scoreChunksByEmbeddingModels(
    chunks: List<RagIndexedChunk>,
    query: String,
    embed: suspend (model: String, query: String) -> FloatArray?,
): List<ScoredChunk> {
    val byModel = chunks.groupBy { it.ollamaModel.trim() }
    val scored = mutableListOf<ScoredChunk>()
    for ((model, list) in byModel) {
        val qVec = runCatching { embed(model, query) }.getOrNull() ?: continue
        for (ch in list) {
            if (ch.embedding.size != qVec.size) continue
            val s = dotProduct(qVec, ch.embedding)
            scored.add(ScoredChunk(ch, s))
        }
    }
    return scored
}

/**
 * Baseline/Threshold — косинус; Hybrid — лексика; LlmRerank — Ollama scores на префиксе списка.
 */
suspend fun applyPipelineMode(
    query: String,
    orderedAfterThreshold: List<ScoredChunk>,
    config: RagRetrievalConfig,
    rerankClient: OllamaRagRerankClient,
): RankedCandidates {
    when (config.pipelineMode) {
        RagPipelineMode.Baseline, RagPipelineMode.Threshold -> {
            val pairs = orderedAfterThreshold.map { it to it.cosine.toDouble() }
            return RankedCandidates(
                orderedAfterThreshold,
                pairs.associate { (sc, d) -> sc.chunk.chunkId to d },
            )
        }
        RagPipelineMode.Hybrid -> {
            val ordered = rerankHybrid(query, orderedAfterThreshold, config.hybridLexicalWeight)
            val pairs = ordered.map { sc ->
                val lex = lexicalJaccard(query, sc.chunk.text)
                val comb = hybridCombinedScore(sc.cosine, lex, config.hybridLexicalWeight).toDouble()
                sc to comb
            }
            return RankedCandidates(ordered, pairs.associate { (sc, d) -> sc.chunk.chunkId to d })
        }
        RagPipelineMode.LlmRerank -> {
            var ordered = orderedAfterThreshold
            val model = config.llmRerankOllamaModel.trim().ifBlank { null }
            val limit = config.llmRerankMaxCandidates.coerceAtLeast(1)
            val slice = ordered.take(limit)
            val pairs = if (model != null) {
                val scoredList = slice.map { sc ->
                    val llm = rerankClient.scoreRelevance(model, query, sc.chunk.text)
                    val final = (llm ?: sc.cosine).toDouble()
                    sc to final
                }.sortedByDescending { it.second }
                val rerankedHead = scoredList.map { it.first }
                val tail = ordered.drop(limit)
                ordered = rerankedHead + tail
                scoredList + tail.map { it to it.cosine.toDouble() }
            } else {
                ordered.map { it to it.cosine.toDouble() }
            }
            return RankedCandidates(ordered, pairs.associate { (sc, d) -> sc.chunk.chunkId to d })
        }
    }
}

/**
 * После recall: опциональный порог по косинусу.
 */
fun applyCosineThreshold(
    candidates: List<ScoredChunk>,
    minSimilarity: Float?,
): List<ScoredChunk> {
    val t = minSimilarity ?: return candidates
    return candidates.filter { it.cosine >= t }
}

/**
 * Hybrid rerank: пересортировать по combined(cosine, lexical).
 */
fun rerankHybrid(
    query: String,
    candidates: List<ScoredChunk>,
    lexicalWeight: Float,
): List<ScoredChunk> {
    return candidates
        .sortedByDescending { ch ->
            val lex = lexicalJaccard(query, ch.chunk.text)
            hybridCombinedScore(ch.cosine, lex, lexicalWeight)
        }
}

/** Жёсткий верхний предел чанков в одном запросе (контекст модели и время). */
const val RAG_MAX_CHUNKS_IN_CONTEXT = 500

/**
 * @param scoredSize число чанков после глобального скоринга (до recall).
 */
fun effectiveRecallTopK(config: RagRetrievalConfig, scoredSize: Int): Int {
    if (scoredSize <= 0) return 1
    val cap = minOf(scoredSize, RAG_MAX_CHUNKS_IN_CONTEXT)
    if (config.scanAllChunks) {
        return cap.coerceAtLeast(1)
    }
    return minOf(config.recallTopK.coerceAtLeast(1), cap)
}

/**
 * @param orderedSize длина списка кандидатов после recall, порога и rerank (перед take final).
 */
fun effectiveFinalTopK(config: RagRetrievalConfig, orderedSize: Int): Int {
    if (orderedSize <= 0) return 1
    val cap = minOf(orderedSize, RAG_MAX_CHUNKS_IN_CONTEXT)
    if (config.scanAllChunks) {
        return cap.coerceAtLeast(1)
    }
    return minOf(config.finalTopK.coerceAtLeast(1), cap)
}
