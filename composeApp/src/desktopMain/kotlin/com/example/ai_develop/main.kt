package com.example.ai_develop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.ai_develop.di.initKoin
import com.example.ai_develop.di.pauseAllTasksOnAppLaunch
import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.domain.ChatStreamingUseCase
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.mcp.LocalNewsMcpServer
import com.example.ai_develop.mcp.bootstrapDefaultMcpIfNeeded
import com.example.ai_develop.presentation.compose.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

fun main(args: Array<String>) {
    val koinApp = initKoin()
    val koin = koinApp.koin
    koin.pauseAllTasksOnAppLaunch()

    LocalNewsMcpServer.start()

    bootstrapDefaultMcpIfNeeded(
        mcpRepository = koin.get(),
        transport = koin.get(),
        agentToolRegistry = koin.get(),
    )

    try {
        if (args.contains("--cli")) {
            println("🖥️ Running in CLI mode...")
            val useCase: ChatStreamingUseCase = koin.get()
            val repository: ChatRepository = koin.get()
            val scope = CoroutineScope(SupervisorJob())

            val cliManager = CliAgentManager(useCase, repository, scope)
            cliManager.start()
        } else {
            application {
                Window(
                    onCloseRequest = {
                        LocalNewsMcpServer.stop()
                        exitApplication()
                    },
                    title = "AI Develop Agent",
                ) {
                    val viewModel: LLMViewModel = koin.get()
                    App(viewModel)
                }
            }
        }
    } finally {
        LocalNewsMcpServer.stop()
    }
}
