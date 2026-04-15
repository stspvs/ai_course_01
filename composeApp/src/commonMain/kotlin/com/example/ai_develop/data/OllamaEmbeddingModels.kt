package com.example.ai_develop.data

import kotlinx.serialization.Serializable

@Serializable
internal data class OllamaEmbedRequest(
    val model: String,
    val input: String,
)

/**
 * Ollama /api/embed may return [embeddings] (batch) or legacy [embedding].
 */
@Serializable
internal data class OllamaEmbedResponse(
    val embeddings: List<List<Double>>? = null,
    val embedding: List<Double>? = null,
)
