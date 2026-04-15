package com.example.ai_develop.domain

data class TextChunk(
    val index: Int,
    val start: Int,
    val end: Int,
    val text: String,
)

object TextChunker {

    fun chunk(text: String, chunkSize: Int, overlap: Int): List<TextChunk> =
        chunk(text, ChunkStrategy.FIXED_WINDOW, chunkSize, overlap)

    fun chunk(text: String, strategy: ChunkStrategy, chunkSize: Int, overlap: Int): List<TextChunk> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(overlap >= 0) { "overlap must be non-negative" }
        require(overlap < chunkSize) { "overlap must be less than chunkSize" }
        if (text.isEmpty()) return emptyList()
        val raw = when (strategy) {
            ChunkStrategy.FIXED_WINDOW -> rangesFixedWindow(0, text.length, chunkSize, overlap)
            ChunkStrategy.PARAGRAPH -> rangesParagraphs(text, chunkSize, overlap)
            ChunkStrategy.SENTENCE -> rangesSentences(text, chunkSize, overlap)
            ChunkStrategy.RECURSIVE -> rangesRecursive(text, 0, text.length, chunkSize, overlap, 0)
        }
        return raw.mapIndexed { index, range ->
            val a = range.first
            val b = range.last + 1
            TextChunk(index = index, start = a, end = b, text = text.substring(a, b))
        }
    }

    private fun rangesFixedWindow(rangeStart: Int, rangeEndExclusive: Int, chunkSize: Int, overlap: Int): List<IntRange> {
        val step = chunkSize - overlap
        val out = mutableListOf<IntRange>()
        var start = rangeStart
        while (start < rangeEndExclusive) {
            val end = (start + chunkSize).coerceAtMost(rangeEndExclusive)
            out.add(start until end)
            if (end >= rangeEndExclusive) break
            start += step
        }
        return out
    }

    private fun paragraphSpans(text: String): List<IntRange> {
        val re = Regex("\\r?\\n\\s*\\r?\\n")
        if (!re.containsMatchIn(text)) {
            return listOf(0 until text.length)
        }
        val out = mutableListOf<IntRange>()
        var start = 0
        re.findAll(text).forEach { m ->
            val end = m.range.first
            if (end > start) out.add(start until end)
            start = m.range.last + 1
        }
        if (start < text.length) out.add(start until text.length)
        return out
    }

    private fun rangesParagraphs(text: String, chunkSize: Int, overlap: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (span in paragraphSpans(text)) {
            val len = span.last - span.first + 1
            if (len <= chunkSize) {
                out.add(span)
            } else {
                out.addAll(rangesFixedWindow(span.first, span.last + 1, chunkSize, overlap))
            }
        }
        return out
    }

    private fun sentenceSpans(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val re = Regex("(?<=[.!?…])\\s+")
        val out = mutableListOf<IntRange>()
        var start = 0
        re.findAll(text).forEach { m ->
            val end = m.range.first
            if (end > start) out.add(start until end)
            start = m.range.last + 1
        }
        if (start < text.length) out.add(start until text.length)
        return out
    }

    private fun mergeSpansToLimit(full: String, spans: List<IntRange>, maxLen: Int): List<IntRange> {
        if (spans.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var cur = spans[0]
        for (i in 1 until spans.size) {
            val next = spans[i]
            val combined = full.substring(cur.first, next.last + 1)
            if (combined.length <= maxLen) {
                cur = cur.first until next.last + 1
            } else {
                out.add(cur)
                cur = next
            }
        }
        out.add(cur)
        return out
    }

    private fun rangesSentences(text: String, chunkSize: Int, overlap: Int): List<IntRange> {
        val spans = sentenceSpans(text)
        if (spans.isEmpty()) return emptyList()
        val merged = mergeSpansToLimit(text, spans, chunkSize)
        val out = mutableListOf<IntRange>()
        for (span in merged) {
            val len = span.last - span.first + 1
            if (len <= chunkSize) {
                out.add(span)
            } else {
                out.addAll(rangesFixedWindow(span.first, span.last + 1, chunkSize, overlap))
            }
        }
        return out
    }

    private fun splitBySeparator(full: String, rangeStart: Int, rangeEndExclusive: Int, sep: String): List<IntRange> {
        val sub = full.substring(rangeStart, rangeEndExclusive)
        if (sep.isEmpty()) return listOf(rangeStart until rangeEndExclusive)
        val parts = mutableListOf<IntRange>()
        var from = 0
        while (from <= sub.length) {
            val idx = if (from < sub.length) sub.indexOf(sep, from) else -1
            if (idx == -1) {
                if (from < sub.length) {
                    parts.add((rangeStart + from) until rangeEndExclusive)
                }
                break
            }
            if (idx > from) {
                parts.add((rangeStart + from) until (rangeStart + idx))
            }
            from = idx + sep.length
        }
        return parts.filter { it.first < it.last + 1 }
    }

    private fun mergeRangesToLimit(full: String, parts: List<IntRange>, chunkSize: Int): List<IntRange> {
        if (parts.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var cur = parts[0]
        for (i in 1 until parts.size) {
            val next = parts[i]
            val combined = full.substring(cur.first, next.last + 1)
            if (combined.length <= chunkSize) {
                cur = cur.first until next.last + 1
            } else {
                out.add(cur)
                cur = next
            }
        }
        out.add(cur)
        return out
    }

    private fun rangesRecursive(
        full: String,
        rangeStart: Int,
        rangeEndExclusive: Int,
        chunkSize: Int,
        overlap: Int,
        sepDepth: Int,
    ): List<IntRange> {
        val len = rangeEndExclusive - rangeStart
        if (len <= 0) return emptyList()
        if (len <= chunkSize) return listOf(rangeStart until rangeEndExclusive)

        val seps = listOf("\n\n", "\n", ". ", " ")
        if (sepDepth >= seps.size) {
            return rangesFixedWindow(rangeStart, rangeEndExclusive, chunkSize, overlap)
        }

        val sep = seps[sepDepth]
        val parts = splitBySeparator(full, rangeStart, rangeEndExclusive, sep)
        if (parts.size <= 1) {
            return rangesRecursive(full, rangeStart, rangeEndExclusive, chunkSize, overlap, sepDepth + 1)
        }

        val merged = mergeRangesToLimit(full, parts, chunkSize)
        val result = mutableListOf<IntRange>()
        for (r in merged) {
            val rLen = r.last - r.first + 1
            if (rLen <= chunkSize) {
                result.add(r)
            } else {
                result.addAll(rangesRecursive(full, r.first, r.last + 1, chunkSize, overlap, sepDepth + 1))
            }
        }
        return result
    }
}
