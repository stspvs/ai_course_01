package com.example.ai_develop.domain

/**
 * Какие элементы UI показывать для [RagPipelineMode], согласовано с [com.example.ai_develop.data.RagContextRetriever].
 */
fun RagPipelineMode.showsMinSimilarity(): Boolean = this != RagPipelineMode.Baseline

fun RagPipelineMode.showsHybridLexicalWeight(): Boolean = this == RagPipelineMode.Hybrid

fun RagPipelineMode.showsLlmRerankControls(): Boolean = this == RagPipelineMode.LlmRerank

fun RagPipelineMode.showsEvaluationThresholdStep(): Boolean = showsMinSimilarity()

fun RagPipelineMode.showsEvaluationHeuristicStep(): Boolean = showsHybridLexicalWeight()

fun RagPipelineMode.showsEvaluationLlmRerankStep(): Boolean = showsLlmRerankControls()
