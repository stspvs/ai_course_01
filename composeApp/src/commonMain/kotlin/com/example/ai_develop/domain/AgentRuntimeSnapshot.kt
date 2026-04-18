package com.example.ai_develop.domain

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
