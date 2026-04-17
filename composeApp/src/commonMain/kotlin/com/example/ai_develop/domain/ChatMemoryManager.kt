package com.example.ai_develop.domain

class ChatMemoryManager {

    /**
     * Обрезает историю под [ChatMemoryStrategy.windowSize].
     *
     * Сначала восстанавливается ветка по [parentId] ([getBranchHistory]). Если цепочка оборвана
     * (частые случаи в task-чате: не у всех сообщений заполнен parent), ветка содержит только
     * хвост из одного узла — тогда для **линейного** чата (`agentBranches` пуст) берём сообщения
     * в порядке [ChatMessage.timestamp] и [takeLast] по окну стратегии.
     */
    fun processMessages(
        messages: List<ChatMessage>,
        strategy: ChatMemoryStrategy,
        currentBranchId: String? = null,
        agentBranches: List<ChatBranch> = emptyList()
    ): List<ChatMessage> {
        val linearSorted = messages.sortedWith(compareBy({ it.timestamp }, { it.id }))
        val branchMessages = getBranchHistory(messages, currentBranchId, agentBranches)
        val useLinearFallback =
            agentBranches.isEmpty() &&
                branchMessages.size < linearSorted.size &&
                linearSorted.isNotEmpty()
        val effectiveHistory = if (useLinearFallback) linearSorted else branchMessages
        return effectiveHistory.takeLast(strategy.windowSize)
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

    /**
     * История для UI: как [getBranchHistory], но если цепочка по [ChatMessage.parentId] оборвана
     * (типичный случай — сообщения из БД без сохранённого parentId), показываем полный линейный
     * порядок по [ChatMessage.timestamp]. Для режима ветвления без «дыр» в цепочке поведение совпадает с [getBranchHistory].
     */
    fun getDisplayHistory(
        messages: List<ChatMessage>,
        currentBranchId: String?,
        agentBranches: List<ChatBranch>
    ): List<ChatMessage> {
        val linearSorted = messages.sortedWith(compareBy({ it.timestamp }, { it.id }))
        val branchMessages = getBranchHistory(messages, currentBranchId, agentBranches)
        val useLinearFallback =
            agentBranches.isEmpty() &&
                branchMessages.size < linearSorted.size &&
                linearSorted.isNotEmpty()
        return if (useLinearFallback) linearSorted else branchMessages
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

    /**
     * Формирует системный промпт, включая данные из профиля пользователя и рабочей памяти.
     * @param includeUserProfile для Executor/Inspector в task-саге — false (по спецификации).
     * @param includeAgentWorkingMemoryInSystem для Executor/Inspector в task-саге — false: рабочая память агента не дублируется в system (task-level WM передаётся в user-сообщении).
     */
    fun wrapSystemPrompt(
        agent: Agent,
        includeUserProfile: Boolean = true,
        includeAgentWorkingMemoryInSystem: Boolean = true
    ): String {
        val promptBuilder = StringBuilder(agent.systemPrompt)
        
        if (includeUserProfile) {
            agent.userProfile?.let { profile ->
                if (profile.preferences.isNotEmpty() || profile.constraints.isNotEmpty()) {
                    promptBuilder.append("\n\n=== USER PERSONALIZATION ===\n")
                    if (profile.preferences.isNotEmpty()) {
                        promptBuilder.append("User Preferences (Style, Format, Tone): ${profile.preferences}\n")
                    }
                    if (profile.constraints.isNotEmpty()) {
                        promptBuilder.append("User Constraints (What NOT to use): ${profile.constraints}\n")
                    }
                }
            }
        }

        if (includeAgentWorkingMemoryInSystem) {
            val wm = agent.workingMemory
            promptBuilder.append("\n=== WORKING MEMORY (Current Status) ===\n")
            val dialogueGoal = wm.dialogueGoal ?: wm.currentTask
            dialogueGoal?.let { if (it.isNotEmpty()) promptBuilder.append("DIALOGUE GOAL: $it\n") }
            wm.currentTask?.let {
                if (it.isNotEmpty() && it != wm.dialogueGoal) promptBuilder.append("CURRENT TASK (label): $it\n")
            }
            wm.progress?.let { if (it.isNotEmpty()) promptBuilder.append("CURRENT PROGRESS: $it\n") }
            if (wm.userClarifications.isNotEmpty()) {
                promptBuilder.append("\nUSER CLARIFICATIONS (already established):\n")
                wm.userClarifications.forEach { c -> promptBuilder.append("- $c\n") }
            }
            if (wm.fixedTermsAndConstraints.isNotEmpty()) {
                promptBuilder.append("\nFIXED TERMS AND CONSTRAINTS:\n")
                wm.fixedTermsAndConstraints.forEach { t -> promptBuilder.append("- $t\n") }
            }

            val extractedFacts = wm.extractedFacts.facts
            if (extractedFacts.isNotEmpty()) {
                promptBuilder.append("\n=== RELEVANT CONTEXT (Extracted Facts) ===\n")
                extractedFacts.forEach { promptBuilder.append("- $it\n") }
            }
        }
        
        return promptBuilder.toString()
    }

    /**
     * Формирует сообщение с данными из кратковременной памяти (summary, sticky facts)
     * для подстановки в простой промпт (список сообщений).
     */
    fun getShortTermMemoryMessage(agent: Agent): ChatMessage? {
        val content = when (val strategy = agent.memoryStrategy) {
            is ChatMemoryStrategy.StickyFacts -> {
                val facts = strategy.facts.facts
                if (facts.isNotEmpty()) {
                    "=== TEMPORARY MEMORY (Active Facts) ===\n" + facts.joinToString("\n") { "- $it" }
                } else null
            }
            is ChatMemoryStrategy.Summarization -> {
                strategy.summary?.let {
                    if (it.isNotEmpty()) {
                        "=== TEMPORARY MEMORY (Conversation Summary) ===\n$it"
                    } else null
                }
            }
            else -> null
        }
        
        return content?.let {
            ChatMessage(
                role = "system",
                message = it,
                source = SourceType.SYSTEM,
                isSystemNotification = true
            )
        }
    }
}
