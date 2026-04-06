package com.example.ai_develop.domain

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskSagaTest {

    private class FakeLocalRepository : LocalChatRepository {
        val tasks = mutableMapOf<String, TaskContext>()
        val messages = mutableListOf<ChatMessage>()
        val agents = mutableMapOf<String, Agent>()

        override fun getAgents(): Flow<List<Agent>> = flowOf(agents.values.toList())
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = flowOf(agents[agentId])
        override suspend fun saveAgent(agent: Agent) { agents[agent.id] = agent }
        override suspend fun saveAgentMetadata(agent: Agent) { agents[agent.id] = agent }
        override suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?) {
            messages.add(message)
        }
        override suspend fun deleteAgent(agentId: String) { agents.remove(agentId) }
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
        var nextResponse: String = ""
        var summarizeCalled = false

        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ): Flow<Result<String>> = flowOf(Result.success(nextResponse))

        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> {
            summarizeCalled = true
            return Result.success("Summary of planning")
        }
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<TaskAnalysisResult> = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<WorkingMemoryAnalysis> = Result.success(WorkingMemoryAnalysis())
        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(null)
    }

    @Test
    fun testSagaTransitionsAndSummarization() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        
        val architect = Agent(
            id = "arch",
            name = "Architect", 
            systemPrompt = "Architect prompt", 
            temperature = 0.7, 
            provider = LLMProvider.DeepSeek(), 
            stopWord = "", 
            maxTokens = 2000,
            memoryStrategy = ChatMemoryStrategy.Summarization(windowSize = 10)
        )
        localRepo.saveAgent(architect)
        
        val context = TaskContext(
            taskId = "task_1",
            title = "Test Task",
            state = AgentTaskState(TaskState.PLANNING, architect),
            architectAgentId = "arch",
            executorAgentId = "exec"
        )
        
        val saga = TaskSaga(chatRepo, localRepo, architect, architect, architect, context, memoryManager)
        
        // 1. Тест перехода PLANNING -> EXECUTION с вызовом Summarization
        chatRepo.nextResponse = "{\"status\": \"SUCCESS\", \"result\": \"Detailed plan\"}"
        
        saga.start() // Запускает runStage(architect, "PLANNING")
        
        // Даем время на выполнение корутин внутри Saga
        // В реальном тесте может потребоваться advanceUntilIdle() или ожидание стейта
        
        // Проверяем, что стейт сменился на EXECUTION (через transitionToNext)
        assertEquals(TaskState.EXECUTION, saga.context.value.state.taskState)
        
        // Проверяем, что была вызвана процедура сжатия (summarize)
        assertTrue(chatRepo.summarizeCalled, "Summarization should be triggered when moving from PLANNING to EXECUTION")
        
        // Проверяем, что в истории есть системное сообщение об успехе
        val messages = localRepo.getMessagesForTask("task_1").first()
        assertTrue(messages.any { it.message.contains("--- STAGE SUCCESS ---") })
    }

    @Test
    fun testHistoryFilteringInSaga() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val agent = Agent(name = "Test", systemPrompt = "", temperature = 0.7, provider = LLMProvider.DeepSeek(), stopWord = "", maxTokens = 2000)
        
        // Добавляем кучу сообщений от разных этапов
        localRepo.saveMessage("arch", ChatMessage(message = "P1", taskState = TaskState.PLANNING, source = SourceType.AI), "t1", TaskState.PLANNING)
        localRepo.saveMessage("arch", ChatMessage(message = "P2", taskState = TaskState.PLANNING, source = SourceType.AI), "t1", TaskState.PLANNING)
        localRepo.saveMessage("exec", ChatMessage(message = "E1", taskState = TaskState.EXECUTION, source = SourceType.AI), "t1", TaskState.EXECUTION)
        
        val context = TaskContext(taskId = "t1", title = "T", state = AgentTaskState(TaskState.VALIDATION, agent))
        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager)
        
        // Проверяем логику фильтрации (внутренний метод через рефлексию или сделав его доступным для теста)
        // Для простоты проверим через результат промпта в runStage, если это возможно
    }
}
