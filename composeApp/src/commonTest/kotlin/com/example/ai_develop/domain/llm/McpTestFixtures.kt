package com.example.ai_develop.domain.llm

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*

import com.example.ai_develop.data.McpDiscoveredTool
import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.McpServerLinkStatus
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.McpToolBindingRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

internal class EmptyMcpRepository : McpRepository {
    override fun observeServers(): Flow<List<McpServerRecord>> = flowOf(emptyList())

    override fun observeMcpRegistryChanges(): Flow<Unit> = merge(
        flowOf(Unit),
        emptyFlow(),
    )
    override suspend fun getAllServers(): List<McpServerRecord> = emptyList()
    override suspend fun getServer(id: String): McpServerRecord? = null
    override suspend fun upsertServer(record: McpServerRecord) {}
    override suspend fun deleteServer(id: String) {}
    override suspend fun getBindingsForServer(serverId: String): List<McpToolBindingRecord> = emptyList()
    override suspend fun upsertBinding(record: McpToolBindingRecord) {}
    override suspend fun deleteBinding(id: String) {}
    override suspend fun getEnabledBindingsForRuntime(): List<McpToolBindingRecord> = emptyList()
    override suspend fun getAllBindings(): List<McpToolBindingRecord> = emptyList()
    override suspend fun updateServerSyncState(
        serverId: String,
        toolsJson: String,
        error: String?,
        syncAt: Long,
        linkStatus: McpServerLinkStatus,
    ) {
    }

    override suspend fun replaceToolsFromSync(serverId: String, tools: List<McpDiscoveredTool>) {
    }
}

internal class NoopMcpTransport : McpTransport {
    override suspend fun listTools(server: McpServerRecord): Result<McpListToolsResult> =
        Result.failure(UnsupportedOperationException())

    override suspend fun callTool(
        server: McpServerRecord,
        toolName: String,
        arguments: Map<String, kotlinx.serialization.json.JsonElement>,
    ): Result<String> {
        return Result.failure(UnsupportedOperationException())
    }
}

internal fun testAgentToolRegistry(
    baseTools: List<AgentTool> = emptyList(),
): AgentToolRegistry = AgentToolRegistry(
    baseTools = baseTools,
    mcpRepository = EmptyMcpRepository(),
    transport = NoopMcpTransport(),
)
