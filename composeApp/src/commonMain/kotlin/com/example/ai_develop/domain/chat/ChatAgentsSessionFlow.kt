@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.ai_develop.domain.chat

import com.example.ai_develop.domain.agent.AgentActivity
import com.example.ai_develop.domain.agent.PhaseStatusHint
import com.example.ai_develop.domain.task.GetAgentsUseCase
import com.example.ai_develop.domain.llm.GENERAL_CHAT_ID
import com.example.ai_develop.domain.llm.LLMProvider
import com.example.ai_develop.domain.llm.LlmUiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Собирает срез списка агентов из БД и live-состояние выбранного [com.example.ai_develop.domain.agent.AutonomousAgent].
 */
class ChatAgentsSessionFlow(
    private val getAgentsUseCase: GetAgentsUseCase,
    private val sessionPort: AgentChatSessionPort,
) {

    fun observe(
        pendingDeletedAgentIds: Flow<Set<String>>,
        selectedAgentId: Flow<String>,
    ): Flow<LlmUiResult.SessionSliceUpdated> = combine(
        getAgentsUseCase(),
        pendingDeletedAgentIds,
        combine(
            selectedAgentId.distinctUntilChanged(),
            sessionPort.agentCacheGeneration,
        ) { id, _ -> id }
            .flatMapLatest { id ->
                flow {
                    sessionPort.ensureToolsLoaded()
                    val autonomousAgent = sessionPort.getOrCreateAgent(id, null)
                    emitAll(
                        autonomousAgent.uiState.map { ui ->
                            SelectedAgentUiSlice(
                                targetId = id,
                                snapshot = ui.agent,
                                isLoading = ui.isProcessing,
                                activity = ui.agentActivity,
                                phaseHint = ui.phaseHint,
                                streamingPreview = ui.streamingPreview,
                            )
                        },
                    )
                }
            },
        sessionPort.observeMcpRegistryRefresh(),
    ) { agentsFromDb, pending, slice, _ ->
        val visibleFromDb = agentsFromDb.filter { it.id !in pending }
        val updatedAgent = slice.snapshot
            ?: visibleFromDb.find { it.id == slice.targetId }
            ?: createDefaultAgent(slice.targetId)
        val merged = mergeAgentsFromDbWithSelection(visibleFromDb, slice.targetId, updatedAgent)
        merged to slice
    }.mapLatest { (finalAgents, slice) ->
        val targetId = slice.targetId
        sessionPort.ensureToolsLoaded()
        val agentForTools = finalAgents.find { it.id == targetId }
            ?: createDefaultAgent(targetId)
        val toolNames = sessionPort.toolNamesForAgent(agentForTools)
        LlmUiResult.SessionSliceUpdated(
            agents = finalAgents,
            isLoading = slice.isLoading,
            agentActivity = slice.activity,
            phaseHint = slice.phaseHint,
            streamingPreview = slice.streamingPreview,
            availableToolNames = toolNames,
        )
    }
}

private data class SelectedAgentUiSlice(
    val targetId: String,
    val snapshot: Agent?,
    val isLoading: Boolean,
    val activity: AgentActivity,
    val phaseHint: PhaseStatusHint?,
    val streamingPreview: String,
)

private fun mergeAgentsFromDbWithSelection(
    agentsFromDb: List<Agent>,
    targetId: String,
    selectedDetail: Agent,
): List<Agent> {
    if (agentsFromDb.isEmpty()) {
        return listOf(selectedDetail)
    }
    val merged = agentsFromDb.map { agent ->
        if (agent.id == targetId) selectedDetail else agent
    }
    return if (merged.none { it.id == targetId }) {
        merged + selectedDetail
    } else {
        merged
    }
}

private fun createDefaultAgent(targetId: String): Agent {
    return Agent(
        id = targetId,
        name = if (targetId == GENERAL_CHAT_ID) "Общий чат" else "Новый агент",
        systemPrompt = "You are a helpful assistant.",
        temperature = 0.7,
        provider = LLMProvider.Yandex(),
        stopWord = "",
        maxTokens = 2000,
        mcpAllowedBindingIds = emptyList(),
    )
}
