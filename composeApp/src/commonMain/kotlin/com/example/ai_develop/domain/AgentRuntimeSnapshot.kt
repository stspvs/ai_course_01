package com.example.ai_develop.domain

/**
 * Снимок для подготовки запросов к LLM/RAG: один объект вместо разрозненных чтений репозитория и [_agent].
 */
data class AgentRuntimeSnapshot(
    val agent: Agent,
    val stage: AgentStage,
)
