package com.example.ai_develop.domain

data class TextChunk(
    val index: Int,
    val start: Int,
    val end: Int,
    val text: String,
)

object TextChunker {

    fun chunk(text: String, chunkSize: Int, overlap: Int): List<TextChunk> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(overlap >= 0) { "overlap must be non-negative" }
        require(overlap < chunkSize) { "overlap must be less than chunkSize" }

        if (text.isEmpty()) return emptyList()

        val step = chunkSize - overlap
        val out = mutableListOf<TextChunk>()
        var start = 0
        var index = 0
        while (start < text.length) {
            val end = (start + chunkSize).coerceAtMost(text.length)
            val slice = text.substring(start, end)
            out.add(TextChunk(index = index, start = start, end = end, text = slice))
            if (end >= text.length) break
            start += step
            index++
        }
        return out
    }
}
