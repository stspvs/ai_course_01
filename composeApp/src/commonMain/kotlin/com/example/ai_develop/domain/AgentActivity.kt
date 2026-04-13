package com.example.ai_develop.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Фаза работы агента для UI: стриминг ответа, вызов инструмента (в т.ч. MCP), промежуточная обработка.
 */
@Serializable
sealed class AgentActivity {
    @Serializable
    @SerialName("idle")
    data object Idle : AgentActivity()

    /** Идёт запрос к LLM (токены). */
    @Serializable
    @SerialName("streaming")
    data object Streaming : AgentActivity()

    /** Выполняется инструмент с данным именем. */
    @Serializable
    @SerialName("runningTool")
    data class RunningTool(val toolName: String) : AgentActivity()

    /** Ожидание / синхронизация между шагами при активном [AutonomousAgent.isProcessing]. */
    @Serializable
    @SerialName("working")
    data object Working : AgentActivity()
}
