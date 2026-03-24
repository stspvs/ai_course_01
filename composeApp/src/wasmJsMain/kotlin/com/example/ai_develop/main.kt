package com.example.ai_develop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.example.ai_develop.di.initKoin
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.compose.App
import kotlinx.browser.document
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initKoin()
    ComposeViewport(document.body!!) {
        val viewModel: LLMViewModel = koinViewModel()
        App(viewModel)
    }
}
