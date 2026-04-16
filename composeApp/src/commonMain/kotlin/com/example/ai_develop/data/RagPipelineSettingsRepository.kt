package com.example.ai_develop.data

import com.example.ai_develop.domain.RagRetrievalConfig
import kotlinx.coroutines.flow.Flow

interface RagPipelineSettingsRepository {
    fun observeConfig(): Flow<RagRetrievalConfig>
    suspend fun getConfig(): RagRetrievalConfig
    suspend fun saveConfig(config: RagRetrievalConfig)
}
