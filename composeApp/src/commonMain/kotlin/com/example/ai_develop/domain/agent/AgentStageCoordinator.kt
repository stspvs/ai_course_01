package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

/**
 * Режим стадий агента: workflow ([AgentStateMachine]) или чат без графа переходов.
 *
 * [currentState] — снимок для [ChatRepository.saveAgentState]; [currentStage] — для сообщений и LLM.
 */
interface AgentStageCoordinator {
    fun currentState(): AgentState
    fun currentStage(): AgentStage
    fun transitionTo(nextStage: AgentStage): Result<AgentState>
}

/** Стадии в промпте и на сообщениях фиксированы; переходы запрещены. */
class ChatStageCoordinator(
    initial: AgentState,
) : AgentStageCoordinator {
    private var base: AgentState = initial.copy(currentStage = AgentStage.PLANNING)

    override fun currentState(): AgentState = base

    override fun currentStage(): AgentStage = AgentStage.PLANNING

    override fun transitionTo(nextStage: AgentStage): Result<AgentState> =
        Result.failure(IllegalStateException("Workflow stages are disabled for this agent"))
}

/** Обёртка над существующим FSM: переходы и план только здесь. */
class WorkflowStageCoordinator(
    private val fsm: AgentStateMachine,
) : AgentStageCoordinator {
    override fun currentState(): AgentState = fsm.getCurrentState()

    override fun currentStage(): AgentStage = fsm.getCurrentState().currentStage

    override fun transitionTo(nextStage: AgentStage): Result<AgentState> = fsm.transitionTo(nextStage)
}
