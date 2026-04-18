package com.example.ai_develop.domain.chat
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStreamingUseCaseTest {

    private fun stubRepository(): ChatRepository = object : ChatRepository {
        override fun chatStreaming(m: List<ChatMessage>, s: String, mt: Int, t: Double, sw: String, j: Boolean, p: LLMProvider) =
            flowOf(Result.success("token"))

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

    @Test
    fun testInvokeReturnsStreamingFlow() = runTest {
        val useCase = ChatStreamingUseCase(stubRepository(), ChatMemoryManager(), this, testAgentToolRegistry(), EmptyMcpRepository())
        val result = useCase(emptyList(), "", 100, 0.7, "", false, LLMProvider.Yandex()).toList()
        assertEquals(1, result.size)
        assertEquals("token", result[0].getOrNull())
    }

    @Test
    fun getOrCreateAgent_returnsSameInstanceForSameId() = runTest {
        val useCase = ChatStreamingUseCase(stubRepository(), ChatMemoryManager(), this, testAgentToolRegistry(), EmptyMcpRepository())
        val a = useCase.getOrCreateAgent("agent-a")
        val b = useCase.getOrCreateAgent("agent-a")
        assertSame(a, b)
        useCase.evictAgent("agent-a")
        advanceUntilIdle()
    }

    @Test
    fun evictAgent_incrementsCacheGenerationAndRecreatesAgent() = runTest {
        val useCase = ChatStreamingUseCase(stubRepository(), ChatMemoryManager(), this, testAgentToolRegistry(), EmptyMcpRepository())
        val gen0 = useCase.agentCacheGeneration.value
        val first = useCase.getOrCreateAgent("x")
        useCase.evictAgent("x")
        assertEquals(gen0 + 1, useCase.agentCacheGeneration.value)
        val second = useCase.getOrCreateAgent("x")
        assertNotSame(first, second)
        useCase.evictAgent("x")
        advanceUntilIdle()
    }

    @Test
    fun evictAllAgents_clearsCacheAndIncrementsGeneration() = runTest {
        val useCase = ChatStreamingUseCase(stubRepository(), ChatMemoryManager(), this, testAgentToolRegistry(), EmptyMcpRepository())
        val gen0 = useCase.agentCacheGeneration.value
        val a = useCase.getOrCreateAgent("a")
        val b = useCase.getOrCreateAgent("b")
        useCase.evictAllAgents()
        assertEquals(gen0 + 1, useCase.agentCacheGeneration.value)
        val a2 = useCase.getOrCreateAgent("a")
        assertNotSame(a, a2)
        useCase.evictAllAgents()
        advanceUntilIdle()
    }
}
