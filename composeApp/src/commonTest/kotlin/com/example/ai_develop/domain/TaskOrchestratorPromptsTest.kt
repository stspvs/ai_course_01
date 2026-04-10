package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
    fun executorUserContent_showsLastExecutionWithoutInspectorWhenVerificationMissing() {
        val prompt = TaskOrchestratorPrompts.executorUserContent(
            plan = samplePlan,
            stepIndex = 0,
            lastVerification = null,
            workingMemory = null,
            lastExecution = ExecutionResult(success = true, output = "only exec", errors = null)
        )
        assertContains(prompt, "=== LAST EXECUTION RESULT (most recent deliverable for this step, JSON) ===")
        assertContains(prompt, "only exec")
        assertFalse(prompt.contains("=== INSPECTOR FEEDBACK"))
    }

    @Test
    fun executorUserContent_previousStepFeedback_usesDistinctHeaders() {
        val prompt = TaskOrchestratorPrompts.executorUserContent(
            plan = samplePlan,
            stepIndex = 1,
            lastVerification = VerificationResult(true),
            workingMemory = null,
            lastExecution = ExecutionResult(true, "prev step out", null),
            isFeedbackFromPreviousCompletedStep = true
        )
        assertContains(prompt, "previous completed plan step")
        assertContains(prompt, "prev step out")
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
        assertContains(prompt, "=== PLAN (structured) ===")
        assertContains(prompt, "\"goal\":\"g\"")
        val idxPos = prompt.indexOf("=== CURRENT STEP INDEX ===")
        val curStepPos = prompt.indexOf("=== CURRENT STEP (execute only this")
        val planPos = prompt.indexOf("=== PLAN (structured) ===")
        val wmPos = prompt.indexOf("=== TASK WORKING MEMORY ===")
        val execPos = prompt.indexOf("=== LAST EXECUTION RESULT")
        val inspPos = prompt.indexOf("=== INSPECTOR FEEDBACK")
        assertTrue(
            idxPos < curStepPos && curStepPos < planPos && planPos < wmPos && wmPos < execPos && execPos < inspPos,
            "порядок: индекс шага → CURRENT STEP → план → рабочая память → последнее исполнение → инспектор"
        )
        assertContains(prompt, "prev code")
        assertContains(prompt, "Issues:")
        assertContains(prompt, "- fix me")
        assertContains(prompt, "Suggestions:")
        assertContains(prompt, "- try harder")
        assertFalse(prompt.contains("=== INSPECTOR: REQUIRED FIXES"))
    }

    @Test
    fun taskInvariantsSystemAppendix_listsInvariantsForSystemPrompt() {
        val inv = listOf(TaskInvariant("i1", "No nulls in public API"))
        val block = TaskOrchestratorPrompts.taskInvariantsSystemAppendix(inv)
        assertContains(block, "=== TASK INVARIANTS (fixed rules for this task; apply in planning, execution, and verification) ===")
        assertContains(block, "логическое И")
        assertContains(block, "No nulls in public API")
        assertContains(block, "позитивный")
    }

    @Test
    fun taskInvariantsSystemAppendix_negativePolarity_showsNegationHint() {
        val inv = listOf(TaskInvariant("i1", "архитектура MVI", InvariantPolarity.NEGATIVE))
        val block = TaskOrchestratorPrompts.taskInvariantsSystemAppendix(inv)
        assertContains(block, "негативный")
        assertContains(block, "архитектура MVI")
    }

    @Test
    fun invariantInspectorUserContent_containsInvariantAndDataOnly() {
        val inv = TaskInvariant("id", "Output must mention Kotlin")
        val exec = ExecutionResult(true, "fun hello() = \"Kotlin\"", null)
        val prompt = TaskOrchestratorPrompts.invariantInspectorUserContent(inv, exec)
        assertContains(prompt, "=== INVARIANT (rule to check) ===")
        assertContains(prompt, "Output must mention Kotlin")
        assertContains(prompt, "=== DATA (Executor deliverable as JSON")
        assertContains(prompt, "fun hello()")
        assertFalse(prompt.contains("=== PLAN (structured) ==="), "узкий промпт без плана")
    }

    @Test
    fun taskInvariantsSystemAppendix_emptyList_isEmptyString() {
        assertEquals("", TaskOrchestratorPrompts.taskInvariantsSystemAppendix(emptyList()))
    }

    @Test
    fun invariantInspectorUserContent_blankInvariant_showsPlaceholder() {
        val prompt = TaskOrchestratorPrompts.invariantInspectorUserContent(
            TaskInvariant("id", "   "),
            sampleExecution
        )
        assertContains(prompt, "(empty invariant)")
    }
}
