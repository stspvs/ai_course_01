package com.example.ai_develop.data

import com.example.ai_develop.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@Serializable
private data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
)

@Serializable
private data class OllamaGenerateResponse(
    val response: String? = null,
)

/**
 * Оценка релевантности чанка запросу через Ollama /api/generate (одно число 0–10 в ответе).
 */
class OllamaRagRerankClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val baseUrl: String = BuildConfig.OLLAMA_BASE_URL,
) {

    suspend fun scoreRelevance(model: String, query: String, chunkText: String): Float? {
        val prompt = buildString {
            appendLine("Rate how relevant the passage is to the user query. Reply with ONE number from 0 to 10 only, no other text.")
            appendLine("Query: ${query.take(2000)}")
            appendLine("Passage: ${chunkText.take(3000)}")
        }
        val m = model.trim()
        if (m.isEmpty()) return null
        val url = baseUrl.trimEnd('/') + "/api/generate"
        val body = json.encodeToString(
            OllamaGenerateRequest(
                model = m,
                prompt = prompt,
                stream = false,
            ),
        )
        val responseText: String = runCatching {
            httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body<String>()
        }.getOrNull() ?: return null

        val parsed = runCatching { json.decodeFromString<OllamaGenerateResponse>(responseText) }.getOrNull()
        val raw = parsed?.response?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val num = extractFirstNumber(raw) ?: return null
        return (num / 10f).coerceIn(0f, 1f)
    }

    private fun extractFirstNumber(s: String): Float? {
        val m = Regex("-?\\d+(\\.\\d+)?").find(s) ?: return null
        return m.value.toFloatOrNull()
    }
}
