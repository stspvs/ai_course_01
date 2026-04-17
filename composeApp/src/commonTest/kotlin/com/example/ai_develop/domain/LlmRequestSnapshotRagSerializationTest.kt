package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Тот же [Json], что в [com.example.ai_develop.data.SqlDelightChatRepository] для полей снимка.
 */
class LlmRequestSnapshotRagSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun roundTrip_withRagStructuredContent() {
        val payload = RagStructuredChatPayload(
            answer = "Ответ",
            sources = listOf(
                RagStructuredSourceLine(source = "f.md", chunkId = "c1", chunkIndex = 2L),
            ),
            quotes = listOf(RagStructuredQuoteLine(text = "цит", chunkId = "c1")),
            validationNote = "заметка",
        )
        val original = LlmRequestSnapshot(
            effectiveSystemPrompt = "sys",
            inputMessagesText = "u",
            providerName = "p",
            model = "m",
            agentStage = "s",
            temperature = 0.3,
            maxTokens = 100,
            isJsonMode = true,
            ragAttribution = RagAttribution(
                used = true,
                insufficientRelevance = false,
                sources = listOf(
                    RagSourceRef(
                        documentTitle = "D",
                        sourceFileName = "f.md",
                        chunkIndex = 2L,
                        chunkId = "c1",
                        chunkText = "текст",
                    ),
                ),
            ),
            ragStructuredContent = payload,
        )
        val encoded = json.encodeToString(LlmRequestSnapshot.serializer(), original)
        val decoded = json.decodeFromString(LlmRequestSnapshot.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTrip_ragStructuredContentNull() {
        val original = LlmRequestSnapshot(
            effectiveSystemPrompt = "",
            inputMessagesText = "",
            providerName = "",
            model = "",
            agentStage = "",
            temperature = 0.0,
            maxTokens = 0,
            isJsonMode = false,
            ragStructuredContent = null,
        )
        val encoded = json.encodeToString(LlmRequestSnapshot.serializer(), original)
        val decoded = json.decodeFromString(LlmRequestSnapshot.serializer(), encoded)
        assertEquals(original, decoded)
    }
}
