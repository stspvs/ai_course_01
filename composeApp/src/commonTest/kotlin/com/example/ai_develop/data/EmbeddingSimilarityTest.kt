package com.example.ai_develop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmbeddingSimilarityTest {

    @Test
    fun dotProduct_orthogonal_vectors_isZero() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0f, dotProduct(a, b), 1e-6f)
    }

    @Test
    fun dotProduct_sameNormalizedVector_isOne() {
        val a = floatArrayOf(1f, 0f, 0f)
        assertEquals(1f, dotProduct(a, a), 1e-6f)
    }

    @Test
    fun dotProduct_dimensionMismatch_throws() {
        assertFailsWith<IllegalArgumentException> {
            dotProduct(floatArrayOf(1f), floatArrayOf(1f, 0f))
        }
    }
}
