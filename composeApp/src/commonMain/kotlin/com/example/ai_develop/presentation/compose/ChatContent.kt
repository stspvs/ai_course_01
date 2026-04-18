@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.ai_develop.presentation.compose

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_develop.domain.*
import kotlin.math.min

private fun ChatMessage.supportsRagDetailDialog(): Boolean {
    if (source != SourceType.AI && role.lowercase() != "assistant") return false
    val attr = llmRequestSnapshot?.ragAttribution ?: return false
    if (attr.debug != null) return true
    if (attr.insufficientRelevance) return true
    return attr.used && attr.sources.isNotEmpty()
}

@Composable
internal fun ChatContent(
    state: LLMStateModel,
    input: String,
    onInputChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    onToggleStreaming: (Boolean) -> Unit,
    onToggleHistory: (Boolean) -> Unit,
    onSelectAgent: (String?) -> Unit,
    onUpdateStrategy: (ChatMemoryStrategy) -> Unit,
    onCreateBranch: (String, String) -> Unit,
    onSwitchBranch: (String?) -> Unit,
    onForceUpdateMemory: () -> Unit = {}
) {
    val activeAgent = state.agents.find { it.id == state.selectedAgentId }
    val messages = state.currentMessages
    var menuExpanded by remember { mutableStateOf(false) }
    var showFacts by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }
    var showMemoryPanel by remember { mutableStateOf(false) }

    val strategy = activeAgent?.memoryStrategy
    var ragDetailMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var phaseTimingsMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val isBranchingMode = strategy is ChatMemoryStrategy.Branching
    val isSummarizationMode = strategy is ChatMemoryStrategy.Summarization
    val isStickyFactsMode = strategy is ChatMemoryStrategy.StickyFacts
    val isTaskOrientedMode = strategy is ChatMemoryStrategy.TaskOriented

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE3F2FD))
        ) {
            ChatTopBar(
                activeAgentName = activeAgent?.name ?: "Общий чат",
                isAgentSelected = activeAgent != null,
                isLoading = state.isLoading,
                tokensUsed = state.currentTokensUsed,
                agents = state.agents,
                onSelectAgent = onSelectAgent,
                menuExpanded = menuExpanded,
                onMenuToggle = { menuExpanded = it },
                onShowFacts = { showFacts = !showFacts },
                hasFacts = (strategy as? ChatMemoryStrategy.StickyFacts)?.facts?.facts?.isNotEmpty() == true,
                onShowSummary = { showSummary = !showSummary },
                hasSummary = (strategy as? ChatMemoryStrategy.Summarization)?.summary?.isNotBlank() == true,
                onShowBranches = { showBranches = !showBranches },
                onShowTask = { showMemoryPanel = !showMemoryPanel },
                isBranchingMode = isBranchingMode,
                isSummarizationMode = isSummarizationMode,
                isStickyFactsMode = isStickyFactsMode,
                isTaskOrientedMode = isTaskOrientedMode,
                hasBranches = activeAgent?.branches?.isNotEmpty() == true,
                isSlidingMode = strategy is ChatMemoryStrategy.SlidingWindow || strategy == null,
                slidingCount = min(messages.size, strategy?.windowSize ?: 10),
                summarySnippet = (strategy as? ChatMemoryStrategy.Summarization)?.summary,
                factsCount = (strategy as? ChatMemoryStrategy.StickyFacts)?.facts?.facts?.size ?: 0,
                branchName = activeAgent?.branches?.find { it.id == activeAgent.currentBranchId }?.name ?: "Основная",
                branchMessagesCount = messages.size
            )

            AnimatedVisibility(
                visible = showFacts && isStickyFactsMode,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val facts = (strategy as? ChatMemoryStrategy.StickyFacts)?.facts?.facts ?: emptyList()
                FactsPanel(facts = facts)
            }

            AnimatedVisibility(
                visible = showSummary && isSummarizationMode,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val summary = (strategy as? ChatMemoryStrategy.Summarization)?.summary
                SummaryPanel(summary = summary)
            }

            AnimatedVisibility(
                visible = showBranches && isBranchingMode,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                BranchesPanel(
                    branches = activeAgent?.branches ?: emptyList(),
                    currentBranchId = activeAgent?.currentBranchId,
                    onSwitchBranch = onSwitchBranch,
                    onCreateBranch = { branchName ->
                        val currentBranchId = activeAgent?.currentBranchId
                        val lastMsgId = if (currentBranchId != null) {
                            activeAgent.branches.find { it.id == currentBranchId }?.lastMessageId
                        } else {
                            activeAgent?.branches?.find { it.id == "main_branch" }?.lastMessageId
                                ?: activeAgent?.messages?.lastOrNull()?.id
                        }

                        if (lastMsgId != null) {
                            onCreateBranch(lastMsgId, branchName)
                        }
                    }
                )
            }

            val listState = rememberLazyListState()
            
            LaunchedEffect(
                messages.size,
                messages.lastOrNull()?.content?.length,
                state.isLoading,
                state.agentActivity,
                state.streamingPreview.length,
            ) {
                val extraRows = when {
                    !state.isLoading -> 0
                    state.streamingPreview.isNotBlank() -> 2
                    else -> 1
                }
                val lastIndex = messages.lastIndex + extraRows
                if (lastIndex >= 0) {
                    listState.scrollToItem(lastIndex)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageItem(
                        message = message,
                        isBranchingMode = isBranchingMode,
                        onCreateBranch = { branchName -> onCreateBranch(message.id, branchName) },
                        onOpenRagDialog = { ragDetailMessage = it },
                        onOpenPhaseTimings = { phaseTimingsMessage = it },
                    )
                }
                items(
                    items = if (state.isLoading && state.streamingPreview.isNotBlank()) {
                        listOf("stream_preview")
                    } else {
                        emptyList()
                    },
                    key = { it },
                ) {
                    StreamingPreviewBubble(text = state.streamingPreview)
                }
                items(
                    items = if (state.isLoading) listOf("agent_activity_bubble") else emptyList(),
                    key = { it }
                ) {
                    AgentActivityBubble(
                        activity = state.agentActivity,
                        phaseHint = state.phaseHint,
                    )
                }
            }

            ChatInputArea(
                input = input,
                onInputChange = onInputChange,
                onSendMessage = onSendMessage,
                onClearChat = onClearChat
            )

            ragDetailMessage?.let { msg ->
                RagChunksDetailDialog(
                    message = msg,
                    onDismiss = { ragDetailMessage = null },
                )
            }

            phaseTimingsMessage?.phaseTimings?.let { timings ->
                PhaseTimingsBottomSheet(
                    timings = timings,
                    onDismiss = { phaseTimingsMessage = null },
                )
            }
        }

        // Боковая панель Состояния Памяти (Краткосрочная + Рабочая + Долгая)
        AnimatedVisibility(
            visible = showMemoryPanel && activeAgent != null,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
        ) {
            activeAgent?.let { agent ->
                MemorySidePanel(
                    agent = agent,
                    currentMessagesCount = messages.size,
                    onClose = { showMemoryPanel = false },
                    onRefresh = onForceUpdateMemory
                )
            }
        }
    }
}

