package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.*
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
        
        return chatStreamingUseCase(
            messages = agent.messages.filter { it.branchId == branchId },
            systemPrompt = agent.systemPrompt + context,
            maxTokens = agent.maxTokens,
            temperature = agent.temperature,
            stopWord = agent.stopWord,
            isJsonMode = false,
            provider = agent.agentProfile?.memoryModelProvider ?: agent.provider
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
