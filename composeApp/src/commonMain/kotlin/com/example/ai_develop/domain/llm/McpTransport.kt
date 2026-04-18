package com.example.ai_develop.domain.llm

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*

import com.example.ai_develop.data.McpServerRecord
import kotlinx.serialization.json.JsonElement

data class McpListToolsResult(
    val tools: List<McpToolInfo>,
)

data class McpToolInfo(
    val name: String,
    val description: String?,
    /** JSON Schema входа (объект из MCP), по умолчанию "{}". */
    val inputSchemaJson: String = "{}",
)

/**
 * Вызов MCP: Streamable HTTP или stdio (локальный процесс). Реализация — desktop.
 */
interface McpTransport {
    suspend fun listTools(server: McpServerRecord): Result<McpListToolsResult>

    suspend fun callTool(
        server: McpServerRecord,
        toolName: String,
        arguments: Map<String, JsonElement>,
    ): Result<String>

    /** Закрыть stdio-сессию и процесс для сервера (при смене настроек или удалении). */
    suspend fun disposeServer(serverId: String) {}
}
