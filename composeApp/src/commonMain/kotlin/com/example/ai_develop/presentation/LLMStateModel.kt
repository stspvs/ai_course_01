package com.example.ai_develop.presentation

import com.example.ai_develop.domain.*
import kotlinx.serialization.Serializable

const val GENERAL_CHAT_ID = "general_chat_id"

@Serializable
data class LLMStateModel(
    val agents: List<Agent> = listOf(
        Agent(
            id = GENERAL_CHAT_ID,
            name = "Общий чат",
            systemPrompt = "You are a helpful assistant.",
            temperature = 1.0,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 3000,
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(10)
        )
    ),
    val selectedAgentId: String? = GENERAL_CHAT_ID,
    val isLoading: Boolean = false,
    val isStreamingEnabled: Boolean = true,
    val sendFullHistory: Boolean = true,
    val isJsonMode: Boolean = false,
) {
    val selectedAgent: Agent?
        get() = agents.find { it.id == selectedAgentId }

    /**
     * Возвращает список сообщений только для текущей выбранной ветки.
     * Использует parentId для восстановления цепочки.
     */
    val currentMessages: List<ChatMessage>
        get() {
            val agent = selectedAgent ?: return emptyList()
            val messages = agent.messages
            if (messages.isEmpty()) return emptyList()

            val currentBranchId = agent.currentBranchId
            val agentBranches = agent.branches
            
            // 1. Определяем "точку входа" (ID последнего сообщения ветки)
            val lastId = if (currentBranchId != null) {
                agentBranches.find { it.id == currentBranchId }?.lastMessageId
            } else {
                val mainBranchEntry = agentBranches.find { it.id == "main_branch" }
                if (mainBranchEntry != null) {
                    mainBranchEntry.lastMessageId
                } else {
                    // Если записи о ветке нет, берем самое последнее сообщение, 
                    // которое относится к основной ветке или не имеет branchId
                    messages.lastOrNull { it.branchId == null || it.branchId == "main_branch" }?.id
                }
            }

            if (lastId == null) return emptyList()

            // 2. Оптимизируем поиск через Map
            val msgMap = messages.associateBy { it.id }
            
            // 3. Восстанавливаем цепочку строго вверх по parentId
            val result = mutableListOf<ChatMessage>()
            var currentId: String? = lastId
            
            val maxIterations = messages.size + 1
            var iterations = 0
            
            while (currentId != null && iterations < maxIterations) {
                val msg = msgMap[currentId]
                if (msg != null) {
                    result.add(0, msg)
                    currentId = msg.parentId
                } else {
                    break
                }
                iterations++
            }
            return result
        }
    
    val currentTokensUsed: Int
        get() = selectedAgent?.totalTokensUsed ?: 0
}
