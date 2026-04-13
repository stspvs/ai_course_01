package com.example.ai_develop.data

import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Подготовка текста ответа MCP для пузырька чата:
 * — убирает служебную подпись «JSON:» (в начале или отдельной строкой);
 * — если после обычного текста идёт дублирующий JSON (массив/объект), удаляет его.
 *
 * Чистый JSON без пояснения не трогаем (чтобы не обнулять короткие ответы только из JSON).
 */
fun stripLeadingJsonColonLabel(text: String): String {
    var s = text.trimStart()
    while (true) {
        val m = Regex("""(?i)^JSON:\s*""").find(s) ?: break
        s = s.removeRange(m.range).trimStart()
    }
    s = s.replace(Regex("""(?i)(\r?\n)\s*JSON:\s*(\r?\n)"""), "\n")
    s = stripTrailingDuplicateJsonPayload(s)
    return s
}

private fun stripTrailingDuplicateJsonPayload(s: String): String {
    var cur = s.trimEnd()
    while (true) {
        val next = stripOneTrailingJsonSuffixIfProseBefore(cur) ?: break
        cur = next.trimEnd()
    }
    return cur
}

/**
 * Удаляет суффикс, который целиком является валидным JSON, только если перед ним есть непустой «прозаический» текст.
 */
private fun stripOneTrailingJsonSuffixIfProseBefore(t: String): String? {
    val trimmed = t.trimEnd()
    if (trimmed.isEmpty()) return null

    val lastNl = trimmed.lastIndexOf('\n')
    val lastLine = if (lastNl < 0) trimmed else trimmed.substring(lastNl + 1)
    val lastTrimmed = lastLine.trim()
    if (lastTrimmed.isNotEmpty() && (lastTrimmed.startsWith('[') || lastTrimmed.startsWith('{'))) {
        if (runCatching { json.parseToJsonElement(lastTrimmed) }.isSuccess) {
            val prefix = if (lastNl < 0) "" else trimmed.substring(0, lastNl)
            if (prefix.isBlank()) return null
            return prefix.trimEnd()
        }
    }

    for (i in trimmed.indices) {
        if (trimmed[i] != '[' && trimmed[i] != '{') continue
        val atLineStart = i == 0 || trimmed[i - 1] == '\n' || trimmed[i - 1] == '\r'
        if (!atLineStart) continue
        val candidate = trimmed.substring(i)
        if (runCatching { json.parseToJsonElement(candidate) }.isSuccess) {
            val prefix = trimmed.substring(0, i)
            if (prefix.isBlank()) return null
            return prefix.trimEnd()
        }
    }
    return null
}
