package com.example.ai_develop.data

import kotlin.math.sqrt

/**
 * L2-нормализация эмбеддингов: вектор делится на ||v||₂, чтобы длина стала 1.
 * Тогда косинусное сходство совпадает со скалярным произведением — удобно для RAG-поиска по BLOB.
 * Нулевой вектор возвращается как копия без изменений (избегаем NaN).
 */
object EmbeddingNormalization {

    fun l2Normalize(values: FloatArray): FloatArray {
        if (values.isEmpty()) return floatArrayOf()
        var sumSq = 0.0
        for (x in values) {
            val d = x.toDouble()
            sumSq += d * d
        }
        if (sumSq == 0.0) return values.copyOf()
        val invNorm = (1.0 / sqrt(sumSq)).toFloat()
        return FloatArray(values.size) { i -> values[i] * invNorm }
    }

    fun l2Norm(values: FloatArray): Float {
        var sumSq = 0.0
        for (x in values) {
            val d = x.toDouble()
            sumSq += d * d
        }
        return sqrt(sumSq).toFloat()
    }
}
