package com.example.ai_develop.domain.llm

import com.example.ai_develop.domain.agent.AgentActivity
import com.example.ai_develop.domain.chat.Agent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmStateReducerTest {

    @Test
    fun selectionChanged_updatesSelectedId() {
        val s0 = LLMStateModel(selectedAgentId = GENERAL_CHAT_ID)
        val s1 = reduceLlmState(s0, LlmUiResult.SelectionChanged("other"))
        assertEquals("other", s1.selectedAgentId)
    }

    @Test
    fun sessionSliceUpdated_updatesAgentsAndPreview() {
        val agent = Agent(
            id = "a1",
            name = "A",
            systemPrompt = "p",
            temperature = 0.5,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100,
        )
        val s0 = LLMStateModel()
        val s1 = reduceLlmState(
            s0,
            LlmUiResult.SessionSliceUpdated(
                agents = listOf(agent),
                isLoading = true,
                agentActivity = AgentActivity.Streaming,
                phaseHint = null,
                streamingPreview = "tok",
                availableToolNames = listOf("t1"),
            ),
        )
        assertEquals(listOf(agent), s1.agents)
        assertTrue(s1.isLoading)
        assertEquals(AgentActivity.Streaming, s1.agentActivity)
        assertEquals("tok", s1.streamingPreview)
        assertEquals(listOf("t1"), s1.availableToolNames)
    }

    @Test
    fun memoryAgentPatched_updatesList() {
        val a = Agent(
            id = "x",
            name = "Old",
            systemPrompt = "p",
            temperature = 0.5,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100,
        )
        val s0 = LLMStateModel(agents = listOf(a))
        val s1 = reduceLlmState(
            s0,
            LlmUiResult.MemoryAgentPatched("x") { it.copy(name = "New") },
        )
        assertEquals("New", s1.agents.single().name)
    }
}
