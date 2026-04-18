package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

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
     * `true`: ответ пользователю — текст из MCP; дополнительный вызов LLM после инструментов не делается.
     * Несколько MCP в одном ответе модели — см. [AgentEngine.parseAllToolCalls] и пакетный путь в [AutonomousAgent].
     */
    override val suppressLlmFollowUp: Boolean = true

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
