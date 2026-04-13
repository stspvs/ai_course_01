package com.example.ai_develop.data

import kotlinx.serialization.Serializable

enum class McpDeploymentKind {
    LOCAL,
    REMOTE,
    ;

    companion object {
        fun fromStored(value: String): McpDeploymentKind =
            entries.find { it.name == value } ?: REMOTE
    }
}

/** Транспорт MCP: HTTP (Streamable) или локальный процесс по stdin/stdout. */
enum class McpWireKind {
    STREAMABLE_HTTP,
    STDIO,
    ;

    companion object {
        fun fromStored(value: String): McpWireKind =
            entries.find { it.name == value } ?: STREAMABLE_HTTP
    }
}

@Serializable
data class McpServerRecord(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val headersJson: String = "{}",
    val lastSyncToolsJson: String = "",
    val lastSyncError: String? = null,
    val lastSyncAt: Long = 0L,
    val deploymentKind: McpDeploymentKind = McpDeploymentKind.REMOTE,
    /** Команда запуска процесса MCP на машине (docker, npx, …); для каждого сервера отдельно. */
    val startCommand: String = "",
    /** Состояние после последней проверки подключения (listTools). */
    val linkStatus: McpServerLinkStatus = McpServerLinkStatus.UNKNOWN,
    /** [McpWireKind.STDIO]: подключение через дочерний процесс и [startCommand] (например gradlew.bat …). */
    val wireKind: McpWireKind = McpWireKind.STREAMABLE_HTTP,
)

@Serializable
data class McpDiscoveredTool(
    val name: String,
    val description: String = "",
    val inputSchemaJson: String = "{}",
)

@Serializable
data class McpToolBindingRecord(
    val id: String,
    val serverId: String,
    val mcpToolName: String,
    val description: String = "",
    val inputSchemaJson: String = "{}",
    val enabled: Boolean = false,
)

/** Стабильный id строки инструмента в БД для пары (serverId, mcpToolName). */
fun stableMcpToolBindingId(serverId: String, mcpToolName: String): String =
    "mcpbind_" + serverId + "_" + mcpToolName.replace(":", "_").replace("/", "_")
