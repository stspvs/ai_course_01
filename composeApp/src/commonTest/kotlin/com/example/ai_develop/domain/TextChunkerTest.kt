package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextChunkerTest {

    @Test
    fun emptyText() {
        assertTrue(TextChunker.chunk("", 100, 10).isEmpty())
    }

    @Test
    fun singleChunkWhenShorterThanSize() {
        val t = "hello"
        val c = TextChunker.chunk(t, 100, 10)
        assertEquals(1, c.size)
        assertEquals(0, c[0].start)
        assertEquals(5, c[0].end)
        assertEquals("hello", c[0].text)
    }

    @Test
    fun slidingWindowOverlap() {
        val t = "abcdefghij"
        val c = TextChunker.chunk(t, 4, 2)
        assertEquals(4, c.size)
        assertEquals("abcd", c[0].text)
        assertEquals("cdef", c[1].text)
        assertEquals("efgh", c[2].text)
        assertEquals("ghij", c[3].text)
    }

    @Test
    fun overlapMustBeLessThanChunkSize() {
        assertFailsWith<IllegalArgumentException> {
            TextChunker.chunk("a", 10, 10)
        }
    }

    @Test
    fun paragraphStrategySplitsOnBlankLines() {
        val t = "First block.\n\nSecond block."
        val c = TextChunker.chunk(t, ChunkStrategy.PARAGRAPH, 100, 10)
        assertEquals(2, c.size)
        assertEquals("First block.", c[0].text)
        assertEquals("Second block.", c[1].text)
    }

    @Test
    fun fixedWindowDelegatesToOverload() {
        val t = "abcdefghij"
        val a = TextChunker.chunk(t, 4, 2)
        val b = TextChunker.chunk(t, ChunkStrategy.FIXED_WINDOW, 4, 2)
        assertEquals(a.size, b.size)
        assertContentEquals(a.map { it.text }, b.map { it.text })
    }

    @Test
    fun chunkSizeMustBePositive() {
        assertFailsWith<IllegalArgumentException> {
            TextChunker.chunk("a", 0, 0)
        }
    }

    @Test
    fun overlapMustBeNonNegative() {
        assertFailsWith<IllegalArgumentException> {
            TextChunker.chunk("a", 5, -1)
        }
    }

    @Test
    fun sentenceStrategy_mergesWithinChunkSize() {
        val t = "One. Two! Three?"
        val c = TextChunker.chunk(t, ChunkStrategy.SENTENCE, 100, 10)
        assertTrue(c.isNotEmpty())
        assertTrue(c.joinToString(" ") { it.text }.contains("One"))
    }

    @Test
    fun recursiveStrategy_producesRangesCoveringText() {
        val t = "alpha beta gamma delta epsilon"
        val c = TextChunker.chunk(t, ChunkStrategy.RECURSIVE, 12, 2)
        assertTrue(c.isNotEmpty())
        assertEquals(t.length, c.last().end)
        for (ch in c) {
            assertEquals(t.substring(ch.start, ch.end), ch.text)
        }
    }

    @Test
    fun stress_manySmallWindows() {
        val t = "x".repeat(10_000)
        val c = TextChunker.chunk(t, ChunkStrategy.FIXED_WINDOW, 32, 8)
        assertTrue(c.size > 100)
        assertTrue(c.all { it.text.length <= 32 })
    }

    @Test
    fun corner_chunkSizeOne_overlapZero() {
        val t = "abcd"
        val c = TextChunker.chunk(t, ChunkStrategy.FIXED_WINDOW, 1, 0)
        assertEquals(4, c.size)
        assertEquals("a", c[0].text)
        assertEquals("d", c[3].text)
    }

    @Test
    fun paragraphStrategy_longParagraph_fallsBackToWindow() {
        val longPara = "w".repeat(200)
        val t = "short\n\n$longPara"
        val c = TextChunker.chunk(t, ChunkStrategy.PARAGRAPH, 40, 5)
        assertTrue(c.size >= 3)
    }
}
