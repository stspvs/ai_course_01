package com.example.ai_develop.domain.rag
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.llm.*

import kotlin.test.Test
import kotlin.test.assertEquals

class ChunkStrategyTest {

    @Test
    fun fromId_knownId_returnsStrategy() {
        assertEquals(ChunkStrategy.PARAGRAPH, ChunkStrategy.fromId("PARAGRAPH"))
        assertEquals(ChunkStrategy.SENTENCE, ChunkStrategy.fromId("SENTENCE"))
        assertEquals(ChunkStrategy.RECURSIVE, ChunkStrategy.fromId("RECURSIVE"))
    }

    @Test
    fun fromId_nullOrUnknown_defaultsToFixedWindow() {
        assertEquals(ChunkStrategy.FIXED_WINDOW, ChunkStrategy.fromId(null))
        assertEquals(ChunkStrategy.FIXED_WINDOW, ChunkStrategy.fromId(""))
        assertEquals(ChunkStrategy.FIXED_WINDOW, ChunkStrategy.fromId("NOPE"))
    }

    @Test
    fun ids_areUnique() {
        val ids = ChunkStrategy.entries.map { it.id }.toSet()
        assertEquals(ChunkStrategy.entries.size, ids.size)
    }
}
