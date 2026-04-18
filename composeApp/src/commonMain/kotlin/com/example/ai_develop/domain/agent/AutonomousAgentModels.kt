package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.serialization.Serializable

/** Как сейчас доставляется текст ответа в UI (согласовано с [AutonomousAgentUiState]). */
@Serializable
sealed class AgentTextDelivery {
    @Serializable
    data object Idle : AgentTextDelivery()

    @Serializable
    data object StreamingIncremental : AgentTextDelivery()

    @Serializable
    data object BufferingFullBody : AgentTextDelivery()

    @Serializable
    data object Error : AgentTextDelivery()
}

fun phaseStatusHintLabel(hint: PhaseStatusHint): String = when (hint) {
    is PhaseStatusHint.Rag -> hint.label
    is PhaseStatusHint.Tool -> "Выполняется инструмент: ${hint.name}"
    PhaseStatusHint.AwaitingLlm -> "Ожидание ответа модели…"
    is PhaseStatusHint.Rewrite -> hint.label
}

@Serializable
sealed class PhaseStatusHint {
    @Serializable
    data class Rag(val label: String = "Поиск в базе знаний…") : PhaseStatusHint()

    @Serializable
    data class Tool(val name: String) : PhaseStatusHint()

    @Serializable
    data object AwaitingLlm : PhaseStatusHint()

    @Serializable
    data class Rewrite(val label: String = "Уточнение запроса…") : PhaseStatusHint()
}

@Serializable
data class ToolPhaseDuration(
    val toolName: String,
    val ms: Long,
)

/**
 * Длительности этапов одного пользовательского сообщения (детали по тапу на сообщение).
 */
@Serializable
data class AgentPhaseTimings(
    val prepareRequestMs: Long = 0L,
    val ragRewriteMs: Long = 0L,
    val ragRetrieveMs: Long = 0L,
    val llmRoundMs: List<Long> = emptyList(),
    val toolDurations: List<ToolPhaseDuration> = emptyList(),
    val ragJsonParseMs: Long = 0L,
    val totalMs: Long = 0L,
    val firstTokenMs: Long? = null,
)

/**
 * Единый публичный снимок агента для UI.
 */
data class AutonomousAgentUiState(
    val agent: Agent? = null,
    val isProcessing: Boolean = false,
    val agentActivity: AgentActivity = AgentActivity.Idle,
    val delivery: AgentTextDelivery = AgentTextDelivery.Idle,
    val streamingPreview: String = "",
    val phaseHint: PhaseStatusHint? = null,
    val lastStreamError: String? = null,
    val currentRunTimings: AgentPhaseTimings? = null,
    val lastCompletedTimings: AgentPhaseTimings? = null,
)

/** Результат одного раунда LLM: сырой текст для парсинга инструментов и текст для пользователя. */
data class LlmStepResult(
    val rawModelText: String,
    val displayAssistantText: String,
)

class PhaseTimingCollector {
    private val startNs = System.nanoTime()
    private val llmRounds = mutableListOf<Long>()
    private val toolDurations = mutableListOf<ToolPhaseDuration>()
    var prepareRequestMs: Long = 0L
    var ragRewriteMs: Long = 0L
    var ragRetrieveMs: Long = 0L
    var ragJsonParseMs: Long = 0L
    var firstTokenMs: Long? = null
    private var firstTokenRecorded = false

    suspend fun <T> markPrepareRequest(block: suspend () -> T): T {
        val t = nanoToMs(System.nanoTime())
        val r = block()
        prepareRequestMs += nanoToMs(System.nanoTime()) - t
        return r
    }

    suspend fun <T> markRagRewrite(block: suspend () -> T): T {
        val t = nanoToMs(System.nanoTime())
        val r = block()
        ragRewriteMs += nanoToMs(System.nanoTime()) - t
        return r
    }

    suspend fun <T> markRagRetrieve(block: suspend () -> T): T {
        val t = nanoToMs(System.nanoTime())
        val r = block()
        ragRetrieveMs += nanoToMs(System.nanoTime()) - t
        return r
    }

    suspend fun markLlmRound(block: suspend () -> Unit) {
        val t = nanoToMs(System.nanoTime())
        block()
        llmRounds.add(nanoToMs(System.nanoTime()) - t)
    }

    suspend fun <T> markTool(toolName: String, block: suspend () -> T): T {
        val t = nanoToMs(System.nanoTime())
        val r = block()
        toolDurations.add(ToolPhaseDuration(toolName, nanoToMs(System.nanoTime()) - t))
        return r
    }

    suspend fun <T> markRagJsonParse(block: suspend () -> T): T {
        val t = nanoToMs(System.nanoTime())
        val r = block()
        ragJsonParseMs += nanoToMs(System.nanoTime()) - t
        return r
    }

    fun onFirstTokenIfNeeded() {
        if (!firstTokenRecorded) {
            firstTokenRecorded = true
            firstTokenMs = nanoToMs(System.nanoTime()) - nanoToMs(startNs)
        }
    }

    fun build(): AgentPhaseTimings {
        val total = nanoToMs(System.nanoTime()) - nanoToMs(startNs)
        return AgentPhaseTimings(
            prepareRequestMs = prepareRequestMs,
            ragRewriteMs = ragRewriteMs,
            ragRetrieveMs = ragRetrieveMs,
            llmRoundMs = llmRounds.toList(),
            toolDurations = toolDurations.toList(),
            ragJsonParseMs = ragJsonParseMs,
            totalMs = total,
            firstTokenMs = firstTokenMs,
        )
    }

    private fun nanoToMs(ns: Long): Long = ns / 1_000_000
}

suspend fun <T> PhaseTimingCollector?.ragRewriteTimed(block: suspend () -> T): T =
    when (this) {
        null -> block()
        else -> markRagRewrite(block)
    }

suspend fun <T> PhaseTimingCollector?.ragRetrieveTimed(block: suspend () -> T): T =
    when (this) {
        null -> block()
        else -> markRagRetrieve(block)
    }
