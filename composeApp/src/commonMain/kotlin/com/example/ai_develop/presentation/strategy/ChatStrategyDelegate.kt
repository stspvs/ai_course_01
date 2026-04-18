package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import kotlinx.coroutines.flow.Flow

/**
 * Interface for chat strategy delegation.
 */
interface ChatStrategyDelegate {
    fun sendMessage(
        agent: Agent,
        message: String,
        onResult: (Result<String>) -> Unit
    ): Flow<Result<String>>
}

class DefaultChatStrategyDelegate(
    private val chatStreamingUseCase: ChatStreamingUseCase,
    private val repository: ChatRepository
) : ChatStrategyDelegate {

    override fun sendMessage(
        agent: Agent,
        message: String,
        onResult: (Result<String>) -> Unit
    ): Flow<Result<String>> {
        val branchId = agent.currentBranchId ?: "main_branch"
        
        // Prepare context based on strategy
        val context = prepareContext(agent)
        
        // Build personalization prompt from user profile
        val personalizationPrompt = agent.userProfile?.let { profile ->
            buildString {
                if (profile.preferences.isNotBlank()) {
                    append("\nUSER PREFERENCES:\n${profile.preferences}")
                }
                if (profile.constraints.isNotBlank()) {
                    append("\nUSER CONSTRAINTS (DO NOT DO THIS):\n${profile.constraints}")
                }
            }
        } ?: ""
        
        return chatStreamingUseCase(
            messages = agent.messages.filter { it.branchId == branchId },
            systemPrompt = agent.systemPrompt + personalizationPrompt + context,
            maxTokens = agent.maxTokens,
            temperature = agent.temperature,
            stopWord = agent.stopWord,
            isJsonMode = false,
            provider = agent.userProfile?.memoryModelProvider ?: agent.provider
        )
    }

    private fun prepareContext(agent: Agent): String {
        return when (val strategy = agent.memoryStrategy) {
            is ChatMemoryStrategy.StickyFacts -> {
                if (strategy.facts.facts.isNotEmpty()) {
                    "\n=== ACTIVE FACTS ===\n" + strategy.facts.facts.joinToString("\n") { "- $it" }
                } else ""
            }
            is ChatMemoryStrategy.Summarization -> strategy.summary?.let { "\n=== SUMMARY ===\n$it" } ?: ""
            else -> ""
        }
    }
}
