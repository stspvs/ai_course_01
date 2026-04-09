package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskOrchestratorPromptsTest {

    private val samplePlan = PlanResult(
        goal = "g",
        steps = listOf(
            "Step A — logic only",
            "Step B — data class",
            "Step C — Android integration"
        ),
        successCriteria = "Everything works end-to-end",
        constraints = null,
        contextSummary = null
    )

    private val sampleExecution = ExecutionResult(success = true, output = "```kotlin\n// code\n```", errors = null)

    @Test
    fun inspectorUserContent_nonFinalStep_includesDoNotRequireEndToEndCriteria() {
        val prompt = TaskOrchestratorPrompts.inspectorUserContent(
            plan = samplePlan,
            stepIndex = 0,
            execution = sampleExecution,
            successCriteria = samplePlan.successCriteria
        )
        assertContains(prompt, "=== VERIFICATION RULES ===")
        assertContains(prompt, "=== CURRENT STEP (scope of this verification) ===")
        assertContains(prompt, "Step A — logic only")
        assertContains(
            prompt,
            "This is NOT the final plan step: do NOT require full end-to-end SUCCESS CRITERIA"
        )
        assertFalse(
            prompt.contains("This is the FINAL plan step"),
            "промпт для не последнего шага не должен требовать финальную проверку целиком"
        )
    }

    @Test
    fun inspectorUserContent_finalStep_includesOverallSuccessCriteriaCheck() {
        val prompt = TaskOrchestratorPrompts.inspectorUserContent(
            plan = samplePlan,
            stepIndex = 2,
            execution = sampleExecution,
            successCriteria = samplePlan.successCriteria
        )
        assertContains(prompt, "Step C — Android integration")
        assertContains(prompt, "This is the FINAL plan step: also check whether SUCCESS CRITERIA (overall)")
        assertFalse(
            prompt.contains("NOT the final plan step"),
            "на последнем шаге не должно быть правила про не-финальный шаг"
        )
    }

    @Test
    fun inspectorUserContent_singleStepPlan_isTreatedAsFinal() {
        val oneStep = PlanResult(
            goal = "only",
            steps = listOf("Only step"),
            successCriteria = "done",
            constraints = null,
            contextSummary = null
        )
        val prompt = TaskOrchestratorPrompts.inspectorUserContent(
            plan = oneStep,
            stepIndex = 0,
            execution = sampleExecution,
            successCriteria = "done"
        )
        assertTrue(prompt.contains("FINAL plan step"), "единственный шаг = финальный для верификации")
    }

    @Test
    fun inspectorUserContent_excludesPlanningPhaseChat() {
        val prompt = TaskOrchestratorPrompts.inspectorUserContent(
            plan = samplePlan,
            stepIndex = 0,
            execution = sampleExecution,
            successCriteria = samplePlan.successCriteria
        )
        assertFalse(
            prompt.contains("PLANNING PHASE"),
            "полный чат планирования не должен попадать в промпт инспектора"
        )
    }

    @Test
    fun inspectorUserContent_includesPreviousVerificationAndExecutionResult() {
        val prev = VerificationResult(success = false, issues = listOf("missing X"), suggestions = null)
        val prompt = TaskOrchestratorPrompts.inspectorUserContent(
            plan = samplePlan,
            stepIndex = 0,
            execution = sampleExecution,
            successCriteria = samplePlan.successCriteria,
            lastVerification = prev
        )
        assertContains(prompt, "=== PREVIOUS VERIFICATION (last inspector verdict on this step, JSON) ===")
        assertContains(prompt, "missing X")
        assertContains(prompt, "=== EXECUTION RESULT (executor deliverable for this step, JSON) ===")
        assertContains(prompt, "```kotlin")
    }

    @Test
    fun executorUserContent_failedVerification_listsIssuesAndPutsInspectorBelowWorkingMemory() {
        val prompt = TaskOrchestratorPrompts.executorUserContent(
            plan = samplePlan,
            stepIndex = 0,
            lastVerification = VerificationResult(
                success = false,
                issues = listOf("fix me"),
                suggestions = listOf("try harder")
            ),
            workingMemory = "wm body",
            lastExecution = ExecutionResult(success = true, output = "prev code", errors = null)
        )
        assertFalse(prompt.contains("=== PLAN (structured) ==="), "исполнителю не передаём полный план JSON")
        val wmPos = prompt.indexOf("=== TASK WORKING MEMORY ===")
        val execPos = prompt.indexOf("=== LAST EXECUTION RESULT")
        val inspPos = prompt.indexOf("=== INSPECTOR FEEDBACK")
        assertTrue(wmPos >= 0 && execPos > wmPos && inspPos > execPos, "порядок: WM → последнее исполнение → инспектор")
        assertContains(prompt, "prev code")
        assertContains(prompt, "Issues:")
        assertContains(prompt, "- fix me")
        assertContains(prompt, "Suggestions:")
        assertContains(prompt, "- try harder")
        assertFalse(prompt.contains("=== INSPECTOR: REQUIRED FIXES"))
    }
}
