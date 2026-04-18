package com.example.ai_develop.domain.task

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

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

/** Нумерация шагов в одной строке: «1. …», «1) …» (частый русский формат в пользовательских промптах). */
private val numberedStepHead = Regex("^\\d{1,2}[.)]")

/**
 * Если весь план пришёл одной строкой с нумерацией «1. … 2. …» или «1) … 2) …», разбиваем на отдельные шаги.
 * Иначе сага после первого EXECUTION+VERIFICATION считает задачу выполненной ([TaskSaga] сравнивает индекс с [steps].lastIndex).
 */
fun PlanResult.expandNumberedSteps(): PlanResult {
    if (steps.size != 1) return this
    val raw = steps.first()
    // Граница перед «N.» / «N)» после перевода строки
    val byNewline = raw.split(Regex("\\n\\s*(?=\\d{1,2}[.)]\\s)"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val pieces = if (byNewline.size >= 2) {
        byNewline
    } else {
        // Граница перед следующим пунктом в той же строке: «… 2) …»
        raw.split(Regex("\\s+(?=\\d{1,2}[.)]\\s)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    if (pieces.size < 2) return this
    val onlyNumbered = pieces.filter { numberedStepHead.containsMatchIn(it) }
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

/** Максимальная длина текста условия инварианта (символов). */
const val MAX_TASK_INVARIANT_TEXT_LENGTH = 80

/**
 * Строка для [VerificationResult.issues], когда инспектор шага плана вернул success, но хотя бы один
 * пользовательский инвариант не прошёл: итоговая верификация считается неуспешной (нельзя переходить к DONE),
 * исполнителю в [VerificationResult.suggestions] уходят только провалившиеся инварианты, без подсказок инспектора.
 *
 * Single [VerificationResult.issues] line when the plan-step inspector returned success but at least one
 * task invariant failed: overall verification is invalid for completion; the executor should fix using
 * invariant lines in [VerificationResult.suggestions] only.
 */
const val INVARIANT_OVERRIDE_ISSUE_MESSAGE =
    "The plan-step inspector accepted this step, but one or more task invariants failed; address only the invariant feedback below."

const val PLAN_INVARIANT_OVERRIDE_ISSUE_MESSAGE =
    "The plan inspector accepted this plan, but one or more task invariants failed; address only the invariant feedback below."

@Serializable
enum class InvariantPolarity {
    /** Описание — то, что должно выполняться / присутствовать. */
    POSITIVE,

    /** Описание — то, чего быть не должно (запрет). */
    NEGATIVE
}

@Serializable
data class TaskInvariant(
    val id: String,
    val text: String,
    val polarity: InvariantPolarity = InvariantPolarity.POSITIVE
)

@Serializable
data class InvariantVerificationResult(
    val success: Boolean,
    val reason: String? = null
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
    /**
     * После успешной верификации шага [lastExecution]/[lastVerification] обнуляются при переходе к следующему шагу,
     * иначе инспектор следующего шага увидел бы чужой вердикт. Эти поля копируют последний результат и вердикт
     * **завершённого** шага только для **первого** промпта исполнителя на новом шаге (см. [TaskSaga]).
     */
    val executorCarryExecution: ExecutionResult? = null,
    val executorCarryVerification: VerificationResult? = null,
    val workingMemory: String? = null,

    val outcome: TaskOutcome? = null,
    val awaitingPlanConfirmation: Boolean = false,
    val executionRetryCount: Int = 0,
    val verificationRetryCount: Int = 0,
    val planVerificationRetryCount: Int = 0,
    val planningLlmCalls: Int = 0,
    val executionLlmCalls: Int = 0,
    val verificationLlmCalls: Int = 0,
    val planVerificationLlmCalls: Int = 0,
    val maxRetries: Int = 3,
    val maxPlanningSteps: Int = 50,
    val maxExecutionSteps: Int = 50,
    val maxVerificationSteps: Int = 50,
    val maxPlanVerificationSteps: Int = 50,
    val autoCompress: Boolean = true,
    val compressAfterMessages: Int = 20,
    val planningMessagesSinceCompress: Int = 0,
    val cancelled: Boolean = false,
    val verbose: Boolean = false,
    val invariants: List<TaskInvariant> = emptyList()
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
                executorCarryExecution = null,
                executorCarryVerification = null,
                workingMemory = null,
                outcome = null,
                awaitingPlanConfirmation = false,
                executionRetryCount = 0,
                verificationRetryCount = 0,
                planVerificationRetryCount = 0,
                planningLlmCalls = 0,
                executionLlmCalls = 0,
                verificationLlmCalls = 0,
                planVerificationLlmCalls = 0,
                maxRetries = previous.maxRetries,
                maxPlanningSteps = previous.maxPlanningSteps,
                maxExecutionSteps = previous.maxExecutionSteps,
                maxVerificationSteps = previous.maxVerificationSteps,
                maxPlanVerificationSteps = previous.maxPlanVerificationSteps,
                autoCompress = previous.autoCompress,
                compressAfterMessages = previous.compressAfterMessages,
                planningMessagesSinceCompress = 0,
                cancelled = false,
                verbose = previous.verbose,
                invariants = previous.invariants
            )
        }
    }
}

/**
 * Сливает основной вердикт инспектора по шагу плана с результатами проверок пользовательских инвариантов.
 *
 * Если инспектор вернул [VerificationResult.success] `true`, но хотя бы один инвариант не прошёл, итоговая
 * верификация считается проваленной: [VerificationResult.issues] — [INVARIANT_OVERRIDE_ISSUE_MESSAGE],
 * [VerificationResult.suggestions] — только строки по провалившимся инвариантам (подсказки инспектора не передаются).
 *
 * В остальных случаях провалы инвариантов добавляются к [VerificationResult.suggestions]; [issues] без подмены.
 */
fun VerificationResult.mergedWithInvariantResults(
    invariants: List<TaskInvariant>,
    invariantResults: List<InvariantVerificationResult>,
    invariantOverrideIssueMessage: String = INVARIANT_OVERRIDE_ISSUE_MESSAGE
): VerificationResult {
    require(invariants.size == invariantResults.size)
    val allInvOk = invariantResults.all { it.success }
    // Строки по каждому провалу инварианта (для исполнителя в suggestions).
    val extraSuggestions = invariants.zip(invariantResults)
        .filter { !it.second.success }
        .map { (inv, r) ->
            val head = inv.text.trim().let { t ->
                if (t.length > MAX_TASK_INVARIANT_TEXT_LENGTH) {
                    t.take(MAX_TASK_INVARIANT_TEXT_LENGTH) + "…"
                } else {
                    t
                }
            }
            val pol = when (inv.polarity) {
                InvariantPolarity.POSITIVE -> "позитивный"
                InvariantPolarity.NEGATIVE -> "негативный"
            }
            val detail = r.reason?.trim().orEmpty().ifBlank { "Does not satisfy the invariant." }
            "[Invariant, $pol] $head — $detail"
        }
    // Инспектор «дал добро» по шагу плана (this.success == true), но инвариант(ы) не прошли:
    // итог — проверка не пройдена; подсказки самого инспектора сюда не смешиваем (только extraSuggestions).
    if (success && !allInvOk) {
        return VerificationResult(
            success = false,
            issues = listOf(invariantOverrideIssueMessage),
            suggestions = extraSuggestions.takeIf { it.isNotEmpty() }
        )
    }
    val finalSuccess = success && allInvOk
    val mergedSugg = suggestions.orEmpty() + extraSuggestions
    return VerificationResult(
        success = finalSuccess,
        issues = issues,
        suggestions = mergedSugg.takeIf { it.isNotEmpty() }
    )
}
