package com.example.ai_develop.domain

class ChatMemoryManager {
    
    fun processMessages(
        messages: List<ChatMessage>,
        strategy: ChatMemoryStrategy,
        currentBranchId: String? = null
    ): List<ChatMessage> {
        // Фильтруем сообщения по текущей ветке
        val branchMessages = if (currentBranchId == null) {
            messages.filter { it.parentId == null }
        } else {
            getMessagesForBranch(messages, currentBranchId)
        }

        return when (strategy) {
            is ChatMemoryStrategy.SlidingWindow -> {
                branchMessages.takeLast(strategy.windowSize)
            }
            is ChatMemoryStrategy.StickyFacts -> {
                branchMessages.takeLast(strategy.windowSize)
            }
            is ChatMemoryStrategy.Summarization -> {
                branchMessages.takeLast(strategy.windowSize)
            }
        }
    }

    private fun getMessagesForBranch(messages: List<ChatMessage>, branchLastId: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        var currentId: String? = branchLastId
        
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
            is ChatMemoryStrategy.Summarization -> {
                if (strategy.summary.isNullOrBlank()) {
                    basePrompt
                } else {
                    "$basePrompt\n\nТЕКУЩЕЕ СЖАТОЕ СОДЕРЖАНИЕ ПРЕДЫДУЩЕЙ БЕСЕДЫ:\n${strategy.summary}"
                }
            }
        }
    }
}
