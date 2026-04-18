package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.Mutex

/**
 * Колбэки для [ToolInvocationOrchestrator]: агент, синк с БД, активность, следующий LLM-шаг.
 */
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
        replaceLastAssistant: Boolean,
    ) -> String,
)
