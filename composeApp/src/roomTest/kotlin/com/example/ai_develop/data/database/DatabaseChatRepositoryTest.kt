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

// Имитируем контекст для Android тестов если нужно, но в KMP Room 2.7.0+ 
// для Unit тестов контекст часто не нужен при использовании билдера без параметров или с factory
class DatabaseChatRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: DatabaseChatRepository

    @BeforeTest
    fun setup() {
        val builder = Room.inMemoryDatabaseBuilder<AppDatabase>(
            factory = { AppDatabaseConstructor.initialize() }
        )
        db = builder.setDriver(BundledSQLiteDriver()).build()
        repository = DatabaseChatRepository(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun testSaveAndRetrieveAgent() = runTest {
        val agent = Agent(
            id = "test_agent_1",
            name = "Test Agent",
            systemPrompt = "You are a tester",
            temperature = 0.7,
            provider = LLMProvider.DeepSeek(),
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
            agentProfile = AgentProfile(
                name = "Profile Name",
                about = "About info",
                style = "Professional",
                globalInstructions = "Always be polite",
                constraints = listOf("No slang", "No emojis"),
                preferences = mapOf("key" to "value"),
                globalFacts = listOf("Global fact 1"),
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
        assertEquals(complexAgent.agentProfile, initialLoad.agentProfile)
        assertEquals(complexAgent.workingMemory, initialLoad.workingMemory)
        assertEquals(complexAgent.memoryStrategy, initialLoad.memoryStrategy)

        val updatedAgent = complexAgent.copy(
            name = "Updated Advanced Agent",
            temperature = 0.9,
            provider = LLMProvider.DeepSeek("deepseek-reasoner"),
            memoryStrategy = ChatMemoryStrategy.Summarization(windowSize = 25),
            agentProfile = complexAgent.agentProfile?.copy(
                style = "Casual",
                constraints = listOf("Use emojis")
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
        assertEquals(LLMProvider.DeepSeek("deepseek-reasoner"), finalLoad.provider)
        assertEquals(ChatMemoryStrategy.Summarization(windowSize = 25), finalLoad.memoryStrategy)
        assertEquals("Casual", finalLoad.agentProfile?.style)
        assertEquals(listOf("Use emojis"), finalLoad.agentProfile?.constraints)
        assertEquals("100%", finalLoad.workingMemory.progress)
        assertTrue(finalLoad.workingMemory.isAutoUpdateEnabled)
        
        assertEquals(complexAgent.agentProfile?.about, finalLoad.agentProfile?.about)
        assertEquals(complexAgent.workingMemory.currentTask, finalLoad.workingMemory.currentTask)
    }
}
