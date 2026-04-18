package com.example.ai_develop.domain.task

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.serialization.Serializable

@Serializable
enum class TaskState {
    PLANNING, PLAN_VERIFICATION, EXECUTION, VERIFICATION, DONE;

    companion object {
        fun fromPersisted(value: String): TaskState = when (value) {
            "VALIDATION" -> VERIFICATION
            else -> runCatching { valueOf(value) }.getOrDefault(PLANNING)
        }
    }
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
    /** После первого «запуска» true; сброс/новая задача — false (не путать с [isPaused]). */
    val isStarted: Boolean = false,
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
    val validatorColor: Long = 0xFF9C27B0,
    val runtimeState: TaskRuntimeState = TaskRuntimeState.defaultFor(taskId)
) {
    val isReadyToRun: Boolean
        get() = architectAgentId != null && executorAgentId != null && validatorAgentId != null

    val missingAgents: List<String>
        get() = buildList {
            if (architectAgentId == null) add("Архитектор")
            if (executorAgentId == null) add("Исполнитель")
            if (validatorAgentId == null) add("Валидатор")
        }
}
