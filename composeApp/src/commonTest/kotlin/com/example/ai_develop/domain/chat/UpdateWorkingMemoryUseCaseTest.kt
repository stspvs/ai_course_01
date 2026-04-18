package com.example.ai_develop.domain.chat
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.koin.test.KoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateWorkingMemoryUseCaseTest : KoinTest {

    private class FakeChatRepository : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider) = flowOf<Result<String>>()
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts(listOf("Факт")))
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider) = Result.success("Summary")
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(TaskAnalysisResult())
        
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<WorkingMemoryAnalysis> {
            return Result.success(
                WorkingMemoryAnalysis(
                    currentTask = "Новая задача",
                    progress = "80%",
                    dialogueGoal = "Цель теста",
                    clarifications = listOf("Уточнение А"),
                    fixedTermsAndConstraints = listOf("Термин Б"),
                ),
            )
        }

        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun deleteAgent(agentId: String) {}
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = emptyFlow()
    }

    @Test
    fun `invoke should return updated working memory from repository`() = runTest {
        val repository = FakeChatRepository()
        val useCase = UpdateWorkingMemoryUseCase(repository)
        val agent = Agent(name = "Test", systemPrompt = "", temperature = 0.7, provider = LLMProvider.Yandex(), stopWord = "", maxTokens = 100)

        val result = useCase.update(agent)

        assertTrue(result.isSuccess)
        val wm = result.getOrNull()
        assertEquals("Новая задача", wm?.currentTask)
        assertEquals("Цель теста", wm?.dialogueGoal)
        assertEquals(listOf("Уточнение А"), wm?.userClarifications)
        assertEquals(listOf("Термин Б"), wm?.fixedTermsAndConstraints)
    }
}
