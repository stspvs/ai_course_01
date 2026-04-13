package com.example.ai_develop.mcp

import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.McpWireKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopMcpTransportStdioTest {

    @Test
    fun listTools_stdio_rejectsComposeAppRunTask() = runBlocking {
        val transport = DesktopMcpTransport()
        val server = McpServerRecord(
            id = "test-stdio",
            displayName = "test",
            baseUrl = "stdio://local",
            startCommand = """cd /d "C:\proj" && gradlew.bat :composeApp:run""",
            wireKind = McpWireKind.STDIO,
        )
        val r = transport.listTools(server)
        assertTrue(r.isFailure, "must not spawn Gradle desktop run as stdio MCP")
        val msg = r.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            msg.contains("composeApp", ignoreCase = true),
            "expected hint about composeApp:run, got: $msg",
        )
    }
}
