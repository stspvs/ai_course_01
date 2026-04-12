package com.example.ai_develop.mcp

import com.example.ai_develop.domain.McpListToolsResult
import com.example.ai_develop.domain.McpToolInfo
import com.example.ai_develop.domain.McpTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopMcpTransport : McpTransport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun listTools(baseUrl: String, headersJson: String): Result<McpListToolsResult> {
        val client = createClient(headersJson)
        return try {
            val mcp = client.mcpStreamableHttp(url = baseUrl)
            try {
                val r = mcp.listTools()
                Result.success(
                    McpListToolsResult(
                        tools = r.tools.map { t ->
                            McpToolInfo(name = t.name, description = t.description)
                        },
                    ),
                )
            } finally {
                mcp.close()
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    override suspend fun callTool(
        baseUrl: String,
        headersJson: String,
        toolName: String,
        arguments: Map<String, JsonElement>,
    ): Result<String> {
        val client = createClient(headersJson)
        return try {
            val mcp = client.mcpStreamableHttp(url = baseUrl)
            try {
                val result = mcp.callTool(name = toolName, arguments = arguments)
                Result.success(formatToolResult(result))
            } finally {
                mcp.close()
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    private fun createClient(headersJson: String): HttpClient {
        val headerMap = parseHeaders(headersJson)
        return HttpClient(CIO) {
            install(SSE)
            defaultRequest {
                headerMap.forEach { (k, v) -> headers.append(k, v) }
            }
        }
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        if (headersJson.isBlank() || headersJson == "{}") return emptyMap()
        return try {
            val obj = json.decodeFromString(JsonObject.serializer(), headersJson)
            obj.mapValues { (_, v) ->
                when (v) {
                    is JsonObject -> v.toString()
                    else -> v.jsonPrimitive.content
                }
            }
        } catch (_: Exception) {
            emptyMap()
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
