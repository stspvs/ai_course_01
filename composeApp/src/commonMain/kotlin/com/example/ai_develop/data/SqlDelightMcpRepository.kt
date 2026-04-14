package com.example.ai_develop.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.ai_develop.database.AgentDatabase
import com.example.aidevelop.database.McpServerEntity
import com.example.aidevelop.database.McpToolBindingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

class SqlDelightMcpRepository(
    private val db: AgentDatabase,
) : McpRepository {

    private val queries = db.agentDatabaseQueries

    override fun observeServers(): Flow<List<McpServerRecord>> {
        return queries.getAllMcpServers()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toRecord() } }
    }

    override fun observeMcpRegistryChanges(): Flow<Unit> = merge(
        flowOf(Unit),
        queries.getAllMcpServers()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { },
        queries.getAllMcpBindings()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { },
    )

    override suspend fun getAllServers(): List<McpServerRecord> = withContext(Dispatchers.Default) {
        queries.getAllMcpServers().executeAsList().map { it.toRecord() }
    }

    override suspend fun getServer(id: String): McpServerRecord? = withContext(Dispatchers.Default) {
        queries.getMcpServer(id).executeAsOneOrNull()?.toRecord()
    }

    override suspend fun upsertServer(record: McpServerRecord) = withContext(Dispatchers.Default) {
        queries.insertMcpServer(
            id = record.id,
            displayName = record.displayName,
            baseUrl = record.baseUrl,
            enabled = record.enabled,
            headersJson = record.headersJson,
            lastSyncToolsJson = record.lastSyncToolsJson,
            lastSyncError = record.lastSyncError,
            lastSyncAt = record.lastSyncAt,
            deploymentKind = "REMOTE",
            startCommand = record.startCommand,
            linkStatus = record.linkStatus.name,
            wireKind = record.wireKind.name,
        )
    }

    override suspend fun deleteServer(id: String) = withContext(Dispatchers.Default) {
        queries.deleteMcpServer(id)
    }

    override suspend fun getBindingsForServer(serverId: String): List<McpToolBindingRecord> =
        withContext(Dispatchers.Default) {
            queries.getMcpBindingsForServer(serverId).executeAsList().map { it.toRecord() }
        }

    override suspend fun upsertBinding(record: McpToolBindingRecord) = withContext(Dispatchers.Default) {
        queries.insertMcpToolBinding(
            id = record.id,
            serverId = record.serverId,
            mcpToolName = record.mcpToolName,
            description = record.description,
            inputSchemaJson = record.inputSchemaJson,
            enabled = record.enabled,
        )
    }

    override suspend fun deleteBinding(id: String) = withContext(Dispatchers.Default) {
        queries.deleteMcpBinding(id)
    }

    override suspend fun getEnabledBindingsForRuntime(): List<McpToolBindingRecord> =
        withContext(Dispatchers.Default) {
            val servers = queries.getAllMcpServers().executeAsList()
                .filter { it.enabled }
                .associateBy { it.id }
            queries.getAllMcpBindings().executeAsList()
                .filter { it.enabled && servers.containsKey(it.serverId) }
                .map { it.toRecord() }
        }

    override suspend fun updateServerSyncState(
        serverId: String,
        toolsJson: String,
        error: String?,
        syncAt: Long,
        linkStatus: McpServerLinkStatus,
    ) = withContext(Dispatchers.Default) {
        val row = queries.getMcpServer(serverId).executeAsOneOrNull() ?: return@withContext
        queries.insertMcpServer(
            id = row.id,
            displayName = row.displayName,
            baseUrl = row.baseUrl,
            enabled = row.enabled,
            headersJson = row.headersJson,
            lastSyncToolsJson = toolsJson,
            lastSyncError = error,
            lastSyncAt = syncAt,
            deploymentKind = row.deploymentKind,
            startCommand = row.startCommand,
            linkStatus = linkStatus.name,
            wireKind = row.wireKind,
        )
    }

    override suspend fun replaceToolsFromSync(serverId: String, tools: List<McpDiscoveredTool>) =
        withContext(Dispatchers.Default) {
            val existing = queries.getMcpBindingsForServer(serverId).executeAsList()
                .associateBy { it.mcpToolName }
            val newNames = tools.map { it.name }.toSet()
            for (tool in tools) {
                val prev = existing[tool.name]
                val id = prev?.id ?: stableMcpToolBindingId(serverId, tool.name)
                queries.insertMcpToolBinding(
                    id = id,
                    serverId = serverId,
                    mcpToolName = tool.name,
                    description = tool.description,
                    inputSchemaJson = tool.inputSchemaJson,
                    enabled = prev?.enabled ?: true,
                )
            }
            for (row in existing.values) {
                if (row.mcpToolName !in newNames) {
                    queries.deleteMcpBinding(row.id)
                }
            }
        }
}

private fun McpServerEntity.toRecord() = McpServerRecord(
    id = id,
    displayName = displayName,
    baseUrl = baseUrl,
    enabled = enabled,
    headersJson = headersJson,
    lastSyncToolsJson = lastSyncToolsJson,
    lastSyncError = lastSyncError,
    lastSyncAt = lastSyncAt,
    startCommand = startCommand,
    linkStatus = McpServerLinkStatus.fromStored(linkStatus),
    wireKind = McpWireKind.fromStored(wireKind),
)

private fun McpToolBindingEntity.toRecord() = McpToolBindingRecord(
    id = id,
    serverId = serverId,
    mcpToolName = mcpToolName,
    description = description,
    inputSchemaJson = inputSchemaJson,
    enabled = enabled,
)
