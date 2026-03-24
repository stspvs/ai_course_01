package com.example.ai_develop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.ai_develop.di.initKoin
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.compose.App
import org.koin.compose.viewmodel.koinViewModel

fun main() = application {
    initKoin()
    Window(onCloseRequest = ::exitApplication, title = "AI Develop") {
        val viewModel: LLMViewModel = koinViewModel()
        App(viewModel)
    }
}
