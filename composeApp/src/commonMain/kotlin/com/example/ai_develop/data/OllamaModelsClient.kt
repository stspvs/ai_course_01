package com.example.ai_develop.data

import com.example.ai_develop.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OllamaTagsResponse(
    val models: List<OllamaTagModel> = emptyList(),
)

@Serializable
private data class OllamaTagModel(
    val name: String,
    val model: String? = null,
)

class OllamaModelsClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val baseUrl: String = BuildConfig.OLLAMA_BASE_URL,
) {

    suspend fun listModelNames(): Result<List<String>> = runCatching {
        val url = baseUrl.trimEnd('/') + "/api/tags"
        val text: String = httpClient.get(url).body()
        val parsed = json.decodeFromString<OllamaTagsResponse>(text)
        parsed.models.mapNotNull { m ->
            val n = m.name.trim().ifBlank { m.model?.trim().orEmpty() }
            n.takeIf { it.isNotEmpty() }
        }.distinct()
    }
}
