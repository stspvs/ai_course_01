package com.example.ai_develop.domain

import kotlinx.serialization.json.JsonElement

data class McpListToolsResult(
    val tools: List<McpToolInfo>,
)

data class McpToolInfo(
    val name: String,
    val description: String?,
)

/**
 * Вызов MCP Streamable HTTP (listTools / callTool). Реализация — desktop; на остальных платформах — заглушка.
 */
interface McpTransport {
    suspend fun listTools(baseUrl: String, headersJson: String): Result<McpListToolsResult>

    suspend fun callTool(
        baseUrl: String,
        headersJson: String,
        toolName: String,
        arguments: Map<String, JsonElement>,
    ): Result<String>
}
