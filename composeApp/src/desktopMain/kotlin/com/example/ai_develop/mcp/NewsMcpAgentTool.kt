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

/**
 * Agent tool that calls the local MCP [news_search] tool over Streamable HTTP.
 */
class NewsMcpAgentTool(
    private val mcpBaseUrl: String = "http://127.0.0.1:${BuildConfig.NEWS_MCP_PORT}/mcp",
) : AgentTool {

    override val suppressLlmFollowUp: Boolean = true

    override val name: String = "news_search"

    override val description: String =
        "Search recent news headlines via NewsAPI (MCP). Input: search keywords in English, e.g. artificial intelligence."

    override suspend fun execute(input: String): String {
        val query = input.trim()
        if (query.isEmpty()) return "Error: empty search query for news_search."

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
                formatToolResult(result)
            } finally {
                mcp.close()
            }
        } catch (e: Exception) {
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
