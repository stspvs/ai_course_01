package com.example.ai_develop.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCioEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class McpConnectionTest {

    @Test
    fun mcpStreamableHttp_connectsAndListsTools() = runBlocking {
        val server = embeddedServer(ServerCioEngine, host = "127.0.0.1", port = 0) {
            configureMinimalMcp()
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().single().port

        val httpClient = HttpClient(CIO) {
            install(SSE)
        }
        try {
            val mcpClient = httpClient.mcpStreamableHttp(url = "http://127.0.0.1:$port/mcp")

            assertNotNull(mcpClient.serverCapabilities)
            assertNotNull(mcpClient.serverCapabilities?.tools)

            val result = mcpClient.listTools()
            assertTrue(result.tools.isNotEmpty())
            assertEquals("smoke-tool", result.tools.single().name)

            mcpClient.close()
        } finally {
            httpClient.close()
            server.stop(500, 1000)
        }
    }
}

private fun Application.configureMinimalMcp() {
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowNonSimpleContentTypes = true
        allowHeader("Mcp-Session-Id")
        allowHeader("Mcp-Protocol-Version")
        exposeHeader("Mcp-Session-Id")
        exposeHeader("Mcp-Protocol-Version")
    }
    // ContentNegotiation + json(McpJson) is installed by mcpStreamableHttp; a duplicate breaks GET /mcp (SSE).
    mcpStreamableHttp {
        createMinimalServer()
    }
}

private fun createMinimalServer(): Server {
    return Server(
        Implementation(
            name = "ai-develop-smoke-mcp",
            version = "1.0.0",
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    ).apply {
        addTool(
            name = "smoke-tool",
            description = "Minimal MCP smoke test tool",
        ) { _ ->
            CallToolResult(content = listOf(TextContent("ok")))
        }
    }
}
