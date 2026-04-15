package com.example.ai_develop.domain

import kotlin.test.Test
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
}
