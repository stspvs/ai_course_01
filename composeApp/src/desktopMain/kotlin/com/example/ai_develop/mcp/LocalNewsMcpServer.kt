package com.example.ai_develop.mcp

import com.example.ai_develop.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.cio.CIO as ServerCioEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Embedded MCP (Streamable HTTP) server that exposes [news_search] and proxies to NewsAPI.org.
 */
object LocalNewsMcpServer {

    private val log = LoggerFactory.getLogger(LocalNewsMcpServer::class.java)

    @Volatile
    private var server: EmbeddedServer<out ApplicationEngine, *>? = null

    @Volatile
    private var outboundClient: HttpClient? = null

    fun start(): EmbeddedServer<out ApplicationEngine, *>? {
        if (server != null) return server
        val key = BuildConfig.NEWSAPI_KEY
        val port = BuildConfig.NEWS_MCP_PORT
        val outbound = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
        }
        outboundClient = outbound
        val mcpServer = createNewsMcpServer(newsApiKey = key, httpClient = outbound)
        return try {
            // CIO вместо Netty: на Streamable HTTP GET (SSE) в MCP SDK 0.11 + Netty возможна гонка
            // appendSseHeaders() после commit ответа (см. kotlin-sdk #681). CIO обычно обходит это.
            val embedded = embeddedServer(ServerCioEngine, host = "127.0.0.1", port = port) {
                configureNewsMcpApplication(mcpServer)
            }
            embedded.start(wait = false)
            server = embedded
            log.info("Local News MCP server listening on http://127.0.0.1:$port/mcp")
            embedded
        } catch (e: Exception) {
            log.error("Failed to start Local News MCP server on port $port", e)
            outbound.close()
            outboundClient = null
            null
        }
    }

    fun stop() {
        val wasRunning = server != null
        server?.stop(500, 2_000)
        server = null
        outboundClient?.close()
        outboundClient = null
        if (wasRunning) {
            log.info("Local News MCP server stopped")
        }
    }
}

fun Application.configureNewsMcpApplication(mcpServer: io.modelcontextprotocol.kotlin.sdk.server.Server) {
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
    mcpStreamableHttp {
        mcpServer
    }
}
