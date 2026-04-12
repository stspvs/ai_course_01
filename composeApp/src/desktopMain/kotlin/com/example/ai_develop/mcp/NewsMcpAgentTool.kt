package com.example.ai_develop.mcp

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.domain.AgentTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Agent tool that calls the local MCP [news_search] tool over Streamable HTTP.
 */
class NewsMcpAgentTool(
    private val mcpBaseUrl: String = "http://127.0.0.1:${BuildConfig.NEWS_MCP_PORT}/mcp",
) : AgentTool {

    private val log = LoggerFactory.getLogger(NewsMcpAgentTool::class.java)

    override val suppressLlmFollowUp: Boolean = true

    override val name: String = "news_search"

    override val description: String =
        "Search recent news via NewsAPI (MCP). Input: search keywords in any language (e.g. USA news, новости)."

    override suspend fun execute(input: String): String {
        val query = input.trim()
        if (query.isEmpty()) {
            log.warn("news_search: empty query")
            return "Error: empty search query for news_search."
        }

        log.info("news_search MCP call: url={}, qChars={}, qUtf8={}", mcpBaseUrl, query.length, queryForAsciiLog(query))
        val httpClient = HttpClient(CIO) {
            install(SSE)
        }
        return try {
            val mcp = httpClient.mcpStreamableHttp(url = mcpBaseUrl)
            try {
                val result = mcp.callTool(
                    name = "news_search",
                    arguments = mapOf("query" to JsonPrimitive(query)),
                )
                val text = formatToolResult(result)
                log.info(
                    "news_search MCP ok: responseChars={}, isError={}",
                    text.length,
                    result.isError,
                )
                text
            } finally {
                mcp.close()
            }
        } catch (e: Exception) {
            log.error(
                "news_search MCP failed (server port {}): {}",
                BuildConfig.NEWS_MCP_PORT,
                e.message,
                e,
            )
            "MCP news_search failed (is the local MCP server running on ${BuildConfig.NEWS_MCP_PORT}?): ${e.message}"
        } finally {
            httpClient.close()
        }
    }
}


private fun formatToolResult(result: CallToolResult): String {
    return result.content.joinToString("\n") { part ->
        when (part) {
            is TextContent -> part.text
            else -> part.toString()
        }
    }
}
