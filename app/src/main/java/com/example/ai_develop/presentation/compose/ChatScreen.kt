package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ai_develop.presentation.DeepSeekViewModel
import com.example.ai_develop.presentation.SourceType

@Composable
internal fun ChatScreen(viewModel: DeepSeekViewModel) {
    val messages = viewModel.chatMessages

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages.size) { index ->
                val message = messages[index]
                Text(
                    text = message.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .background(if (message.source == SourceType.USER) Color.Blue else Color.Gray)
                        .padding(8.dp),
                    color = Color.White
                )
            }
        }

        var input by remember { mutableStateOf("") }
        Row {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (input.isNotBlank()) {
                    viewModel.sendMessage(input)
                    input = ""
                }
            }) {
                Text("Send")
            }
        }
    }
}