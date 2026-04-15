package com.example.ai_develop.data

import com.example.ai_develop.domain.RagAttribution
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
) {

    /**
     * Подобрать top-K чанков по косинусной близости к запросу.
     * При пустом запросе, отсутствии чанков или недоступности Ollama — `null` (чат без RAG).
     */
    suspend fun retrieve(userQuery: String): RagRetrievalResult? {
        val q = userQuery.trim()
        if (q.isEmpty()) return null

        val chunks = ragEmbeddingRepository.loadAllChunksForRetrieval()
        if (chunks.isEmpty()) return null

        val byModel = chunks.groupBy { it.ollamaModel }
        val scored = mutableListOf<Pair<RagIndexedChunk, Float>>()
        for ((model, list) in byModel) {
            val qVec = runCatching { ollamaEmbeddingClient.embed(model, q) }.getOrNull() ?: continue
            for (ch in list) {
                if (ch.embedding.size != qVec.size) continue
                val s = dotProduct(qVec, ch.embedding)
                scored.add(ch to s)
            }
        }
        if (scored.isEmpty()) return null

        val top = scored
            .sortedByDescending { it.second }
            .distinctBy { it.first.chunkId }
            .take(TOP_K)

        val sources = top.map { (ch, sc) ->
            RagSourceRef(
                documentTitle = ch.documentTitle,
                sourceFileName = ch.sourceFileName,
                chunkIndex = ch.chunkIndex,
                score = sc.toDouble(),
                chunkId = ch.chunkId,
                chunkText = ch.text.trim(),
            )
        }
        val contextText = buildString {
            top.forEachIndexed { i, (ch, _) ->
                if (i > 0) appendLine()
                appendLine("--- Fragment ${i + 1} (${ch.documentTitle} / ${ch.sourceFileName}, chunk ${ch.chunkIndex}) ---")
                appendLine(ch.text.trim())
            }
        }
        return RagRetrievalResult(
            contextText = contextText.trim(),
            attribution = RagAttribution(used = true, sources = sources),
        )
    }

    private companion object {
        private const val TOP_K = 5
    }
}
