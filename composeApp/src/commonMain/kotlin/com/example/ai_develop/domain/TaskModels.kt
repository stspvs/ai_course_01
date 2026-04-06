package com.example.ai_develop.domain

import kotlinx.serialization.Serializable

@Serializable
enum class TaskState {
    PLANNING, EXECUTION, VALIDATION, DONE
}

@Serializable
data class AgentTaskState(
    val taskState: TaskState,
    val agent: Agent
)

@Serializable
data class TaskContext(
    val taskId: String,
    val title: String,
    val state: AgentTaskState,
    val isPaused: Boolean = false,
    val step: Int = 0,
    val plan: List<String> = emptyList(),
    val planDone: List<String> = emptyList(),
    val currentPlanStep: String? = null,
    val totalCount: Int = 0,
    val architectAgentId: String? = null,
    val executorAgentId: String? = null,
    val validatorAgentId: String? = null,
    val architectColor: Long = 0xFF2196F3,
    val executorColor: Long = 0xFF4CAF50,
    val validatorColor: Long = 0xFF9C27B0
)
