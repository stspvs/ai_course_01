package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.McpToolBindingRecord
import com.example.ai_develop.data.inferPrimaryArgument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Базовые инструменты + динамические MCP-привязки из БД. Снимок обновляется через [reloadFromDatabase].
 * MCP на агента: только привязки из [Agent.mcpAllowedBindingIds].
 */
class AgentToolRegistry(
    private val baseTools: List<AgentTool>,
    private val mcpRepository: McpRepository,
    private val transport: McpTransport,
) {
    private val mutex = Mutex()
    /** Все включённые MCP-инструменты по id привязки (после [reloadFromDatabase]). */
    private var cachedMcpToolsByBindingId: Map<String, AgentTool> = emptyMap()

    /**
     * Инструменты для [agent]: базовые + MCP с id из [Agent.mcpAllowedBindingIds].
     * Пустой allowlist — только [baseTools].
     */
    fun toolsFor(agent: Agent): List<AgentTool> {
        if (agent.mcpAllowedBindingIds.isEmpty()) return baseTools.toList()
        val allowed = agent.mcpAllowedBindingIds.toSet()
        val ordered = agent.mcpAllowedBindingIds.mapNotNull { id -> cachedMcpToolsByBindingId[id] }
        return baseTools + ordered
    }

    /** Полный список имён MCP после синхронизации (для отладки/UI каталога). */
    fun currentMcpToolNames(): List<String> = cachedMcpToolsByBindingId.values.map { it.name }

    suspend fun reloadFromDatabase() {
        mutex.withLock {
            val serversById = mcpRepository.getAllServers().associateBy { it.id }
            val bindings = mcpRepository.getEnabledBindingsForRuntime()
            val exposedByBindingId = resolveExposedToolNames(bindings, serversById)
            cachedMcpToolsByBindingId = buildMap {
                for (binding in bindings) {
                    val server = serversById[binding.serverId] ?: continue
                    if (!server.enabled) continue
                    val exposedName = exposedByBindingId[binding.id] ?: binding.mcpToolName
                    val desc = binding.description.ifBlank {
                        "MCP tool ${binding.mcpToolName} on ${server.displayName}"
                    }
                    val schemaHint = when {
                        binding.inputSchemaJson.isBlank() || binding.inputSchemaJson == "{}" -> ""
                        binding.inputSchemaJson.length > 500 ->
                            binding.inputSchemaJson.take(500) + "…"
                        else -> binding.inputSchemaJson
                    }
                    val fullDescription = if (schemaHint.isNotEmpty()) {
                        "$desc\n\nParameters: $schemaHint"
                    } else {
                        desc
                    }
                    val primaryArgument = inferPrimaryArgument(binding.inputSchemaJson)
                    put(
                        binding.id,
                        DynamicMcpAgentTool(
                            name = exposedName,
                            description = fullDescription,
                            server = server,
                            mcpToolName = binding.mcpToolName,
                            primaryArgument = primaryArgument,
                            transport = transport,
                        )
                    )
                }
            }
        }
    }

    private fun resolveExposedToolNames(
        bindings: List<McpToolBindingRecord>,
        serversById: Map<String, McpServerRecord>,
    ): Map<String, String> {
        val byMcpName = bindings.groupBy { it.mcpToolName }
        val out = HashMap<String, String>()
        for ((mcpName, group) in byMcpName) {
            if (group.size == 1) {
                out[group.single().id] = mcpName
            } else {
                for (b in group) {
                    val s = serversById[b.serverId] ?: continue
                    val prefix = s.displayName
                        .replace(Regex("[^a-zA-Z0-9_а-яА-ЯёЁ]+"), "_")
                        .trim('_')
                        .take(32)
                        .ifBlank { b.serverId.take(8) }
                    out[b.id] = "${prefix}_${mcpName}"
                }
            }
        }
        return out
    }
}
