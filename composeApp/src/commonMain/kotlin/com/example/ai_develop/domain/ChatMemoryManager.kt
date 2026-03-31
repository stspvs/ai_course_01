package com.example.ai_develop.domain

class ChatMemoryManager {
    
    fun processMessages(
        messages: List<ChatMessage>,
        strategy: ChatMemoryStrategy,
        currentBranchId: String? = null,
        agentBranches: List<ChatBranch> = emptyList()
    ): List<ChatMessage> {
        val branchMessages = getBranchHistory(messages, currentBranchId, agentBranches)

        return when (strategy) {
            is ChatMemoryStrategy.SlidingWindow -> branchMessages.takeLast(strategy.windowSize)
            is ChatMemoryStrategy.StickyFacts -> branchMessages.takeLast(strategy.windowSize)
            is ChatMemoryStrategy.Branching -> branchMessages.takeLast(strategy.windowSize)
            is ChatMemoryStrategy.Summarization -> branchMessages.takeLast(strategy.windowSize)
        }
    }

    fun getBranchHistory(
        messages: List<ChatMessage>,
        currentBranchId: String?,
        agentBranches: List<ChatBranch>
    ): List<ChatMessage> {
        val lastId = if (currentBranchId != null) {
            agentBranches.find { it.id == currentBranchId }?.lastMessageId
        } else {
            agentBranches.find { it.id == "main_branch" }?.lastMessageId ?: messages.lastOrNull()?.id
        }

        return if (lastId != null) {
            getMessagesForBranch(messages, lastId)
        } else {
            emptyList()
        }
    }

    private fun getMessagesForBranch(messages: List<ChatMessage>, branchLastMessageId: String): List<ChatMessage> {
        val messageMap = messages.associateBy { it.id }
        val result = mutableListOf<ChatMessage>()
        var currentId: String? = branchLastMessageId
        
        while (currentId != null) {
            val msg = messageMap[currentId]
            if (msg != null) {
                result.add(0, msg)
                currentId = msg.parentId
            } else {
                break
            }
        }
        return result
    }

    fun wrapSystemPrompt(basePrompt: String, strategy: ChatMemoryStrategy): String {
        return when (strategy) {
            is ChatMemoryStrategy.StickyFacts -> basePrompt + strategy.facts.toSystemPrompt()
            is ChatMemoryStrategy.Summarization -> {
                strategy.currentSummary?.let { 
                    "$basePrompt\n\nSUMMARY OF PREVIOUS CONVERSATION:\n$it"
                } ?: basePrompt
            }
            else -> basePrompt
        }
    }
}
