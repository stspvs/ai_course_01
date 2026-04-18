package com.example.ai_develop.domain.task
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

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

    @Test
    fun expandNumberedSteps_splitsParenthesisNumberingInline() {
        val oneBlock =
            "1) взять курсы за дату 2) взять последние курсы 3) посчитать разницу 4) сохранить в файл"
        val expanded = PlanResult("g", listOf(oneBlock), "c").expandNumberedSteps()
        assertEquals(4, expanded.steps.size)
        assertEquals("1) взять курсы за дату", expanded.steps[0])
        assertEquals("4) сохранить в файл", expanded.steps[3])
    }

    @Test
    fun expandNumberedSteps_splitsParenthesisNumberingPerLine() {
        val oneBlock = """
            1) шаг один
            2) шаг два
        """.trimIndent()
        val expanded = PlanResult("g", listOf(oneBlock), "c").expandNumberedSteps()
        assertEquals(2, expanded.steps.size)
    }
}
