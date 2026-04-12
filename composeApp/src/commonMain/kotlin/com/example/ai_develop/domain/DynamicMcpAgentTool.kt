package com.example.ai_develop.domain

import kotlinx.serialization.json.JsonPrimitive

class DynamicMcpAgentTool(
    override val name: String,
    override val description: String,
    private val baseUrl: String,
    private val headersJson: String,
    private val mcpToolName: String,
    private val inputArgumentKey: String,
    private val transport: McpTransport,
) : AgentTool {

    override val suppressLlmFollowUp: Boolean = true

    override suspend fun execute(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "Error: empty input for tool \"$name\"."
        val args = mapOf(inputArgumentKey to JsonPrimitive(trimmed))
        return transport.callTool(baseUrl, headersJson, mcpToolName, args).fold(
            onSuccess = { it },
            onFailure = { e -> "MCP tool failed (${mcpToolName}): ${e.message}" },
        )
    }
}
