package com.example.ai_develop.domain

class ChatMemoryManager {
    
    fun processMessages(
        messages: List<ChatMessage>,
        strategy: ChatMemoryStrategy,
        currentBranchId: String? = null,
        agentBranches: List<ChatBranch> = emptyList()
    ): List<ChatMessage> {
        val branchMessages = getBranchHistory(messages, currentBranchId, agentBranches)
        return branchMessages.takeLast(strategy.windowSize)
    }

    fun getBranchHistory(
        messages: List<ChatMessage>,
        currentBranchId: String?,
        agentBranches: List<ChatBranch>
    ): List<ChatMessage> {
        val branchKey = currentBranchId ?: "main_branch"
        val branch = agentBranches.find { it.id == branchKey }
        
        // 1. Ищем последний ID сообщения через указатель ветки
        var lastId = branch?.lastMessageId
        
        // 2. Если указателя нет, ищем последнее сообщение, помеченное этим branchId
        if (lastId == null) {
            lastId = messages.lastOrNull { it.branchId == branchKey }?.id
        }
        
        // 3. Специальная обработка для основной ветки
        if (lastId == null && branchKey == "main_branch") {
            // Пробуем сообщения без указания ветки (старый формат или по умолчанию)
            lastId = messages.lastOrNull { it.branchId == null }?.id
            
            // Если веток вообще нет, берем просто последнее сообщение
            if (lastId == null && agentBranches.isEmpty()) {
                lastId = messages.lastOrNull()?.id
            }
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
        
        val visited = mutableSetOf<String>() // Защита от циклов
        while (currentId != null && currentId !in visited) {
            visited.add(currentId)
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
                strategy.summary?.let {
                    "$basePrompt\n\nSUMMARY OF PREVIOUS CONVERSATION:\n$it"
                } ?: basePrompt
            }
            else -> basePrompt
        }
    }
}
