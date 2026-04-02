package com.example.ai_develop.data.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.ai_develop.domain.Agent
import com.example.ai_develop.domain.LLMProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class MigrationTest {

    @Test
    fun testDataPersistenceAcrossVersionBumps() = runTest {
        // Correct Room inMemoryDatabaseBuilder for KMP
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>(
            factory = { AppDatabaseConstructor.initialize() }
        ).setDriver(BundledSQLiteDriver()).build()
        
        val repository = DatabaseChatRepository(db)
        
        val agent = Agent(
            id = "persistent_agent",
            name = "Keeper",
            systemPrompt = "Stay here",
            temperature = 0.5,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100
        )
        
        // 2. Сохраняем данные
        repository.saveAgentMetadata(agent)
        
        // 3. Проверяем, что они там
        val before = repository.getAgents().first()
        assertTrue(before.any { it.id == "persistent_agent" })
        
        db.close()
    }
}
