package com.example.ai_develop.domain

class ChatMemoryManager {
    
    fun processMessages(
        messages: List<ChatMessage>,
        strategy: ChatMemoryStrategy,
        currentBranchId: String? = null,
        agentBranches: List<ChatBranch> = emptyList()
    ): List<ChatMessage> {
        // 1. Сначала восстанавливаем цепочку сообщений для текущей ветки
        val branchMessages = getBranchHistory(messages, currentBranchId, agentBranches)

        // 2. Применяем ограничения выбранной стратегии
        return when (strategy) {
            is ChatMemoryStrategy.SlidingWindow -> {
                branchMessages.takeLast(strategy.windowSize)
            }
            is ChatMemoryStrategy.StickyFacts -> {
                branchMessages.takeLast(strategy.windowSize)
            }
            is ChatMemoryStrategy.Branching -> {
                branchMessages.takeLast(strategy.windowSize)
            }
            is ChatMemoryStrategy.Summarization -> {
                branchMessages.takeLast(strategy.windowSize)
            }
        }
    }

    fun getBranchHistory(
        messages: List<ChatMessage>,
        currentBranchId: String?,
        agentBranches: List<ChatBranch>
    ): List<ChatMessage> {
        val lastId = if (currentBranchId != null) {
            // Если выбрана конкретная ветка, восстанавливаем историю от её последнего сообщения
            val branch = agentBranches.find { it.id == currentBranchId }
            branch?.lastMessageId
        } else {
            // Если ветка не выбрана (Основная), ищем её метаданные по спец. ID "main_branch"
            val mainBranch = agentBranches.find { it.id == "main_branch" }
            mainBranch?.lastMessageId ?: messages.lastOrNull()?.id
        }

        return if (lastId != null) {
            getMessagesForBranch(messages, lastId)
        } else {
            emptyList()
        }
    }

    private fun getMessagesForBranch(messages: List<ChatMessage>, branchLastMessageId: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        var currentId: String? = branchLastMessageId
        
        // Идем от указанного сообщения к началу по цепочке parentId
        while (currentId != null) {
            val msg = messages.find { it.id == currentId }
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
            is ChatMemoryStrategy.SlidingWindow -> basePrompt
            is ChatMemoryStrategy.StickyFacts -> {
                basePrompt + strategy.facts.toSystemPrompt()
            }
            is ChatMemoryStrategy.Branching -> {
                basePrompt
            }
            is ChatMemoryStrategy.Summarization -> {
                if (strategy.currentSummary != null) {
                    basePrompt + "\n\nSUMMARY OF PREVIOUS CONVERSATION:\n" + strategy.currentSummary
                } else {
                    basePrompt
                }
            }
        }
    }
}
