package com.example.ai_develop.data

import com.example.ai_develop.domain.RagAttribution
import com.example.ai_develop.domain.RagRetrievalDebug
import com.example.ai_develop.domain.RagSourceRef
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RagStructuredResponseValidationTest {

    private val chunkA = RagSourceRef(
        documentTitle = "Doc",
        sourceFileName = "a.md",
        chunkIndex = 0L,
        score = 0.9,
        chunkId = "id-a",
        chunkText = "Альфа бета гамма дельта.",
    )

    @Test
    fun parse_validJson_extractsAnswerSourcesQuotes() {
        val raw = """{"answer":"Ответ","sources":[{"source":"a.md","chunk_id":"id-a","chunk_index":0}],"quotes":[{"text":"Альфа бета","chunk_id":"id-a"}]}"""
        val p = parseRagStructuredJson(raw).getOrThrow()
        assertEquals("Ответ", p.answer)
        assertEquals(1, p.sources.size)
        assertEquals("id-a", p.sources[0].chunkId)
        assertEquals(1, p.quotes.size)
    }

    @Test
    fun validate_grounded_missingSources_reportsIssue() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val parsed = RagStructuredJson(
            answer = "x",
            sources = emptyList(),
            quotes = listOf(RagQuoteJsonItem(text = "Альфа бета", chunkId = "id-a")),
        )
        val issues = validateRagStructuredAgainstAttribution(parsed, attr)
        assertTrue(issues.any { it.contains("источников") })
    }

    @Test
    fun validate_grounded_missingQuotes_reportsIssue() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val parsed = RagStructuredJson(
            answer = "x",
            sources = listOf(RagSourceJsonItem(source = "a.md", chunkId = "id-a", chunkIndex = 0L)),
            quotes = emptyList(),
        )
        val issues = validateRagStructuredAgainstAttribution(parsed, attr)
        assertTrue(issues.any { it.contains("цитат") })
    }

    @Test
    fun validate_quoteSubstring_passes() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val parsed = RagStructuredJson(
            answer = "ok",
            sources = listOf(RagSourceJsonItem(source = "a.md", chunkId = "id-a", chunkIndex = 0L)),
            quotes = listOf(RagQuoteJsonItem(text = "Альфа бета", chunkId = "id-a")),
        )
        assertTrue(validateRagStructuredAgainstAttribution(parsed, attr).isEmpty())
    }

    @Test
    fun validate_quoteNotSubstring_reportsIssue() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val parsed = RagStructuredJson(
            answer = "ok",
            sources = listOf(RagSourceJsonItem(source = "a.md", chunkId = "id-a", chunkIndex = 0L)),
            quotes = listOf(RagQuoteJsonItem(text = "нет в чанке", chunkId = "id-a")),
        )
        val issues = validateRagStructuredAgainstAttribution(parsed, attr)
        assertTrue(issues.any { it.contains("фрагментом чанка") })
    }

    @Test
    fun validate_insufficientRelevance_extraSources_reportsIssue() {
        val attr = RagAttribution(
            used = false,
            insufficientRelevance = true,
            sources = emptyList(),
            debug = RagRetrievalDebug(emptyReason = "ниже порога"),
        )
        val parsed = RagStructuredJson(
            answer = "Не знаю",
            sources = listOf(RagSourceJsonItem(source = "x", chunkId = "id-a", chunkIndex = 0L)),
            quotes = emptyList(),
        )
        val issues = validateRagStructuredAgainstAttribution(parsed, attr)
        assertTrue(issues.any { it.contains("Источники должны быть пустыми") })
    }

    @Test
    fun validate_insufficientRelevance_extraQuotes_reportsIssue() {
        val attr = RagAttribution(used = false, insufficientRelevance = true, sources = emptyList())
        val parsed = RagStructuredJson(
            answer = "Не знаю",
            sources = emptyList(),
            quotes = listOf(RagQuoteJsonItem(text = "x", chunkId = "id-a")),
        )
        val issues = validateRagStructuredAgainstAttribution(parsed, attr)
        assertTrue(issues.any { it.contains("Цитаты должны быть пустыми") })
    }

    @Test
    fun validate_unknownChunkIdInQuote_reportsIssue() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val parsed = RagStructuredJson(
            answer = "ok",
            sources = listOf(RagSourceJsonItem(source = "a.md", chunkId = "id-a", chunkIndex = 0L)),
            quotes = listOf(RagQuoteJsonItem(text = "Альфа", chunkId = "unknown")),
        )
        val issues = validateRagStructuredAgainstAttribution(parsed, attr)
        assertTrue(issues.any { it.contains("неизвестный chunk_id") })
    }

    @Test
    fun heal_healsWrongChunkIdWhenQuoteUniquelyMatchesOneChunk() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val parsed = RagStructuredJson(
            answer = "ok",
            sources = listOf(RagSourceJsonItem(source = "a.md", chunkId = "id-a", chunkIndex = 0L)),
            quotes = listOf(RagQuoteJsonItem(text = "Альфа", chunkId = "wrong-id")),
        )
        val healed = healRagStructuredQuoteChunkIds(parsed, attr)
        assertTrue(validateRagStructuredAgainstAttribution(healed, attr).isEmpty())
        assertEquals("id-a", healed.quotes.single().chunkId)
    }

    @Test
    fun heal_doesNotHealWhenAmbiguousSubstring() {
        val chunkB = RagSourceRef(
            documentTitle = "Doc",
            sourceFileName = "b.md",
            chunkIndex = 1L,
            score = 0.8,
            chunkId = "id-b",
            chunkText = "Префикс общая подстрока суффикс.",
        )
        val chunkC = RagSourceRef(
            documentTitle = "Doc",
            sourceFileName = "c.md",
            chunkIndex = 2L,
            score = 0.8,
            chunkId = "id-c",
            chunkText = "Другой префикс общая подстрока другой суффикс.",
        )
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkB, chunkC))
        val parsed = RagStructuredJson(
            answer = "ok",
            sources = listOf(
                RagSourceJsonItem(source = "b.md", chunkId = "id-b", chunkIndex = 1L),
                RagSourceJsonItem(source = "c.md", chunkId = "id-c", chunkIndex = 2L),
            ),
            quotes = listOf(RagQuoteJsonItem(text = "общая подстрока", chunkId = "unknown")),
        )
        val healed = healRagStructuredQuoteChunkIds(parsed, attr)
        assertEquals("unknown", healed.quotes.single().chunkId)
        val issues = validateRagStructuredAgainstAttribution(healed, attr)
        assertTrue(issues.any { it.contains("неизвестный chunk_id") })
    }

    @Test
    fun processRagAssistantRawJson_healsWrongQuoteChunkId_endToEnd() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val raw =
            """{"answer":"Да","sources":[{"source":"a.md","chunk_id":"id-a","chunk_index":0}],"quotes":[{"text":"Альфа","chunk_id":"wrong-id"}]}"""
        val r = processRagAssistantRawJson(raw, attr)
        assertNull(r.parseWarning)
        val p = r.structuredPayload
        assertNotNull(p)
        assertEquals("id-a", p.quotes.single().chunkId)
    }

    @Test
    fun format_includesSections() {
        val parsed = RagStructuredJson(
            answer = "Текст",
            sources = listOf(RagSourceJsonItem(source = "f.md", chunkId = "c1", chunkIndex = 2L)),
            quotes = listOf(RagQuoteJsonItem(text = "цит", chunkId = "c1")),
        )
        val out = formatRagStructuredForChat(parsed, emptyList())
        assertContains(out, "Текст")
        assertContains(out, "Источники:")
        assertContains(out, "Цитаты:")
        assertContains(out, "c1")
    }

    @Test
    fun processRagAssistantRawJson_malformed_fallsBackToCleanedText() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val r = processRagAssistantRawJson("not json", attr)
        assertEquals("not json", r.formattedChatText)
        assertNotNull(r.parseWarning)
    }

    @Test
    fun normalizeForQuoteMatch_collapsesWhitespace() {
        val chunk = chunkA.copy(
            chunkText = "Альфа   бета\nгамма",
        )
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunk))
        val parsed = RagStructuredJson(
            answer = "ok",
            sources = listOf(RagSourceJsonItem(source = "a.md", chunkId = "id-a", chunkIndex = 0L)),
            quotes = listOf(RagQuoteJsonItem(text = "Альфа бета гамма", chunkId = "id-a")),
        )
        assertTrue(validateRagStructuredAgainstAttribution(parsed, attr).isEmpty())
    }

    @Test
    fun validate_emptyDb_noGrounding_sourcesMustBeEmptyIfModelAdds() {
        val attr = RagAttribution(
            used = false,
            insufficientRelevance = false,
            sources = emptyList(),
            debug = RagRetrievalDebug(emptyReason = "В базе нет проиндексированных чанков"),
        )
        val parsed = RagStructuredJson(
            answer = "Не знаю, уточните",
            sources = emptyList(),
            quotes = emptyList(),
        )
        val issues = validateRagStructuredAgainstAttribution(parsed, attr)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun toRagStructuredChatPayload_trimsAnswerAndQuoteText() {
        val parsed = RagStructuredJson(
            answer = "  Ответ  ",
            sources = listOf(RagSourceJsonItem(source = "f.md", chunkId = "c1", chunkIndex = 3L)),
            quotes = listOf(RagQuoteJsonItem(text = "  цитата  ", chunkId = "c1")),
        )
        val p = toRagStructuredChatPayload(parsed, "  note  ")
        assertEquals("Ответ", p.answer)
        assertEquals("f.md", p.sources.single().source)
        assertEquals("c1", p.sources.single().chunkId)
        assertEquals(3L, p.sources.single().chunkIndex)
        assertEquals("цитата", p.quotes.single().text)
        assertEquals("  note  ", p.validationNote)
    }

    @Test
    fun processRagAssistantRawJson_valid_setsStructuredPayload() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val raw = """{"answer":"Да","sources":[{"source":"a.md","chunk_id":"id-a","chunk_index":0}],"quotes":[{"text":"Альфа","chunk_id":"id-a"}]}"""
        val r = processRagAssistantRawJson(raw, attr)
        assertNull(r.parseWarning)
        val p = r.structuredPayload
        assertNotNull(p)
        assertEquals("Да", p.answer)
        assertEquals("a.md", p.sources.single().source)
        assertEquals("id-a", p.sources.single().chunkId)
        assertEquals(0L, p.sources.single().chunkIndex)
        assertEquals("Альфа", p.quotes.single().text)
        assertEquals("id-a", p.quotes.single().chunkId)
    }

    @Test
    fun processRagAssistantRawJson_malformed_structuredPayloadNull() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val r = processRagAssistantRawJson("{broken", attr)
        assertNotNull(r.parseWarning)
        assertNull(r.structuredPayload)
    }

    @Test
    fun processRagAssistantRawJson_nullAttribution_structuredPayloadNull() {
        val raw = """{"answer":"x","sources":[],"quotes":[]}"""
        val r = processRagAssistantRawJson(raw, null)
        assertNull(r.parseWarning)
        assertNull(r.structuredPayload)
    }

    @Test
    fun processRagAssistantRawJson_validationIssues_stillStructuredPayloadWithNote() {
        val attr = RagAttribution(used = true, insufficientRelevance = false, sources = listOf(chunkA))
        val raw = """{"answer":"ok","sources":[],"quotes":[]}"""
        val r = processRagAssistantRawJson(raw, attr)
        assertNotNull(r.parseWarning)
        val p = r.structuredPayload
        assertNotNull(p)
        assertEquals("ok", p.answer)
        assertTrue(p.sources.isEmpty())
        assertTrue(p.quotes.isEmpty())
        assertEquals(r.parseWarning, p.validationNote)
    }
}
