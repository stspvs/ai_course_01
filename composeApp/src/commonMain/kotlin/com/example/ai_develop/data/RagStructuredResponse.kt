package com.example.ai_develop.data

import com.example.ai_develop.domain.rag.RagAttribution
import com.example.ai_develop.domain.rag.RagStructuredChatPayload
import com.example.ai_develop.domain.rag.RagStructuredQuoteLine
import com.example.ai_develop.domain.rag.RagStructuredSourceLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RagStructuredJson(
    val answer: String,
    val sources: List<RagSourceJsonItem> = emptyList(),
    val quotes: List<RagQuoteJsonItem> = emptyList(),
)

@Serializable
data class RagSourceJsonItem(
    /** Имя файла или краткий идентификатор источника. */
    val source: String,
    @SerialName("chunk_id") val chunkId: String,
    @SerialName("chunk_index") val chunkIndex: Long,
)

@Serializable
data class RagQuoteJsonItem(
    val text: String,
    @SerialName("chunk_id") val chunkId: String,
)

data class RagStructuredParseResult(
    val formattedChatText: String,
    val parseWarning: String? = null,
    /** Заполняется при успешном разборе JSON. */
    val structuredPayload: RagStructuredChatPayload? = null,
)

fun toRagStructuredChatPayload(
    parsed: RagStructuredJson,
    validationNote: String?,
): RagStructuredChatPayload = RagStructuredChatPayload(
    answer = parsed.answer.trim(),
    sources = parsed.sources.map {
        RagStructuredSourceLine(source = it.source, chunkId = it.chunkId, chunkIndex = it.chunkIndex)
    },
    quotes = parsed.quotes.map {
        RagStructuredQuoteLine(text = it.text.trim(), chunkId = it.chunkId)
    },
    validationNote = validationNote?.takeIf { it.isNotBlank() },
)

private val ragJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun parseRagStructuredJson(raw: String): Result<RagStructuredJson> = runCatching {
    val trimmed = raw.trim()
    val jsonStart = trimmed.indexOf('{').takeIf { it >= 0 } ?: error("No JSON object in response")
    val jsonEnd = trimmed.lastIndexOf('}').takeIf { it >= jsonStart } ?: error("No closing brace")
    val slice = trimmed.substring(jsonStart, jsonEnd + 1)
    ragJson.decodeFromString<RagStructuredJson>(slice)
}

/**
 * Нормализация для проверки подстроки: схлопывание пробелов и переводов строк.
 */
internal fun normalizeForQuoteMatch(s: String): String =
    s.replace(Regex("\\s+"), " ").trim()

/**
 * Если модель указала в цитате несуществующий [RagQuoteJsonItem.chunkId], но текст цитаты — подстрока
 * ровно одного чанка из [RagAttribution.sources], подставляет корректный [RagSourceRef.chunkId].
 */
internal fun healRagStructuredQuoteChunkIds(
    parsed: RagStructuredJson,
    attribution: RagAttribution,
): RagStructuredJson {
    val byId = attribution.sources.associateBy { it.chunkId }
    val healedQuotes = parsed.quotes.map { q ->
        if (byId[q.chunkId] != null) return@map q
        val nq = normalizeForQuoteMatch(q.text)
        if (nq.isEmpty()) return@map q
        val candidates = attribution.sources.filter { src ->
            normalizeForQuoteMatch(src.chunkText).contains(nq)
        }
        when (candidates.size) {
            1 -> RagQuoteJsonItem(text = q.text, chunkId = candidates.single().chunkId)
            else -> q
        }
    }
    return parsed.copy(quotes = healedQuotes)
}

fun validateRagStructuredAgainstAttribution(
    parsed: RagStructuredJson,
    attribution: RagAttribution,
): List<String> {
    val issues = mutableListOf<String>()
    val byId = attribution.sources.associateBy { it.chunkId }
    val hasGrounding = attribution.used && !attribution.insufficientRelevance && attribution.sources.isNotEmpty()

    if (hasGrounding) {
        if (parsed.sources.isEmpty()) {
            issues += "В ответе нет источников при наличии контекста из базы."
        }
        if (parsed.quotes.isEmpty()) {
            issues += "В ответе нет цитат при наличии контекста из базы."
        }
    }

    if (attribution.insufficientRelevance || !attribution.used) {
        if (parsed.sources.isNotEmpty()) {
            issues += "Источники должны быть пустыми при отсутствии надёжного контекста."
        }
        if (parsed.quotes.isNotEmpty()) {
            issues += "Цитаты должны быть пустыми при отсутствии надёжного контекста."
        }
    }

    for (q in parsed.quotes) {
        val ref = byId[q.chunkId]
        if (ref == null) {
            issues += "Цитата ссылается на неизвестный chunk_id: ${q.chunkId}"
            continue
        }
        val nq = normalizeForQuoteMatch(q.text)
        val nt = normalizeForQuoteMatch(ref.chunkText)
        if (nq.isNotEmpty() && !nt.contains(nq)) {
            issues += "Цитата не является фрагментом чанка ${q.chunkId}."
        }
    }

    return issues
}

fun formatRagStructuredForChat(
    parsed: RagStructuredJson,
    issues: List<String>,
): String = buildString {
    appendLine(parsed.answer.trim())
    if (issues.isNotEmpty()) {
        appendLine()
        appendLine("— Примечание: ${issues.joinToString(" ")} —")
    }
    if (parsed.sources.isNotEmpty()) {
        appendLine()
        appendLine("Источники:")
        parsed.sources.forEachIndexed { i, s ->
            appendLine("${i + 1}. ${s.source} (chunk_id=${s.chunkId}, chunk_index=${s.chunkIndex})")
        }
    }
    if (parsed.quotes.isNotEmpty()) {
        appendLine()
        appendLine("Цитаты:")
        parsed.quotes.forEachIndexed { i, q ->
            appendLine("${i + 1}. [${q.chunkId}] «${q.text.trim()}»")
        }
    }
}.trim()

fun processRagAssistantRawJson(
    rawModelText: String,
    attribution: RagAttribution?,
): RagStructuredParseResult {
    val attr = attribution ?: return RagStructuredParseResult(
        formattedChatText = rawModelText.trim(),
        parseWarning = null,
        structuredPayload = null,
    )
    val cleaned = stripLeadingJsonColonLabel(rawModelText)
    val parsed = parseRagStructuredJson(cleaned).getOrNull()
        ?: return RagStructuredParseResult(
            formattedChatText = cleaned.trim(),
            parseWarning = "Не удалось разобрать JSON ответа модели.",
            structuredPayload = null,
        )
    val healed = healRagStructuredQuoteChunkIds(parsed, attr)
    val issues = validateRagStructuredAgainstAttribution(healed, attr)
    val formatted = formatRagStructuredForChat(healed, issues)
    val note = issues.takeIf { it.isNotEmpty() }?.joinToString(" ")
    return RagStructuredParseResult(
        formattedChatText = formatted,
        parseWarning = note,
        structuredPayload = toRagStructuredChatPayload(healed, note),
    )
}
