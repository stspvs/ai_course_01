package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskSagaReducerTest {

    private val dummyAgent = Agent(
        id = "a1",
        name = "A",
        systemPrompt = "",
        provider = LLMProvider.DeepSeek(),
        temperature = 0.7,
        stopWord = "",
        maxTokens = 2000
    )

    private fun ctx(
        taskState: TaskState,
        step: Int = 0,
        taskId: String = "t1",
        runtime: TaskRuntimeState.() -> TaskRuntimeState = { this }
    ): TaskContext {
        val base = TaskRuntimeState.defaultFor(taskId)
        return TaskContext(
            taskId = taskId,
            title = "Title",
            state = AgentTaskState(taskState, dummyAgent),
            step = step,
            runtimeState = base.runtime()
        )
    }

    private val samplePlan = PlanResult("g", listOf("s1"), "sc", null, null)
    private val sampleExec = ExecutionResult(true, "out", null)

    @Test
    fun stageTransition_planningToExecution_updatesPlanAndStepCount() {
        val plan = PlanResult(
            goal = "g",
            steps = listOf("s1", "s2"),
            successCriteria = "sc",
            constraints = null,
            contextSummary = null
        )
        val before = ctx(TaskState.PLANNING) { copy(stepCount = 1) }
        val after = TaskSagaReducer.stageTransition(before, TaskState.PLANNING, TaskState.EXECUTION, plan)
        assertNotNull(after)
        assertEquals(TaskState.EXECUTION, after.state.taskState)
        assertEquals(TaskState.EXECUTION, after.runtimeState.stage)
        assertEquals(listOf("s1", "s2"), after.plan)
        assertEquals("s1", after.currentPlanStep)
        assertEquals(2, after.runtimeState.stepCount)
        assertEquals(0, after.runtimeState.currentPlanStepIndex)
        assertEquals(false, after.runtimeState.awaitingPlanConfirmation)
    }

    @Test
    fun stageTransition_executionToVerification_preservesPlanStepIndex() {
        val plan = PlanResult("g", listOf("a", "b"), "sc", null, null)
        val before = ctx(TaskState.EXECUTION) {
            copy(currentPlanStepIndex = 1, planResult = plan, stepCount = 3)
        }
        val after = TaskSagaReducer.stageTransition(before, TaskState.EXECUTION, TaskState.VERIFICATION, plan)
        assertNotNull(after)
        assertEquals(TaskState.VERIFICATION, after.state.taskState)
        assertEquals(1, after.runtimeState.currentPlanStepIndex)
        assertEquals(4, after.runtimeState.stepCount)
    }

    @Test
    fun stageTransition_wrongFrom_returnsNull() {
        val before = ctx(TaskState.EXECUTION)
        assertNull(TaskSagaReducer.stageTransition(before, TaskState.PLANNING, TaskState.EXECUTION, null))
    }

    @Test
    fun finishOutcome_setsPausedAndDone() {
        val before = ctx(TaskState.EXECUTION)
        val after = TaskSagaReducer.finishOutcome(before, TaskOutcome.TIMEOUT)
        assertNotNull(after)
        assertTrue(after.isPaused)
        assertEquals(TaskState.DONE, after.state.taskState)
        assertEquals(TaskOutcome.TIMEOUT, after.runtimeState.outcome)
    }

    @Test
    fun finishCancelled_setsCancelledFlag() {
        val before = ctx(TaskState.PLANNING)
        val after = TaskSagaReducer.finishCancelled(before)
        assertNotNull(after)
        assertEquals(TaskOutcome.CANCELLED, after.runtimeState.outcome)
        assertTrue(after.runtimeState.cancelled)
    }

    @Test
    fun verificationSuccessTerminal() {
        val before = ctx(TaskState.VERIFICATION) { copy(stepCount = 5) }
        val after = TaskSagaReducer.verificationSuccessTerminal(before)
        assertNotNull(after)
        assertEquals(TaskState.DONE, after.state.taskState)
        assertEquals(TaskOutcome.SUCCESS, after.runtimeState.outcome)
        assertNull(after.runtimeState.lastVerification)
        assertNull(after.runtimeState.lastExecution)
    }

    @Test
    fun verificationSuccessNextStep() {
        val prevVer = VerificationResult(success = true, issues = null, suggestions = null)
        val before = ctx(TaskState.VERIFICATION) {
            copy(
                currentPlanStepIndex = 0,
                stepCount = 2,
                lastExecution = sampleExec,
                lastVerification = prevVer
            )
        }
        val after = TaskSagaReducer.verificationSuccessNextStep(before, 1, "step2")
        assertNotNull(after)
        assertEquals(TaskState.EXECUTION, after.state.taskState)
        assertEquals(1, after.runtimeState.currentPlanStepIndex)
        assertEquals(3, after.runtimeState.stepCount)
        assertEquals("step2", after.currentPlanStep)
        assertEquals(0, after.runtimeState.executionRetryCount)
        assertNull(after.runtimeState.lastExecution)
        assertNull(after.runtimeState.lastVerification)
        assertEquals(sampleExec, after.runtimeState.executorCarryExecution)
        assertEquals(prevVer, after.runtimeState.executorCarryVerification)
    }

    @Test
    fun verificationFailToExecution() {
        val before = ctx(TaskState.VERIFICATION) { copy(verificationRetryCount = 2) }
        val after = TaskSagaReducer.verificationFailToExecution(before, 3)
        assertNotNull(after)
        assertEquals(TaskState.EXECUTION, after.state.taskState)
        assertEquals(3, after.runtimeState.verificationRetryCount)
    }

    @Test
    fun reduce_dispatchesToSameLogic() {
        val plan = PlanResult("g", listOf("x"), "sc", null, null)
        val c = ctx(TaskState.PLANNING)
        val e1 = TaskSagaReducer.reduce(
            c,
            TaskSagaReducer.Event.StageTransition(TaskState.PLANNING, TaskState.EXECUTION, plan)
        )
        val e2 = TaskSagaReducer.stageTransition(c, TaskState.PLANNING, TaskState.EXECUTION, plan)
        assertEquals(e1, e2)
    }

    @Test
    fun syncRuntimeStage_idempotentWhenConsistent() {
        val c = ctx(TaskState.EXECUTION) { copy(stage = TaskState.EXECUTION) }
        assertEquals(c, TaskSagaReducer.syncRuntimeStage(c))
    }

    @Test
    fun incrementPlanningMessagesSinceCompress() {
        val before = ctx(TaskState.PLANNING) { copy(planningMessagesSinceCompress = 2) }
        val after = TaskSagaReducer.incrementPlanningMessagesSinceCompress(before)
        assertEquals(3, after.runtimeState.planningMessagesSinceCompress)
        assertEquals(TaskState.PLANNING, after.runtimeState.stage)
    }

    @Test
    fun setPlanAwaitingUserConfirmation() {
        val plan = PlanResult("goal", listOf("a", "b"), "sc", null, null)
        val before = ctx(TaskState.PLANNING)
        val after = TaskSagaReducer.setPlanAwaitingUserConfirmation(before, plan)
        assertTrue(after.runtimeState.awaitingPlanConfirmation)
        assertEquals(plan, after.runtimeState.planResult)
        assertEquals(listOf("a", "b"), after.plan)
    }

    @Test
    fun syncRuntimeStage_repairsDriftBetweenStateAndRuntime() {
        val broken = ctx(TaskState.EXECUTION) {
            copy(stage = TaskState.PLANNING, taskId = "t1")
        }
        val fixed = TaskSagaReducer.syncRuntimeStage(broken)
        assertEquals(TaskState.EXECUTION, fixed.runtimeState.stage)
        assertEquals("t1", fixed.runtimeState.taskId)
    }

    @Test
    fun stageTransition_planningToDone_withoutPlanResult() {
        val before = ctx(TaskState.PLANNING) { copy(stepCount = 0) }
        val after = TaskSagaReducer.stageTransition(before, TaskState.PLANNING, TaskState.DONE, null)
        assertNotNull(after)
        assertEquals(TaskState.DONE, after.state.taskState)
        assertEquals(TaskState.DONE, after.runtimeState.stage)
    }

    @Test
    fun stageTransition_planningToDone_keepsPlanListWhenPlanResultNull() {
        val before = ctx(TaskState.PLANNING) { copy(stepCount = 0) }.copy(plan = listOf("old"))
        val after = TaskSagaReducer.stageTransition(before, TaskState.PLANNING, TaskState.DONE, null)
        assertNotNull(after)
        assertEquals(listOf("old"), after.plan)
    }

    @Test
    fun stageTransition_executionToDone() {
        val before = ctx(TaskState.EXECUTION)
        val after = TaskSagaReducer.stageTransition(before, TaskState.EXECUTION, TaskState.DONE, samplePlan)
        assertNotNull(after)
        assertEquals(TaskState.DONE, after.state.taskState)
    }

    @Test
    fun stageTransition_illegalPair_returnsNull() {
        val p = ctx(TaskState.PLANNING)
        assertNull(TaskSagaReducer.stageTransition(p, TaskState.PLANNING, TaskState.VERIFICATION, null))
        val e = ctx(TaskState.EXECUTION)
        assertNull(TaskSagaReducer.stageTransition(e, TaskState.EXECUTION, TaskState.EXECUTION, null))
        val v = ctx(TaskState.VERIFICATION)
        assertNull(TaskSagaReducer.stageTransition(v, TaskState.VERIFICATION, TaskState.PLANNING, null))
    }

    @Test
    fun stageTransition_planningToExecution_nullPlanResult_keepsExistingPlan_currentPlanStepFromPlanResultOnly() {
        val before = ctx(TaskState.PLANNING).copy(plan = listOf("p1", "p2"))
        val after = TaskSagaReducer.stageTransition(before, TaskState.PLANNING, TaskState.EXECUTION, null)
        assertNotNull(after)
        assertEquals(listOf("p1", "p2"), after.plan)
        assertNull(after.currentPlanStep)
    }

    @Test
    fun stageTransition_executionToVerification_preservesLastExecution() {
        val before = ctx(TaskState.EXECUTION) {
            copy(
                planResult = samplePlan,
                lastExecution = sampleExec,
                currentPlanStepIndex = 0
            )
        }
        val after = TaskSagaReducer.stageTransition(
            before,
            TaskState.EXECUTION,
            TaskState.VERIFICATION,
            samplePlan
        )
        assertNotNull(after)
        assertEquals(sampleExec, after.runtimeState.lastExecution)
    }

    @Test
    fun finishOutcome_fromDone_returnsNull() {
        val before = ctx(TaskState.DONE)
        assertNull(TaskSagaReducer.finishOutcome(before, TaskOutcome.FAILED))
        assertNull(TaskSagaReducer.finishCancelled(before))
    }

    @Test
    fun finishOutcome_eachOutcome() {
        val stages = listOf(TaskState.PLANNING, TaskState.EXECUTION, TaskState.VERIFICATION)
        val outcomes = listOf(
            TaskOutcome.SUCCESS,
            TaskOutcome.FAILED,
            TaskOutcome.TIMEOUT,
            TaskOutcome.CANCELLED
        )
        for (stage in stages) {
            for (outcome in outcomes) {
                val b = ctx(stage)
                val after = TaskSagaReducer.finishOutcome(b, outcome)
                assertNotNull(after, "stage=$stage outcome=$outcome")
                assertEquals(TaskState.DONE, after.state.taskState)
                assertEquals(outcome, after.runtimeState.outcome)
                assertTrue(after.isPaused)
            }
        }
    }

    @Test
    fun finishCancelled_fromExecution() {
        val after = TaskSagaReducer.finishCancelled(ctx(TaskState.EXECUTION))
        assertNotNull(after)
        assertTrue(after.runtimeState.cancelled)
    }

    @Test
    fun verificationSuccessTerminal_wrongState_returnsNull() {
        assertNull(TaskSagaReducer.verificationSuccessTerminal(ctx(TaskState.PLANNING)))
        assertNull(TaskSagaReducer.verificationSuccessTerminal(ctx(TaskState.EXECUTION)))
        assertNull(TaskSagaReducer.verificationSuccessTerminal(ctx(TaskState.DONE)))
    }

    @Test
    fun verificationSuccessNextStep_wrongState_returnsNull() {
        assertNull(TaskSagaReducer.verificationSuccessNextStep(ctx(TaskState.PLANNING), 0, null))
    }

    @Test
    fun verificationFailToExecution_wrongState_returnsNull() {
        assertNull(TaskSagaReducer.verificationFailToExecution(ctx(TaskState.EXECUTION), 0))
    }

    @Test
    fun reduce_allEventKinds() {
        val plan = samplePlan
        val cPlan = ctx(TaskState.PLANNING)
        assertNotNull(
            TaskSagaReducer.reduce(cPlan, TaskSagaReducer.Event.StageTransition(TaskState.PLANNING, TaskState.EXECUTION, plan))
        )
        assertNotNull(TaskSagaReducer.reduce(cPlan, TaskSagaReducer.Event.Finish(TaskOutcome.FAILED)))
        assertNotNull(TaskSagaReducer.reduce(cPlan, TaskSagaReducer.Event.FinishCancelled))

        val cVer = ctx(TaskState.VERIFICATION)
        assertNotNull(TaskSagaReducer.reduce(cVer, TaskSagaReducer.Event.VerificationSuccessTerminal))
        assertNotNull(
            TaskSagaReducer.reduce(cVer, TaskSagaReducer.Event.VerificationNextStep(1, "x"))
        )
        assertNotNull(
            TaskSagaReducer.reduce(cVer, TaskSagaReducer.Event.VerificationFailToExecution(1))
        )
    }

    @Test
    fun reduce_finishFromDone_returnsNull() {
        val done = ctx(TaskState.DONE)
        assertNull(TaskSagaReducer.reduce(done, TaskSagaReducer.Event.Finish(TaskOutcome.FAILED)))
        assertNull(TaskSagaReducer.reduce(done, TaskSagaReducer.Event.FinishCancelled))
    }

    @Test
    fun setPlanAwaitingUserConfirmation_syncsTaskId() {
        val before = ctx(TaskState.PLANNING, taskId = "tid-99")
        val after = TaskSagaReducer.setPlanAwaitingUserConfirmation(before, samplePlan)
        assertEquals("tid-99", after.runtimeState.taskId)
        assertEquals(TaskState.PLANNING, after.runtimeState.stage)
    }

    @Test
    fun incrementPlanningMessagesSinceCompress_fromZero() {
        val before = ctx(TaskState.PLANNING) { copy(planningMessagesSinceCompress = 0) }
        assertEquals(1, TaskSagaReducer.incrementPlanningMessagesSinceCompress(before).runtimeState.planningMessagesSinceCompress)
    }

    @Test
    fun incrementPlanningMessagesSinceCompress_doesNotChangeTaskStateEnum() {
        val before = ctx(TaskState.PLANNING)
        val after = TaskSagaReducer.incrementPlanningMessagesSinceCompress(before)
        assertEquals(TaskState.PLANNING, after.state.taskState)
    }

    @Test
    fun verificationSuccessTerminal_incrementsContextStep() {
        val before = ctx(TaskState.VERIFICATION, step = 7)
        val after = TaskSagaReducer.verificationSuccessTerminal(before)
        assertNotNull(after)
        assertEquals(8, after.step)
    }

    @Test
    fun verificationSuccessNextStep_nullNextStepText_allowed() {
        val after = TaskSagaReducer.verificationSuccessNextStep(ctx(TaskState.VERIFICATION), 2, null)
        assertNotNull(after)
        assertNull(after.currentPlanStep)
    }
}
