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
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val newsSearchLog = LoggerFactory.getLogger("com.example.ai_develop.mcp.news_search")

/** Лог в ASCII (UTF-8 percent-encoding), чтобы кириллица не превращалась в «���» в консоли Windows. */
internal fun queryForAsciiLog(query: String, maxLen: Int = 220): String =
    URLEncoder.encode(query, StandardCharsets.UTF_8).let { if (it.length > maxLen) it.take(maxLen) + "…" else it }

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
                            put(
                                "description",
                                "Keywords (any language). Do not force English only; results are not limited to one language.",
                            )
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
        newsSearchLog.warn("NewsAPI: key missing (set NEWSAPI_KEY in local.properties)")
        return CallToolResult(
            content = listOf(
                TextContent("NEWSAPI_KEY is not configured. Add NEWSAPI_KEY to local.properties."),
            ),
            isError = true,
        )
    }

    val args = request.params.arguments ?: JsonObject(emptyMap())
    val (query, pageSize) = resolveNewsSearchArguments(args)

    if (query.isEmpty()) {
        newsSearchLog.warn("NewsAPI: news_search called without query argument")
        return CallToolResult(
            content = listOf(TextContent("Missing required argument: query")),
            isError = true,
        )
    }

    val qForApi = effectiveNewsApiQuery(query)
    newsSearchLog.info(
        "NewsAPI request: pageSize={}, qChars={}, qUtf8={}",
        pageSize,
        query.length,
        queryForAsciiLog(query),
    )
    if (qForApi != query) {
        newsSearchLog.info("NewsAPI query expanded for API: qUtf8={}", queryForAsciiLog(qForApi))
    }
    return try {
        val response = httpClient.get("https://newsapi.org/v2/everything") {
            parameter("q", qForApi)
            parameter("pageSize", pageSize)
            parameter("sortBy", "publishedAt")
            parameter("apiKey", newsApiKey)
        }
        val bodyText = response.body<String>()
        val parsed = json.decodeFromString<NewsApiEverythingResponse>(bodyText)

        if (parsed.status == "error" || parsed.articles.isEmpty()) {
            val msg = parsed.message ?: "No articles or error from NewsAPI (status=${parsed.status})."
            newsSearchLog.warn("NewsAPI: status={}, message={}, articles={}", parsed.status, parsed.message, parsed.articles.size)
            return CallToolResult(
                content = listOf(TextContent(msg)),
                isError = true,
            )
        }

        newsSearchLog.info(
            "NewsAPI ok: articlesReturned={}, totalResults={}",
            parsed.articles.size,
            parsed.totalResults,
        )

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
        newsSearchLog.error("NewsAPI request failed: {}", e.message, e)
        CallToolResult(
            content = listOf(TextContent("NewsAPI request failed: ${e.message}")),
            isError = true,
        )
    }
}

/**
 * NewsAPI лучше находит материалы, если к русским формулировкам про страны добавить латинские имена.
 */
private fun effectiveNewsApiQuery(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return t
    val lower = t.lowercase()
    val additions = mutableListOf<String>()
    if (("сша" in lower || "америк" in lower) && "usa" !in lower && "united states" !in lower) {
        additions.add("USA")
        additions.add("United States")
    }
    if (("росси" in lower || lower == "рф") && "russia" !in lower) additions.add("Russia")
    if ("украин" in lower && "ukraine" !in lower) additions.add("Ukraine")
    if ("япони" in lower && "japan" !in lower) additions.add("Japan")
    if (("франц" in lower || "париж" in lower) && "france" !in lower) {
        additions.add("France")
        if ("париж" in lower && "paris" !in lower) additions.add("Paris")
    }
    return if (additions.isEmpty()) t else "$t OR ${additions.distinct().joinToString(" OR ")}"
}

/**
 * Собирает `query` и `pageSize` из JSON и/или из строки вида
 * `query=world news, pageSize=5`, которую модель иногда кладёт целиком в поле `query`.
 */
internal fun resolveNewsSearchArguments(args: JsonObject): Pair<String, Int> {
    var raw = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
    var pageSize = args["pageSize"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: 5

    if (raw.isEmpty()) return "" to pageSize

    val parsed = parseKeyValueStyleToolInput(raw)
    if (parsed.query != null) {
        raw = parsed.query
    } else if (parsed.pageSize != null &&
        Regex("^pageSize\\s*=\\s*\\d+$", RegexOption.IGNORE_CASE).matches(raw.trim())
    ) {
        raw = ""
    }
    if (parsed.pageSize != null) pageSize = parsed.pageSize

    return raw.trim() to pageSize
}

/**
 * Разбор `key=value` через запятую (например из одного аргумента инструмента).
 * Если строка без `=`, возвращает пустой разбор — тогда используется исходный текст как поисковый запрос.
 */
internal fun parseKeyValueStyleToolInput(s: String): ParsedNewsKwargs {
    if (!s.contains('=')) return ParsedNewsKwargs(null, null)

    var q: String? = null
    var ps: Int? = null

    val chunks = s.split(',')
    for (chunk in chunks) {
        val t = chunk.trim()
        val eq = t.indexOf('=')
        if (eq <= 0) continue
        val key = t.substring(0, eq).trim().lowercase()
        val value = t.substring(eq + 1).trim()
        when (key) {
            "query" -> q = value
            "pagesize" -> value.toIntOrNull()?.let { ps = it.coerceIn(1, 10) }
        }
    }

    return ParsedNewsKwargs(q, ps)
}

internal data class ParsedNewsKwargs(val query: String?, val pageSize: Int?)
