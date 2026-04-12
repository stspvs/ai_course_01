package com.example.ai_develop.mcp

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.McpToolBindingRecord
import com.example.ai_develop.domain.AgentToolRegistry
import com.example.ai_develop.domain.McpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val mcpBootstrapLog = LoggerFactory.getLogger("com.example.ai_develop.mcp.bootstrap")

private const val BUILTIN_LOCAL_NEWS_ID = "builtin-local-news"
private const val BUILTIN_NEWS_BINDING_ID = "builtin-news-search-binding"

fun bootstrapDefaultMcpIfNeeded(
    mcpRepository: McpRepository,
    transport: McpTransport,
    agentToolRegistry: AgentToolRegistry,
) {
    runBlocking {
        if (mcpRepository.getServer(BUILTIN_LOCAL_NEWS_ID) != null) {
            mcpBootstrapLog.debug("Builtin Local News MCP already in DB; reload registry only")
            agentToolRegistry.reloadFromDatabase()
            return@runBlocking
        }
        mcpBootstrapLog.info("Seeding builtin Local News MCP (url port {})", BuildConfig.NEWS_MCP_PORT)
        val url = "http://127.0.0.1:${BuildConfig.NEWS_MCP_PORT}/mcp"
        val server = McpServerRecord(
            id = BUILTIN_LOCAL_NEWS_ID,
            displayName = "Local News (встроенный MCP)",
            baseUrl = url,
            enabled = true,
        )
        mcpRepository.upsertServer(server)

        val syncResult = transport.listTools(url, "{}")
        val now = System.currentTimeMillis()
        val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
        if (syncResult.isSuccess) {
            val tools = syncResult.getOrThrow().tools
            mcpBootstrapLog.info("Builtin Local News MCP listTools: {} tool(s)", tools.size)
            val payload = json.encodeToString(
                ListSerializer(com.example.ai_develop.data.McpDiscoveredTool.serializer()),
                tools.map { com.example.ai_develop.data.McpDiscoveredTool(it.name, it.description.orEmpty()) },
            )
            mcpRepository.updateServerSyncState(BUILTIN_LOCAL_NEWS_ID, payload, null, now)
        } else {
            val err = syncResult.exceptionOrNull()?.message
            mcpBootstrapLog.warn("Builtin Local News MCP listTools failed: {}", err)
            mcpRepository.updateServerSyncState(
                BUILTIN_LOCAL_NEWS_ID,
                "",
                err,
                now,
            )
        }

        mcpRepository.upsertBinding(
            McpToolBindingRecord(
                id = BUILTIN_NEWS_BINDING_ID,
                serverId = BUILTIN_LOCAL_NEWS_ID,
                mcpToolName = "news_search",
                agentToolName = "news_search",
                descriptionOverride = "",
                inputArgumentKey = "query",
                enabled = true,
            ),
        )

        agentToolRegistry.reloadFromDatabase()
    }
}
