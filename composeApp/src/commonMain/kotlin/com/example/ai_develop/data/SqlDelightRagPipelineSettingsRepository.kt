package com.example.ai_develop.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.domain.RagRetrievalConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class SqlDelightRagPipelineSettingsRepository(
    private val db: AgentDatabase,
    private val json: Json,
) : RagPipelineSettingsRepository {

    private val queries = db.agentDatabaseQueries

    override fun observeConfig(): Flow<RagRetrievalConfig> {
        return queries.getRagPipelineSettings()
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row -> row?.configJson?.let { parseConfig(it) } ?: RagRetrievalConfig.Default }
    }

    override suspend fun getConfig(): RagRetrievalConfig = withContext(Dispatchers.Default) {
        queries.getRagPipelineSettings().executeAsOneOrNull()?.configJson?.let { parseConfig(it) }
            ?: RagRetrievalConfig.Default
    }

    override suspend fun saveConfig(config: RagRetrievalConfig) = withContext(Dispatchers.Default) {
        queries.upsertRagPipelineSettings(json.encodeToString(config))
    }

    private fun parseConfig(raw: String): RagRetrievalConfig = runCatching {
        json.decodeFromString<RagRetrievalConfig>(raw)
    }.getOrElse { RagRetrievalConfig.Default }
}