@Composable
private fun AgentActivityBubble(
    activity: AgentActivity,
    phaseHint: PhaseStatusHint?,
) {
    val label = phaseHint?.let { phaseStatusHintLabel(it) }
        ?: when (activity) {
            AgentActivity.Idle -> "Обработка…"
            AgentActivity.Streaming -> "Модель отвечает…"
            is AgentActivity.RunningTool -> "Вызов инструмента: ${activity.toolName}"
            AgentActivity.Working -> "Обработка…"
        }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = Color(0xFFE8EAF6),
            contentColor = Color(0xFF283593),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 0.dp,
                bottomEnd = 16.dp
            ),
            tonalElevation = 1.dp,
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF5C6BC0)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Text(
            text = "Агент",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StreamingPreviewBubble(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            color = Color(0xFFF5F5F5),
            contentColor = Color(0xFF424242),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 0.dp,
                bottomEnd = 16.dp,
            ),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Text(
            text = "Черновик",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PhaseTimingsBottomSheet(
    timings: AgentPhaseTimings,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Этапы ответа",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            PhaseTimingRow("Подготовка запроса", timings.prepareRequestMs)
            PhaseTimingRow("RAG: уточнение запроса", timings.ragRewriteMs)
            PhaseTimingRow("RAG: поиск", timings.ragRetrieveMs)
            if (timings.llmRoundMs.isNotEmpty()) {
                Text("Раунды LLM", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                timings.llmRoundMs.forEachIndexed { i, ms ->
                    PhaseTimingRow("  Раунд ${i + 1}", ms)
                }
            }
            if (timings.toolDurations.isNotEmpty()) {
                Text("Инструменты", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                timings.toolDurations.forEach { t ->
                    PhaseTimingRow("  ${t.toolName}", t.ms)
                }
            }
            PhaseTimingRow("Разбор JSON (RAG)", timings.ragJsonParseMs)
            timings.firstTokenMs?.let { PhaseTimingRow("Первый токен (от начала)", it) }
            HorizontalDivider()
            PhaseTimingRow("Всего", timings.totalMs, emphasize = true)
        }
    }
}

@Composable
private fun PhaseTimingRow(label: String, ms: Long, emphasize: Boolean = false) {
    if (ms <= 0L && !emphasize) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = if (emphasize) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$ms мс",
            style = if (emphasize) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MemorySidePanel(
    agent: Agent,
    currentMessagesCount: Int,
    onClose: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier.width(320.dp).fillMaxHeight(),
        color = Color.White,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Состояние памяти", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                Row {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить всё", tint = Color(0xFF1565C0))
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                    }
                }
            }

            // 1. Краткосрочная память (Current Strategy / Dialogue)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("👉 КРАТКОСРОЧНАЯ ПАМЯТЬ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
                Surface(
                    color = Color(0xFFFFEBEE),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when (val strategy = agent.memoryStrategy) {
                            is ChatMemoryStrategy.SlidingWindow -> {
                                val windowSize = strategy.windowSize
                                val activeCount = min(currentMessagesCount, windowSize)
                                Text("Тип: Sliding Window", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("В памяти: $activeCount из $windowSize последних сообщ.", style = MaterialTheme.typography.bodyMedium)
                                Text("Всего в истории: $currentMessagesCount", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            is ChatMemoryStrategy.Summarization -> {
                                Text("Тип: Summarization", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("Суммаризация (сжатый контекст):", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = strategy.summary ?: "Еще не выполнена. Нажмите 'Обновить'.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            is ChatMemoryStrategy.StickyFacts -> {
                                Text("Тип: Sticky Facts", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("Временные факты контекста:", style = MaterialTheme.typography.labelSmall)
                                if (strategy.facts.facts.isEmpty()) {
                                    Text("Пусто", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                } else {
                                    strategy.facts.facts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                            is ChatMemoryStrategy.Branching -> {
                                val branchName = agent.branches.find { it.id == agent.currentBranchId }?.name ?: "Основная"
                                Text("Тип: Branching", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("Активная ветка: $branchName", style = MaterialTheme.typography.bodyMedium)
                                Text("Сообщений в ветке: $currentMessagesCount", style = MaterialTheme.typography.bodySmall)
                            }
                            is ChatMemoryStrategy.TaskOriented -> {
                                Text("Тип: Task Oriented", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("Фокус на задаче: ${strategy.currentGoal ?: "Не задана"}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // 2. Рабочая память (Current Task)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("👉 РАБОЧАЯ ПАМЯТЬ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val wm = agent.workingMemory
                        Text("Цель диалога:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = wm.dialogueGoal ?: wm.currentTask ?: "Не определена",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Текущая задача (метка):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = wm.currentTask ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (wm.currentTask.isNullOrBlank()) {
                                Color.Gray
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        val progress = wm.progress
                        if (!progress.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Прогресс:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(text = progress, style = MaterialTheme.typography.bodySmall, color = Color(0xFF1B5E20))
                        }
                        if (wm.userClarifications.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Уточнения пользователя:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            wm.userClarifications.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (wm.fixedTermsAndConstraints.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Термины и ограничения:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            wm.fixedTermsAndConstraints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        val wmFacts = wm.extractedFacts.facts
                        if (wmFacts.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Извлеченные факты задачи:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            wmFacts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            // 3. Долговременная память (Profile & Knowledge)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("👉 ПЕРСОНАЛИЗАЦИЯ (ПРОФИЛЬ)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                Surface(
                    color = Color(0xFFE3F2FD),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val profile = agent.userProfile
                        if (profile != null) {
                            if (profile.preferences.isNotEmpty()) {
                                Text("Предпочтения:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(profile.preferences, style = MaterialTheme.typography.bodySmall)
                            }
                            
                            if (profile.constraints.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("Ограничения:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
                                Text(profile.constraints, style = MaterialTheme.typography.bodySmall)
                            }

                            if (profile.preferences.isEmpty() && profile.constraints.isEmpty()) {
                                Text("Предпочтения не заданы.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        } else {
                            Text("Профиль пользователя не настроен.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactsPanel(facts: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = Color(0xFFFFF9C4),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFF57F17), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sticky Facts (Извлечено из контекста):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFFF57F17))
            }
            Spacer(Modifier.height(8.dp))
            if (facts.isEmpty()) {
                Text("Факты еще не извлечены. Пообщайтесь с агентом!", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                facts.forEach { fact ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("• ", fontWeight = FontWeight.Bold)
                        Text(fact, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryPanel(summary: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = Color(0xFFE1F5FE),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF0277BD), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Summarization (Краткое содержание):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF0277BD))
            }
            Spacer(Modifier.height(8.dp))
            if (summary.isNullOrBlank()) {
                Text("Суммаризация еще не выполнена. Продолжайте диалог!", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchesPanel(
    branches: List<ChatBranch>,
    currentBranchId: String?,
    onSwitchBranch: (String?) -> Unit,
    onCreateBranch: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = Color(0xFFE8F5E9),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                val currentBranchName = branches.find { it.id == currentBranchId }?.name ?: "Основная ветка"
                Text(
                    text = "Текущая ветка: $currentBranchName",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Branch", tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = currentBranchId == null || currentBranchId == "main_branch",
                    onClick = { onSwitchBranch(null) },
                    label = { Text("Основная") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2E7D32),
                        selectedLabelColor = Color.White
                    )
                )
                
                branches.filter { it.id != "main_branch" }.forEach { branch ->
                    FilterChip(
                        selected = branch.id == currentBranchId,
                        onClick = { onSwitchBranch(branch.id) },
                        label = { Text(branch.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF2E7D32),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Создать новую ветку") },
            text = {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("Название ветки") },
                    placeholder = { Text("Введите название...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newBranchName.isNotBlank()) {
                        onCreateBranch(newBranchName)
                        newBranchName = ""
                        showCreateDialog = false
                    }
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun RagChunksDetailDialog(
    message: ChatMessage,
    onDismiss: () -> Unit,
) {
    val attr = message.llmRequestSnapshot?.ragAttribution
    val sources = attr?.sources.orEmpty()
    val debug = attr?.debug
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RAG: шаги и фрагменты") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (debug != null) {
                    Text("Пайплайн", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            appendLine("Режим: ${debug.pipelineMode}")
                            appendLine("Запрос: ${debug.originalQuery.take(500)}")
                            if (debug.rewriteApplied || debug.retrievalQuery != debug.originalQuery) {
                                appendLine("Для поиска: ${debug.retrievalQuery.take(500)}")
                            }
                            appendLine("Recall K: ${debug.recallTopK}, final K: ${debug.finalTopK}")
                            appendLine("После recall: ${debug.candidatesAfterRecall}, после порога: ${debug.candidatesAfterThreshold}, после rerank: ${debug.candidatesAfterRerank}")
                            debug.minSimilarity?.let { appendLine("Порог (отсечение по cosine): $it") }
                            debug.hybridLexicalWeight?.let { appendLine("Баланс w (косинус в смеси): $it") }
                            debug.llmRerankModel?.let { appendLine("Модель LLM-rerank: $it") }
                            debug.emptyReason?.let { appendLine("Причина пустого контекста: $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Мин. similarity — фильтр по косинусу до пересортировки. У фрагмента: cosine — близость эмбеддингов; " +
                            "«Итог Hybrid» — w·cosine + (1−w)·Jaccard; для LLM rerank — оценка модели.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                }
                Text(
                    text = if (sources.isEmpty()) {
                        "Фрагменты из базы не были подмешаны в промпт (или список недоступен)."
                    } else {
                        "Ниже — текст, добавленный в системный промпт. Ответ мог опираться на эти фрагменты и на общие знания модели."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val finalScoreLabel = when (debug?.pipelineMode) {
                    RagPipelineMode.Hybrid -> "Итог Hybrid"
                    RagPipelineMode.LlmRerank -> "Оценка LLM"
                    else -> "итог"
                }
                sources.forEachIndexed { i, src ->
                    HorizontalDivider()
                    Text(
                        text = "${i + 1}. ${src.documentTitle}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = buildString {
                            append(src.sourceFileName)
                            append(" · чанк ${src.chunkIndex}")
                            src.score?.let { append(" · косинус " + "%.3f".format(it)) }
                            src.finalScore?.let { append(" · $finalScoreLabel " + "%.3f".format(it)) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                    )
                    if (src.chunkText.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = src.chunkText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            text = "Текст фрагмента недоступен (сообщение до обновления приложения).",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    isBranchingMode: Boolean,
    onCreateBranch: (String) -> Unit,
    onOpenRagDialog: (ChatMessage) -> Unit,
    onOpenPhaseTimings: (ChatMessage) -> Unit,
) {
    var showBranchDialog by remember { mutableStateOf(false) }
    var branchName by remember { mutableStateOf("") }
    var selectedRagChunkId by remember(message.id) { mutableStateOf<String?>(null) }

    val hasStructuredRag = message.llmRequestSnapshot?.ragStructuredContent.shouldRenderStructuredBubble()
    val openRagOnClick = message.supportsRagDetailDialog() && !hasStructuredRag
    val openPhaseTimings = message.phaseTimings != null && message.role.equals("assistant", ignoreCase = true)

    fun onPrimaryClick() {
        when {
            openPhaseTimings -> onOpenPhaseTimings(message)
            openRagOnClick -> onOpenRagDialog(message)
            else -> Unit
        }
    }

    val outerModifier = when {
        isBranchingMode && (openPhaseTimings || openRagOnClick) -> Modifier.combinedClickable(
            onClick = { onPrimaryClick() },
            onLongClick = { showBranchDialog = true },
        )
        isBranchingMode -> Modifier.combinedClickable(
            onClick = {},
            onLongClick = { showBranchDialog = true },
        )
        openPhaseTimings || openRagOnClick -> Modifier.clickable { onPrimaryClick() }
        else -> Modifier
    }

    Box(modifier = outerModifier) {
        MessageBubble(
            message = message,
            onRagChunkClick = if (hasStructuredRag) {
                { id -> selectedRagChunkId = id }
            } else {
                null
            },
            onOpenRagPipelineDialog = if (hasStructuredRag && message.supportsRagDetailDialog()) {
                { onOpenRagDialog(message) }
            } else {
                null
            },
        )
    }

    val ragAttr = message.llmRequestSnapshot?.ragAttribution
    selectedRagChunkId?.let { chunkId ->
        if (ragAttr != null) {
            RagChunkMaterialDialog(
                chunkId = chunkId,
                attribution = ragAttr,
                onDismiss = { selectedRagChunkId = null },
            )
        }
    }

    if (showBranchDialog && isBranchingMode) {
        AlertDialog(
            onDismissRequest = { showBranchDialog = false },
            title = { Text("Создать новую ветку?") },
            text = {
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Название ветки") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onCreateBranch(branchName.ifBlank { "Ветка от ${message.id.take(4)}" })
                    showBranchDialog = false
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showBranchDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ChatTopBar(
    activeAgentName: String,
    isAgentSelected: Boolean,
    isLoading: Boolean,
    tokensUsed: Int,
    agents: List<Agent>,
    onSelectAgent: (String?) -> Unit,
    menuExpanded: Boolean,
    onMenuToggle: (Boolean) -> Unit,
    onShowFacts: () -> Unit,
    hasFacts: Boolean,
    onShowSummary: () -> Unit,
    hasSummary: Boolean,
    onShowBranches: () -> Unit,
    onShowTask: () -> Unit,
    isBranchingMode: Boolean,
    isSummarizationMode: Boolean,
    isStickyFactsMode: Boolean,
    isTaskOrientedMode: Boolean,
    hasBranches: Boolean,
    isSlidingMode: Boolean = false,
    slidingCount: Int = 0,
    summarySnippet: String? = null,
    factsCount: Int = 0,
    branchName: String? = null,
    branchMessagesCount: Int = 0
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shadowElevation = 4.dp
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onMenuToggle(true) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isAgentSelected) Color(0xFF673AB7) else Color(0xFF1976D2)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isAgentSelected) Icons.Default.Person else Icons.Default.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeAgentName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.Gray)
                        }
                        Text(
                            text = if (isLoading) "Печатает..." else "В сети",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLoading) Color(0xFF673AB7) else Color.Gray
                        )
                    }
                }
                
                if (isAgentSelected) {
                    Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onShowTask) {
                            Icon(
                                Icons.Default.CheckCircle, 
                                contentDescription = "Memory State", 
                                tint = Color(0xFF1565C0)
                            )
                        }
                        
                        when {
                            isSlidingMode -> {
                                Text(
                                    text = "Слайдинг: $slidingCount сообщ.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            isBranchingMode -> {
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
                                    Text(
                                        text = branchName ?: "Основная",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "$branchMessagesCount сообщ.",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        color = Color.Gray
                                    )
                                }
                                IconButton(onClick = onShowBranches) {
                                    BadgedBox(
                                        badge = { 
                                            if (hasBranches) {
                                                Badge(containerColor = Color(0xFF2E7D32)) {
                                                    Text("!", color = Color.White)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Branches", tint = if (hasBranches) Color(0xFF2E7D32) else Color.Gray)
                                    }
                                }
                            }
                            isSummarizationMode -> {
                                if (!summarySnippet.isNullOrBlank()) {
                                    Text(
                                        text = "Суммари: " + summarySnippet.take(15) + "...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF0277BD),
                                        modifier = Modifier.padding(end = 8.dp),
                                        maxLines = 1
                                    )
                                }
                                IconButton(onClick = onShowSummary) {
                                    BadgedBox(
                                        badge = { 
                                            if (hasSummary) {
                                                Badge(containerColor = Color(0xFF0277BD)) {
                                                    Text("!", color = Color.White)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Summary", tint = if (hasSummary) Color(0xFF0277BD) else Color.Gray)
                                    }
                                }
                            }
                            isStickyFactsMode -> {
                                if (factsCount > 0) {
                                    Text(
                                        text = "Факты: $factsCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFF57F17),
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                                IconButton(onClick = onShowFacts) {
                                    BadgedBox(
                                        badge = { 
                                            if (hasFacts) {
                                                Badge(containerColor = Color(0xFFF57F17)) {
                                                    Text("!", color = Color.White)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = "Facts", tint = if (hasFacts) Color(0xFFF57F17) else Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        text = "$tokensUsed",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4A148C)
                    )
                    Text(
                        text = "токенов",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuToggle(false) },
                modifier = Modifier.width(200.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("Общий чат") },
                    onClick = {
                        onSelectAgent(null)
                        onMenuToggle(false)
                    },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF1976D2)) }
                )
                agents.filter { it.id != GENERAL_CHAT_ID }.forEach { agent ->
                    DropdownMenuItem(
                        text = { Text(agent.name) },
                        onClick = {
                            onSelectAgent(agent.id)
                            onMenuToggle(false)
                        },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF673AB7)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputArea(
    input: String,
    onInputChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        color = Color.White
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Кнопка сброса вне поля: во вложенном trailingIcon клики часто не доходут до IconButton (desktop).
            IconButton(onClick = onClearChat) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Clear Chat",
                    tint = Color.Gray
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .sendMessageOnEnter(input) { onSendMessage(input) },
                placeholder = { Text("Сообщение...") },
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (input.isNotBlank()) {
                            onSendMessage(input)
                        }
                    }
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = { if (input.isNotBlank()) onSendMessage(input) },
                containerColor = Color(0xFF1976D2),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
