package com.example.ai_develop.data.database

import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

abstract class AbstractDatabaseChatRepositoryTest {
    protected lateinit var db: AppDatabase
    protected lateinit var repository: DatabaseChatRepository

    @BeforeTest
    fun setup() {
        db = createTestDatabase()
        val agentRepo = DatabaseAgentRepository(db)
        val taskRepo = DatabaseTaskRepository(db)
        val messageRepo = DatabaseMessageRepository(db)
        repository = DatabaseChatRepository(agentRepo, taskRepo, messageRepo)
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
            provider = LLMProvider.Yandex("gpt-lite"),
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
    }

    @Test
    fun testTaskPersistence() = runTest {
        val agent = Agent(name = "Test", systemPrompt = "", temperature = 0.7, provider = LLMProvider.DeepSeek("deepseek-chat"), stopWord = "", maxTokens = 2000)
        val task = TaskContext(
            taskId = "task_1",
            title = "Persist Task",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "arch_1"
        )

        repository.saveTask(task)

        val tasks = repository.getTasks().first()
        assertEquals(1, tasks.size)
        assertEquals("Persist Task", tasks[0].title)
    }

    @Test
    fun testTaskColorPersistence() = runTest {
        val agent = Agent(name = "Test", systemPrompt = "", temperature = 0.7, provider = LLMProvider.DeepSeek("deepseek-chat"), stopWord = "", maxTokens = 2000)
        val task = TaskContext(
            taskId = "task_color",
            title = "Color Task",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectColor = 0xFFFF0000, // Red
            executorColor = 0xFF00FF00, // Green
            validatorColor = 0xFF0000FF  // Blue
        )

        repository.saveTask(task)

        val retrieved = repository.getTasks().first().find { it.taskId == "task_color" }
        assertNotNull(retrieved)
        assertEquals(0xFFFF0000, retrieved.architectColor)
        assertEquals(0xFF00FF00, retrieved.executorColor)
        assertEquals(0xFF0000FF, retrieved.validatorColor)
    }

    @Test
    fun testMessageTaskStatePersistence() = runTest {
        val agentId = "agent_1"
        val taskId = "task_1"
        val message = ChatMessage(message = "Task message", source = SourceType.USER, taskState = TaskState.EXECUTION)
        
        // Save an agent first to satisfy foreign key
        val agent = Agent(id = agentId, name = "Test", systemPrompt = "", temperature = 0.7, provider = LLMProvider.DeepSeek("deepseek-chat"), stopWord = "", maxTokens = 2000)
        repository.saveAgentMetadata(agent)

        repository.saveMessage(agentId, message, taskId = taskId, taskState = TaskState.EXECUTION)

        val taskMessages = repository.getMessagesForTask(taskId).first()
        assertEquals(1, taskMessages.size)
        assertEquals("Task message", taskMessages[0].message)
        assertEquals(TaskState.EXECUTION, taskMessages[0].taskState)
    }
}
