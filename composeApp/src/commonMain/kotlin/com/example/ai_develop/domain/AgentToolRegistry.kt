package com.example.ai_develop.domain

import com.example.ai_develop.data.McpDiscoveredTool
import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.McpServerRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Базовые инструменты + динамические MCP-привязки из БД. Снимок обновляется через [reloadFromDatabase].
 */
class AgentToolRegistry(
    private val baseTools: List<AgentTool>,
    private val mcpRepository: McpRepository,
    private val transport: McpTransport,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val mutex = Mutex()
    private var cachedDynamic: List<AgentTool> = emptyList()

    fun currentTools(): List<AgentTool> = baseTools + cachedDynamic

    suspend fun reloadFromDatabase() {
        mutex.withLock {
            val serversById = mcpRepository.getAllServers().associateBy { it.id }
            val bindings = mcpRepository.getEnabledBindingsForRuntime()
            cachedDynamic = bindings.mapNotNull { binding ->
                val server = serversById[binding.serverId] ?: return@mapNotNull null
                if (!server.enabled) return@mapNotNull null
                val description = binding.descriptionOverride.ifBlank {
                    descriptionFromSyncedTools(server, binding.mcpToolName)
                }.ifBlank { "MCP tool ${binding.mcpToolName} on ${server.displayName}" }
                DynamicMcpAgentTool(
                    name = binding.agentToolName,
                    description = description,
                    baseUrl = server.baseUrl,
                    headersJson = server.headersJson,
                    mcpToolName = binding.mcpToolName,
                    inputArgumentKey = binding.inputArgumentKey.ifBlank { "query" },
                    transport = transport,
                )
            }
        }
    }

    private fun descriptionFromSyncedTools(server: McpServerRecord, mcpToolName: String): String {
        if (server.lastSyncToolsJson.isBlank()) return ""
        return try {
            val list = json.decodeFromString(
                ListSerializer(McpDiscoveredTool.serializer()),
                server.lastSyncToolsJson,
            )
            list.find { it.name == mcpToolName }?.description.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
