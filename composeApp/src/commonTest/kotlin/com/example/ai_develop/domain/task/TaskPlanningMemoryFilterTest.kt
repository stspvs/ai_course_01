package com.example.ai_develop.domain.task
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskPlanningMemoryFilterTest {

    @Test
    fun filterForArchitect_emptyList_isStable() {
        assertEquals(emptyList(), TaskPlanningMemoryFilter.filterForArchitect(emptyList()))
        assertEquals(
            emptyList(),
            TaskPlanningMemoryFilter.filterForArchitect(emptyList(), stalePlannerJsonAfterInspectorRejection = true)
        )
    }

    private val minimalPlannerJson = """
        {
          "success": true,
          "plan": {
            "goal": "g",
            "steps": ["s1"],
            "successCriteria": "c",
            "constraints": null,
            "contextSummary": null
          },
          "questions": [],
          "requiresUserConfirmation": false
        }
    """.trimIndent()

    @Test
    fun stalePlannerJsonAfterInspector_stripsJsonBeforeLastUserMessage() {
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", message = "ок", timestamp = 1L, taskId = "t", taskState = TaskState.PLANNING),
            ChatMessage(
                id = "json",
                role = "assistant",
                message = minimalPlannerJson,
                timestamp = 2L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            ),
            ChatMessage(
                id = "u2",
                role = "user",
                message = "начинай след этап",
                timestamp = 3L,
                taskId = "t",
                taskState = TaskState.PLANNING
            )
        )
        val stripped = TaskPlanningMemoryFilter.filterForArchitect(
            messages,
            stalePlannerJsonAfterInspectorRejection = true
        )
        assertEquals(listOf("u1", "u2"), stripped.map { it.id })

        val kept = TaskPlanningMemoryFilter.filterForArchitect(
            messages,
            stalePlannerJsonAfterInspectorRejection = false
        )
        assertEquals(listOf("u1", "json", "u2"), kept.map { it.id })
    }

    @Test
    fun multiplePlannerJsonAssistantMessages_keepOnlyLast() {
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", message = "go", timestamp = 1L, taskId = "t", taskState = TaskState.PLANNING),
            ChatMessage(
                id = "j1",
                role = "assistant",
                message = minimalPlannerJson,
                timestamp = 2L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            ),
            ChatMessage(
                id = "j2",
                role = "assistant",
                message = minimalPlannerJson.replace("\"g\"", "\"g2\""),
                timestamp = 3L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            ),
            ChatMessage(
                id = "j3",
                role = "assistant",
                message = minimalPlannerJson.replace("\"g\"", "\"g3\""),
                timestamp = 4L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            )
        )
        val out = TaskPlanningMemoryFilter.filterForArchitect(messages)
        assertEquals(listOf("u1", "j3"), out.map { it.id })
        assertTrue(out[1].message.contains("g3"))
    }

    @Test
    fun plannerJsonInterleavedWithPlainAssistant_keepsPlainAndLastJson() {
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", message = "?", timestamp = 1L, taskId = "t", taskState = TaskState.PLANNING),
            ChatMessage(
                id = "plain",
                role = "assistant",
                message = "Какой формат вывода предпочитаете?",
                timestamp = 2L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            ),
            ChatMessage(
                id = "j1",
                role = "assistant",
                message = minimalPlannerJson,
                timestamp = 3L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            ),
            ChatMessage(
                id = "j2",
                role = "assistant",
                message = minimalPlannerJson.replace("\"g\"", "\"final\""),
                timestamp = 4L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            )
        )
        val out = TaskPlanningMemoryFilter.filterForArchitect(messages)
        assertEquals(listOf("u1", "plain", "j2"), out.map { it.id })
    }

    @Test
    fun dropsSystemBubblesAndNonPlanningStages() {
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", message = "hi", timestamp = 1L, taskId = "t", taskState = TaskState.PLANNING),
            ChatMessage(
                id = "sys1",
                role = "system",
                message = "▶ stage",
                timestamp = 2L,
                taskId = "t",
                taskState = TaskState.PLANNING,
                isSystemNotification = true,
                source = SourceType.SYSTEM
            ),
            ChatMessage(
                id = "a1",
                role = "assistant",
                message = "plan",
                timestamp = 3L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            ),
            ChatMessage(
                id = "ex1",
                role = "assistant",
                message = "code",
                timestamp = 4L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.EXECUTION
            ),
            ChatMessage(
                id = "v1",
                role = "assistant",
                message = "ok",
                timestamp = 5L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.VERIFICATION
            )
        )
        val out = TaskPlanningMemoryFilter.filterForArchitect(messages)
        assertEquals(listOf("u1", "a1"), out.map { it.id })
    }

    @Test
    fun planVerificationBlock_collapsesToLastInspectorAssistant() {
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", message = "q", timestamp = 1L, taskId = "t", taskState = TaskState.PLANNING),
            ChatMessage(
                id = "p1",
                role = "assistant",
                message = "json plan",
                timestamp = 2L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            ),
            ChatMessage(
                id = "pvu",
                role = "user",
                message = "plan dump",
                timestamp = 3L,
                taskId = "t",
                taskState = TaskState.PLAN_VERIFICATION
            ),
            ChatMessage(
                id = "pvi1",
                role = "assistant",
                message = "inv1",
                timestamp = 4L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLAN_VERIFICATION
            ),
            ChatMessage(
                id = "pvi2",
                role = "assistant",
                message = "inv2",
                timestamp = 5L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLAN_VERIFICATION
            )
        )
        val out = TaskPlanningMemoryFilter.filterForArchitect(messages)
        assertEquals(listOf("u1", "p1", "pvi2"), out.map { it.id })
    }

    @Test
    fun multiplePvRounds_eachContributesLastInspectorOnly() {
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", message = "a", timestamp = 1L, taskId = "t", taskState = TaskState.PLANNING),
            ChatMessage(
                id = "p1",
                role = "assistant",
                message = "plan1",
                timestamp = 2L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            ),
            ChatMessage(
                id = "in1",
                role = "assistant",
                message = "fail1",
                timestamp = 3L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLAN_VERIFICATION
            ),
            ChatMessage(id = "u2", role = "user", message = "b", timestamp = 4L, taskId = "t", taskState = TaskState.PLANNING),
            ChatMessage(
                id = "p2",
                role = "assistant",
                message = "plan2",
                timestamp = 5L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLANNING
            ),
            ChatMessage(
                id = "in2",
                role = "assistant",
                message = "fail2",
                timestamp = 6L,
                source = SourceType.AI,
                taskId = "t",
                taskState = TaskState.PLAN_VERIFICATION
            )
        )
        val out = TaskPlanningMemoryFilter.filterForArchitect(messages)
        assertEquals(listOf("u1", "p1", "in1", "u2", "p2", "in2"), out.map { it.id })
    }

    @Test
    fun legacyNullTaskState_treatedAsPlanningWhenUserOrArchitect() {
        val messages = listOf(
            ChatMessage(id = "u", role = "user", message = "x", timestamp = 1L, taskId = "t", taskState = null),
            ChatMessage(
                id = "a",
                role = "assistant",
                message = "y",
                timestamp = 2L,
                source = SourceType.AI,
                taskId = "t",
                taskState = null
            )
        )
        val out = TaskPlanningMemoryFilter.filterForArchitect(messages)
        assertEquals(2, out.size)
        assertTrue(out.all { it.id == "u" || it.id == "a" })
    }
}
