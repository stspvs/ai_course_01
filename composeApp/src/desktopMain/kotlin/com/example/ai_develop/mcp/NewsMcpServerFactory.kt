package com.example.ai_develop.mcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun createNewsMcpServer(
    newsApiKey: String,
    httpClient: HttpClient,
): Server {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    return Server(
        Implementation(
            name = "ai-develop-news-mcp",
            version = "1.0.0",
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    ).apply {
        addTool(
            name = "news_search",
            description = "Search recent news via NewsAPI.org (everything). Arguments: query (string, required), pageSize (integer, optional, 1-10).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Search keywords; English queries work best.")
                        },
                    )
                    put(
                        "pageSize",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Number of articles to return (1-10).")
                        },
                    )
                },
                required = listOf("query"),
            ),
        ) { request: CallToolRequest ->
            handleNewsSearch(newsApiKey, httpClient, json, request)
        }
    }
}

private suspend fun handleNewsSearch(
    newsApiKey: String,
    httpClient: HttpClient,
    json: Json,
    request: CallToolRequest,
): CallToolResult {
    if (newsApiKey.isBlank()) {
        return CallToolResult(
            content = listOf(
                TextContent("NEWSAPI_KEY is not configured. Add NEWSAPI_KEY to local.properties."),
            ),
            isError = true,
        )
    }

    val args = request.params.arguments ?: JsonObject(emptyMap())
    val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
    val pageSize = args["pageSize"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: 5

    if (query.isEmpty()) {
        return CallToolResult(
            content = listOf(TextContent("Missing required argument: query")),
            isError = true,
        )
    }

    return try {
        val response = httpClient.get("https://newsapi.org/v2/everything") {
            parameter("q", query)
            parameter("pageSize", pageSize)
            parameter("sortBy", "publishedAt")
            parameter("language", "en")
            parameter("apiKey", newsApiKey)
        }
        val bodyText = response.body<String>()
        val parsed = json.decodeFromString<NewsApiEverythingResponse>(bodyText)

        if (parsed.status == "error" || parsed.articles.isEmpty()) {
            val msg = parsed.message ?: "No articles or error from NewsAPI (status=${parsed.status})."
            return CallToolResult(
                content = listOf(TextContent(msg)),
                isError = true,
            )
        }

        val lines = parsed.articles.mapIndexed { i, a ->
            val title = a.title?.trim().orEmpty().ifEmpty { "(no title)" }
            val src = a.source?.name?.trim().orEmpty().ifEmpty { "unknown source" }
            val url = a.url?.trim().orEmpty()
            "${i + 1}. $title — $src\n   $url"
        }
        val text = buildString {
            appendLine("Found ${parsed.articles.size} article(s) (totalResults=${parsed.totalResults ?: "?"}).")
            appendLine()
            append(lines.joinToString("\n\n"))
        }
        CallToolResult(content = listOf(TextContent(text)))
    } catch (e: Exception) {
        CallToolResult(
            content = listOf(TextContent("NewsAPI request failed: ${e.message}")),
            isError = true,
        )
    }
}
