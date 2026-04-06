package com.example.ai_develop.domain

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskSagaDetailedTest {

    private class FakeLocalRepository : LocalChatRepository {
        private val _tasks = MutableStateFlow<List<TaskContext>>(emptyList())
        private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        private val _agents = MutableStateFlow<List<Agent>>(emptyList())

        override fun getAgents(): Flow<List<Agent>> = _agents.asStateFlow()
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = _agents.map { list -> list.find { it.id == agentId } }
        override suspend fun saveAgent(agent: Agent) {
            _agents.value = _agents.value.filterNot { it.id == agent.id } + agent
        }

        override suspend fun saveAgentMetadata(agent: Agent) {
            saveAgent(agent)
        }

        override suspend fun saveMessage(
            agentId: String,
            message: ChatMessage,
            taskId: String?,
            taskState: TaskState?
        ) {
            _messages.value = _messages.value + message.copy(taskId = taskId, taskState = taskState)
        }

        override suspend fun deleteAgent(agentId: String) {
            _agents.value = _agents.value.filterNot { it.id == agentId }
        }

        override fun getTasks(): Flow<List<TaskContext>> = _tasks.asStateFlow()
        override suspend fun saveTask(task: TaskContext) {
            _tasks.value = _tasks.value.filterNot { it.taskId == task.taskId } + task
        }

        override suspend fun deleteTask(task: TaskContext) {
            _tasks.value = _tasks.value.filterNot { it.taskId == task.taskId }
        }

        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> = _messages.map { list -> list.filter { it.taskId == taskId } }
        override suspend fun deleteMessagesForTask(taskId: String) {
            _messages.value = _messages.value.filterNot { it.taskId == taskId }
        }
    }

    private class FakeChatRepository : ChatRepository {
        val systemPrompts = mutableListOf<String>()
        var nextResponse: String = ""

        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ): Flow<Result<String>> {
            systemPrompts.add(systemPrompt)
            return flowOf(Result.success(nextResponse))
        }

        override suspend fun extractFacts(
            messages: List<ChatMessage>,
            currentFacts: ChatFacts,
            provider: LLMProvider
        ) = Result.success(ChatFacts())

        override suspend fun summarize(
            messages: List<ChatMessage>,
            previousSummary: String?,
            instruction: String,
            provider: LLMProvider
        ) = Result.success("summary")

        override suspend fun analyzeTask(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ) = Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ) = Result.success(WorkingMemoryAnalysis())

        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String) = null
        override suspend fun getProfile(agentId: String) = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) =
            emptyList<Invariant>()

        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String) = flowOf(null)
    }

    @Test
    fun `runStage should include correct system instruction for each stage`() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = Agent(
            id = "a1",
            name = "A",
            systemPrompt = "Agent Prompt",
            temperature = 0.7,
            provider = LLMProvider.DeepSeek(),
            stopWord = "",
            maxTokens = 2000
        )

        val context = TaskContext(
            taskId = "t1",
            title = "Task Title",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false
        )
        localRepo.saveAgent(agent)
        localRepo.saveTask(context)

        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)

        // 1. PLANNING stage
        chatRepo.nextResponse = "{\"status\": \"SUCCESS\", \"result\": \"P\"}"
        saga.start()
        advanceUntilIdle()
        
        assertTrue(chatRepo.systemPrompts.any { it.contains("PLANNING stage") }, "Should have called PLANNING stage")
        assertTrue(chatRepo.systemPrompts.any { it.contains("EXECUTION stage") }, "Should have called EXECUTION stage")
        assertTrue(chatRepo.systemPrompts.any { it.contains("VALIDATION stage") }, "Should have called VALIDATION stage")
    }

    @Test
    fun `transitionBack should create failed notification and return to previous state`() =
        runTest {
            val localRepo = FakeLocalRepository()
            val chatRepo = FakeChatRepository()
            val memoryManager = ChatMemoryManager()
            val testDispatcher = UnconfinedTestDispatcher(testScheduler)
            val agent = Agent(
                id = "a1",
                name = "A",
                systemPrompt = "P",
                temperature = 0.7,
                provider = LLMProvider.DeepSeek(),
                stopWord = "",
                maxTokens = 2000
            )

            val context = TaskContext(
                taskId = "t1",
                title = "Task Title",
                state = AgentTaskState(TaskState.EXECUTION, agent),
                executorAgentId = "a1",
                architectAgentId = "a1",
                validatorAgentId = "a1",
                isPaused = false
            )
            localRepo.saveAgent(agent)
            localRepo.saveTask(context)

            val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)

            chatRepo.nextResponse = "{\"status\": \"FAILED\", \"result\": \"Error reason\"}"
            saga.start()
            advanceUntilIdle()

            assertEquals(TaskState.PLANNING, saga.context.value.state.taskState)

            val messages = localRepo.getMessagesForTask("t1").first()
            val failedMsg = messages.find { it.message.contains("--- STAGE FAILED/REJECTED ---") }
            assertNotNull(failedMsg)
            assertTrue(failedMsg.message.contains("Error reason"))
            assertEquals(SourceType.SYSTEM, failedMsg.source)
        }

    @Test
    fun `runStage should handle invalid JSON as FAILED`() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = Agent(
            id = "a1",
            name = "A",
            systemPrompt = "P",
            temperature = 0.7,
            provider = LLMProvider.DeepSeek(),
            stopWord = "",
            maxTokens = 2000
        )

        val context = TaskContext(
            taskId = "t1",
            title = "T",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false
        )
        localRepo.saveAgent(agent)
        localRepo.saveTask(context)
        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)

        chatRepo.nextResponse = "This is not JSON at all"
        saga.start()
        advanceUntilIdle()

        assertEquals(TaskState.PLANNING, saga.context.value.state.taskState)
        val messages = localRepo.getMessagesForTask("t1").first()
        assertTrue(messages.any { it.message.contains("Invalid JSON response") })
    }
}
