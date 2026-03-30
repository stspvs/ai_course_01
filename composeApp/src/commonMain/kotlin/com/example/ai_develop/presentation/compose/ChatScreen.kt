package com.example.ai_develop.presentation.compose

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ai_develop.presentation.*

@Composable
internal fun App(viewModel: LLMViewModel) {
    MaterialTheme {
        ChatScreen(viewModel)
    }
}

@Composable
internal fun ChatScreen(viewModel: LLMViewModel) {
    val state by viewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var chatInput by rememberSaveable { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            ChatBottomNavigation(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "TabTransition"
            ) { targetTab ->
                when (targetTab) {
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
                        onSelectAgent = { viewModel.selectAgent(it) }
                    )

                    1 -> AgentsContent(
                        state = state,
                        templates = viewModel.agentTemplates,
                        onCreateAgent = { viewModel.createAgent() },
                        onUpdateAgent = { id, n, p, t, pr, s, m, k, sp, sd -> 
                            viewModel.updateAgent(id, n, p, t, pr, s, m, k, sp, sd) 
                        },
                        onDeleteAgent = { viewModel.deleteAgent(it) },
                        onDuplicateAgent = { viewModel.duplicateAgent(it) },
                        onSelectAgent = { viewModel.selectAgent(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBottomNavigation(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFFF3E5F5),
        tonalElevation = 8.dp
    ) {
        val tabs = listOf(
            Triple(0, "Чат", Icons.Default.Email),
            Triple(1, "Агенты", Icons.Default.Person)
        )
        tabs.forEach { (index, label, icon) ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF4A148C),
                    selectedTextColor = Color(0xFF4A148C),
                    indicatorColor = Color(0xFFE1BEE7)
                )
            )
        }
    }
}
