package com.example.ai_develop.domain

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskSagaDetailedTest {

    private class FakeLocalRepository : LocalChatRepository {
        val tasks = mutableMapOf<String, TaskContext>()
        val messages = mutableListOf<ChatMessage>()
        val agents = mutableMapOf<String, Agent>()

        override fun getAgents(): Flow<List<Agent>> = flowOf(agents.values.toList())
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = flowOf(agents[agentId])
        override suspend fun saveAgent(agent: Agent) {
            agents[agent.id] = agent
        }

        override suspend fun saveAgentMetadata(agent: Agent) {
            agents[agent.id] = agent
        }

        override suspend fun saveMessage(
            agentId: String,
            message: ChatMessage,
            taskId: String?,
            taskState: TaskState?
        ) {
            messages.add(message)
        }

        override suspend fun deleteAgent(agentId: String) {
            agents.remove(agentId)
        }

        override fun getTasks(): Flow<List<TaskContext>> = flowOf(tasks.values.toList())
        override suspend fun saveTask(task: TaskContext) {
            tasks[task.taskId] = task
        }

        override suspend fun deleteTask(task: TaskContext) {
            tasks.remove(task.taskId)
        }

        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> = flowOf(messages)
        override suspend fun deleteMessagesForTask(taskId: String) {
            messages.clear()
        }
    }

    private class FakeChatRepository : ChatRepository {
        var lastSystemPrompt: String = ""
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
            lastSystemPrompt = systemPrompt
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
            validatorAgentId = "a1"
        )

        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager)

        // 1. Check PLANNING instruction
        chatRepo.nextResponse = "{\"status\": \"SUCCESS\", \"result\": \"P\"}"
        saga.start()
        assertTrue(chatRepo.lastSystemPrompt.contains("PLANNING stage"))
        assertTrue(chatRepo.lastSystemPrompt.contains("Task Title"))
        assertTrue(chatRepo.lastSystemPrompt.contains("return a JSON response"))

        // 2. Check EXECUTION instruction
        chatRepo.nextResponse = "{\"status\": \"SUCCESS\", \"result\": \"E\"}"
        assertEquals(TaskState.EXECUTION, saga.context.value.state.taskState)
        assertTrue(chatRepo.lastSystemPrompt.contains("EXECUTION stage"))

        // 3. Check VALIDATION instruction
        chatRepo.nextResponse = "{\"status\": \"SUCCESS\", \"result\": \"V\"}"
        assertEquals(TaskState.VALIDATION, saga.context.value.state.taskState)
        assertTrue(chatRepo.lastSystemPrompt.contains("VALIDATION stage"))
    }

    @Test
    fun `transitionBack should create failed notification and return to previous state`() =
        runTest {
            val localRepo = FakeLocalRepository()
            val chatRepo = FakeChatRepository()
            val memoryManager = ChatMemoryManager()
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
                architectAgentId = "a1"
            )

            val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager)

            chatRepo.nextResponse = "{\"status\": \"FAILED\", \"result\": \"Error reason\"}"
            saga.start()

            assertEquals(TaskState.PLANNING, saga.context.value.state.taskState)

            val failedMsg =
                localRepo.messages.find { it.message.contains("--- STAGE FAILED/REJECTED ---") }
            assertNotNull(failedMsg)
            assertTrue(failedMsg!!.message.contains("Error reason"))
            assertEquals(SourceType.SYSTEM, failedMsg!!.source)
        }

    @Test
    fun `runStage should handle invalid JSON as FAILED`() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
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
            architectAgentId = "a1"
        )
        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager)

        chatRepo.nextResponse = "This is not JSON at all"
        saga.start()

        assertEquals(TaskState.PLANNING, saga.context.value.state.taskState)
        assertTrue(localRepo.messages.any { it.message.contains("Invalid JSON response") })
    }
}
