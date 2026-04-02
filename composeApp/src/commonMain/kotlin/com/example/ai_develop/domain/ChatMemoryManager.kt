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
        val promptBuilder = StringBuilder(agent.systemPrompt)
        
        // 1. User Profile (Personalization)
        agent.userProfile?.let { profile ->
            promptBuilder.append("\n\n=== USER PROFILE (Personalization) ===\n")
            if (profile.preferences.isNotEmpty()) promptBuilder.append("User Preferences (style, format, etc.): ${profile.preferences}\n")
            if (profile.constraints.isNotEmpty()) promptBuilder.append("User Constraints (what NOT to use): ${profile.constraints}\n")
        }

        // 2. Working Memory
        val wm = agent.workingMemory
        promptBuilder.append("\n=== WORKING MEMORY (Current Status) ===\n")
        wm.currentTask?.let { if (it.isNotEmpty()) promptBuilder.append("CURRENT GOAL: $it\n") }
        wm.progress?.let { if (it.isNotEmpty()) promptBuilder.append("CURRENT PROGRESS: $it\n") }
        
        val extractedFacts = wm.extractedFacts.facts
        if (extractedFacts.isNotEmpty()) {
            promptBuilder.append("\n=== RELEVANT CONTEXT (Extracted Facts) ===\n")
            extractedFacts.forEach { promptBuilder.append("- $it\n") }
        }

        // 3. Temporary Memory (Сжатие контекста)
        when (val strategy = agent.memoryStrategy) {
            is ChatMemoryStrategy.StickyFacts -> {
                val facts = strategy.facts.facts
                if (facts.isNotEmpty()) {
                    promptBuilder.append("\n=== TEMPORARY MEMORY (Active Facts) ===\n")
                    facts.forEach { promptBuilder.append("- $it\n") }
                }
            }
            is ChatMemoryStrategy.Summarization -> {
                strategy.summary?.let {
                    if (it.isNotEmpty()) {
                        promptBuilder.append("\n=== TEMPORARY MEMORY (Conversation Summary) ===\n$it")
                    }
                }
            }
            else -> {}
        }
        
        return promptBuilder.toString()
    }
}
