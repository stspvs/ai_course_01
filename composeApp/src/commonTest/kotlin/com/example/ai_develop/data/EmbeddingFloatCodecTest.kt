package com.example.ai_develop.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmbeddingFloatCodecTest {

    @Test
    fun roundtrip() {
        val original = floatArrayOf(0f, -1.5f, 3.25f, 1e-7f)
        val bytes = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(original)
        assertEquals(original.size * 4, bytes.size)
        val back = EmbeddingFloatCodec.littleEndianBytesToFloatArray(bytes)
        assertContentEquals(original, back)
    }

    @Test
    fun rejectsOddBlobLength() {
        assertFailsWith<IllegalArgumentException> {
            EmbeddingFloatCodec.littleEndianBytesToFloatArray(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun l2NormalizeUnitLength() {
        val n = EmbeddingNormalization.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, EmbeddingNormalization.l2Norm(n), absoluteTolerance = 1e-5f)
        assertEquals(0.6f, n[0], absoluteTolerance = 1e-5f)
        assertEquals(0.8f, n[1], absoluteTolerance = 1e-5f)
    }

    @Test
    fun l2NormalizeZeroVector() {
        val n = EmbeddingNormalization.l2Normalize(floatArrayOf(0f, 0f))
        assertEquals(0f, n[0])
        assertEquals(0f, n[1])
    }

    @Test
    fun l2NormalizeIdempotentForUnitVector() {
        val unit = EmbeddingNormalization.l2Normalize(floatArrayOf(3f, 4f))
        val again = EmbeddingNormalization.l2Normalize(unit)
        assertContentEquals(unit, again)
    }
}
