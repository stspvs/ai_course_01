@file:OptIn(io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi::class)

package com.example.ai_develop.mcp

import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.stripLeadingJsonColonLabel
import com.example.ai_develop.data.McpWireKind
import com.example.ai_develop.domain.llm.McpListToolsResult
import com.example.ai_develop.domain.llm.McpToolInfo
import com.example.ai_develop.domain.llm.McpTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpClient
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap

class DesktopMcpTransport : McpTransport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val stdioSessions = ConcurrentHashMap<String, StdioMcpSession>()
    private val stdioSessionsLock = Mutex()

    override suspend fun listTools(server: McpServerRecord): Result<McpListToolsResult> {
        return when (server.wireKind) {
            McpWireKind.STREAMABLE_HTTP -> listToolsHttp(server)
            McpWireKind.STDIO -> listToolsStdio(server)
        }
    }

    override suspend fun callTool(
        server: McpServerRecord,
        toolName: String,
        arguments: Map<String, JsonElement>,
    ): Result<String> {
        return when (server.wireKind) {
            McpWireKind.STREAMABLE_HTTP -> callToolHttp(server, toolName, arguments)
            McpWireKind.STDIO -> callToolStdio(server, toolName, arguments)
        }
    }

    override suspend fun disposeServer(serverId: String) {
        stdioSessionsLock.withLock {
            stdioSessions.remove(serverId)?.close()
        }
    }

    private suspend fun listToolsHttp(server: McpServerRecord): Result<McpListToolsResult> {
        val client = createClient(server.headersJson)
        return try {
            val mcp = client.mcpStreamableHttp(url = server.baseUrl)
            try {
                val r = mcp.listTools()
                Result.success(
                    McpListToolsResult(
                        tools = r.tools.map { t ->
                            McpToolInfo(
                                name = t.name,
                                description = t.description,
                                inputSchemaJson = encodeToolInputSchema(t.inputSchema),
                            )
                        },
                    ),
                )
            } finally {
                mcp.close()
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException(describeThrowableChain(e), e))
        } finally {
            client.close()
        }
    }

    private suspend fun callToolHttp(
        server: McpServerRecord,
        toolName: String,
        arguments: Map<String, JsonElement>,
    ): Result<String> {
        val client = createClient(server.headersJson)
        return try {
            val mcp = client.mcpStreamableHttp(url = server.baseUrl)
            try {
                val result = mcp.callTool(name = toolName, arguments = arguments)
                Result.success(formatToolResult(result))
            } finally {
                mcp.close()
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException(describeThrowableChain(e), e))
        } finally {
            client.close()
        }
    }

    private suspend fun listToolsStdio(server: McpServerRecord): Result<McpListToolsResult> {
        val cmd = server.startCommand.trim()
        if (cmd.isEmpty()) {
            return Result.failure(IllegalArgumentException("Для stdio укажите команду запуска процесса (Gradle, java …)"))
        }
        var session: StdioMcpSession? = null
        return try {
            session = ensureStdioSession(server)
            session.mutex.withLock {
                val r = session.client.listTools()
                Result.success(
                    McpListToolsResult(
                        tools = r.tools.map { t ->
                            McpToolInfo(
                                name = t.name,
                                description = t.description,
                                inputSchemaJson = encodeToolInputSchema(t.inputSchema),
                            )
                        },
                    ),
                )
            }
        } catch (e: Exception) {
            Result.failure(stdioUserFacingException(e, session, server))
        }
    }

    private suspend fun callToolStdio(
        server: McpServerRecord,
        toolName: String,
        arguments: Map<String, JsonElement>,
    ): Result<String> {
        val cmd = server.startCommand.trim()
        if (cmd.isEmpty()) {
            return Result.failure(IllegalArgumentException("Для stdio укажите команду запуска процесса"))
        }
        var session: StdioMcpSession? = null
        return try {
            session = ensureStdioSession(server)
            session.mutex.withLock {
                val result = session.client.callTool(name = toolName, arguments = arguments)
                Result.success(formatToolResult(result))
            }
        } catch (e: Exception) {
            Result.failure(stdioUserFacingException(e, session, server))
        }
    }

    private suspend fun ensureStdioSession(server: McpServerRecord): StdioMcpSession {
        val cmd = server.startCommand.trim()
        require(cmd.isNotEmpty()) { "Пустая команда stdio" }
        return stdioSessionsLock.withLock {
            val old = stdioSessions[server.id]
            if (old != null && old.command == cmd) {
                return@withLock old
            }
            old?.close()
            val newSession = createStdioSession(cmd)
            stdioSessions[server.id] = newSession
            newSession
        }
    }

    private suspend fun createStdioSession(command: String): StdioMcpSession {
        rejectStdioCommandIfLaunchesMainDesktopApp(command)
        val process = spawnShellProcess(command)
        val stderrBuffer = StringBuilder()
        val pipedIn = PipedInputStream()
        val pipedOut = PipedOutputStream(pipedIn)
        startStderrTeeToBufferAndPipe(process, pipedOut, stderrBuffer)
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = pipedIn.asSource().buffered(),
        )
        val client = mcpClient(
            clientInfo = Implementation(name = "ai-develop", version = "1.0.0"),
            transport = transport,
        )
        return StdioMcpSession(
            process = process,
            client = client,
            command = command,
            stderrBuffer = stderrBuffer,
        )
    }

    /**
     * Дублирует stderr процесса: в [stderrBuffer] (для диагностики в UI) и в [pipedOut] (для StdioClientTransport).
     */
    private fun startStderrTeeToBufferAndPipe(
        process: Process,
        pipedOut: PipedOutputStream,
        stderrBuffer: StringBuilder,
        maxChars: Int = 24_000,
    ) {
        val thread = Thread({
            try {
                process.errorStream.use { err ->
                    pipedOut.use { out ->
                        val buf = ByteArray(4096)
                        while (true) {
                            val n = err.read(buf)
                            if (n <= 0) break
                            synchronized(stderrBuffer) {
                                stderrBuffer.append(String(buf, 0, n, Charsets.UTF_8))
                                if (stderrBuffer.length > maxChars) {
                                    stderrBuffer.delete(0, stderrBuffer.length - maxChars)
                                }
                            }
                            out.write(buf, 0, n)
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { pipedOut.close() }
            }
        }, "mcp-stdio-stderr-tee")
        thread.isDaemon = true
        thread.start()
    }

    private fun stdioUserFacingException(
        e: Exception,
        session: StdioMcpSession?,
        server: McpServerRecord,
    ): Exception {
        val sb = StringBuilder()
        sb.append(describeThrowableChain(e))
        if (session != null) {
            runCatching { Thread.sleep(150) }
            sb.append("\nПроцесс: ").append(if (session.process.isAlive) "ещё работает" else "завершён")
            val exit = session.process.run {
                if (isAlive) {
                    null
                } else {
                    try {
                        exitValue()
                    } catch (_: IllegalThreadStateException) {
                        null
                    }
                }
            }
            exit?.let { sb.append("\nКод выхода процесса: ").append(it) }
            val errTail = synchronized(session.stderrBuffer) {
                session.stderrBuffer.toString().trim()
            }
            if (errTail.isNotBlank()) {
                val max = 6000
                val tail = if (errTail.length > max) "…" + errTail.takeLast(max) else errTail
                sb.append("\nStderr (хвост): ").append(tail)
            } else {
                sb.append("\nStderr: (пусто или ещё не успело попасть в буфер)")
            }
        }
        sb.append("\nКоманда: ").append(server.startCommand.trim())
        return RuntimeException(sb.toString(), e)
    }

    private fun describeThrowableChain(t: Throwable): String = buildString {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 10) {
            if (isNotEmpty()) append(" ← ")
            append(cur::class.simpleName ?: "Throwable")
            val msg = cur.message
            if (!msg.isNullOrBlank()) {
                append(": ").append(msg.trim())
            }
            cur = cur.cause
            depth++
        }
    }

    /**
     * Подсказки и доки часто предлагают `gradlew … :composeApp:run` — это поднимает полное окно этого приложения,
     * а не MCP по stdin/stdout; при «Обновить список tools» пользователь видит «вторую копию» клиента.
     */
    private fun rejectStdioCommandIfLaunchesMainDesktopApp(command: String) {
        if (Regex("""(?i)composeapp\s*:\s*run\b""").containsMatchIn(command)) {
            throw IllegalArgumentException(
                "Команда stdio не должна быть «:composeApp:run» — это запускает полное настольное приложение, " +
                    "а не отдельный MCP-сервер по stdin/stdout. Задайте другую Gradle-задачу для stdio MCP " +
                    "или используйте транспорт Streamable HTTP к уже запущенному endpoint.",
            )
        }
    }

    private fun spawnShellProcess(command: String): Process {
        val trimmed = command.trim()
        return if (System.getProperty("os.name").lowercase().contains("win")) {
            ProcessBuilder("cmd.exe", "/c", trimmed).start()
        } else {
            ProcessBuilder("sh", "-c", trimmed).start()
        }
    }

    private fun createClient(headersJson: String): HttpClient {
        val headerMap = parseHeaders(headersJson)
        return HttpClient(CIO) {
            install(SSE)
            defaultRequest {
                headerMap.forEach { (k, v) -> headers.append(k, v) }
            }
        }
    }

    private fun encodeToolInputSchema(schema: ToolSchema?): String {
        if (schema == null) return "{}"
        return try {
            json.encodeToString(ToolSchema.serializer(), schema)
        } catch (_: Exception) {
            "{}"
        }
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        if (headersJson.isBlank() || headersJson == "{}") return emptyMap()
        return try {
            val obj = json.decodeFromString(JsonObject.serializer(), headersJson)
            obj.mapValues { (_, v) ->
                when (v) {
                    is JsonObject -> v.toString()
                    else -> v.jsonPrimitive.content
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

private class StdioMcpSession(
    val process: Process,
    val client: Client,
    val command: String,
    val stderrBuffer: StringBuilder,
    val mutex: Mutex = Mutex(),
) {
    suspend fun close() {
        runCatching { client.close() }
        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}

private fun formatToolResult(result: CallToolResult): String {
    val raw = result.content.joinToString("\n") { part ->
        when (part) {
            is TextContent -> part.text
            else -> part.toString()
        }
    }
    return stripLeadingJsonColonLabel(raw)
}
