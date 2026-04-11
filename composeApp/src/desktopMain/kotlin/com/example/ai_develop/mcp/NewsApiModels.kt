package com.example.ai_develop.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class NewsApiEverythingResponse(
    val status: String? = null,
    @SerialName("totalResults") val totalResults: Int? = null,
    val articles: List<NewsApiArticle> = emptyList(),
    val message: String? = null,
    val code: String? = null,
)

@Serializable
internal data class NewsApiArticle(
    val title: String? = null,
    val url: String? = null,
    val source: NewsApiSource? = null,
)

@Serializable
internal data class NewsApiSource(
    val name: String? = null,
)
