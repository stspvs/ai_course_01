@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package com.example.ai_develop.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

const val GENERAL_CHAT_ID = "general_chat_id"

@Serializable
data class LLMStateModel(
    val agents: List<Agent> = listOf(
        Agent(
            id = GENERAL_CHAT_ID,
            name = "Общий чат",
            systemPrompt = "You are a helpful assistant.",
            temperature = 1.0,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 3000,
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(10)
        )
    ),
    val selectedAgentId: String? = GENERAL_CHAT_ID,
    val isLoading: Boolean = false,
    val isStreamingEnabled: Boolean = true,
    val sendFullHistory: Boolean = true,
    val isJsonMode: Boolean = false,
    val currentAgentState: AgentState? = null,
    val activeInvariants: List<Invariant> = emptyList()
) {
    @Transient
    private val memoryManager = ChatMemoryManager()

    val selectedAgent: Agent?
        get() = agents.find { it.id == selectedAgentId }

    val currentMessages: List<ChatMessage>
        get() {
            val agent = selectedAgent ?: return emptyList()
            return memoryManager.getDisplayHistory(
                messages = agent.messages,
                currentBranchId = agent.currentBranchId,
                agentBranches = agent.branches
            )
        }
    
    val currentTokensUsed: Int
        get() = selectedAgent?.totalTokensUsed ?: 0
}
