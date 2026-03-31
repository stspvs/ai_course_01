package com.example.ai_develop.domain

class ChatMemoryManager {
    
    fun processMessages(
        messages: List<ChatMessage>,
        strategy: ChatMemoryStrategy,
        currentBranchId: String? = null
    ): List<ChatMessage> {
        // 1. Сначала восстанавливаем цепочку сообщений для текущей ветки
        // Если branchId не задан, считаем историю линейной и берем все сообщения.
        val branchMessages = if (currentBranchId != null) {
            getMessagesForBranch(messages, currentBranchId)
        } else {
            // Если ветка не выбрана, берем все сообщения как есть.
            // Старая логика messages.filter { it.parentId == null } была ошибочной,
            // так как она отфильтровывала все сообщения ассистента (у которых есть parentId).
            messages
        }

        // 2. Применяем ограничения выбранной стратегии
        return when (strategy) {
            is ChatMemoryStrategy.SlidingWindow -> {
                // Храним только последние N сообщений
                branchMessages.takeLast(strategy.windowSize)
            }
            is ChatMemoryStrategy.StickyFacts -> {
                // В Sticky Facts мы тоже ограничиваем окно сообщений, 
                // так как важные факты пойдут в системный промпт
                branchMessages.takeLast(strategy.windowSize)
            }
            is ChatMemoryStrategy.Branching -> {
                // В режиме ветвления обычно отправляется вся ветка целиком до текущего момента
                // или последние N сообщений этой ветки
                branchMessages.takeLast(strategy.windowSize)
            }
            is ChatMemoryStrategy.Summarization -> {
                // При суммаризации мы берем последние N сообщений,
                // а остальное уже в сжатом виде (в системном промпте или первом сообщении)
                branchMessages.takeLast(strategy.windowSize)
            }
        }
    }

    private fun getMessagesForBranch(messages: List<ChatMessage>, branchLastMessageId: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        var currentId: String? = branchLastMessageId
        
        // Идем от последнего сообщения к началу по цепочке parentId
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
                // Добавляем блок facts в системный запрос
                basePrompt + strategy.facts.toSystemPrompt()
            }
            is ChatMemoryStrategy.Branching -> {
                // В режиме ветвления системный промпт обычно не меняется,
                // либо можно добавить пометку о текущей ветке
                basePrompt
            }
            is ChatMemoryStrategy.Summarization -> {
                // Добавляем текущую суммаризацию в системный запрос
                if (strategy.currentSummary != null) {
                    basePrompt + "\n\nSUMMARY OF PREVIOUS CONVERSATION:\n" + strategy.currentSummary
                } else {
                    basePrompt
                }
            }
        }
    }
}
