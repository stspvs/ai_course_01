package com.example.ai_develop.data

import kotlinx.serialization.Serializable

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
)

@Serializable
data class McpDiscoveredTool(
    val name: String,
    val description: String = "",
)

@Serializable
data class McpToolBindingRecord(
    val id: String,
    val serverId: String,
    val mcpToolName: String,
    val agentToolName: String,
    val descriptionOverride: String = "",
    val inputArgumentKey: String = "query",
    val enabled: Boolean = false,
)
