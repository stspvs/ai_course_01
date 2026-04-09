package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PlanResultExpandStepsTest {

    @Test
    fun expandNumberedSteps_splitsSingleStringWithNumberedSubsteps() {
        val oneBlock =
            "План разработки приложения утвержден. Этапы включают: 1. Планирование архитектуры. " +
                "2. Разработка общего модуля. 3. Разработка Android-модуля. 4. Отладка и оптимизация."
        val plan = PlanResult(
            goal = "g",
            steps = listOf(oneBlock),
            successCriteria = "c"
        )
        val expanded = plan.expandNumberedSteps()
        assertEquals(4, expanded.steps.size)
        assertEquals("1. Планирование архитектуры.", expanded.steps[0])
        assertEquals("4. Отладка и оптимизация.", expanded.steps[3])
    }

    @Test
    fun expandNumberedSteps_leavesMultiStepListUnchanged() {
        val plan = PlanResult(
            goal = "g",
            steps = listOf("a", "b"),
            successCriteria = "c"
        )
        assertEquals(plan, plan.expandNumberedSteps())
    }

    @Test
    fun expandNumberedSteps_splitsNumberedLinesWithNewlines() {
        val oneBlock = """
            1. Первый шаг.
            2. Второй шаг.
            3. Третий шаг.
        """.trimIndent()
        val expanded = PlanResult("g", listOf(oneBlock), "c").expandNumberedSteps()
        assertEquals(3, expanded.steps.size)
    }
}
