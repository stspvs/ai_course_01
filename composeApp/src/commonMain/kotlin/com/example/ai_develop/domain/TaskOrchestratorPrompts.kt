package com.example.ai_develop.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TaskOrchestratorPrompts {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Отдельный блок для конца **системного** промпта планировщика, исполнителя и инспектора.
     * Пустой список — пустая строка.
     */
    fun taskInvariantsSystemAppendix(invariants: List<TaskInvariant>): String {
        if (invariants.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine(
                "=== TASK INVARIANTS (fixed rules for this task; apply in planning, execution, and verification) ==="
            )
            appendLine(
                "Все правила действуют одновременно (логическое И). Негативный инвариант запрещает то, что в тексте; " +
                    "позитивный — требует, чтобы формулировка была верна. Несколько инвариантов могут разными словами " +
                    "описывать одно ограничение — это не противоречие, а разные углы проверки."
            )
            appendLine()
            appendTaskInvariantNumberedLines(invariants)
        }
    }

    private fun StringBuilder.appendTaskInvariantNumberedLines(invariants: List<TaskInvariant>) {
        invariants.forEachIndexed { i, inv ->
            val pol = when (inv.polarity) {
                InvariantPolarity.POSITIVE ->
                    "позитивный: описанное ниже должно выполняться (должно быть верно)"
                InvariantPolarity.NEGATIVE ->
                    "негативный: описанного ниже не должно быть (запрет; например, не та архитектура)"
            }
            appendLine("${i + 1}. ($pol) ${inv.text.trim()}")
        }
    }

    fun executorUserContent(
        plan: PlanResult,
        stepIndex: Int,
        lastVerification: VerificationResult?,
        workingMemory: String?,
        lastExecution: ExecutionResult? = null,
        /** True when [lastExecution]/[lastVerification] describe the **previous** plan step (carry-over after success). */
        isFeedbackFromPreviousCompletedStep: Boolean = false
    ): String = buildString {
        val totalSteps = plan.steps.size.coerceAtLeast(1)
        appendLine("=== CURRENT STEP INDEX ===")
        appendLine("${stepIndex + 1} / $totalSteps (0-based index: $stepIndex)")
        appendLine()
        val stepText = plan.steps.getOrNull(stepIndex)?.trim().orEmpty()
        appendLine("=== CURRENT STEP (execute only this; deliverables go into JSON \"output\") ===")
        appendLine(if (stepText.isNotEmpty()) stepText else "(empty step at index $stepIndex)")
        appendLine()
        appendLine("=== PLAN (structured) ===")
        appendLine(json.encodeToString(PlanResult.serializer(), plan))
        appendLine()
        if (!workingMemory.isNullOrBlank()) {
            appendLine("=== TASK WORKING MEMORY ===")
            appendLine(workingMemory)
            appendLine()
        }
        // Блоки независимы: при повторе после провала верификации оба должны быть; при отсутствии одного из полей
        // в состоянии второй блок всё равно показывается (не требуем lastVerification для вывода lastExecution).
        if (lastExecution != null) {
            val execHeader =
                when {
                    isFeedbackFromPreviousCompletedStep ->
                        "=== LAST EXECUTION RESULT (previous completed plan step — context only; your assignment is CURRENT STEP above, JSON) ==="
                    lastVerification != null ->
                        "=== LAST EXECUTION RESULT (submitted before this feedback; what the inspector reviewed, JSON) ==="
                    else ->
                        "=== LAST EXECUTION RESULT (most recent deliverable for this step, JSON) ==="
                }
            appendLine(execHeader)
            appendLine(json.encodeToString(ExecutionResult.serializer(), lastExecution))
            appendLine()
        }
        if (lastVerification != null) {
            appendLine(
                if (isFeedbackFromPreviousCompletedStep) {
                    "=== INSPECTOR FEEDBACK (previous completed plan step — context only; your assignment is CURRENT STEP above) ==="
                } else {
                    "=== INSPECTOR FEEDBACK (same plan step — address issues/suggestions before re-submitting if not successful) ==="
                }
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

    fun invariantInspectorUserContent(invariant: TaskInvariant, execution: ExecutionResult): String = buildString {
        appendLine("=== SCOPE (single invariant only) ===")
        appendLine(
            "Evaluate only this one invariant. Do not reference other task invariants or state that they contradict each other."
        )
        appendLine()
        appendLine("=== POLARITY (how to interpret INVARIANT below) ===")
        when (invariant.polarity) {
            InvariantPolarity.POSITIVE ->
                appendLine(
                    "POSITIVE: DATA must satisfy the description — the described property/state should hold."
                )
            InvariantPolarity.NEGATIVE ->
                appendLine(
                    "NEGATIVE: DATA must NOT match the forbidden description — " +
                        "e.g. if the text says \"MVI architecture\", the deliverable must NOT be/use MVI."
                )
        }
        appendLine()
        appendLine("=== INVARIANT (rule to check) ===")
        appendLine(invariant.text.trim().ifBlank { "(empty invariant)" })
        appendLine()
        appendLine("=== DATA (Executor deliverable as JSON; primary content is usually \"output\") ===")
        appendLine(json.encodeToString(ExecutionResult.serializer(), execution))
        appendLine()
        appendLine("Respond with JSON only: {\"success\":true/false,\"reason\":\"...\"}")
    }
}
