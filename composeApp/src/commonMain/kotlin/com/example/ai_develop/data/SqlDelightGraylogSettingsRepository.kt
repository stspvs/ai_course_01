package com.example.ai_develop.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.ai_develop.database.AgentDatabase
import com.example.aidevelop.database.GraylogSettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val defaultGraylogSettings = GraylogSettings()

class SqlDelightGraylogSettingsRepository(
    private val db: AgentDatabase,
) : GraylogSettingsRepository {

    private val queries = db.agentDatabaseQueries

    override fun observeSettings(): Flow<GraylogSettings> {
        return queries.getGraylogSettings()
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row -> row?.toSettings() ?: defaultGraylogSettings }
    }

    override suspend fun getSettings(): GraylogSettings = withContext(Dispatchers.Default) {
        queries.getGraylogSettings().executeAsOneOrNull()?.toSettings() ?: defaultGraylogSettings
    }

    override suspend fun saveSettings(settings: GraylogSettings) = withContext(Dispatchers.Default) {
        queries.upsertGraylogSettings(
            webUrl = settings.webUrl.trim().ifBlank { defaultGraylogSettings.webUrl },
            startCommand = settings.startCommand,
        )
    }
}

private fun GraylogSettingsEntity.toSettings() = GraylogSettings(
    webUrl = webUrl,
    startCommand = startCommand,
)
