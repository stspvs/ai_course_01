package com.example.ai_develop.domain

import com.example.ai_develop.data.stripLeadingJsonColonLabel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.Mutex
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
        fsm: AgentStateMachine,
        rawModelResponse: String,
        ctx: ToolInvocationContext,
    ) {
        val visitedToolCalls = mutableSetOf<String>()
        var cumulative = ""
        val stage = fsm.getCurrentState().currentStage
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
            var toolExecutionFailed = false
            val execAgent = ctx.getAgent()
            val toolResultText: String = try {
                if (execAgent == null) {
                    toolExecutionFailed = true
                    "Tool error: agent state missing."
                } else {
                    suspend fun exec(): String {
                        val r = engine.executeToolCall(execAgent, toolCall)
                        if (r != null) return r
                        toolExecutionFailed = true
                        val names = engine.registeredToolNames(execAgent)
                        val hint = if (names.isNotEmpty()) names.joinToString(", ") else "none"
                        return "Tool error: unknown tool «${toolCall.toolName}». Registered: $hint"
                    }
                    if (ctx.timing != null) {
                        ctx.timing.markTool(toolCall.toolName) { exec() }
                    } else {
                        exec()
                    }
                }
            } catch (e: Exception) {
                toolExecutionFailed = true
                "Tool error: ${e.message ?: e::class.simpleName}"
            }
            ctx.setPhaseHint(null)
            ctx.setActivity(AgentActivity.Working)

            val block = engine.formatMergedAssistantWithToolResult(
                strippedPreamble = "",
                toolName = toolCall.toolName,
                toolResult = toolResultText,
            )
            cumulative = if (cumulative.isEmpty()) block else "$cumulative\n\n$block"

            ctx.processingMutex.withLock {
                ctx.updateAgent { agent ->
                    val msgs = agent?.messages ?: return@updateAgent agent
                    val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                    if (lastAssistant == null) {
                        val fallback = ctx.createMessage(
                            "system",
                            blocksWithProse(cumulative),
                            msgs.lastOrNull()?.id,
                            stage,
                            null,
                        )
                        return@updateAgent agent.copy(messages = msgs + fallback)
                    }
                    val display = blocksWithProse(cumulative)
                    val newTokens = estimateTokens(display)
                    val updated = msgs.map { msg ->
                        if (msg.id == lastAssistant.id) {
                            msg.copy(message = display, tokensUsed = newTokens)
                        } else msg
                    }
                    agent.copy(messages = updated)
                }
            }
            ctx.getAgent()?.let { ctx.syncWithRepository(it) }

            if (toolExecutionFailed) break
        }
    }

    suspend fun runToolChainLoop(
        collector: FlowCollector<String>,
        fsm: AgentStateMachine,
        initialResponseText: String,
        ctx: ToolInvocationContext,
    ) {
        var rawToolSourceText = initialResponseText
        var toolCall = engine.parseToolCall(rawToolSourceText)
        var iterations = 0
        val visitedToolCalls = mutableSetOf<String>()
        var lastMergedAssistantBody: String? = null
        var mergedBodyBeforeLlmFollowUp: String? = null

        while (toolCall != null && iterations < maxToolChainIterations) {
            val tcLoop = toolCall!!
            val callKey = "${tcLoop.toolName}\u0000${tcLoop.input.trim()}"
            if (!visitedToolCalls.add(callKey)) {
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
                break
            }

            ctx.setPhaseHint(PhaseStatusHint.Tool(tcLoop.toolName))
            ctx.setActivity(AgentActivity.RunningTool(tcLoop.toolName))
            var toolExecutionFailed = false
            val agentForExec = ctx.getAgent()
            val toolResultText: String = try {
                if (agentForExec == null) {
                    toolExecutionFailed = true
                    "Tool error: agent state missing."
                } else {
                    suspend fun exec(): String {
                        val r = engine.executeToolCall(agentForExec, tcLoop)
                        if (r != null) return r
                        toolExecutionFailed = true
                        val names = engine.registeredToolNames(agentForExec)
                        val hint = if (names.isNotEmpty()) names.joinToString(", ") else "none"
                        return "Tool error: unknown tool «${tcLoop.toolName}». Registered: $hint"
                    }
                    if (ctx.timing != null) {
                        ctx.timing.markTool(tcLoop.toolName) { exec() }
                    } else {
                        exec()
                    }
                }
            } catch (e: Exception) {
                toolExecutionFailed = true
                "Tool error: ${e.message ?: e::class.simpleName}"
            }
            ctx.setPhaseHint(null)
            ctx.setActivity(AgentActivity.Working)

            ctx.processingMutex.withLock {
                ctx.updateAgent { agent ->
                    val msgs = agent?.messages ?: return@updateAgent agent
                    val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                    if (lastAssistant == null) {
                        val fallback = ctx.createMessage(
                            "system",
                            "Tool Result: ${stripLeadingJsonColonLabel(toolResultText)}",
                            msgs.lastOrNull()?.id,
                            fsm.getCurrentState().currentStage,
                            null,
                        )
                        return@updateAgent agent.copy(messages = msgs + fallback)
                    }
                    val block = engine.formatMergedAssistantWithToolResult(
                        strippedPreamble = "",
                        toolName = tcLoop.toolName,
                        toolResult = toolResultText,
                    )
                    val merged = lastMergedAssistantBody?.let { prev ->
                        "${prev.trimEnd()}\n\n$block"
                    } ?: block
                    lastMergedAssistantBody = merged
                    val newTokens = estimateTokens(merged)
                    val updated = msgs.map { msg ->
                        if (msg.id == lastAssistant.id) {
                            msg.copy(message = merged, tokensUsed = newTokens)
                        } else msg
                    }
                    agent.copy(messages = updated)
                }
            }
            ctx.getAgent()?.let { ctx.syncWithRepository(it) }

            rawToolSourceText = engine.stripFirstToolInvocation(rawToolSourceText)
            val nextToolInSameLlmResponse = engine.parseToolCall(rawToolSourceText.trim())
            if (nextToolInSameLlmResponse != null) {
                toolCall = nextToolInSameLlmResponse
                iterations++
                continue
            }

            if (toolExecutionFailed) {
                break
            }

            val agentSnapshot = ctx.getAgent() ?: break
            mergedBodyBeforeLlmFollowUp = lastMergedAssistantBody
            lastMergedAssistantBody = null
            ctx.setPhaseHint(PhaseStatusHint.AwaitingLlm)
            val responseFromLlm = ctx.executeStreamingStep(
                collector,
                agentSnapshot,
                fsm.getCurrentState().currentStage,
                true,
            )
            ctx.setPhaseHint(null)
            ctx.getAgent()?.let { ctx.syncWithRepository(it) }
            rawToolSourceText = responseFromLlm
            toolCall = engine.parseToolCall(rawToolSourceText)
            iterations++
        }
    }
}

class ToolInvocationContext(
    val processingMutex: Mutex,
    val timing: PhaseTimingCollector?,
    val getAgent: () -> Agent?,
    val updateAgent: suspend ((Agent?) -> Agent?) -> Unit,
    val syncWithRepository: suspend (Agent) -> Unit,
    val setActivity: (AgentActivity) -> Unit,
    val setPhaseHint: (PhaseStatusHint?) -> Unit,
    val createMessage: (
        role: String,
        content: String,
        parentId: String?,
        agentStage: AgentStage,
        llmSnapshot: LlmRequestSnapshot?,
    ) -> ChatMessage,
    val executeStreamingStep: suspend (
        collector: FlowCollector<String>,
        agent: Agent,
        stage: AgentStage,
        replaceLastAssistant: Boolean,
    ) -> String,
)
