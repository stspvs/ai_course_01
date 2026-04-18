package com.example.ai_develop.domain.task
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskInvariantVerificationTest {

    private val sampleContext = TaskContext(
        taskId = "t",
        title = "My task",
        state = AgentTaskState(
            TaskState.PLANNING,
            Agent(
                id = "a",
                name = "n",
                systemPrompt = "",
                temperature = 0.7,
                provider = LLMProvider.DeepSeek(),
                stopWord = "",
                maxTokens = 2000
            )
        )
    )

    @Test
    fun validatorInstruction_planStep_mentionsCurrentStepAndVerificationRules() {
        val role = ValidatorRole()
        val s = role.getValidatorInstruction(sampleContext, ValidatorInstructionKind.PlanStep)
        assertContains(s, "CURRENT STEP")
        assertContains(s, "VERIFICATION RULES")
    }

    @Test
    fun validatorInstruction_wholePlan_mentionsPreExecutionReview() {
        val role = ValidatorRole()
        val s = role.getValidatorInstruction(sampleContext, ValidatorInstructionKind.WholePlan)
        assertContains(s, "plan verification")
        assertContains(s, "before any execution")
    }

    @Test
    fun architectInstruction_afterInspectorFeedback_containsFeedbackBlockAndAutonomousRevisionRules() {
        val role = ArchitectRole()
        val ctx = sampleContext.copy(
            runtimeState = sampleContext.runtimeState.copy(
                lastVerification = VerificationResult(false, listOf("gap"), listOf("hint"))
            )
        )
        val s = role.getSystemInstruction(ctx)
        assertContains(s, "=== PLAN INSPECTOR FEEDBACK (from last automated plan verification) ===")
        assertContains(s, "gap")
        assertContains(s, "Do **not** ask the user for permission")
        assertContains(s, "automated plan verification")
    }

    @Test
    fun architectInstruction_freshPlanning_asksUserConfirmBeforeJson() {
        val role = ArchitectRole()
        val s = role.getSystemInstruction(sampleContext)
        assertContains(s, "explicit confirmation")
        assertFalse(s.contains("=== PLAN INSPECTOR FEEDBACK"))
    }

    @Test
    fun validatorInstruction_taskInvariantPlan_mentionsPlanJsonData() {
        val role = ValidatorRole()
        val s = role.getValidatorInstruction(sampleContext, ValidatorInstructionKind.TaskInvariantPlan)
        assertContains(s, "PlanResult")
        assertContains(s, "structured plan")
    }

    @Test
    fun validatorInstruction_taskInvariant_doesNotReferencePlanStepRulesBlock() {
        val role = ValidatorRole()
        val s = role.getValidatorInstruction(sampleContext, ValidatorInstructionKind.TaskInvariant)
        assertContains(s, "INVARIANT")
        assertContains(s, "DATA")
        assertFalse(s.contains("VERIFICATION RULES block in the user message"))
    }

    @Test
    fun validatorRole_getSystemInstruction_matchesPlanStepKind() {
        val role = ValidatorRole()
        assertEquals(
            role.getValidatorInstruction(sampleContext, ValidatorInstructionKind.PlanStep),
            role.getSystemInstruction(sampleContext)
        )
    }

    @Test
    fun validatorInstruction_taskInvariant_specifiesJsonShapeWithReason() {
        val role = ValidatorRole()
        val s = role.getValidatorInstruction(sampleContext, ValidatorInstructionKind.TaskInvariant)
        assertContains(s, "\"success\"")
        assertContains(s, "\"reason\"")
        assertContains(s, "POLARITY")
        assertContains(s, "NEGATIVE")
        assertContains(s, "contradicts other task rules")
    }

    @Test
    fun mergedWithInvariantResults_allPass_keepsMainSuccess() {
        val main = VerificationResult(success = true, issues = null, suggestions = listOf("tip"))
        val inv = listOf(TaskInvariant("a", "rule1"))
        val results = listOf(InvariantVerificationResult(true, null))
        val m = main.mergedWithInvariantResults(inv, results)
        assertTrue(m.success)
        assertEquals(listOf("tip"), m.suggestions)
    }

    @Test
    fun mergedWithInvariantResults_invariantFail_appendsSuggestionsAndFails() {
        val main = VerificationResult(success = true, issues = null, suggestions = null)
        val inv = listOf(TaskInvariant("a", "Use Kotlin", InvariantPolarity.POSITIVE))
        val results = listOf(InvariantVerificationResult(false, reason = "Found Java only"))
        val m = main.mergedWithInvariantResults(inv, results)
        assertFalse(m.success)
        assertEquals(1, m.suggestions?.size)
        assertTrue(m.suggestions!!.first().contains("[Invariant, позитивный]"))
        assertTrue(m.suggestions!!.first().contains("Found Java only"))
    }

    @Test
    fun mergedWithInvariantResults_negativePolarity_labelInSuggestion() {
        val main = VerificationResult(true)
        val m = main.mergedWithInvariantResults(
            listOf(TaskInvariant("i", "архитектура MVI", InvariantPolarity.NEGATIVE)),
            listOf(InvariantVerificationResult(false, reason = "всё ещё MVI"))
        )
        assertFalse(m.success)
        assertTrue(m.suggestions!!.first().contains("[Invariant, негативный]"))
        assertTrue(m.suggestions!!.first().contains("архитектура MVI"))
    }

    @Test
    fun parseInvariantVerificationResult_acceptsPlainJson() {
        val r = AutonomousTaskJsonParsers.parseInvariantVerificationResult(
            """{"success":false,"reason":"bad"}"""
        )
        assertEquals(false, r?.success)
        assertEquals("bad", r?.reason)
    }

    @Test
    fun mergedWithInvariantResults_emptyLists_noOp() {
        val main = VerificationResult(success = true, issues = listOf("a"), suggestions = listOf("s"))
        val m = main.mergedWithInvariantResults(emptyList(), emptyList())
        assertTrue(m.success)
        assertEquals(listOf("a"), m.issues)
        assertEquals(listOf("s"), m.suggestions)
    }

    @Test
    fun mergedWithInvariantResults_mismatchedSizes_throws() {
        val main = VerificationResult(true)
        assertFailsWith<IllegalArgumentException> {
            main.mergedWithInvariantResults(
                listOf(TaskInvariant("1", "a")),
                emptyList()
            )
        }
    }

    @Test
    fun mergedWithInvariantResults_mainFailed_staysFailedEvenIfInvariantsPass() {
        val main = VerificationResult(success = false, issues = listOf("step bad"), suggestions = null)
        val m = main.mergedWithInvariantResults(
            listOf(TaskInvariant("i", "rule")),
            listOf(InvariantVerificationResult(true, null))
        )
        assertFalse(m.success)
        assertEquals(listOf("step bad"), m.issues)
    }

    @Test
    fun mergedWithInvariantResults_inspectorPassedInvariantFail_replacesIssuesAndDropsInspectorSuggestions() {
        val main = VerificationResult(
            success = true,
            issues = listOf("from main"),
            suggestions = listOf("hint")
        )
        val m = main.mergedWithInvariantResults(
            listOf(TaskInvariant("i", "x")),
            listOf(InvariantVerificationResult(false, reason = "inv fail"))
        )
        assertFalse(m.success)
        assertEquals(listOf(INVARIANT_OVERRIDE_ISSUE_MESSAGE), m.issues)
        assertEquals(1, m.suggestions?.size)
        assertFalse(m.suggestions!!.any { it == "hint" })
        assertTrue(m.suggestions!!.first().contains("inv fail"))
    }

    @Test
    fun mergedWithInvariantResults_inspectorPassedInvariantFail_stripsInspectorTipsFromSuggestions() {
        val main = VerificationResult(
            success = true,
            issues = null,
            suggestions = listOf("inspector tip")
        )
        val m = main.mergedWithInvariantResults(
            listOf(TaskInvariant("i", "Use Kotlin")),
            listOf(InvariantVerificationResult(false, reason = "still Java"))
        )
        assertFalse(m.success)
        assertEquals(listOf(INVARIANT_OVERRIDE_ISSUE_MESSAGE), m.issues)
        assertEquals(1, m.suggestions?.size)
        assertFalse(m.suggestions!!.any { it.contains("inspector tip") })
        assertTrue(m.suggestions!!.first().contains("[Invariant,"))
        assertTrue(m.suggestions!!.first().contains("still Java"))
    }

    @Test
    fun mergedWithInvariantResults_blankReason_usesDefaultDetail() {
        val main = VerificationResult(true)
        val m = main.mergedWithInvariantResults(
            listOf(TaskInvariant("i", "rule")),
            listOf(InvariantVerificationResult(false, reason = "   "))
        )
        assertFalse(m.success)
        assertTrue(m.suggestions!!.first().contains("Does not satisfy the invariant."))
    }

    @Test
    fun mergedWithInvariantResults_longInvariantText_truncatesWithEllipsis() {
        val longText = "x".repeat(120)
        val main = VerificationResult(true)
        val m = main.mergedWithInvariantResults(
            listOf(TaskInvariant("i", longText)),
            listOf(InvariantVerificationResult(false, reason = "nope"))
        )
        val line = m.suggestions!!.first()
        assertTrue(line.contains("…"), "ожидается усечение длинного текста инварианта")
        val headPart = line.substringAfter("[Invariant, позитивный] ").substringBefore(" —")
        assertEquals(MAX_TASK_INVARIANT_TEXT_LENGTH + 1, headPart.length)
        assertTrue(headPart.endsWith("…"))
    }

    @Test
    fun mergedWithInvariantResults_multipleFailures_allListed() {
        val main = VerificationResult(true)
        val invs = listOf(
            TaskInvariant("a", "r1"),
            TaskInvariant("b", "r2"),
            TaskInvariant("c", "r3")
        )
        val results = listOf(
            InvariantVerificationResult(false, reason = "e1"),
            InvariantVerificationResult(true, null),
            InvariantVerificationResult(false, reason = "e3")
        )
        val m = main.mergedWithInvariantResults(invs, results)
        assertFalse(m.success)
        assertEquals(2, m.suggestions?.count { it.startsWith("[Invariant,") })
        assertTrue(m.suggestions!!.any { it.contains("e1") })
        assertTrue(m.suggestions!!.any { it.contains("e3") })
    }

    @Test
    fun mergedWithInvariantResults_stress_manyInvariants_allPass() {
        val main = VerificationResult(true)
        val n = 150
        val invs = List(n) { TaskInvariant("id-$it", "rule $it") }
        val results = List(n) { InvariantVerificationResult(true, null) }
        val m = main.mergedWithInvariantResults(invs, results)
        assertTrue(m.success)
        assertNull(m.suggestions)
    }

    @Test
    fun mergedWithInvariantResults_emptyMainSuggestionsList_invariantPass_yieldsNullSuggestions() {
        val main = VerificationResult(success = true, issues = null, suggestions = emptyList())
        val m = main.mergedWithInvariantResults(
            listOf(TaskInvariant("i", "r")),
            listOf(InvariantVerificationResult(true, null))
        )
        assertTrue(m.success)
        assertNull(m.suggestions)
    }

    @Test
    fun parseInvariantVerificationResult_markdownFence_stillParses() {
        val text = "```json\n{\"success\":false,\"reason\":\"inside fence\"}\n```"
        val r = AutonomousTaskJsonParsers.parseInvariantVerificationResult(text)
        assertEquals(false, r?.success)
        assertEquals("inside fence", r?.reason)
    }

    @Test
    fun parseInvariantVerificationResult_withPreamble_picksJson() {
        val r = AutonomousTaskJsonParsers.parseInvariantVerificationResult(
            "Here you go:\n{\"success\":true,\"reason\":null}\n"
        )
        assertNotNull(r)
        assertTrue(r!!.success)
    }

    @Test
    fun parseInvariantVerificationResult_twoJsonObjects_usesLastValid() {
        val r = AutonomousTaskJsonParsers.parseInvariantVerificationResult(
            """{"success":true}{"success":false,"reason":"final"}"""
        )
        assertEquals(false, r?.success)
        assertEquals("final", r?.reason)
    }

    @Test
    fun parseInvariantVerificationResult_legacySagaResponse_failed() {
        val r = AutonomousTaskJsonParsers.parseInvariantVerificationResult(
            """{"status":"FAILED","result":"broken"}"""
        )
        assertEquals(false, r?.success)
        assertEquals("broken", r?.reason)
    }

    @Test
    fun parseInvariantVerificationResult_legacySagaResponse_success() {
        val r = AutonomousTaskJsonParsers.parseInvariantVerificationResult(
            """{"status":"SUCCESS","result":"ok"}"""
        )
        assertEquals(true, r?.success)
    }

    @Test
    fun parseInvariantVerificationResult_garbage_returnsNull() {
        assertNull(AutonomousTaskJsonParsers.parseInvariantVerificationResult("no braces"))
        assertNull(AutonomousTaskJsonParsers.parseInvariantVerificationResult(""))
    }

    @Test
    fun parseInvariantVerificationResult_stress_largeReasonInJson() {
        val big = "я".repeat(8000)
        val escaped = big.replace("\\", "\\\\").replace("\"", "\\\"")
        val raw = """{"success":false,"reason":"$escaped"}"""
        val r = AutonomousTaskJsonParsers.parseInvariantVerificationResult(raw)
        assertEquals(false, r?.success)
        assertEquals(big.length, r?.reason?.length)
    }

    @Test
    fun taskInvariantsSystemAppendix_stress_maxInvariants_listed() {
        val invs = List(20) { TaskInvariant("id-$it", "line $it") }
        val block = TaskOrchestratorPrompts.taskInvariantsSystemAppendix(invs)
        assertContains(block, "=== TASK INVARIANTS")
        repeat(20) {
            assertContains(block, "line $it")
        }
    }

    @Test
    fun invariantInspectorUserContent_stress_unicodeAndEscaping_inDataBlock() {
        val exec = ExecutionResult(true, "fun `тест`() = \"\\\"\"", null)
        val prompt = TaskOrchestratorPrompts.invariantInspectorUserContent(
            TaskInvariant("i", "Правило 🎯"),
            exec
        )
        assertContains(prompt, "Правило 🎯")
        assertContains(prompt, "тест")
    }

    @Test
    fun invariantInspectorUserContent_negativePolarity_explainsNegation() {
        val prompt = TaskOrchestratorPrompts.invariantInspectorUserContent(
            TaskInvariant("i", "MVI", InvariantPolarity.NEGATIVE),
            ExecutionResult(true, "out", null)
        )
        assertContains(prompt, "=== SCOPE (single invariant only) ===")
        assertContains(prompt, "=== POLARITY")
        assertContains(prompt, "NEGATIVE")
        assertContains(prompt, "MVI")
    }
}
