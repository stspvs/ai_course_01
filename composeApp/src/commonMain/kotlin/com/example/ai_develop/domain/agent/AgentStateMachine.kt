package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

class AgentStateMachine(
    private val initialState: AgentState
) {
    private var currentState = initialState

    fun getCurrentState() = currentState

    fun canTransitionTo(nextStage: AgentStage): Boolean {
        return when (currentState.currentStage) {
            AgentStage.PLANNING -> nextStage == AgentStage.EXECUTION
            AgentStage.EXECUTION -> nextStage == AgentStage.REVIEW || nextStage == AgentStage.PLANNING
            AgentStage.REVIEW -> nextStage == AgentStage.DONE || nextStage == AgentStage.PLANNING || nextStage == AgentStage.EXECUTION
            AgentStage.DONE -> nextStage == AgentStage.PLANNING
        }
    }

    fun transitionTo(nextStage: AgentStage): Result<AgentState> {
        if (!canTransitionTo(nextStage)) {
            return Result.failure(IllegalStateException("Cannot transition from ${currentState.currentStage} to $nextStage"))
        }
        currentState = currentState.copy(currentStage = nextStage)
        return Result.success(currentState)
    }

    fun handleInvariantViolation(violation: String): AgentState {
        // При нарушении инварианта в фазе исполнения или ревью — всегда возвращаемся в планирование
        currentState = currentState.copy(currentStage = AgentStage.PLANNING)
        return currentState
    }

    fun updatePlan(newPlan: AgentPlan): AgentState {
        currentState = currentState.copy(plan = newPlan)
        return currentState
    }
}
