package com.example.ai_develop.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
import com.example.ai_develop.domain.rag.RagPipelineMode
import com.example.ai_develop.domain.rag.RagRetrievalConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightRagPipelineSettingsRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var driver: SqlDriver
    private lateinit var database: AgentDatabase
    private lateinit var sut: SqlDelightRagPipelineSettingsRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgentDatabase.Schema.create(driver)
        database = AgentDatabase(
            driver = driver,
            AgentMessageEntityAdapter = com.example.aidevelop.database.AgentMessageEntity.Adapter(
                stageAdapter = stageAdapter
            ),
            AgentStateEntityAdapter = com.example.aidevelop.database.AgentStateEntity.Adapter(
                currentStageAdapter = stageAdapter
            ),
            InvariantEntityAdapter = com.example.aidevelop.database.InvariantEntity.Adapter(
                stageAdapter = stageAdapter
            )
        )
        sut = SqlDelightRagPipelineSettingsRepository(database, json)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun getConfig_returnsDefaultWhenMissing() = runTest {
        assertEquals(RagRetrievalConfig.Default, sut.getConfig())
    }

    @Test
    fun saveAndGet_roundTrips() = runTest {
        val cfg = RagRetrievalConfig(
            pipelineMode = RagPipelineMode.Hybrid,
            recallTopK = 12,
            finalTopK = 3,
            minSimilarity = 0.42f,
            hybridLexicalWeight = 0.66f,
            queryRewriteEnabled = true,
            rewriteOllamaModel = "rewrite-m",
            llmRerankOllamaModel = "rerank-m",
            llmRerankMaxCandidates = 7,
        )
        sut.saveConfig(cfg)
        assertEquals(cfg, sut.getConfig())
        assertEquals(cfg, sut.observeConfig().first())
    }

    @Test
    fun parseConfig_invalidJsonInDb_fallsBackToDefault() = runTest {
        database.agentDatabaseQueries.upsertRagPipelineSettings("{ not json")
        assertEquals(RagRetrievalConfig.Default, sut.getConfig())
    }

    @Test
    fun observeConfig_emitsAfterSave() = runTest {
        val cfg = RagRetrievalConfig(pipelineMode = RagPipelineMode.LlmRerank, recallTopK = 2)
        sut.saveConfig(cfg)
        assertEquals(cfg, sut.observeConfig().first())
    }
}
