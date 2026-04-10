package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class TaskStageExecutionBannerTest {

    @Test
    fun executionEntryMessage_listsAllStepsAndMarksCurrent() {
        val plan = PlanResult(
            goal = "g",
            steps = listOf("шаг A", "шаг B", "шаг C"),
            successCriteria = "c",
            constraints = null,
            contextSummary = null
        )
        val msg = TaskStageExecutionBanner.executionEntryMessage(plan, 1)
        assertTrue(msg.startsWith("▶ Начинается этап: исполнение (исполнитель)"))
        assertContains(msg, "Всего пунктов плана: 3")
        assertContains(msg, "пункт 2 из 3")
        assertContains(msg, "1. шаг A")
        assertContains(msg, "2. ▶ шаг B  ◀")
        assertContains(msg, "3. шаг C")
    }

    @Test
    fun executionEntryMessage_coercesIndexIntoRange() {
        val plan = PlanResult("g", listOf("only"), "c", null, null)
        val msg = TaskStageExecutionBanner.executionEntryMessage(plan, 99)
        assertContains(msg, "1. ▶ only  ◀")
    }
}
