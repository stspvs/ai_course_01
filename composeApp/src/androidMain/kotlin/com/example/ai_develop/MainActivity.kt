package com.example.ai_develop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.ai_develop.presentation.compose.App
import com.example.ai_develop.presentation.LLMViewModel
import org.koin.compose.viewmodel.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: LLMViewModel = koinViewModel()
            App(viewModel)
        }
    }
}
