package com.example.ai_develop.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStreamingUseCaseTest {
    private lateinit var repository: ChatRepository
    private lateinit var useCase: ChatStreamingUseCase
    private val testScope = TestScope()

    @BeforeTest
    fun setup() {
        repository = object : ChatRepository {
            override fun chatStreaming(m: List<ChatMessage>, s: String, mt: Int, t: Double, sw: String, j: Boolean, p: LLMProvider) = flowOf(Result.success("token"))
            override suspend fun extractFacts(m: List<ChatMessage>, cf: ChatFacts, p: LLMProvider) = Result.success(ChatFacts())
            override suspend fun summarize(m: List<ChatMessage>, ps: String?, i: String, p: LLMProvider) = Result.success("")
            override suspend fun analyzeTask(m: List<ChatMessage>, i: String, p: LLMProvider) = Result.success(TaskAnalysisResult())
            override suspend fun analyzeWorkingMemory(m: List<ChatMessage>, i: String, p: LLMProvider) = Result.success(WorkingMemoryAnalysis())
            override suspend fun saveAgentState(state: AgentState) {}
            override suspend fun getAgentState(agentId: String): AgentState? = null
            override suspend fun deleteAgent(agentId: String) {}
            override suspend fun getProfile(agentId: String): UserProfile? = null
            override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
            override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
            override suspend fun saveInvariant(invariant: Invariant) {}
            override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(null)
        }
        useCase = ChatStreamingUseCase(repository, ChatMemoryManager(), testScope, testAgentToolRegistry(), EmptyMcpRepository())
    }

    @Test
    fun testInvokeReturnsStreamingFlow() = runTest {
        val result = useCase(emptyList(), "", 100, 0.7, "", false, LLMProvider.Yandex()).toList()
        assertEquals(1, result.size)
        assertEquals("token", result[0].getOrNull())
    }
}
