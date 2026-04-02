package com.example.ai_develop.domain

import kotlinx.serialization.Serializable

enum class AgentStage {
    PLANNING, EXECUTION, REVIEW, DONE
}

@Serializable
data class AgentStep(
    val id: String,
    val description: String,
    val isCompleted: Boolean = false,
    val output: String? = null
)

@Serializable
data class AgentPlan(
    val steps: List<AgentStep> = emptyList()
)

@Serializable
data class Invariant(
    val id: String,
    val rule: String,
    val stage: AgentStage,
    val isActive: Boolean = true
)

@Serializable
data class AgentProfile(
    val name: String = "",
    val about: String = "",
    val style: String = "",
    val globalInstructions: String = "",
    val constraints: List<String> = emptyList(),
    val preferences: Map<String, String> = emptyMap(),
    val globalFacts: List<String> = emptyList(),
    val memoryModelProvider: LLMProvider? = null
)

@Serializable
data class AgentState(
    val agentId: String,
    val currentStage: AgentStage,
    val currentStepId: String?,
    val plan: AgentPlan
)
