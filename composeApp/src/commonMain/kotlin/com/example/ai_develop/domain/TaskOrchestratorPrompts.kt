package com.example.ai_develop.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TaskOrchestratorPrompts {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun executorUserContent(
        plan: PlanResult,
        stepIndex: Int,
        lastVerification: VerificationResult?,
        workingMemory: String?,
        lastExecution: ExecutionResult? = null
    ): String = buildString {
        val totalSteps = plan.steps.size.coerceAtLeast(1)
        appendLine("=== CURRENT STEP INDEX ===")
        appendLine("${stepIndex + 1} / $totalSteps (0-based index: $stepIndex)")
        appendLine()
        val stepText = plan.steps.getOrNull(stepIndex)?.trim().orEmpty()
        appendLine("=== CURRENT STEP (execute only this; deliverables go into JSON \"output\") ===")
        appendLine(if (stepText.isNotEmpty()) stepText else "(empty step at index $stepIndex)")
        appendLine()
        if (!workingMemory.isNullOrBlank()) {
            appendLine("=== TASK WORKING MEMORY ===")
            appendLine(workingMemory)
            appendLine()
        }
        if (lastVerification != null && lastExecution != null) {
            appendLine(
                "=== LAST EXECUTION RESULT (submitted before this feedback; what the inspector reviewed, JSON) ==="
            )
            appendLine(json.encodeToString(ExecutionResult.serializer(), lastExecution))
            appendLine()
        }
        if (lastVerification != null) {
            appendLine(
                "=== INSPECTOR FEEDBACK (same plan step — address issues/suggestions before re-submitting if not successful) ==="
            )
            appendLine("success: ${lastVerification.success}")
            val issues = lastVerification.issues.orEmpty()
            val suggestions = lastVerification.suggestions.orEmpty()
            if (issues.isNotEmpty()) {
                appendLine("Issues:")
                issues.forEach { appendLine("- $it") }
            }
            if (suggestions.isNotEmpty()) {
                appendLine("Suggestions:")
                suggestions.forEach { appendLine("- $it") }
            }
            if (!lastVerification.success && issues.isEmpty() && suggestions.isEmpty()) {
                appendLine(
                    "(Inspector reported success=false but issues/suggestions are empty. " +
                        "Fix the implementation to match CURRENT STEP above.)"
                )
            }
            appendLine()
        }
        appendLine(
            "Execute only the current step. " +
                "For implementation: put full code/config as text inside JSON \"output\" (markdown fences allowed inside the string); " +
                "do not instruct saving or creating files — text-only deliverable. " +
                "Respond with JSON only: {\"success\":true/false,\"output\":\"...\",\"errors\":[\"...\"]}"
        )
    }

    fun inspectorUserContent(
        plan: PlanResult,
        stepIndex: Int,
        execution: ExecutionResult,
        successCriteria: String,
        lastVerification: VerificationResult? = null
    ): String = buildString {
        appendLine("=== PLAN (structured) ===")
        appendLine(json.encodeToString(PlanResult.serializer(), plan))
        appendLine()
        val totalSteps = plan.steps.size.coerceAtLeast(1)
        val idx = stepIndex.coerceIn(0, (totalSteps - 1).coerceAtLeast(0))
        val stepText = plan.steps.getOrNull(idx)?.trim().orEmpty()
        appendLine("=== CURRENT STEP INDEX (verify this step only, unless noted below) ===")
        appendLine("${idx + 1} / $totalSteps (0-based index: $idx)")
        appendLine()
        appendLine("=== CURRENT STEP (scope of this verification) ===")
        appendLine(if (stepText.isNotEmpty()) stepText else "(empty step at index $idx)")
        appendLine()
        if (lastVerification != null) {
            appendLine("=== PREVIOUS VERIFICATION (last inspector verdict on this step, JSON) ===")
            appendLine(json.encodeToString(VerificationResult.serializer(), lastVerification))
            appendLine()
        }
        appendLine("=== EXECUTION RESULT (executor deliverable for this step, JSON) ===")
        appendLine(json.encodeToString(ExecutionResult.serializer(), execution))
        appendLine()
        appendLine("=== SUCCESS CRITERIA (overall) ===")
        appendLine(successCriteria)
        appendLine()
        val isFinalStep = idx >= plan.steps.lastIndex.coerceAtLeast(0)
        appendLine("=== VERIFICATION RULES ===")
        appendLine(
            "The Executor was instructed to deliver ONLY the CURRENT STEP above, not the whole plan at once."
        )
        appendLine(
            "- You MUST set \"success\": true if the execution adequately fulfills CURRENT STEP. " +
                "Do NOT fail because work that belongs to other plan steps (later or earlier) is missing, " +
                "unless CURRENT STEP explicitly requires that work in its text."
        )
        appendLine(
            "- List issues only when the output fails CURRENT STEP (wrong deliverable, broken code, step text not addressed)."
        )
        if (isFinalStep) {
            appendLine(
                "- This is the FINAL plan step: also check whether SUCCESS CRITERIA (overall) appear satisfied; " +
                    "if not, include that in issues or suggestions."
            )
        } else {
            appendLine(
                "- This is NOT the final plan step: do NOT require full end-to-end SUCCESS CRITERIA or later-step deliverables."
            )
        }
        appendLine()
        appendLine("Respond with JSON: {\"success\":true/false,\"issues\":[\"...\"],\"suggestions\":[\"...\"]}")
    }
}
