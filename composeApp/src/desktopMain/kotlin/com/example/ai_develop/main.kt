package com.example.ai_develop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.ai_develop.di.initKoin
import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.domain.ChatStreamingUseCase
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.compose.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

fun main(args: Array<String>) {
    // Инициализируем зависимости через Koin
    val koinApp = initKoin()
    val koin = koinApp.koin

    if (args.contains("--cli")) {
        // Режим CLI
        println("🖥️ Running in CLI mode...")
        val useCase: ChatStreamingUseCase = koin.get()
        val repository: ChatRepository = koin.get()
        val scope = CoroutineScope(SupervisorJob())

        val cliManager = CliAgentManager(useCase, repository, scope)
        // start() теперь блокирующая и сама читает System.in
        cliManager.start()
    } else {
        // Режим Desktop GUI
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "AI Develop Agent",
            ) {
                val viewModel: LLMViewModel = koin.get()
                App(viewModel)
            }
        }
    }
}
