package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RagPipelineModeUiTest {

    @Test
    fun baseline_hidesThresholdHybridLlmSteps() {
        val m = RagPipelineMode.Baseline
        assertFalse(m.showsMinSimilarity())
        assertFalse(m.showsHybridLexicalWeight())
        assertFalse(m.showsLlmRerankControls())
        assertFalse(m.showsEvaluationThresholdStep())
        assertFalse(m.showsEvaluationHeuristicStep())
        assertFalse(m.showsEvaluationLlmRerankStep())
    }

    @Test
    fun threshold_showsMinSimilarity_only() {
        val m = RagPipelineMode.Threshold
        assertTrue(m.showsMinSimilarity())
        assertFalse(m.showsHybridLexicalWeight())
        assertFalse(m.showsLlmRerankControls())
        assertTrue(m.showsEvaluationThresholdStep())
        assertFalse(m.showsEvaluationHeuristicStep())
        assertFalse(m.showsEvaluationLlmRerankStep())
    }

    @Test
    fun hybrid_showsMinAndHybrid() {
        val m = RagPipelineMode.Hybrid
        assertTrue(m.showsMinSimilarity())
        assertTrue(m.showsHybridLexicalWeight())
        assertFalse(m.showsLlmRerankControls())
        assertTrue(m.showsEvaluationThresholdStep())
        assertTrue(m.showsEvaluationHeuristicStep())
        assertFalse(m.showsEvaluationLlmRerankStep())
    }

    @Test
    fun llmRerank_showsMinAndLlmControls() {
        val m = RagPipelineMode.LlmRerank
        assertTrue(m.showsMinSimilarity())
        assertFalse(m.showsHybridLexicalWeight())
        assertTrue(m.showsLlmRerankControls())
        assertTrue(m.showsEvaluationThresholdStep())
        assertFalse(m.showsEvaluationHeuristicStep())
        assertTrue(m.showsEvaluationLlmRerankStep())
    }
}
