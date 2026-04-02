package com.example.ai_develop.data.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseChatRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: DatabaseChatRepository

    @BeforeTest
    fun setup() {
        // Для KMP тестов в roomTest, если мы хотим, чтобы они компилировались и под Android,
        // обычно используется expect/actual. Но для текущей конфигурации Desktop:
        db = createInMemoryDatabase()
        repository = DatabaseChatRepository(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // Вспомогательная функция (в реальном проекте она была бы в expect/actual)
    private fun createInMemoryDatabase(): AppDatabase {
        return Room.inMemoryDatabaseBuilder<AppDatabase>(
            factory = { AppDatabaseConstructor.initialize() }
        ).setDriver(BundledSQLiteDriver()).build()
    }

    @Test
    fun testSaveAndRetrieveAgent() = runTest {
        val agent = Agent(
            id = "test_agent_1",
            name = "Test Agent",
            systemPrompt = "You are a tester",
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "done",
            maxTokens = 1000,
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(5)
        )

        repository.saveAgentMetadata(agent)

        val agents = repository.getAgents().first()
        assertTrue(agents.any { it.id == agent.id }, "Агент должен присутствовать в списке")
        
        val retrieved = agents.find { it.id == agent.id }
        assertNotNull(retrieved)
        assertEquals(agent.name, retrieved.name)
        assertEquals(agent.systemPrompt, retrieved.systemPrompt)
        assertEquals(agent.provider, retrieved.provider)
        assertEquals(agent.temperature, retrieved.temperature)
        assertEquals(agent.stopWord, retrieved.stopWord)
        assertEquals(agent.maxTokens, retrieved.maxTokens)
        assertEquals(agent.memoryStrategy, retrieved.memoryStrategy)
    }

    @Test
    fun testFullSettingsPersistence() = runTest {
        val complexAgent = Agent(
            id = "complex_agent",
            name = "Advanced Agent",
            systemPrompt = "Original Prompt",
            temperature = 0.5,
            provider = LLMProvider.Yandex("yandex-model"),
            stopWord = "STOP",
            maxTokens = 2000,
            memoryStrategy = ChatMemoryStrategy.StickyFacts(
                windowSize = 15,
                facts = ChatFacts(listOf("fact1", "fact2"))
            ),
            userProfile = UserProfile(
                preferences = "Style: Professional",
                constraints = "No slang, No emojis",
                memoryModelProvider = LLMProvider.OpenRouter("google/gemini-pro")
            ),
            workingMemory = WorkingMemory(
                currentTask = "Testing persistence",
                progress = "50%",
                extractedFacts = ChatFacts(listOf("extracted1")),
                updateInterval = 5,
                analysisWindowSize = 3,
                isAutoUpdateEnabled = false
            )
        )

        repository.saveAgentMetadata(complexAgent)

        val initialLoad = repository.getAgents().first().find { it.id == complexAgent.id }
        assertNotNull(initialLoad)
        assertEquals(complexAgent.userProfile, initialLoad.userProfile)
        assertEquals(complexAgent.workingMemory, initialLoad.workingMemory)
        assertEquals(complexAgent.memoryStrategy, initialLoad.memoryStrategy)

        val updatedAgent = complexAgent.copy(
            name = "Updated Advanced Agent",
            temperature = 0.9,
            provider = LLMProvider.Yandex("new-model"),
            memoryStrategy = ChatMemoryStrategy.Summarization(windowSize = 25),
            userProfile = complexAgent.userProfile?.copy(
                preferences = "Style: Casual",
                constraints = "Use emojis"
            ),
            workingMemory = complexAgent.workingMemory.copy(
                progress = "100%",
                isAutoUpdateEnabled = true
            )
        )

        repository.saveAgentMetadata(updatedAgent)

        val finalLoad = repository.getAgents().first().find { it.id == complexAgent.id }
        assertNotNull(finalLoad)
        assertEquals("Updated Advanced Agent", finalLoad.name)
        assertEquals(0.9, finalLoad.temperature)
        assertEquals(LLMProvider.Yandex("new-model"), finalLoad.provider)
        assertEquals(ChatMemoryStrategy.Summarization(windowSize = 25), finalLoad.memoryStrategy)
        assertEquals("Style: Casual", finalLoad.userProfile?.preferences)
        assertEquals("Use emojis", finalLoad.userProfile?.constraints)
        assertEquals("100%", finalLoad.workingMemory.progress)
        assertTrue(finalLoad.workingMemory.isAutoUpdateEnabled)
        
        assertEquals(complexAgent.workingMemory.currentTask, finalLoad.workingMemory.currentTask)
    }
}
