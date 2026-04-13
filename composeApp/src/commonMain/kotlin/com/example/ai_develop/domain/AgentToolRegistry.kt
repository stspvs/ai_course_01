package com.example.ai_develop.domain

import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.McpToolBindingRecord
import com.example.ai_develop.data.inferPrimaryArgument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Базовые инструменты + динамические MCP-привязки из БД. Снимок обновляется через [reloadFromDatabase].
 */
class AgentToolRegistry(
    private val baseTools: List<AgentTool>,
    private val mcpRepository: McpRepository,
    private val transport: McpTransport,
) {
    private val mutex = Mutex()
    private var cachedDynamic: List<AgentTool> = emptyList()

    fun currentTools(): List<AgentTool> = baseTools + cachedDynamic

    /** Полный список имён инструментов в промпте агента (базовые + MCP). */
    fun currentAllToolNames(): List<String> = currentTools().map { it.name }

    /** Имена MCP-привязок, попавших в рантайм ([reloadFromDatabase]). Без встроенных baseTools. */
    fun currentMcpToolNames(): List<String> = cachedDynamic.map { it.name }

    suspend fun reloadFromDatabase() {
        mutex.withLock {
            val serversById = mcpRepository.getAllServers().associateBy { it.id }
            val bindings = mcpRepository.getEnabledBindingsForRuntime()
            val exposedByBindingId = resolveExposedToolNames(bindings, serversById)
            cachedDynamic = bindings.mapNotNull { binding ->
                val server = serversById[binding.serverId] ?: return@mapNotNull null
                if (!server.enabled) return@mapNotNull null
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
                DynamicMcpAgentTool(
                    name = exposedName,
                    description = fullDescription,
                    server = server,
                    mcpToolName = binding.mcpToolName,
                    primaryArgument = primaryArgument,
                    transport = transport,
                )
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
