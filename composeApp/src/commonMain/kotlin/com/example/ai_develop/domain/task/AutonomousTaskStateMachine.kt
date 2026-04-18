package com.example.ai_develop.domain.task

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

/**
 * Строгие переходы task-level FSM (PLANNING → PLAN_VERIFICATION → EXECUTION → VERIFICATION → DONE).
 */
object AutonomousTaskStateMachine {

    fun canTransition(from: TaskState, to: TaskState): Boolean = when (from) {
        TaskState.PLANNING -> to == TaskState.PLAN_VERIFICATION || to == TaskState.DONE
        TaskState.PLAN_VERIFICATION -> to == TaskState.EXECUTION || to == TaskState.PLANNING || to == TaskState.DONE
        TaskState.EXECUTION -> to == TaskState.VERIFICATION || to == TaskState.DONE
        TaskState.VERIFICATION -> to == TaskState.EXECUTION || to == TaskState.DONE
        TaskState.DONE -> false
    }

    fun canForceDone(from: TaskState): Boolean = from != TaskState.DONE

    /**
     * Проверка глобального лимита шагов (итераций цикла).
     */
    fun isGlobalStepLimitExceeded(runtime: TaskRuntimeState): Boolean =
        runtime.stepCount >= runtime.maxSteps

    fun shouldTimeoutPlanning(runtime: TaskRuntimeState): Boolean =
        runtime.planningLlmCalls >= runtime.maxPlanningSteps

    fun shouldTimeoutExecution(runtime: TaskRuntimeState): Boolean =
        runtime.executionLlmCalls >= runtime.maxExecutionSteps

    fun shouldTimeoutVerification(runtime: TaskRuntimeState): Boolean =
        runtime.verificationLlmCalls >= runtime.maxVerificationSteps

    fun shouldTimeoutPlanVerification(runtime: TaskRuntimeState): Boolean =
        runtime.planVerificationLlmCalls >= runtime.maxPlanVerificationSteps

    fun mergeRuntime(
        runtime: TaskRuntimeState,
        newStage: TaskState,
        incrementStepCount: Boolean = false,
        outcome: TaskOutcome? = runtime.outcome
    ): TaskRuntimeState {
        var next = runtime.copy(stage = newStage, outcome = outcome)
        if (incrementStepCount) {
            next = next.copy(stepCount = next.stepCount + 1)
        }
        return next
    }
}
