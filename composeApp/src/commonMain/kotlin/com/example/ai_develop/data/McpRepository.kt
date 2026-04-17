package com.example.ai_develop.data

import kotlinx.coroutines.flow.Flow

interface McpRepository {
    fun observeServers(): Flow<List<McpServerRecord>>

    /**
     * Эмит при любом изменении записей MCP (серверы или привязки инструментов).
     * Для UI и реестра инструментов агентов — обновление «в реальном времени».
     */
    fun observeMcpRegistryChanges(): Flow<Unit>

    suspend fun getAllServers(): List<McpServerRecord>
    suspend fun getServer(id: String): McpServerRecord?
    suspend fun upsertServer(record: McpServerRecord)
    suspend fun deleteServer(id: String)

    suspend fun getBindingsForServer(serverId: String): List<McpToolBindingRecord>
    suspend fun upsertBinding(record: McpToolBindingRecord)
    suspend fun deleteBinding(id: String)

    /** Все включённые привязки с учётом включённого сервера (для реестра инструментов). */
    suspend fun getEnabledBindingsForRuntime(): List<McpToolBindingRecord>

    /** Все привязки (для экрана назначения MCP агентам). */
    suspend fun getAllBindings(): List<McpToolBindingRecord>

    suspend fun updateServerSyncState(
        serverId: String,
        toolsJson: String,
        error: String?,
        syncAt: Long,
        linkStatus: McpServerLinkStatus,
    )

    /** Заменить список инструментов результатом listTools: upsert по имени, сохранить enabled, удалить отсутствующие на сервере. */
    suspend fun replaceToolsFromSync(serverId: String, tools: List<McpDiscoveredTool>)
}
