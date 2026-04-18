package com.example.ai_develop.domain.llm

import com.example.ai_develop.domain.agent.AgentActivity
import com.example.ai_develop.domain.agent.PhaseStatusHint
import com.example.ai_develop.domain.agent.updateAgent
import com.example.ai_develop.domain.chat.Agent

/**
 * Единственный вход обновлений UI чата LLM (MVI): все изменения [LLMStateModel] проходят через [reduceLlmState].
 */
sealed interface LlmUiResult {
    data class SessionSliceUpdated(
        val agents: List<Agent>,
        val isLoading: Boolean,
        val agentActivity: AgentActivity,
        val phaseHint: PhaseStatusHint?,
        val streamingPreview: String,
        val availableToolNames: List<String>,
    ) : LlmUiResult

    data class SelectionChanged(val agentId: String) : LlmUiResult

    data class ToolsOnlyUpdated(val names: List<String>) : LlmUiResult

    data class MemoryAgentPatched(val agentId: String, val transform: (Agent) -> Agent) : LlmUiResult

    data class MemoryLoading(val isLoading: Boolean) : LlmUiResult

    data class StreamingEnabledChanged(val enabled: Boolean) : LlmUiResult

    data class SendFullHistoryChanged(val enabled: Boolean) : LlmUiResult
}

fun reduceLlmState(state: LLMStateModel, result: LlmUiResult): LLMStateModel {
    return when (result) {
        is LlmUiResult.SessionSliceUpdated -> state.copy(
            agents = result.agents,
            isLoading = result.isLoading,
            agentActivity = result.agentActivity,
            phaseHint = result.phaseHint,
            streamingPreview = result.streamingPreview,
            availableToolNames = result.availableToolNames,
        )
        is LlmUiResult.SelectionChanged -> state.copy(selectedAgentId = result.agentId)
        is LlmUiResult.ToolsOnlyUpdated -> state.copy(availableToolNames = result.names)
        is LlmUiResult.MemoryAgentPatched -> state.copy(
            agents = state.agents.updateAgent(result.agentId, result.transform),
        )
        is LlmUiResult.MemoryLoading -> state.copy(isLoading = result.isLoading)
        is LlmUiResult.StreamingEnabledChanged -> state.copy(isStreamingEnabled = result.enabled)
        is LlmUiResult.SendFullHistoryChanged -> state.copy(sendFullHistory = result.enabled)
    }
}
