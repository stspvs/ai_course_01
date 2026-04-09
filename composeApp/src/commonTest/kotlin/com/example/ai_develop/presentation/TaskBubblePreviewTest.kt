package com.example.ai_develop.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskBubblePreviewTest {

    @Test
    fun normal_shortAndExactPreviewLength_notCollapsible() {
        assertFalse(taskBubbleCollapsible(""))
        assertEquals("", taskBubbleDisplayText("", expanded = false))
        assertEquals("", taskBubbleDisplayText("", expanded = true))

        assertFalse(taskBubbleCollapsible("a"))
        assertEquals("hello", taskBubbleDisplayText("hello", expanded = false))

        val exact = "x".repeat(DEFAULT_TASK_BUBBLE_PREVIEW_CHARS)
        assertFalse(taskBubbleCollapsible(exact))
        assertEquals(exact, taskBubbleDisplayText(exact, expanded = false))
    }

    @Test
    fun normal_longText_collapsedShowsPrefixAndSuffix() {
        val long = "a".repeat(DEFAULT_TASK_BUBBLE_PREVIEW_CHARS + 50)
        assertTrue(taskBubbleCollapsible(long))
        val collapsed = taskBubbleDisplayText(long, expanded = false)
        assertEquals(DEFAULT_TASK_BUBBLE_PREVIEW_CHARS + TASK_BUBBLE_COLLAPSE_SUFFIX.length, collapsed.length)
        assertTrue(collapsed.startsWith("a".repeat(DEFAULT_TASK_BUBBLE_PREVIEW_CHARS)))
        assertTrue(collapsed.endsWith(TASK_BUBBLE_COLLAPSE_SUFFIX))
    }

    @Test
    fun normal_expandedShowsFullText() {
        val long = "b".repeat(400)
        assertEquals(long, taskBubbleDisplayText(long, expanded = true))
    }

    @Test
    fun corner_smallPreviewChars_showsFullWhenShorterThanLimit() {
        val s = "hi"
        assertFalse(taskBubbleCollapsible(s, previewChars = 10))
        assertEquals("hi", taskBubbleDisplayText(s, expanded = false, previewChars = 10))
    }

    @Test
    fun corner_unicodeAndNewlines_preservedInPrefix() {
        val prefix = "Привет 🎉\n" + "z".repeat(DEFAULT_TASK_BUBBLE_PREVIEW_CHARS)
        assertTrue(taskBubbleCollapsible(prefix, previewChars = 50))
        val out = taskBubbleDisplayText(prefix, expanded = false, previewChars = 50)
        assertEquals(50 + TASK_BUBBLE_COLLAPSE_SUFFIX.length, out.length)
        assertTrue(out.endsWith(TASK_BUBBLE_COLLAPSE_SUFFIX))
    }

    @Test
    fun stress_veryLongString_collapsedLengthStable() {
        val huge = "C".repeat(200_000)
        assertTrue(taskBubbleCollapsible(huge))
        val collapsed = taskBubbleDisplayText(huge, expanded = false)
        assertEquals(DEFAULT_TASK_BUBBLE_PREVIEW_CHARS + TASK_BUBBLE_COLLAPSE_SUFFIX.length, collapsed.length)
        assertEquals(huge, taskBubbleDisplayText(huge, expanded = true))
    }

    @Test
    fun stress_repeatedToggleLogic_consistent() {
        val body = "D".repeat(5000)
        for (i in 1..500) {
            val exp = i % 2 == 0
            val t = taskBubbleDisplayText(body, expanded = exp)
            if (exp) assertEquals(body, t) else assertTrue(t.endsWith(TASK_BUBBLE_COLLAPSE_SUFFIX))
        }
    }
}
