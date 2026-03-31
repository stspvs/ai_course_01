package com.example.ai_develop.presentation.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.example.ai_develop.presentation.LLMViewModel

@Composable
fun App(viewModel: LLMViewModel) {
    MaterialTheme {
        ChatScreen(viewModel)
    }
}
