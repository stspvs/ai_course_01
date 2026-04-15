package com.example.ai_develop.data

/**
 * Скалярное произведение для L2-нормализованных векторов (= косинусная близость).
 */
fun dotProduct(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Vector dim mismatch: ${a.size} vs ${b.size}" }
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}
