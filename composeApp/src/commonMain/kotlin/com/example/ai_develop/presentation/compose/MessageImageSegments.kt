package com.example.ai_develop.presentation.compose

/**
 * Ordered segments for rendering chat message body: plain text and inline image URLs.
 */
sealed interface MessageBodySegment {
    data class Text(val content: String) : MessageBodySegment
    data class Image(val url: String) : MessageBodySegment
}

private val markdownImageRegex = Regex("""!\[[^\]]*]\((https?://[^)]+)\)""")
/** Alternation: QuickChart first (no file extension in path), then static image URLs. */
private val bareImageOrQuickChartRegex = Regex(
    """(?:(?:https?://(?:www\.)?quickchart\.io/chart\?[^\s<>"']+)|(?:https?://[^\s<>"']+\.(?:png|jpg|jpeg|gif|webp|svg)(?:\?[^\s<>"']*)?))""",
    RegexOption.IGNORE_CASE
)

private val imageExtensionInUrl = Regex("""\.(png|jpg|jpeg|gif|webp|svg)(\?|#|$)""", RegexOption.IGNORE_CASE)
private val quickChartImageUrlRegex =
    Regex("""^https?://(?:www\.)?quickchart\.io/chart\?.*\bchart=""", RegexOption.IGNORE_CASE)

internal fun isHttpImageUrl(url: String): Boolean {
    val t = url.trim()
    if (!t.startsWith("http://", ignoreCase = true) && !t.startsWith("https://", ignoreCase = true)) {
        return false
    }
    if (quickChartImageUrlRegex.containsMatchIn(t)) return true
    return imageExtensionInUrl.containsMatchIn(t)
}

/**
 * Splits [raw] into text and image URL segments in document order.
 * Supports markdown `![](url)` and bare `https://.../file.ext` URLs (http/https only).
 */
fun parseMessageBodySegments(raw: String): List<MessageBodySegment> {
    val result = mutableListOf<MessageBodySegment>()
    var pos = 0
    while (pos < raw.length) {
        val nextMd = markdownImageRegex.find(raw, pos)
        val nextStart = nextMd?.range?.first ?: raw.length
        if (nextStart > pos) {
            result += parseBareUrlsInTextChunk(raw.substring(pos, nextStart))
            pos = nextStart
            continue
        }
        if (nextMd != null) {
            val url = nextMd.groupValues[1].trim()
            if (isHttpImageUrl(url)) {
                result += MessageBodySegment.Image(url)
            } else {
                result += MessageBodySegment.Text(nextMd.value)
            }
            pos = nextMd.range.last + 1
        } else {
            break
        }
    }
    return mergeAdjacentText(result)
}

private fun parseBareUrlsInTextChunk(chunk: String): List<MessageBodySegment> {
    if (chunk.isEmpty()) return emptyList()
    val out = mutableListOf<MessageBodySegment>()
    var start = 0
    for (m in bareImageOrQuickChartRegex.findAll(chunk)) {
        if (m.range.first > start) {
            val text = chunk.substring(start, m.range.first)
            if (text.isNotEmpty()) out += MessageBodySegment.Text(text)
        }
        val url = m.value
        if (isHttpImageUrl(url)) {
            out += MessageBodySegment.Image(url)
        } else {
            out += MessageBodySegment.Text(m.value)
        }
        start = m.range.last + 1
    }
    if (start < chunk.length) {
        val text = chunk.substring(start)
        if (text.isNotEmpty()) out += MessageBodySegment.Text(text)
    }
    return out
}

private fun mergeAdjacentText(segments: List<MessageBodySegment>): List<MessageBodySegment> {
    val merged = mutableListOf<MessageBodySegment>()
    for (seg in segments) {
        if (seg is MessageBodySegment.Text && merged.lastOrNull() is MessageBodySegment.Text) {
            val prev = merged.removeAt(merged.lastIndex) as MessageBodySegment.Text
            merged += MessageBodySegment.Text(prev.content + seg.content)
        } else {
            merged += seg
        }
    }
    return merged
}

/** Image URLs from [raw] in order (for task bubbles that render text separately). */
fun imageUrlsInOrder(raw: String): List<String> =
    parseMessageBodySegments(raw).filterIsInstance<MessageBodySegment.Image>().map { it.url }

/** Text parts only (excludes extracted image URLs and markdown image syntax). */
fun messageBodyTextOnly(segments: List<MessageBodySegment>): String =
    segments.filterIsInstance<MessageBodySegment.Text>().joinToString("") { it.content }
