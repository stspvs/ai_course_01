package com.example.ai_develop.data

import kotlin.test.Test
import kotlin.test.assertTrue

class RagQueryRewritePromptTest {

    @Test
    fun prompt_requiresOutputLanguageToMatchUser() {
        assertTrue(
            ragQueryRewriteSystemPrompt.contains("MUST match", ignoreCase = true),
            "Expected explicit language-matching rule",
        )
        assertTrue(
            ragQueryRewriteSystemPrompt.contains("do not translate", ignoreCase = true),
            "Expected no-translation rule",
        )
        assertTrue(
            ragQueryRewriteSystemPrompt.contains("do not switch languages", ignoreCase = true),
            "Expected no language-switch rule",
        )
    }

    @Test
    fun prompt_mentionsRussianAndEnglishAsExamples() {
        assertTrue(ragQueryRewriteSystemPrompt.contains("Russian", ignoreCase = true))
        assertTrue(ragQueryRewriteSystemPrompt.contains("English", ignoreCase = true))
    }

    @Test
    fun prompt_singleLineAndNoExplanation() {
        assertTrue(ragQueryRewriteSystemPrompt.contains("one concise search line", ignoreCase = true))
        assertTrue(ragQueryRewriteSystemPrompt.contains("No quotes", ignoreCase = true))
        assertTrue(ragQueryRewriteSystemPrompt.contains("no explanation", ignoreCase = true))
    }
}
