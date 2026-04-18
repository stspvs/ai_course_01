package com.example.ai_develop.mcp

import com.example.ai_develop.domain.agent.AgentToolRegistry
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val mcpBootstrapLog = LoggerFactory.getLogger("com.example.ai_develop.mcp.bootstrap")

fun bootstrapDefaultMcpIfNeeded(
    agentToolRegistry: AgentToolRegistry,
) {
    runBlocking {
        mcpBootstrapLog.debug("Reloading agent tool registry from MCP bindings in DB")
        agentToolRegistry.reloadFromDatabase()
    }
}
