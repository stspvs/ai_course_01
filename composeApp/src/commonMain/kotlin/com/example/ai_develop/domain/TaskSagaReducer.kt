package com.example.ai_develop.domain

/**
 * Чистые обновления [TaskContext] для task-level FSM ([TaskSaga]).
 * Побочные эффекты (сообщения в чат, LLM, сжатие) остаются в саге.
 *
 * Все публичные функции сохраняют инвариант `runtimeState.stage == state.taskState` и `taskId` в runtime.
 */
object TaskSagaReducer {

    fun syncRuntimeStage(ctx: TaskContext): TaskContext =
        ctx.copy(runtimeState = ctx.runtimeState.copy(stage = ctx.state.taskState, taskId = ctx.taskId))

    /**
     * Эквивалент блока `updateStateInDb` внутри [TaskSaga.transitionToNextState].
     */
    fun stageTransition(
        ctx: TaskContext,
        from: TaskState,
        to: TaskState,
        planResult: PlanResult?
    ): TaskContext? {
        if (ctx.state.taskState != from) return null
        if (!AutonomousTaskStateMachine.canTransition(from, to)) return null
        val newPlan = planResult?.steps ?: ctx.plan
        val rs = ctx.runtimeState
        val next = ctx.copy(
            state = ctx.state.copy(taskState = to),
            plan = newPlan,
            currentPlanStep = planResult?.steps?.firstOrNull(),
            step = ctx.step + 1,
            runtimeState = rs.copy(
                planResult = planResult ?: rs.planResult,
                lastExecution = rs.lastExecution,
                lastVerification = when {
                    from == TaskState.PLANNING && to == TaskState.PLAN_VERIFICATION -> null
                    from == TaskState.PLAN_VERIFICATION && to == TaskState.EXECUTION -> null
                    else -> rs.lastVerification
                },
                awaitingPlanConfirmation = false,
                currentPlanStepIndex = when (from) {
                    TaskState.EXECUTION -> rs.currentPlanStepIndex
                    else -> 0
                },
                stepCount = rs.stepCount + 1,
                planVerificationRetryCount = when {
                    from == TaskState.PLAN_VERIFICATION && to == TaskState.EXECUTION -> 0
                    else -> rs.planVerificationRetryCount
                },
                planVerificationLlmCalls = when {
                    from == TaskState.PLAN_VERIFICATION && to == TaskState.EXECUTION -> 0
                    else -> rs.planVerificationLlmCalls
                }
            )
        )
        return syncRuntimeStage(next)
    }

    /**
     * Проверка плана провалена (или инварианты) — возврат к архитектору с [TaskRuntimeState.lastVerification].
     */
    fun planVerificationFailToPlanning(ctx: TaskContext): TaskContext? {
        if (ctx.state.taskState != TaskState.PLAN_VERIFICATION) return null
        if (!AutonomousTaskStateMachine.canTransition(TaskState.PLAN_VERIFICATION, TaskState.PLANNING)) return null
        val rs = ctx.runtimeState
        val next = ctx.copy(
            state = ctx.state.copy(taskState = TaskState.PLANNING),
            runtimeState = rs.copy(
                awaitingPlanConfirmation = false,
                planVerificationRetryCount = 0
            )
        )
        return syncRuntimeStage(next)
    }

    fun finishOutcome(ctx: TaskContext, outcome: TaskOutcome): TaskContext? {
        if (!AutonomousTaskStateMachine.canTransition(ctx.state.taskState, TaskState.DONE)) return null
        val next = ctx.copy(
            isPaused = true,
            state = ctx.state.copy(taskState = TaskState.DONE),
            runtimeState = ctx.runtimeState.copy(
                stage = TaskState.DONE,
                outcome = outcome
            )
        )
        return syncRuntimeStage(next)
    }

    fun finishCancelled(ctx: TaskContext): TaskContext? {
        if (!AutonomousTaskStateMachine.canTransition(ctx.state.taskState, TaskState.DONE)) return null
        val next = ctx.copy(
            isPaused = true,
            state = ctx.state.copy(taskState = TaskState.DONE),
            runtimeState = ctx.runtimeState.copy(
                stage = TaskState.DONE,
                outcome = TaskOutcome.CANCELLED,
                cancelled = true
            )
        )
        return syncRuntimeStage(next)
    }

