package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Подписка на [ChatRepository.observeAgentState] после одного начального [initialRefresh].
 * Вынесено из [AutonomousAgent], чтобы оркестратор не смешивать жизненный цикл БД и сценарии чата.
 */
class AgentRepositorySynchronizer(
    private val agentId: String,
    private val repository: ChatRepository,
    private val scope: CoroutineScope,
    private val onAgentState: suspend (AgentState) -> Unit,
) {
    fun start(initialRefresh: suspend () -> Unit): Job =
        scope.launch {
            initialRefresh()
            repository.observeAgentState(agentId).collect { state ->
                if (state != null) onAgentState(state)
            }
        }
}
