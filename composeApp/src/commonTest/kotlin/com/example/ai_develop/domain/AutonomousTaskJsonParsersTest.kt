package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AutonomousTaskJsonParsersTest {

    @Test
    fun parsePlannerOutputSample() {
        val s =
            """{"success":true,"plan":{"goal":"g","steps":["one"],"successCriteria":"c"},"questions":[],"requiresUserConfirmation":false}"""
        val p = AutonomousTaskJsonParsers.parsePlannerOutput(s)
        assertNotNull(p)
        assertEquals(false, p!!.requiresUserConfirmation)
        assertEquals(true, p.success)
    }

    @Test
    fun normalizePlanResultUnwrapsLegacyJsonInsideMarkdownStep() {
        val inner = """{"status": "SUCCESS", "result": "План по Kotlin Multiplatform утверждён. Шаги: архитектура, общий модуль, Android."}"""
        val fenced = "```\n$inner\n```"
        val plan = PlanResult(
            goal = "Новая задача 3",
            steps = listOf(fenced),
            successCriteria = "Satisfy the stated goal.",
            constraints = null,
            contextSummary = null
        )
        val n = AutonomousTaskJsonParsers.normalizePlanResult(plan)
        assertEquals(
            "План по Kotlin Multiplatform утверждён. Шаги: архитектура, общий модуль, Android.",
            n.steps.single()
        )
    }

    @Test
    fun parseVerificationResultSample() {
        val s = """{"success":true,"issues":null,"suggestions":null}"""
        val v = AutonomousTaskJsonParsers.parseVerificationResult(s)
        assertNotNull(v)
        assertEquals(true, v!!.success)
    }

    @Test
    fun parseExecutionResultPrefersLastJsonWhenModelOutputsTwoObjects() {
        val text = """
            Draft:
            {"success":false,"output":"old","errors":["draft"]}
            Final:
            {"success":true,"output":"final answer","errors":[]}
        """.trimIndent()
        val e = AutonomousTaskJsonParsers.parseExecutionResult(text)
        assertNotNull(e)
        assertEquals(true, e!!.success)
        assertEquals("final answer", e.output)
    }

    @Test
    fun parseVerificationResultPrefersLastJsonWhenModelOutputsTwoObjects() {
        val text = """
            {"success":false,"issues":["first"],"suggestions":null}
            {"success":true,"issues":[],"suggestions":null}
        """.trimIndent()
        val v = AutonomousTaskJsonParsers.parseVerificationResult(text)
        assertNotNull(v)
        assertEquals(true, v!!.success)
    }
}
