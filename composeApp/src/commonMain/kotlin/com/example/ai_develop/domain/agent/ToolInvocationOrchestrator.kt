package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import com.example.ai_develop.data.stripLeadingJsonColonLabel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.withLock

/**
 * Цепочка вызовов инструментов из ответа модели и merge результатов в сообщения.
 *
 * Инвариант: **retrieval / RAG не вызываются** из этого класса — только парсинг `rawModelResponse`,
 * исполнение tool и следующий раунд LLM через [ToolInvocationContext.executeStreamingStep] с тем же
 * [AgentInferenceStrategy], что и до tool-фазы.
 */
class ToolInvocationOrchestrator(
    private val engine: AgentEngine,
    private val maxToolChainIterations: Int = 32,
) {
    suspend fun runSuppressOnlyToolSequence(
        calls: List<ParsedToolCall>,
        currentStage: () -> AgentStage,
        rawModelResponse: String,
        ctx: ToolInvocationContext,
    ) {
        val visitedToolCalls = mutableSetOf<String>()
        var cumulative = ""
        val stage = currentStage()
        val proseFromModel = engine.stripToolSyntaxFromAssistantText(rawModelResponse).trim()

        fun blocksWithProse(toolBlocks: String): String {
            return when {
                proseFromModel.isEmpty() -> toolBlocks
                toolBlocks.isEmpty() -> proseFromModel
                else -> "$proseFromModel\n\n$toolBlocks"
            }
        }

        for (toolCall in calls) {
            val callKey = "${toolCall.toolName}\u0000${toolCall.input.trim()}"
            if (!visitedToolCalls.add(callKey)) continue

            ctx.setPhaseHint(PhaseStatusHint.Tool(toolCall.toolName))
            ctx.setActivity(AgentActivity.RunningTool(toolCall.toolName))
            val outcome = executeToolCallWithOutcome(toolCall, ctx.getAgent(), ctx.timing)
            ctx.setPhaseHint(null)
            ctx.setActivity(AgentActivity.Working)

            val block = engine.formatMergedAssistantWithToolResult(
                strippedPreamble = "",
                toolName = toolCall.toolName,
                toolResult = outcome.resultText,
            )
            cumulative = if (cumulative.isEmpty()) block else "$cumulative\n\n$block"
            val display = blocksWithProse(cumulative)
            applySuppressAssistantMerge(ctx, stage, display)
            ctx.getAgent()?.let { ctx.syncWithRepository(it) }

            if (outcome.failed) break
        }
    }

    suspend fun runToolChainLoop(
        collector: FlowCollector<String>,
        currentStage: () -> AgentStage,
        initialResponseText: String,
        ctx: ToolInvocationContext,
    ) {
        val state = ToolChainLoopState.initial(engine, initialResponseText)

        while (state.toolCall != null && state.iterations < maxToolChainIterations) {
            val tcLoop = state.toolCall!!
            val callKey = "${tcLoop.toolName}\u0000${tcLoop.input.trim()}"
            if (!state.visitedToolCalls.add(callKey)) {
                recoverFromDuplicateToolCall(
                    ctx,
                    state.mergedBodyBeforeLlmFollowUp,
                    state.lastMergedAssistantBody,
                )
                break
            }

            ctx.setPhaseHint(PhaseStatusHint.Tool(tcLoop.toolName))
            ctx.setActivity(AgentActivity.RunningTool(tcLoop.toolName))
            val outcome = executeToolCallWithOutcome(tcLoop, ctx.getAgent(), ctx.timing)
            ctx.setPhaseHint(null)
            ctx.setActivity(AgentActivity.Working)

            state.lastMergedAssistantBody = mergeChainIterationIntoAssistant(
                ctx,
                currentStage,
                tcLoop,
                outcome.resultText,
                state.lastMergedAssistantBody,
            )
            ctx.getAgent()?.let { ctx.syncWithRepository(it) }

            state.rawToolSourceText = engine.stripFirstToolInvocation(state.rawToolSourceText)
            val nextToolInSameLlmResponse =
                engine.parseToolCall(state.rawToolSourceText.trim())
            if (nextToolInSameLlmResponse != null) {
                state.toolCall = nextToolInSameLlmResponse
                state.iterations++
                continue
            }

            if (outcome.failed) {
                break
            }

            if (ctx.getAgent() == null) break
            state.mergedBodyBeforeLlmFollowUp = state.lastMergedAssistantBody
            state.lastMergedAssistantBody = null
            ctx.setPhaseHint(PhaseStatusHint.AwaitingLlm)
            val responseFromLlm = ctx.executeStreamingStep(
                collector,
                true,
            )
            ctx.setPhaseHint(null)
            ctx.getAgent()?.let { ctx.syncWithRepository(it) }
            state.rawToolSourceText = responseFromLlm
            state.toolCall = engine.parseToolCall(state.rawToolSourceText)
            state.iterations++
        }
    }

    private data class ToolExecutionOutcome(
        val resultText: String,
        val failed: Boolean,
    )

    private suspend fun executeToolCallWithOutcome(
        toolCall: ParsedToolCall,
        agent: Agent?,
        timing: PhaseTimingCollector?,
    ): ToolExecutionOutcome {
        if (agent == null) {
            return ToolExecutionOutcome(
                resultText = "Tool error: agent state missing.",
                failed = true,
            )
        }
        return try {
            suspend fun exec(): ToolExecutionOutcome {
                val r = engine.executeToolCall(agent, toolCall)
                if (r != null) return ToolExecutionOutcome(r, failed = false)
                val names = engine.registeredToolNames(agent)
                val hint = if (names.isNotEmpty()) names.joinToString(", ") else "none"
                return ToolExecutionOutcome(
                    resultText = "Tool error: unknown tool «${toolCall.toolName}». Registered: $hint",
                    failed = true,
                )
            }
            if (timing != null) {
                timing.markTool(toolCall.toolName) { exec() }
            } else {
                exec()
            }
        } catch (e: Exception) {
            ToolExecutionOutcome(
                resultText = "Tool error: ${e.message ?: e::class.simpleName}",
                failed = true,
            )
        }
    }

    private suspend fun applySuppressAssistantMerge(
        ctx: ToolInvocationContext,
        stage: AgentStage,
        displayText: String,
    ) {
        ctx.processingMutex.withLock {
            ctx.updateAgent { agent ->
                val msgs = agent?.messages ?: return@updateAgent agent
                val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                if (lastAssistant == null) {
                    val fallback = ctx.createMessage(
                        "system",
                        displayText,
                        msgs.lastOrNull()?.id,
                        stage,
                        null,
                    )
                    return@updateAgent agent.copy(messages = msgs + fallback)
                }
                val newTokens = estimateTokens(displayText)
                val updated = msgs.map { msg ->
                    if (msg.id == lastAssistant.id) {
                        msg.copy(message = displayText, tokensUsed = newTokens)
                    } else msg
                }
                agent.copy(messages = updated)
            }
        }
    }

    /**
     * Повтор того же tool+input: убрать tool-синтаксис из последнего assistant, при пустоте — fallback или удалить сообщение.
     */
    private suspend fun recoverFromDuplicateToolCall(
        ctx: ToolInvocationContext,
        mergedBodyBeforeLlmFollowUp: String?,
        lastMergedAssistantBody: String?,
    ) {
        ctx.processingMutex.withLock {
            ctx.updateAgent { agent ->
                val msgs = agent?.messages ?: return@updateAgent agent
                val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                    ?: return@updateAgent agent
                val cleaned = engine.stripToolSyntaxFromAssistantText(lastAssistant.message)
                val trimmed = cleaned.trim()
                if (trimmed.isEmpty()) {
                    val fallback = mergedBodyBeforeLlmFollowUp?.trim().orEmpty()
                        .ifEmpty { lastMergedAssistantBody?.trim().orEmpty() }
                    if (fallback.isNotEmpty()) {
                        val newTokens = estimateTokens(fallback)
                        agent.copy(
                            messages = msgs.map { msg ->
                                if (msg.id == lastAssistant.id) {
                                    msg.copy(message = fallback, tokensUsed = newTokens)
                                } else msg
                            },
                        )
                    } else {
                        agent.copy(messages = msgs.filter { it.id != lastAssistant.id })
                    }
                } else {
                    val newTokens = estimateTokens(trimmed)
                    agent.copy(
                        messages = msgs.map { msg ->
                            if (msg.id == lastAssistant.id) {
                                msg.copy(message = trimmed, tokensUsed = newTokens)
                            } else msg
                        },
                    )
                }
            }
        }
        ctx.getAgent()?.let { ctx.syncWithRepository(it) }
    }

    private suspend fun mergeChainIterationIntoAssistant(
        ctx: ToolInvocationContext,
        currentStage: () -> AgentStage,
        toolCall: ParsedToolCall,
        toolResultText: String,
        lastMergedAssistantBody: String?,
    ): String? {
        var resultBody: String? = lastMergedAssistantBody
        ctx.processingMutex.withLock {
            ctx.updateAgent { agent ->
                val msgs = agent?.messages ?: return@updateAgent agent
                val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                if (lastAssistant == null) {
                    val fallback = ctx.createMessage(
                        "system",
                        "Tool Result: ${stripLeadingJsonColonLabel(toolResultText)}",
                        msgs.lastOrNull()?.id,
                        currentStage(),
                        null,
                    )
                    resultBody = lastMergedAssistantBody
                    return@updateAgent agent.copy(messages = msgs + fallback)
                }
                val block = engine.formatMergedAssistantWithToolResult(
                    strippedPreamble = "",
                    toolName = toolCall.toolName,
                    toolResult = toolResultText,
                )
                val merged = lastMergedAssistantBody?.let { prev ->
                    "${prev.trimEnd()}\n\n$block"
                } ?: block
                resultBody = merged
                val newTokens = estimateTokens(merged)
                val updated = msgs.map { msg ->
                    if (msg.id == lastAssistant.id) {
                        msg.copy(message = merged, tokensUsed = newTokens)
                    } else msg
                }
                agent.copy(messages = updated)
            }
        }
        return resultBody
    }

    private class ToolChainLoopState(
        var rawToolSourceText: String,
        var toolCall: ParsedToolCall?,
        var iterations: Int,
        val visitedToolCalls: MutableSet<String> = mutableSetOf(),
        var lastMergedAssistantBody: String? = null,
        var mergedBodyBeforeLlmFollowUp: String? = null,
    ) {
        companion object {
            fun initial(engine: AgentEngine, initialResponseText: String) = ToolChainLoopState(
                rawToolSourceText = initialResponseText,
                toolCall = engine.parseToolCall(initialResponseText),
                iterations = 0,
            )
        }
    }
}
