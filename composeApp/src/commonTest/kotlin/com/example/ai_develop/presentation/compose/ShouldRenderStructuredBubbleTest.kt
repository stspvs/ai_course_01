package com.example.ai_develop.presentation.compose

import com.example.ai_develop.domain.RagStructuredChatPayload
import com.example.ai_develop.domain.RagStructuredQuoteLine
import com.example.ai_develop.domain.RagStructuredSourceLine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Регрессия: пустой [RagStructuredChatPayload] не должен заставлять [MessageBubble]
 * рендерить только [RagStructuredAssistantContent] без текста — нужен fallback на [com.example.ai_develop.domain.ChatMessage.message].
 */
class ShouldRenderStructuredBubbleTest {

    @Test
    fun nullPayload_doesNotRenderStructuredBubble() {
        val payload: RagStructuredChatPayload? = null
        assertFalse(payload.shouldRenderStructuredBubble())
    }

    @Test
    fun allFieldsEmpty_doesNotRenderStructuredBubble() {
        val payload = RagStructuredChatPayload(
            answer = "",
            sources = emptyList(),
            quotes = emptyList(),
            validationNote = null,
        )
        assertFalse(payload.shouldRenderStructuredBubble())
    }

    @Test
    fun blankAnswerWhitespaceOnly_doesNotRenderStructuredBubble() {
        val payload = RagStructuredChatPayload(
            answer = "   \n\t  ",
            sources = emptyList(),
            quotes = emptyList(),
            validationNote = null,
        )
        assertFalse(payload.shouldRenderStructuredBubble())
    }

    @Test
    fun nonBlankAnswer_rendersStructuredBubble() {
        val payload = RagStructuredChatPayload(
            answer = "Ответ",
            sources = emptyList(),
            quotes = emptyList(),
            validationNote = null,
        )
        assertTrue(payload.shouldRenderStructuredBubble())
    }

    @Test
    fun emptyAnswerButSources_rendersStructuredBubble() {
        val payload = RagStructuredChatPayload(
            answer = "",
            sources = listOf(
                RagStructuredSourceLine(source = "a.md", chunkId = "c1", chunkIndex = 0L),
            ),
            quotes = emptyList(),
            validationNote = null,
        )
        assertTrue(payload.shouldRenderStructuredBubble())
    }

    @Test
    fun emptyAnswerButQuotes_rendersStructuredBubble() {
        val payload = RagStructuredChatPayload(
            answer = "",
            sources = emptyList(),
            quotes = listOf(
                RagStructuredQuoteLine(text = "цитата", chunkId = "c1"),
            ),
            validationNote = null,
        )
        assertTrue(payload.shouldRenderStructuredBubble())
    }

    @Test
    fun validationNoteOnly_rendersStructuredBubble() {
        val payload = RagStructuredChatPayload(
            answer = "",
            sources = emptyList(),
            quotes = emptyList(),
            validationNote = "Несоответствие схеме",
        )
        assertTrue(payload.shouldRenderStructuredBubble())
    }

    @Test
    fun nullableExtension_matchesChatContentHasStructuredRagSemantics() {
        fun hasStructuredRagLike(snapshotPayload: RagStructuredChatPayload?) =
            snapshotPayload.shouldRenderStructuredBubble()

        assertFalse(hasStructuredRagLike(null))
        assertFalse(
            hasStructuredRagLike(
                RagStructuredChatPayload(answer = "", sources = emptyList(), quotes = emptyList(), validationNote = null),
            ),
        )
        assertTrue(
            hasStructuredRagLike(
                RagStructuredChatPayload(answer = "ok", sources = emptyList(), quotes = emptyList(), validationNote = null),
            ),
        )
    }
}
