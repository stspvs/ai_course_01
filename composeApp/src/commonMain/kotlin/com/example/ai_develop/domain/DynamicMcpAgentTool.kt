package com.example.ai_develop.domain

import com.example.ai_develop.data.McpPrimaryArgumentKind
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.buildMcpPrimaryArgumentMap
import com.example.ai_develop.data.tryParseFullMcpToolArgumentsObject

class DynamicMcpAgentTool(
    override val name: String,
    override val description: String,
    private val server: McpServerRecord,
    private val mcpToolName: String,
    private val primaryArgument: McpPrimaryArgumentKind,
    private val transport: McpTransport,
) : AgentTool {

    /**
     * Должен быть false, иначе после первого MCP-вызова [AutonomousAgent] не делает следующий ход LLM
     * и многошаговые сценарии (несколько инструментов подряд в одном пользовательском сообщении) невозможны.
     */
    override val suppressLlmFollowUp: Boolean = false

    override suspend fun execute(input: String): String {
        val fullObject = tryParseFullMcpToolArgumentsObject(input)
        val args = if (fullObject != null) {
            fullObject
        } else {
            buildMcpPrimaryArgumentMap(primaryArgument, input).getOrElse { e ->
                return e.message ?: "Error: ${e::class.simpleName}"
            }
        }
        return transport.callTool(server, mcpToolName, args).fold(
            onSuccess = { it },
            onFailure = { e -> "MCP tool failed (${mcpToolName}): ${e.message}" },
        )
    }
}
