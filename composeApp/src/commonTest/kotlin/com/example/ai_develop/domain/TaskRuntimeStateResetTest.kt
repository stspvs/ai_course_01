package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskRuntimeStateResetTest {

    @Test
    fun resetProgressPreservingUserSettings_keepsLimitsAndFlags() {
        val prev = TaskRuntimeState.defaultFor("tid").copy(
            stepCount = 7,
            maxSteps = 42,
            maxRetries = 5,
            maxPlanningSteps = 11,
            maxExecutionSteps = 22,
            maxVerificationSteps = 33,
            autoCompress = false,
            compressAfterMessages = 15,
            verbose = true,
            invariants = listOf(TaskInvariant("i1", "must use Kotlin"))
        )
        val next = TaskRuntimeState.resetProgressPreservingUserSettings(prev)
        assertEquals("tid", next.taskId)
        assertEquals(42, next.maxSteps)
        assertEquals(5, next.maxRetries)
        assertEquals(11, next.maxPlanningSteps)
        assertEquals(22, next.maxExecutionSteps)
        assertEquals(33, next.maxVerificationSteps)
        assertEquals(false, next.autoCompress)
        assertEquals(15, next.compressAfterMessages)
        assertEquals(true, next.verbose)
        assertEquals(listOf(TaskInvariant("i1", "must use Kotlin")), next.invariants)
    }

    @Test
    fun resetProgressPreservingUserSettings_clearsProgress() {
        val prev = TaskRuntimeState.defaultFor("tid").copy(
            stepCount = 9,
            currentPlanStepIndex = 2,
            planResult = PlanResult("g", listOf("a"), "sc"),
            lastExecution = ExecutionResult(true, "out"),
            lastVerification = VerificationResult(true),
            workingMemory = "wm",
            outcome = TaskOutcome.FAILED,
            awaitingPlanConfirmation = true,
            executionRetryCount = 1,
            verificationRetryCount = 2,
            planningLlmCalls = 3,
            executionLlmCalls = 4,
            verificationLlmCalls = 5,
            planningMessagesSinceCompress = 8,
            cancelled = true
        )
        val next = TaskRuntimeState.resetProgressPreservingUserSettings(prev)
        assertEquals(0, next.stepCount)
        assertEquals(0, next.currentPlanStepIndex)
        assertNull(next.planResult)
        assertNull(next.lastExecution)
        assertNull(next.lastVerification)
        assertNull(next.workingMemory)
        assertNull(next.outcome)
        assertEquals(false, next.awaitingPlanConfirmation)
        assertEquals(0, next.executionRetryCount)
        assertEquals(0, next.verificationRetryCount)
        assertEquals(0, next.planningLlmCalls)
        assertEquals(0, next.executionLlmCalls)
        assertEquals(0, next.verificationLlmCalls)
        assertEquals(0, next.planningMessagesSinceCompress)
        assertEquals(false, next.cancelled)
    }
}
