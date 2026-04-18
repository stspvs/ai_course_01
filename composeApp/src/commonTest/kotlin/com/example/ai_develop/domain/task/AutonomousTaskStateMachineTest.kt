package com.example.ai_develop.domain.task
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutonomousTaskStateMachineTest {

    @Test
    fun planningToPlanVerificationOrDone() {
        assertTrue(AutonomousTaskStateMachine.canTransition(TaskState.PLANNING, TaskState.PLAN_VERIFICATION))
        assertTrue(AutonomousTaskStateMachine.canTransition(TaskState.PLANNING, TaskState.DONE))
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.PLANNING, TaskState.EXECUTION))
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.PLANNING, TaskState.VERIFICATION))
    }

    @Test
    fun planVerificationToExecutionPlanningOrDone() {
        assertTrue(AutonomousTaskStateMachine.canTransition(TaskState.PLAN_VERIFICATION, TaskState.EXECUTION))
        assertTrue(AutonomousTaskStateMachine.canTransition(TaskState.PLAN_VERIFICATION, TaskState.PLANNING))
        assertTrue(AutonomousTaskStateMachine.canTransition(TaskState.PLAN_VERIFICATION, TaskState.DONE))
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.PLAN_VERIFICATION, TaskState.VERIFICATION))
    }

    @Test
    fun executionToVerificationOrDone() {
        assertTrue(AutonomousTaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.VERIFICATION))
        assertTrue(AutonomousTaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.DONE))
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.PLANNING))
    }

    @Test
    fun verificationToExecutionOrDone() {
        assertTrue(AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.EXECUTION))
        assertTrue(AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.DONE))
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.PLANNING))
    }

    @Test
    fun doneIsTerminal() {
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.DONE, TaskState.PLANNING))
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.DONE, TaskState.PLAN_VERIFICATION))
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.DONE, TaskState.EXECUTION))
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.DONE, TaskState.VERIFICATION))
        assertFalse(AutonomousTaskStateMachine.canTransition(TaskState.DONE, TaskState.DONE))
    }

    @Test
    fun forceDoneFromActiveStages() {
        assertTrue(AutonomousTaskStateMachine.canForceDone(TaskState.PLANNING))
        assertTrue(AutonomousTaskStateMachine.canForceDone(TaskState.PLAN_VERIFICATION))
        assertTrue(AutonomousTaskStateMachine.canForceDone(TaskState.EXECUTION))
        assertTrue(AutonomousTaskStateMachine.canForceDone(TaskState.VERIFICATION))
        assertFalse(AutonomousTaskStateMachine.canForceDone(TaskState.DONE))
    }

    @Test
    fun globalStepLimit() {
        val r = TaskRuntimeState.defaultFor("t").copy(stepCount = 10, maxSteps = 10)
        assertTrue(AutonomousTaskStateMachine.isGlobalStepLimitExceeded(r))
        assertFalse(AutonomousTaskStateMachine.isGlobalStepLimitExceeded(r.copy(stepCount = 9)))
    }

    @Test
    fun globalStepLimit_exceededWhenStepCountAboveMax() {
        val r = TaskRuntimeState.defaultFor("t").copy(stepCount = 11, maxSteps = 10)
        assertTrue(AutonomousTaskStateMachine.isGlobalStepLimitExceeded(r))
    }

    @Test
    fun shouldTimeoutPlanning_boundary() {
        val base = TaskRuntimeState.defaultFor("t").copy(maxPlanningSteps = 50)
        assertFalse(AutonomousTaskStateMachine.shouldTimeoutPlanning(base.copy(planningLlmCalls = 49)))
        assertTrue(AutonomousTaskStateMachine.shouldTimeoutPlanning(base.copy(planningLlmCalls = 50)))
    }

    @Test
    fun shouldTimeoutExecution_boundary() {
        val base = TaskRuntimeState.defaultFor("t").copy(maxExecutionSteps = 50)
        assertFalse(AutonomousTaskStateMachine.shouldTimeoutExecution(base.copy(executionLlmCalls = 49)))
        assertTrue(AutonomousTaskStateMachine.shouldTimeoutExecution(base.copy(executionLlmCalls = 50)))
    }

    @Test
    fun shouldTimeoutVerification_boundary() {
        val base = TaskRuntimeState.defaultFor("t").copy(maxVerificationSteps = 50)
        assertFalse(AutonomousTaskStateMachine.shouldTimeoutVerification(base.copy(verificationLlmCalls = 49)))
        assertTrue(AutonomousTaskStateMachine.shouldTimeoutVerification(base.copy(verificationLlmCalls = 50)))
    }

    @Test
    fun shouldTimeoutPlanVerification_boundary() {
        val base = TaskRuntimeState.defaultFor("t").copy(maxPlanVerificationSteps = 50)
        assertFalse(AutonomousTaskStateMachine.shouldTimeoutPlanVerification(base.copy(planVerificationLlmCalls = 49)))
        assertTrue(AutonomousTaskStateMachine.shouldTimeoutPlanVerification(base.copy(planVerificationLlmCalls = 50)))
    }

    @Test
    fun mergeRuntime_setsStageAndOptionalOutcome() {
        val r = TaskRuntimeState.defaultFor("t").copy(stage = TaskState.PLANNING, outcome = null)
        val m = AutonomousTaskStateMachine.mergeRuntime(r, TaskState.EXECUTION, incrementStepCount = false, outcome = TaskOutcome.FAILED)
        assertEquals(TaskState.EXECUTION, m.stage)
        assertEquals(TaskOutcome.FAILED, m.outcome)
        assertEquals(0, m.stepCount)
    }

    @Test
    fun mergeRuntime_incrementStepCount() {
        val r = TaskRuntimeState.defaultFor("t").copy(stepCount = 3)
        val m = AutonomousTaskStateMachine.mergeRuntime(r, TaskState.VERIFICATION, incrementStepCount = true)
        assertEquals(4, m.stepCount)
    }

    @Test
    fun canTransition_exhaustiveInvalidPairs() {
        val all = TaskState.entries
        for (from in all) {
            for (to in all) {
                val allowed = AutonomousTaskStateMachine.canTransition(from, to)
                when (from) {
                    TaskState.PLANNING -> assertEquals(to == TaskState.PLAN_VERIFICATION || to == TaskState.DONE, allowed)
                    TaskState.PLAN_VERIFICATION -> assertEquals(
                        to == TaskState.EXECUTION || to == TaskState.PLANNING || to == TaskState.DONE,
                        allowed
                    )
                    TaskState.EXECUTION -> assertEquals(to == TaskState.VERIFICATION || to == TaskState.DONE, allowed)
                    TaskState.VERIFICATION -> assertEquals(to == TaskState.EXECUTION || to == TaskState.DONE, allowed)
                    TaskState.DONE -> assertFalse(allowed)
                }
            }
        }
    }
}
