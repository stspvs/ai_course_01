@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ai_develop.domain.GENERAL_CHAT_ID
import com.example.ai_develop.presentation.LLMViewModel

@Composable
fun ChatScreen(viewModel: LLMViewModel) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var chatInput by remember { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Email, contentDescription = "Чат") },
                    label = { Text("Чат") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF4A148C),
                        selectedTextColor = Color(0xFF4A148C),
                        indicatorColor = Color(0xFFE1BEE7)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Агенты") },
                    label = { Text("Агенты") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF4A148C),
                        selectedTextColor = Color(0xFF4A148C),
                        indicatorColor = Color(0xFFE1BEE7)
                    )
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> ChatContent(
                    state = state,
                    input = chatInput,
                    onInputChange = { chatInput = it },
                    onSendMessage = {
                        viewModel.sendMessage(it)
                        chatInput = ""
                    },
                    onClearChat = { viewModel.clearChat() },
                    onToggleStreaming = { viewModel.updateStreamingEnabled(it) },
                    onToggleHistory = { viewModel.updateSendFullHistory(it) },
                    onSelectAgent = { viewModel.selectAgent(it ?: GENERAL_CHAT_ID) },
                    onUpdateStrategy = { viewModel.updateMemoryStrategy(it) },
                    onCreateBranch = { fromId, name -> viewModel.createBranch(fromId, name) },
                    onSwitchBranch = { viewModel.switchBranch(it) },
                    onForceUpdateMemory = { viewModel.forceUpdateMemory() }
                )

                1 -> AgentsContent(
                    state = state,
                    templates = viewModel.agentTemplates,
                    onCreateAgent = { viewModel.createAgent() },
                    onUpdateAgent = { id, n, p, t, pr, s, m, k -> 
                        viewModel.updateAgent(id, n, p, t, pr, s, m, k) 
                    },
                    onUpdateProfile = { id, profile ->
                        viewModel.updateAgentWithProfile(id, profile)
                    },
                    onDeleteAgent = { viewModel.deleteAgent(it) },
                    onDuplicateAgent = { viewModel.duplicateAgent(it) },
                    onSelectAgent = { viewModel.selectAgent(it ?: GENERAL_CHAT_ID) }
                )
            }
        }
    }
}
