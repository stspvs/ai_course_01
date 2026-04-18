package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

/**
 * Режим инференса: прямой LLM или конвейер с RAG-подготовкой ([RagAwareChatRequestPreparer]).
 * На один шаг активна одна стратегия по [Agent.ragEnabled] (см. [AutonomousAgent]).
 */
interface AgentInferenceStrategy {
    suspend fun prepareLlmRequest(
        snapshot: AgentRuntimeSnapshot,
        timing: PhaseTimingCollector?,
    ): PreparedLlmRequest
}

internal class DirectChatInferenceStrategy(
    private val engine: AgentEngine,
) : AgentInferenceStrategy {
    override suspend fun prepareLlmRequest(
        snapshot: AgentRuntimeSnapshot,
        timing: PhaseTimingCollector?,
    ): PreparedLlmRequest {
        val wrapped = if (timing != null) {
            timing.markPrepareRequest {
                engine.prepareChatRequest(
                    snapshot.agent,
                    snapshot.stage,
                    isJsonMode = false,
                    injectWorkflowStageIntoPrompt = snapshot.injectWorkflowStageIntoPrompt,
                )
            }
        } else {
            engine.prepareChatRequest(
                snapshot.agent,
                snapshot.stage,
                isJsonMode = false,
                injectWorkflowStageIntoPrompt = snapshot.injectWorkflowStageIntoPrompt,
            )
        }
        return wrapped
    }
}

internal class RagAugmentedInferenceStrategy(
    private val preparer: RagAwareChatRequestPreparer,
) : AgentInferenceStrategy {
    override suspend fun prepareLlmRequest(
        snapshot: AgentRuntimeSnapshot,
        timing: PhaseTimingCollector?,
    ): PreparedLlmRequest = preparer.prepare(snapshot, timing)
}

internal fun inferenceStrategyFor(
    agent: Agent,
    direct: DirectChatInferenceStrategy,
    rag: RagAugmentedInferenceStrategy,
): AgentInferenceStrategy = if (agent.ragEnabled) rag else direct
