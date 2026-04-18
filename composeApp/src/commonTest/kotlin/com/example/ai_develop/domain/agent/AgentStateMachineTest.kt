package com.example.ai_develop.domain.agent
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentStateMachineTest {

    private fun newFsm(stage: AgentStage) = AgentStateMachine(
        AgentState(
            agentId = "test",
            currentStage = stage,
            currentStepId = null,
            plan = AgentPlan()
        )
    )

    @Test
    fun `test state transitions`() {
        val initialState = AgentState(
            agentId = "test",
            currentStage = AgentStage.PLANNING,
            currentStepId = null,
            plan = AgentPlan()
        )
        val fsm = AgentStateMachine(initialState)

        // Planning -> Execution (Valid)
        assertTrue(fsm.canTransitionTo(AgentStage.EXECUTION))
        fsm.transitionTo(AgentStage.EXECUTION)
        assertEquals(AgentStage.EXECUTION, fsm.getCurrentState().currentStage)

        // Execution -> Review (Valid)
        assertTrue(fsm.canTransitionTo(AgentStage.REVIEW))
        
        // Execution -> Planning (Valid for revision)
        assertTrue(fsm.canTransitionTo(AgentStage.PLANNING))

        // Planning -> Review (Invalid - must go through execution)
        fsm.transitionTo(AgentStage.PLANNING)
        assertFalse(fsm.canTransitionTo(AgentStage.REVIEW))
    }

    @Test
    fun `test invariant violation rollback`() {
        val initialState = AgentState(
            agentId = "test",
            currentStage = AgentStage.EXECUTION,
            currentStepId = "step-1",
            plan = AgentPlan()
        )
        val fsm = AgentStateMachine(initialState)

        fsm.handleInvariantViolation("Constraint failed")
        assertEquals(AgentStage.PLANNING, fsm.getCurrentState().currentStage)
    }

    @Test
    fun reviewStage_allowsDonePlanningOrExecution() {
        val fsm = newFsm(AgentStage.REVIEW)
        assertTrue(fsm.canTransitionTo(AgentStage.DONE))
        assertTrue(fsm.canTransitionTo(AgentStage.PLANNING))
        assertTrue(fsm.canTransitionTo(AgentStage.EXECUTION))
        assertFalse(fsm.canTransitionTo(AgentStage.REVIEW))
    }

    @Test
    fun doneStage_onlyBackToPlanning() {
        val fsm = newFsm(AgentStage.DONE)
        assertTrue(fsm.canTransitionTo(AgentStage.PLANNING))
        assertFalse(fsm.canTransitionTo(AgentStage.EXECUTION))
        assertFalse(fsm.canTransitionTo(AgentStage.REVIEW))
    }

    @Test
    fun transitionTo_rejectsIllegal() {
        val fsm = newFsm(AgentStage.PLANNING)
        val r = fsm.transitionTo(AgentStage.DONE)
        assertTrue(r.isFailure)
    }

    @Test
    fun updatePlan_preservesStage() {
        val fsm = newFsm(AgentStage.EXECUTION)
        val newPlan = AgentPlan(steps = listOf(AgentStep("id", "description")))
        val updated = fsm.updatePlan(newPlan)
        assertEquals(AgentStage.EXECUTION, updated.currentStage)
        assertEquals(newPlan, updated.plan)
    }
}