    fun verificationSuccessTerminal(ctx: TaskContext): TaskContext? {
        if (ctx.state.taskState != TaskState.VERIFICATION) return null
        if (!AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.DONE)) return null
        val next = ctx.copy(
            state = ctx.state.copy(taskState = TaskState.DONE),
            step = ctx.step + 1,
            runtimeState = ctx.runtimeState.copy(
                stage = TaskState.DONE,
                outcome = TaskOutcome.SUCCESS,
                lastVerification = null,
                lastExecution = null,
                executorCarryExecution = null,
                executorCarryVerification = null
            )
        )
        return syncRuntimeStage(next)
    }

    fun verificationSuccessNextStep(
        ctx: TaskContext,
        nextIndex: Int,
        nextStepText: String?
    ): TaskContext? {
        if (ctx.state.taskState != TaskState.VERIFICATION) return null
        if (!AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.EXECUTION)) return null
        val next = ctx.copy(
            state = ctx.state.copy(taskState = TaskState.EXECUTION),
            step = ctx.step + 1,
            currentPlanStep = nextStepText,
            runtimeState = ctx.runtimeState.copy(
                currentPlanStepIndex = nextIndex,
                stepCount = ctx.runtimeState.stepCount + 1,
                lastVerification = null,
                lastExecution = null,
                executorCarryExecution = ctx.runtimeState.lastExecution,
                executorCarryVerification = ctx.runtimeState.lastVerification,
                executionRetryCount = 0
            )
        )
        return syncRuntimeStage(next)
    }

    /** Планировщик задал вопросы — счётчик сообщений для [maybeCompressPlanningDuringDialog]. */
    fun incrementPlanningMessagesSinceCompress(ctx: TaskContext): TaskContext =
        syncRuntimeStage(
            ctx.copy(
                runtimeState = ctx.runtimeState.copy(
                    planningMessagesSinceCompress = ctx.runtimeState.planningMessagesSinceCompress + 1
                )
            )
        )

    /** План готов, ждём подтверждения пользователя ([confirmPlan]). */
    fun setPlanAwaitingUserConfirmation(ctx: TaskContext, plan: PlanResult): TaskContext =
        syncRuntimeStage(
            ctx.copy(
                plan = plan.steps,
                runtimeState = ctx.runtimeState.copy(
                    planResult = plan,
                    awaitingPlanConfirmation = true
                )
            )
        )

    fun verificationFailToExecution(
        ctx: TaskContext,
        verificationRetryCount: Int
    ): TaskContext? {
        if (ctx.state.taskState != TaskState.VERIFICATION) return null
        if (!AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.EXECUTION)) return null
        val rs = ctx.runtimeState
        val next = ctx.copy(
            state = ctx.state.copy(taskState = TaskState.EXECUTION),
            runtimeState = rs.copy(
                verificationRetryCount = verificationRetryCount,
                lastVerification = rs.lastVerification,
                lastExecution = rs.lastExecution
            )
        )
        return syncRuntimeStage(next)
    }

    sealed class Event {
        data class StageTransition(
            val from: TaskState,
            val to: TaskState,
            val planResult: PlanResult?
        ) : Event()

        data class Finish(val outcome: TaskOutcome) : Event()
        data object FinishCancelled : Event()
        data object VerificationSuccessTerminal : Event()
        data class VerificationNextStep(val nextIndex: Int, val nextStepText: String?) : Event()
        data class VerificationFailToExecution(val verificationRetryCount: Int) : Event()
    }

    fun reduce(ctx: TaskContext, event: Event): TaskContext? = when (event) {
        is Event.StageTransition -> stageTransition(ctx, event.from, event.to, event.planResult)
        is Event.Finish -> finishOutcome(ctx, event.outcome)
        Event.FinishCancelled -> finishCancelled(ctx)
        Event.VerificationSuccessTerminal -> verificationSuccessTerminal(ctx)
        is Event.VerificationNextStep -> verificationSuccessNextStep(ctx, event.nextIndex, event.nextStepText)
        is Event.VerificationFailToExecution -> verificationFailToExecution(ctx, event.verificationRetryCount)
    }
}
