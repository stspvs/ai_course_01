package com.example.ai_develop.data

import com.example.ai_develop.domain.rag.RagRetrievalConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagRetrievalPipelineTest {

    private fun chunk(text: String, id: String = "c1") = RagIndexedChunk(
        chunkId = id,
        documentId = "d1",
        chunkIndex = 0L,
        text = text,
        embedding = floatArrayOf(1f, 0f),
        ollamaModel = "m",
        documentTitle = "t",
        sourceFileName = "f.txt",
    )

    @Test
    fun lexicalJaccard_identicalTexts_isOne() {
        val s = lexicalJaccard("hello world", "hello world")
        assertEquals(1f, s, 1e-5f)
    }

    @Test
    fun lexicalJaccard_disjoint_isZero() {
        val s = lexicalJaccard("aaa bbb", "ccc ddd")
        assertEquals(0f, s, 1e-5f)
    }

    @Test
    fun lexicalJaccard_bothEmptyOrPunctuationOnly_isOne() {
        assertEquals(1f, lexicalJaccard("", ""), 1e-5f)
        assertEquals(1f, lexicalJaccard("...", "!!!"), 1e-5f)
    }

    @Test
    fun lexicalJaccard_oneSideEmpty_otherNonEmpty_isZero() {
        assertEquals(0f, lexicalJaccard("", "hello"), 1e-5f)
        assertEquals(0f, lexicalJaccard("world", ""), 1e-5f)
    }

    @Test
    fun lexicalJaccard_singleCharTokensFiltered_partialOverlap() {
        val s = lexicalJaccard("a bb cc", "bb cc dd")
        assertTrue(s > 0f && s < 1f)
    }

    @Test
    fun lexicalJaccard_caseAndPunctuationInsensitive() {
        val s = lexicalJaccard("Hello, WORLD!", "world... hello")
        assertEquals(1f, s, 1e-5f)
    }

    @Test
    fun applyCosineThreshold_filtersBelow() {
        val a = ScoredChunk(chunk("a"), 0.9f)
        val b = ScoredChunk(chunk("b"), 0.2f)
        val out = applyCosineThreshold(listOf(a, b), 0.5f)
        assertEquals(1, out.size)
        assertEquals(0.9f, out[0].cosine)
    }

    @Test
    fun applyCosineThreshold_nullPassesThrough() {
        val list = listOf(ScoredChunk(chunk("a"), 0.1f))
        assertContentEquals(list, applyCosineThreshold(list, null))
    }

    @Test
    fun applyCosineThreshold_allRemoved_isEmpty() {
        val a = ScoredChunk(chunk("a"), 0.1f)
        assertTrue(applyCosineThreshold(listOf(a), 0.5f).isEmpty())
    }

    @Test
    fun hybridCombinedScore_blends() {
        val h = hybridCombinedScore(0.8f, 0.2f, 0.5f)
        assertEquals(0.5f, h, 1e-5f)
    }

    @Test
    fun hybridCombinedScore_weightClampedToZeroOne() {
        assertEquals(hybridCombinedScore(0.8f, 0.2f, 0f), hybridCombinedScore(0.8f, 0.2f, -99f), 1e-5f)
        assertEquals(hybridCombinedScore(0.8f, 0.2f, 1f), hybridCombinedScore(0.8f, 0.2f, 99f), 1e-5f)
    }

    @Test
    fun hybridCombinedScore_extremeWeights() {
        assertEquals(0.3f, hybridCombinedScore(0.3f, 0.9f, 1f), 1e-5f)
        assertEquals(0.9f, hybridCombinedScore(0.3f, 0.9f, 0f), 1e-5f)
    }

    @Test
    fun effectiveRecallTopK_and_finalTopK_coerceAtLeastOne() {
        assertEquals(1, effectiveRecallTopK(RagRetrievalConfig(recallTopK = 0), scoredSize = 100))
        assertEquals(1, effectiveRecallTopK(RagRetrievalConfig(recallTopK = -100), scoredSize = 100))
        assertEquals(1, effectiveFinalTopK(RagRetrievalConfig(finalTopK = 0), orderedSize = 100))
    }

    @Test
    fun effectiveRecallTopK_respectsScoredSizeCap() {
        assertEquals(3, effectiveRecallTopK(RagRetrievalConfig(recallTopK = 99), scoredSize = 3))
    }

    @Test
    fun effectiveRecallTopK_scanAllChunks_usesAllScoredUpToMax() {
        assertEquals(
            RAG_MAX_CHUNKS_IN_CONTEXT,
            effectiveRecallTopK(RagRetrievalConfig(scanAllChunks = true, recallTopK = 1), scoredSize = 9999),
        )
        assertEquals(7, effectiveRecallTopK(RagRetrievalConfig(scanAllChunks = true, recallTopK = 1), scoredSize = 7))
    }

    @Test
    fun effectiveFinalTopK_scanAllChunks_usesOrderedSizeUpToMax() {
        assertEquals(4, effectiveFinalTopK(RagRetrievalConfig(scanAllChunks = true, finalTopK = 1), orderedSize = 4))
    }

    @Test
    fun rerankHybrid_preservesSize() {
        val q = "a"
        val a = ScoredChunk(chunk("x"), 0.5f)
        val b = ScoredChunk(chunk("y"), 0.6f)
        val out = rerankHybrid(q, listOf(a, b), 0.3f)
        assertEquals(2, out.size)
    }

    @Test
    fun rerankHybrid_lexicalCanReorderDespiteLowerCosine() {
        val q = "alpha beta gamma"
        val lowCosHighLex = ScoredChunk(chunk("alpha beta gamma extra", id = "c1"), 0.4f)
        val highCosLowLex = ScoredChunk(chunk("zzz zzz", id = "c2"), 0.95f)
        val out = rerankHybrid(q, listOf(highCosLowLex, lowCosHighLex), lexicalWeight = 0.2f)
        assertEquals("c1", out[0].chunk.chunkId)
    }

    @Test
    fun rerankHybrid_stress_manyCandidates_sameSize() {
        val q = "query"
        val candidates = (0 until 200).map { i ->
            ScoredChunk(chunk("word$i", id = "id$i"), cosine = (i % 17) / 100f)
        }
        val out = rerankHybrid(q, candidates, 0.35f)
        assertEquals(200, out.size)
    }
}
