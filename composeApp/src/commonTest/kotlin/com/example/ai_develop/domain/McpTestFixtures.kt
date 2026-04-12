package com.example.ai_develop.domain

import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.McpToolBindingRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class EmptyMcpRepository : McpRepository {
    override fun observeServers(): Flow<List<McpServerRecord>> = flowOf(emptyList())
    override suspend fun getAllServers(): List<McpServerRecord> = emptyList()
    override suspend fun getServer(id: String): McpServerRecord? = null
    override suspend fun upsertServer(record: McpServerRecord) {}
    override suspend fun deleteServer(id: String) {}
    override suspend fun getBindingsForServer(serverId: String): List<McpToolBindingRecord> = emptyList()
    override suspend fun upsertBinding(record: McpToolBindingRecord) {}
    override suspend fun deleteBinding(id: String) {}
    override suspend fun getEnabledBindingsForRuntime(): List<McpToolBindingRecord> = emptyList()
    override suspend fun updateServerSyncState(
        serverId: String,
        toolsJson: String,
        error: String?,
        syncAt: Long,
    ) {
    }
}

internal class NoopMcpTransport : McpTransport {
    override suspend fun listTools(baseUrl: String, headersJson: String): Result<McpListToolsResult> =
        Result.failure(UnsupportedOperationException())

    override suspend fun callTool(
        baseUrl: String,
        headersJson: String,
        toolName: String,
        arguments: Map<String, kotlinx.serialization.json.JsonElement>,
    ): Result<String> {
        return Result.failure(UnsupportedOperationException())
    }
}

internal fun testAgentToolRegistry(
    baseTools: List<AgentTool> = listOf(CalculatorTool(), WeatherTool()),
): AgentToolRegistry = AgentToolRegistry(
    baseTools = baseTools,
    mcpRepository = EmptyMcpRepository(),
    transport = NoopMcpTransport(),
)
