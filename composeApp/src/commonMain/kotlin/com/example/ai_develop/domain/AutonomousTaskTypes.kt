package com.example.ai_develop.domain

import kotlinx.serialization.Serializable

@Serializable
enum class TaskOutcome {
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED
}

@Serializable
data class PlanResult(
    val goal: String,
    val steps: List<String>,
    val successCriteria: String,
    val constraints: String? = null,
    val contextSummary: String? = null
)

/**
 * Если весь план пришёл одной строкой с нумерацией «1. … 2. …», разбиваем на отдельные шаги.
 * Иначе сага после первого EXECUTION+VERIFICATION считает задачу выполненной ([TaskSaga] сравнивает индекс с [steps].lastIndex).
 */
fun PlanResult.expandNumberedSteps(): PlanResult {
    if (steps.size != 1) return this
    val raw = steps.first()
    val byNewline = raw.split(Regex("\\n\\s*(?=\\d{1,2}\\.\\s)"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val pieces = if (byNewline.size >= 2) {
        byNewline
    } else {
        raw.split(Regex("\\s+(?=\\d{1,2}\\.\\s)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    if (pieces.size < 2) return this
    val onlyNumbered = pieces.filter { Regex("^\\d{1,2}\\.").containsMatchIn(it) }
    if (onlyNumbered.size < 2) return this
    return copy(steps = onlyNumbered)
}

@Serializable
data class ExecutionResult(
    val success: Boolean,
    val output: String,
    val errors: List<String>? = null
)

@Serializable
data class VerificationResult(
    val success: Boolean,
    val issues: List<String>? = null,
    val suggestions: List<String>? = null
)

@Serializable
data class PlannerOutput(
    val success: Boolean,
    val plan: PlanResult? = null,
    val questions: List<String>? = null,
    val requiresUserConfirmation: Boolean = false
)

@Serializable
data class TaskRuntimeState(
    val taskId: String,
    /** Дублирует [AgentTaskState.taskState] для сериализации; при записи всегда синхронизируется в [TaskSaga.updateStateInDb]. */
    val stage: TaskState = TaskState.PLANNING,
    val stepCount: Int = 0,
    val maxSteps: Int = 10,
    val currentPlanStepIndex: Int = 0,
    val planResult: PlanResult? = null,
    val lastExecution: ExecutionResult? = null,
    val lastVerification: VerificationResult? = null,
    val workingMemory: String? = null,

    val outcome: TaskOutcome? = null,
    val awaitingPlanConfirmation: Boolean = false,
    val executionRetryCount: Int = 0,
    val verificationRetryCount: Int = 0,
    val planningLlmCalls: Int = 0,
    val executionLlmCalls: Int = 0,
    val verificationLlmCalls: Int = 0,
    val maxRetries: Int = 3,
    val maxPlanningSteps: Int = 50,
    val maxExecutionSteps: Int = 50,
    val maxVerificationSteps: Int = 50,
    val autoCompress: Boolean = true,
    val compressAfterMessages: Int = 20,
    val planningMessagesSinceCompress: Int = 0,
    val cancelled: Boolean = false,
    val verbose: Boolean = false
) {
    companion object {
        fun defaultFor(taskId: String): TaskRuntimeState = TaskRuntimeState(taskId = taskId)

        /**
         * Сбрасывает прогресс (счётчики, результаты этапов, память), сохраняя пользовательские лимиты и флаги.
         * Используется при сбросе задачи из UI или [TaskSaga.reset], чтобы не откатывать maxSteps и лимиты LLM к умолчанию.
         */
        fun resetProgressPreservingUserSettings(previous: TaskRuntimeState): TaskRuntimeState {
            val taskId = previous.taskId
            return TaskRuntimeState(
                taskId = taskId,
                stage = TaskState.PLANNING,
                stepCount = 0,
                maxSteps = previous.maxSteps,
                currentPlanStepIndex = 0,
                planResult = null,
                lastExecution = null,
                lastVerification = null,
                workingMemory = null,
                outcome = null,
                awaitingPlanConfirmation = false,
                executionRetryCount = 0,
                verificationRetryCount = 0,
                planningLlmCalls = 0,
                executionLlmCalls = 0,
                verificationLlmCalls = 0,
                maxRetries = previous.maxRetries,
                maxPlanningSteps = previous.maxPlanningSteps,
                maxExecutionSteps = previous.maxExecutionSteps,
                maxVerificationSteps = previous.maxVerificationSteps,
                autoCompress = previous.autoCompress,
                compressAfterMessages = previous.compressAfterMessages,
                planningMessagesSinceCompress = 0,
                cancelled = false,
                verbose = previous.verbose
            )
        }
    }
}
