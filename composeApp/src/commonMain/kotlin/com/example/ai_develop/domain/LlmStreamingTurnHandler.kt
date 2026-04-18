package com.example.ai_develop.domain

import com.example.ai_develop.data.RagStructuredParseResult
import com.example.ai_develop.data.processRagAssistantRawJson
import com.example.ai_develop.data.stripLeadingJsonColonLabel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Один шаг стриминга LLM по [PreparedLlmRequest] и запись assistant-сообщения.
 */
class LlmStreamingTurnHandler(
    private val engine: AgentEngine,
) {
    suspend fun executeStreamingStepWithPrepared(
        collector: FlowCollector<String>,
        agent: Agent,
        stage: AgentStage,
        prepared: PreparedLlmRequest,
        replaceLastAssistant: Boolean,
        processingMutex: Mutex,
        timing: PhaseTimingCollector?,
        getAgent: () -> Agent?,
        updateAgent: suspend ((Agent?) -> Agent?) -> Unit,
        setActivity: (AgentActivity) -> Unit,
        onDeliveryChange: (AgentTextDelivery) -> Unit,
        onStreamingChunk: (String) -> Unit,
        onRagJsonParsed: suspend () -> Unit,
        createMessage: (
            role: String,
            content: String,
            parentId: String?,
            agentStage: AgentStage,
            llmSnapshot: LlmRequestSnapshot?,
        ) -> ChatMessage,
    ): String {
        val sb = StringBuilder()
        val ragJsonMode = prepared.snapshot.isJsonMode && prepared.snapshot.ragAttribution != null
        onDeliveryChange(
            if (ragJsonMode) AgentTextDelivery.BufferingFullBody
            else AgentTextDelivery.StreamingIncremental,
        )
        setActivity(AgentActivity.Streaming)
        try {
            suspend fun consumeStream() {
                engine.streamFromPrepared(agent, prepared).collect { chunk ->
                    sb.append(chunk)
                    if (!ragJsonMode) {
                        timing?.onFirstTokenIfNeeded()
                        collector.emit(chunk)
                        onStreamingChunk(chunk)
                    } else {
                        timing?.onFirstTokenIfNeeded()
                    }
                }
            }
            if (timing != null) {
                timing.markLlmRound { consumeStream() }
            } else {
                consumeStream()
            }
        } catch (e: Exception) {
            onDeliveryChange(AgentTextDelivery.Error)
            throw e
        } finally {
            setActivity(AgentActivity.Working)
            onDeliveryChange(AgentTextDelivery.Idle)
        }

        val rawOut = sb.toString()
        val processedRag = if (ragJsonMode) {
            if (timing != null) {
                timing.markRagJsonParse {
                    processRagAssistantRawJson(rawOut, prepared.snapshot.ragAttribution)
                }
            } else {
                processRagAssistantRawJson(rawOut, prepared.snapshot.ragAttribution)
            }
        } else {
            null
        }
        var content = processedRag?.formattedChatText
            ?: stripLeadingJsonColonLabel(rawOut)
        if (content.isBlank()) {
            val warn = processedRag?.parseWarning?.takeIf { it.isNotBlank() }
            content = when {
                warn != null -> warn
                rawOut.isBlank() -> "Пустой ответ модели."
                else -> "Ответ не содержит распознаваемого текста."
            }
        }
        if (ragJsonMode) {
            collector.emit(content)
            onRagJsonParsed()
        }

        processingMutex.withLock {
            val msgs = getAgent()?.messages ?: return@withLock
            val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
            val snapshotForMessage = snapshotWithRagStructured(prepared.snapshot, processedRag)
            if (replaceLastAssistant && lastAssistant != null) {
                val newTokens = estimateTokens(content)
                val updated = msgs.map { msg ->
                    if (msg.id == lastAssistant.id) {
                        msg.copy(
                            message = content,
                            tokensUsed = newTokens,
                            llmRequestSnapshot = snapshotForMessage,
                        )
                    } else msg
                }
                updateAgent { it?.copy(messages = updated) }
            } else {
                val parentId = msgs.lastOrNull()?.id
                val aiMsg = createMessage(
                    "assistant",
                    content,
                    parentId,
                    stage,
                    snapshotForMessage,
                )
                updateAgent { it?.copy(messages = it.messages + aiMsg) }
            }
        }

        return rawOut
    }

    private fun snapshotWithRagStructured(
        base: LlmRequestSnapshot,
        processedRag: RagStructuredParseResult?,
    ): LlmRequestSnapshot {
        val p = processedRag?.structuredPayload ?: return base
        return base.copy(ragStructuredContent = p)
    }
}
