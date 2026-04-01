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
        
        var lastId = branch?.lastMessageId
        
        if (lastId == null) {
            lastId = messages.lastOrNull { it.branchId == branchKey }?.id
        }
        
        if (lastId == null && branchKey == "main_branch") {
            lastId = messages.lastOrNull { it.branchId == null }?.id
            
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
        
        val visited = mutableSetOf<String>()
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

    fun wrapSystemPrompt(agent: Agent): String {
        var fullPrompt = agent.systemPrompt
        
        // 3. Long-term Memory (UserProfile)
        agent.userProfile?.let { profile ->
            fullPrompt += "\n\nUSER PROFILE:\n"
            if (profile.name.isNotEmpty()) fullPrompt += "- Name: ${profile.name}\n"
            profile.preferences.forEach { (key, value) -> fullPrompt += "- $key: $value\n" }
            profile.globalFacts.forEach { fullPrompt += "- $it\n" }
        }

        // 2. Working Memory & Context
        fullPrompt += when (val strategy = agent.memoryStrategy) {
            is ChatMemoryStrategy.StickyFacts -> strategy.facts.toSystemPrompt()
            is ChatMemoryStrategy.Summarization -> {
                strategy.summary?.let {
                    "\n\nSUMMARY OF PREVIOUS CONVERSATION:\n$it"
                } ?: ""
            }
            is ChatMemoryStrategy.TaskOriented -> {
                val taskInfo = mutableListOf<String>()
                strategy.currentTask?.let { taskInfo.add("CURRENT TASK: $it") }
                strategy.progress?.let { taskInfo.add("PROGRESS: $it") }
                val facts = strategy.facts.toSystemPrompt()
                "\n\n${taskInfo.joinToString("\n")}$facts"
            }
            else -> ""
        }
        
        return fullPrompt
    }
}
