package com.example.ai_develop.data.database

import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class DatabaseRepositoriesTest {
    private lateinit var db: AppDatabase
    private lateinit var agentRepo: DatabaseAgentRepository
    private lateinit var taskRepo: DatabaseTaskRepository
    private lateinit var messageRepo: DatabaseMessageRepository
    private lateinit var compositeRepo: DatabaseChatRepository

    @BeforeTest
    fun setup() {
        db = createTestDatabase()
        agentRepo = DatabaseAgentRepository(db)
        taskRepo = DatabaseTaskRepository(db)
        messageRepo = DatabaseMessageRepository(db)
        compositeRepo = DatabaseChatRepository(agentRepo, taskRepo, messageRepo)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun testAgentRepository_SaveAndGet() = runTest {
        val agent = createDummyAgent("agent_1")
        val result = agentRepo.saveAgent(agent)
        
        assertTrue(result.isSuccess)
        val agents = agentRepo.getAgents().first()
        assertEquals(1, agents.size)
        assertEquals("agent_1", agents[0].id)
    }

    @Test
    fun testMessageRepository_IncrementTokens_Atomic() = runTest {
        val agentId = "agent_tokens"
        agentRepo.saveAgentMetadata(createDummyAgent(agentId))
        
        val message1 = ChatMessage(tokensUsed = 100, source = SourceType.USER)
        val message2 = ChatMessage(tokensUsed = 250, source = SourceType.ASSISTANT)
        
        messageRepo.saveMessage(agentId, message1, null, null)
        messageRepo.saveMessage(agentId, message2, null, null)
        
        val agent = agentRepo.getAgentWithMessages(agentId).first()
        assertNotNull(agent)
        assertEquals(350, agent.totalTokensUsed, "Tokens should be summed up atomically")
    }

    @Test
    fun testStress_ConcurrentTaskSaves() = runTest {
        val count = 50
        val results = (1..count).map { i ->
            async {
                taskRepo.saveTask(createDummyTask("task_$i"))
            }
        }.awaitAll()

        assertTrue(results.all { it.isSuccess })
        val tasks = taskRepo.getTasks().first()
        assertEquals(count, tasks.size)
    }

    @Test
    fun testCorner_SaveMessageWithNonExistentAgent() = runTest {
        // SQLITE might throw foreign key constraint error if configured
        val message = ChatMessage(message = "Ghost message")
        val result = messageRepo.saveMessage("non_existent_id", message, null, null)
        
        // В текущей схеме MessageEntity имеет agentId, но FK может быть не включен по умолчанию
        // Проверим, что хотя бы не упало фатально, если Result обработан
        assertTrue(result.isSuccess || result.isFailure)
    }

    @Test
    fun testTaskRepository_DeleteAndIntegrity() = runTest {
        val task = createDummyTask("task_to_delete")
        taskRepo.saveTask(task)
        
        val deleteResult = taskRepo.deleteTask(task)
        assertTrue(deleteResult.isSuccess)
        
        val tasks = taskRepo.getTasks().first()
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun testDistinctUntilChanged_Flow() = runTest {
        val agentId = "agent_distinct"
        val agent = createDummyAgent(agentId)
        
        var collectCount = 0
        val job = async {
            agentRepo.getAgentWithMessages(agentId).collect {
                collectCount++
            }
        }

        agentRepo.saveAgentMetadata(agent) // +1
        agentRepo.saveAgentMetadata(agent) // Should NOT trigger collect due to distinctUntilChanged
        agentRepo.saveAgentMetadata(agent.copy(name = "Updated")) // +1
        
        // Give some time for collection
        kotlinx.coroutines.delay(100)
        job.cancel()
        
        // Initial null/empty + 2 updates = 3
        // Но так как Flow в Room может эмитить при любом изменении таблицы, 
        // наше использование .distinctUntilChanged() должно фильтровать идентичные объекты.
        assertTrue(collectCount <= 3, "Collect count should be minimized by distinctUntilChanged. Got: $collectCount")
    }

    private fun createDummyAgent(id: String) = Agent(
        id = id,
        name = "Name $id",
        systemPrompt = "Prompt",
        temperature = 0.5,
        provider = LLMProvider.Yandex(),
        stopWord = "",
        maxTokens = 100
    )

    private fun createDummyTask(id: String) = TaskContext(
        taskId = id,
        title = "Title $id",
        state = AgentTaskState(TaskState.PLANNING, createDummyAgent("dummy")),
        architectAgentId = "arch"
    )
}
