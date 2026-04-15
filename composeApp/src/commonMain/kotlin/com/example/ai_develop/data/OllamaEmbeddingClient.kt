package com.example.ai_develop.data

import com.example.ai_develop.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class OllamaEmbeddingClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = BuildConfig.OLLAMA_BASE_URL,
) {

    suspend fun embed(model: String, text: String): FloatArray {
        val url = baseUrl.trimEnd('/') + "/api/embed"
        val response: OllamaEmbedResponse = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(OllamaEmbedRequest(model = model, input = text))
        }.body()

        val doubles = response.embedding
            ?: response.embeddings?.firstOrNull()
            ?: error("Ollama embed: empty embedding in response")

        val raw = FloatArray(doubles.size) { i -> doubles[i].toFloat() }
        return EmbeddingNormalization.l2Normalize(raw)
    }
}
