package com.example.ai_develop.data

import com.example.ai_develop.domain.RagRetrievalConfig

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
