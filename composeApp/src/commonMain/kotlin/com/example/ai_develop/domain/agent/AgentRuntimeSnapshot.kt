package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

/**
 * Снимок для подготовки запросов к LLM/RAG: один объект вместо разрозненных чтений репозитория и [_agent].
 *
 * @param injectWorkflowStageIntoPrompt если false — в system prompt не добавляется блок стадии workflow ([AgentEngine]).
 */
data class AgentRuntimeSnapshot(
    val agent: Agent,
    val stage: AgentStage,
    val injectWorkflowStageIntoPrompt: Boolean = true,
)
