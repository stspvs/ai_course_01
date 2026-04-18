package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

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

    /** Ожидание / синхронизация между шагами при активной обработке ([AutonomousAgentUiState.isProcessing] в [AutonomousAgent.uiState]). */
    @Serializable
    @SerialName("working")
    data object Working : AgentActivity()
}
