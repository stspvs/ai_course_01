package com.example.ai_develop.domain

import com.example.ai_develop.data.RagContextRetriever
import com.example.ai_develop.data.RagPipelineSettingsRepository

/**
 * Подготовка [PreparedLlmRequest] с опциональным RAG (retrieval, rewrite).
 * Единая точка логики для путей с включённым и выключенным RAG внутри одного конвейера.
 */
class RagAwareChatRequestPreparer(
    private val agentId: String,
    private val repository: ChatRepository,
    private val engine: AgentEngine,
    private val ragContextRetriever: RagContextRetriever?,
    private val ragPipelineSettingsRepository: RagPipelineSettingsRepository?,
) {
    suspend fun prepare(
        snapshot: AgentRuntimeSnapshot,
        timing: PhaseTimingCollector? = null,
    ): PreparedLlmRequest {
        val agent = snapshot.agent
        val stage = snapshot.stage
        val injectStage = snapshot.injectWorkflowStageIntoPrompt
        val persistedRag = repository.getAgentState(agentId)?.ragEnabled
        val a = agent.copy(ragEnabled = persistedRag ?: agent.ragEnabled)
        if (!a.ragEnabled) {
            return engine.prepareChatRequest(
                a,
                stage,
                isJsonMode = false,
                injectWorkflowStageIntoPrompt = injectStage,
            )
        }
        val last = a.messages.lastOrNull()
        if (last == null || last.role.lowercase() != "user") {
            return engine.prepareChatRequest(
                a,
                stage,
                isJsonMode = false,
                injectWorkflowStageIntoPrompt = injectStage,
            )
        }
        val query = last.message.trim()
        if (query.isEmpty()) {
            return engine.prepareChatRequest(
                a,
                stage,
                isJsonMode = false,
                ragContext = null,
                ragAttribution = RagAttribution(used = false),
                ragStructuredOutput = false,
                injectWorkflowStageIntoPrompt = injectStage,
            )
        }
        val config = runCatching { ragPipelineSettingsRepository?.getConfig() }.getOrNull()
            ?: RagRetrievalConfig.Default
        if (!config.globalRagEnabled) {
            return engine.prepareChatRequest(
                a,
                stage,
                isJsonMode = false,
                injectWorkflowStageIntoPrompt = injectStage,
            )
        }
        var retrievalQuery = query
        var rewriteApplied = false
        if (config.queryRewriteEnabled) {
            val rewriteProvider = resolveRewriteProvider(a, config)
            if (timing != null) {
                timing.markRagRewrite {
                    repository.rewriteQueryForRag(query, rewriteProvider).onSuccess { rw ->
                        if (rw.isNotBlank()) {
                            retrievalQuery = rw.trim()
                            rewriteApplied = true
                        }
                    }
                }
            } else {
                repository.rewriteQueryForRag(query, rewriteProvider).onSuccess { rw ->
                    if (rw.isNotBlank()) {
                        retrievalQuery = rw.trim()
                        rewriteApplied = true
                    }
                }
            }
        }
        if (retrievalQuery.isBlank()) retrievalQuery = query

        val retrieved = if (timing != null) {
            timing.markRagRetrieve {
                ragContextRetriever?.retrieve(
                    originalQuery = query,
                    retrievalQuery = retrievalQuery,
                    config = config,
                    rewriteApplied = rewriteApplied,
                )
            }
        } else {
            ragContextRetriever?.retrieve(
                originalQuery = query,
                retrievalQuery = retrievalQuery,
                config = config,
                rewriteApplied = rewriteApplied,
            )
        }
        val ragGrounded = retrieved != null &&
            retrieved.attribution.used &&
            retrieved.contextText.isNotBlank()
        return if (retrieved != null) {
            if (ragGrounded) {
                engine.prepareChatRequest(
                    a,
                    stage,
                    isJsonMode = true,
                    ragContext = retrieved.contextText,
                    ragAttribution = retrieved.attribution,
                    ragStructuredOutput = true,
                )
            } else {
                engine.prepareChatRequest(
                    a,
                    stage,
                    isJsonMode = false,
                    ragContext = null,
                    ragAttribution = retrieved.attribution,
                    ragStructuredOutput = false,
                )
            }
        } else {
            engine.prepareChatRequest(
                a,
                stage,
                isJsonMode = false,
                ragContext = null,
                ragAttribution = RagAttribution(used = false),
                ragStructuredOutput = false,
            )
        }
    }

    private fun resolveRewriteProvider(agent: Agent, config: RagRetrievalConfig): LLMProvider {
        val m = config.rewriteOllamaModel.trim()
        val p = agent.provider
        return if (p is LLMProvider.Ollama && m.isNotEmpty()) p.copy(model = m) else p
    }
}
