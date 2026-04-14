package com.example.ai_develop.presentation.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import com.example.ai_develop.presentation.LLMViewModel

@Composable
fun App(viewModel: LLMViewModel) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .build()
    }
    MaterialTheme {
        ChatScreen(viewModel)
    }
}
