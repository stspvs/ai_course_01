package com.example.ai_develop.data

import kotlinx.coroutines.flow.Flow

interface GraylogSettingsRepository {
    fun observeSettings(): Flow<GraylogSettings>
    suspend fun getSettings(): GraylogSettings
    suspend fun saveSettings(settings: GraylogSettings)
}
