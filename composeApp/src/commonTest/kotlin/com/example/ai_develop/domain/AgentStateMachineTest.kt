package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentStateMachineTest {

    @Test
    fun `test state transitions`() {
        val initialState = AgentState("test", AgentStage.PLANNING, null, AgentPlan())
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
        val initialState = AgentState("test", AgentStage.EXECUTION, "step-1", AgentPlan())
        val fsm = AgentStateMachine(initialState)

        fsm.handleInvariantViolation("Constraint failed")
        assertEquals(AgentStage.PLANNING, fsm.getCurrentState().currentStage)
    }
}
