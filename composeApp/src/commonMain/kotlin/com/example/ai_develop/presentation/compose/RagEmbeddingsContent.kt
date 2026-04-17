package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.ai_develop.data.EmbeddingFloatCodec
import com.example.ai_develop.data.RagDocumentSummary
import com.example.ai_develop.data.RagStoredChunk
import com.example.ai_develop.domain.ChunkStrategy
import com.example.ai_develop.domain.OllamaDefaultModelName
import com.example.ai_develop.domain.OllamaUiModelNames
import com.example.ai_develop.domain.RagEvaluationScope
import com.example.ai_develop.domain.RagPipelineMode
import com.example.ai_develop.domain.TextChunk
import com.example.ai_develop.domain.showsEvaluationHeuristicStep
import com.example.ai_develop.domain.showsEvaluationLlmRerankStep
import com.example.ai_develop.domain.showsEvaluationThresholdStep
import com.example.ai_develop.domain.showsHybridLexicalWeight
import com.example.ai_develop.domain.showsLlmRerankControls
import com.example.ai_develop.domain.showsMinSimilarity
import com.example.ai_develop.domain.ragPipelineModeDescription
import com.example.ai_develop.domain.ragPipelineModeMenuSubtitle
import com.example.ai_develop.domain.ragPipelinePanelHelpText
import com.example.ai_develop.presentation.RagEmbeddingsUiState
import com.example.ai_develop.presentation.RagEmbeddingsViewModel
import kotlin.math.sqrt
import org.koin.compose.viewmodel.koinViewModel

private val chunkPalette = listOf(
    Color(0x66E1BEE7),
    Color(0x66BBDEFB),
    Color(0x66C8E6C9),
    Color(0x66FFF9C4),
    Color(0x66FFCCBC),
    Color(0x66D1C4E9),
)
private val overlapColor = Color(0x99FF9800)

private const val PREVIEW_MAX_CHARS = 8000

/** Сколько компонент вектора выводить в одну строку (читаемость в моноширинном блоке). */
private const val EMBEDDING_FLOATS_PER_LINE = 8

private fun formatFloatVectorMultiline(floats: FloatArray, perLine: Int = EMBEDDING_FLOATS_PER_LINE): String {
    if (floats.isEmpty()) return ""
    return floats.toList().chunked(perLine).joinToString("\n") { chunk ->
        chunk.joinToString(", ") { v -> "%.6f".format(v.toDouble()) }
    }
}

@Composable
private fun EmbeddingVectorDisplay(
    label: String,
    floats: FloatArray,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        val dim = floats.size
        val norm = sqrt(floats.sumOf { it.toDouble() * it })
        Text(
            "dim=$dim, L2≈${"%.5f".format(norm)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = formatFloatVectorMultiline(floats),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(scroll),
            )
        }
    }
}

@Composable
private fun EmbeddingVectorFromBlob(
    label: String,
    blob: ByteArray,
    modifier: Modifier = Modifier,
) {
    val decoded = runCatching { EmbeddingFloatCodec.littleEndianBytesToFloatArray(blob) }
    val floats = decoded.getOrNull()
    if (floats == null) {
        Text(
            "Вектор: ошибка разбора BLOB (${blob.size} байт): ${decoded.exceptionOrNull()?.message}",
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    EmbeddingVectorDisplay(label = label, floats = floats, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OllamaEmbedModelDropdown(
    models: List<String>,
    value: String,
    onValueChange: (String) -> Unit,
    labelText: String = "Модель Ollama для embed",
) {
    var expanded by remember { mutableStateOf(false) }
    val ordered = remember(models) { models.distinct() }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(labelText) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
                .fillMaxWidth(),
            placeholder = {
                if (ordered.isEmpty()) {
                    Text("Например $OllamaDefaultModelName", style = MaterialTheme.typography.bodySmall)
                }
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (ordered.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Введите имя модели в поле") },
                    onClick = { expanded = false },
                    enabled = false,
                )
            } else {
                ordered.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onValueChange(name)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RagPipelineSettingsCard(
    ui: RagEmbeddingsUiState,
    viewModel: RagEmbeddingsViewModel,
) {
    val cfg = ui.ragPipelineConfig
    val modelChoices = remember(ui.ollamaLocalModels) {
        (ui.ollamaLocalModels + OllamaUiModelNames).distinct()
    }
    var panelExpanded by remember { mutableStateOf(true) }
    var showPipelineHelp by remember { mutableStateOf(false) }
    val ragPipelineDirty = ui.ragPipelineConfig != ui.savedRagPipelineConfig
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ragPipelineDirty) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { panelExpanded = !panelExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Пайплайн поиска RAG", style = MaterialTheme.typography.titleMedium)
                        if (!panelExpanded) {
                            Text(
                                text = buildString {
                                    append("${cfg.pipelineMode} · ")
                                    if (cfg.scanAllChunks) append("все чанки (≤500)")
                                    else append("recall ${cfg.recallTopK} / final ${cfg.finalTopK}")
                                    if (cfg.pipelineMode.showsMinSimilarity() && cfg.minSimilarity != null) {
                                        append(" · min sim")
                                    }
                                    if (cfg.pipelineMode.showsHybridLexicalWeight()) append(" · hybrid")
                                    if (cfg.pipelineMode.showsLlmRerankControls()) append(" · LLM rerank")
                                    if (cfg.queryRewriteEnabled) append(" · rewrite")
                                    if (!cfg.globalRagEnabled) append(" · чаты: без RAG")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (panelExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (panelExpanded) "Свернуть" else "Развернуть",
                    )
                }
                IconButton(onClick = { showPipelineHelp = true }) {
                    Icon(Icons.Default.Info, contentDescription = "Справка по пайплайну RAG")
                }
            }
            if (panelExpanded) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = cfg.globalRagEnabled,
                    onCheckedChange = { viewModel.updateRagPipeline { c -> c.copy(globalRagEnabled = it) } },
                )
                Column(Modifier.weight(1f)) {
                    Text("RAG в чатах агентов", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Если выключено — ни один агент не подмешивает контекст из базы в ответы, даже при включённом RAG в настройках агента. Панель индекса и проверки поиска ниже доступны всегда.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!cfg.globalRagEnabled) {
                Text(
                    "В чатах контекст из базы не используется; индексация и dry-run на этой панели работают как обычно.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Базовый шаг (эмбеддинг + recall) всегда активен. Остальное — по режиму и порогам.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            var modeExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = modeExpanded, onExpandedChange = { modeExpanded = it }) {
                OutlinedTextField(
                    value = when (cfg.pipelineMode) {
                        RagPipelineMode.Baseline -> "Baseline"
                        RagPipelineMode.Threshold -> "Threshold"
                        RagPipelineMode.Hybrid -> "Hybrid"
                        RagPipelineMode.LlmRerank -> "LLM rerank"
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Режим пайплайна") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                    RagPipelineMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        ragPipelineModeMenuSubtitle(m),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                viewModel.updateRagPipeline { it.copy(pipelineMode = m) }
                                modeExpanded = false
                            },
                        )
                    }
                }
            }
            Text(
                text = ragPipelineModeDescription(cfg.pipelineMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = cfg.scanAllChunks,
                    onCheckedChange = { viewModel.updateRagPipeline { c -> c.copy(scanAllChunks = it) } },
                )
                Text(
                    "Все чанки из БД (до лимита)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Включено: в промпт попадают все проиндексированные фрагменты после ранжирования (не более 500 за запрос); Recall/Final K ниже не ограничивают.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cfg.recallTopK.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> viewModel.updateRagPipeline { c -> c.copy(recallTopK = v.coerceAtLeast(1)) } } },
                    label = { Text("Recall top-K") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !cfg.scanAllChunks,
                )
                OutlinedTextField(
                    value = cfg.finalTopK.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> viewModel.updateRagPipeline { c -> c.copy(finalTopK = v.coerceAtLeast(1)) } } },
                    label = { Text("Final top-K") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !cfg.scanAllChunks,
                )
            }
            if (cfg.pipelineMode.showsMinSimilarity()) {
                OutlinedTextField(
                    value = cfg.minSimilarity?.toString().orEmpty(),
                    onValueChange = { s ->
                        val v = s.toFloatOrNull()
                        viewModel.updateRagPipeline { c -> c.copy(minSimilarity = v) }
                    },
                    label = { Text("Мин. similarity (пусто = не задан)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("напр. 0.28") },
                )
            }
            OutlinedTextField(
                value = cfg.answerRelevanceThreshold?.toString().orEmpty(),
                onValueChange = { s ->
                    val v = s.toFloatOrNull()
                    viewModel.updateRagPipeline { c -> c.copy(answerRelevanceThreshold = v) }
                },
                label = { Text("Порог релевантности для ответа (пусто = выкл.)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("ниже — «не знаю», без контекста") },
                supportingText = {
                    Text(
                        "Если лучший чанк ниже порога, контекст не подмешивается; модель отвечает в режиме «не знаю».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            if (cfg.pipelineMode.showsHybridLexicalWeight()) {
                OutlinedTextField(
                    value = cfg.hybridLexicalWeight.toString(),
                    onValueChange = { it.toFloatOrNull()?.let { v -> viewModel.updateRagPipeline { c -> c.copy(hybridLexicalWeight = v.coerceIn(0f, 1f)) } } },
                    label = { Text("Hybrid: w (косинус vs Jaccard), 0…1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text(
                            "Итог: w·cosine + (1−w)·Jaccard по токенам. Больше w — сильнее эмбеддинги; меньше — сильнее совпадение слов.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = cfg.queryRewriteEnabled,
                    onCheckedChange = { viewModel.updateRagPipeline { c -> c.copy(queryRewriteEnabled = it) } },
                )
                Text("Query rewrite (LLM)")
            }
            if (cfg.queryRewriteEnabled) {
                OllamaEmbedModelDropdown(
                    models = modelChoices,
                    value = cfg.rewriteOllamaModel.ifBlank { OllamaDefaultModelName },
                    onValueChange = { viewModel.updateRagPipeline { c -> c.copy(rewriteOllamaModel = it) } },
                    labelText = "Модель для query rewrite (если Ollama)",
                )
            }
            if (cfg.pipelineMode.showsLlmRerankControls()) {
                OllamaEmbedModelDropdown(
                    models = modelChoices,
                    value = cfg.llmRerankOllamaModel.ifBlank { OllamaDefaultModelName },
                    onValueChange = { viewModel.updateRagPipeline { c -> c.copy(llmRerankOllamaModel = it) } },
                    labelText = "Модель для LLM-rerank",
                )
                OutlinedTextField(
                    value = cfg.llmRerankMaxCandidates.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { v ->
                            viewModel.updateRagPipeline { c -> c.copy(llmRerankMaxCandidates = v.coerceAtLeast(1)) }
                        }
                    },
                    label = { Text("Макс. кандидатов для LLM-rerank") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Text("Охват панели оценки (dry-run)", style = MaterialTheme.typography.labelMedium)
            Text(
                "Не влияет на поиск в чате и на проверку «Запустить»; только для теста и будущей отладки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = cfg.evaluationScope == RagEvaluationScope.ALL,
                        onClick = { viewModel.updateRagPipeline { c -> c.copy(evaluationScope = RagEvaluationScope.ALL) } },
                    )
                    Text("Все опциональные шаги")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = cfg.evaluationScope == RagEvaluationScope.SUBSET,
                        onClick = { viewModel.updateRagPipeline { c -> c.copy(evaluationScope = RagEvaluationScope.SUBSET) } },
                    )
                    Text("Выборочно")
                }
            }
            if (cfg.evaluationScope == RagEvaluationScope.SUBSET) {
                val ev = cfg.evaluationStepsEnabled
                val mode = cfg.pipelineMode
                Column {
                    if (mode.showsEvaluationThresholdStep()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(ev.threshold, { v -> viewModel.updateRagPipeline { c -> c.copy(evaluationStepsEnabled = c.evaluationStepsEnabled.copy(threshold = v)) } })
                            Text("Порог")
                        }
                    }
                    if (mode.showsEvaluationHeuristicStep()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(ev.heuristic, { v -> viewModel.updateRagPipeline { c -> c.copy(evaluationStepsEnabled = c.evaluationStepsEnabled.copy(heuristic = v)) } })
                            Text("Эвристика")
                        }
                    }
                    if (mode.showsEvaluationLlmRerankStep()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(ev.llmRerank, { v -> viewModel.updateRagPipeline { c -> c.copy(evaluationStepsEnabled = c.evaluationStepsEnabled.copy(llmRerank = v)) } })
                            Text("LLM rerank")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(ev.queryRewrite, { v -> viewModel.updateRagPipeline { c -> c.copy(evaluationStepsEnabled = c.evaluationStepsEnabled.copy(queryRewrite = v)) } })
                        Text("Query rewrite")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.saveRagPipeline() },
                    enabled = ui.ragPipelineConfig != ui.savedRagPipelineConfig,
                ) { Text("Сохранить настройки RAG") }
                OutlinedButton(onClick = { viewModel.refreshOllamaModelList() }) { Text("Обновить список моделей Ollama") }
            }
            ui.ollamaModelsLoadError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Проверка извлечения (dry-run)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = ui.ragPreviewQuery,
                onValueChange = viewModel::setRagPreviewQuery,
                label = { Text("Тестовый запрос") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.runRagPreviewDryRun() },
                    enabled = !ui.ragPreviewLoading,
                ) { Text("Запустить") }
                if (ui.ragPreviewLoading) {
                    CircularProgressIndicator(Modifier.height(22.dp).width(22.dp))
                }
            }
            ui.ragPreviewText?.let { t ->
                SelectionContainer {
                    Text(t, style = MaterialTheme.typography.bodySmall)
                }
            }
                }
            }
        }
    }
    if (showPipelineHelp) {
        AlertDialog(
            onDismissRequest = { showPipelineHelp = false },
            title = { Text("Справка: пайплайн RAG") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = ragPipelinePanelHelpText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPipelineHelp = false }) {
                    Text("OK")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChunkStrategyDropdown(
    value: ChunkStrategy,
    onValueChange: (ChunkStrategy) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value.labelRu,
            onValueChange = {},
            readOnly = true,
            label = { Text("Стратегия чанкинга") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ChunkStrategy.entries.forEach { strategy ->
                DropdownMenuItem(
                    text = { Text(strategy.labelRu) },
                    onClick = {
                        onValueChange(strategy)
                        expanded = false
                    },
                )
            }
        }
    }
}

private data class ChunkParamLabels(
    val chunkLabel: String,
    val overlapLabel: String,
    val hint: String,
)

private fun chunkParamLabels(strategy: ChunkStrategy): ChunkParamLabels = when (strategy) {
    ChunkStrategy.FIXED_WINDOW -> ChunkParamLabels(
        chunkLabel = "Длина скользящего окна (симв.)",
        overlapLabel = "Перекрытие соседних окон (симв.)",
        hint = "Текст режется окнами фиксированной длины; следующее окно сдвигается на «длина − перекрытие».",
    )
    ChunkStrategy.PARAGRAPH -> ChunkParamLabels(
        chunkLabel = "Макс. длина абзаца-чанка (симв.)",
        overlapLabel = "Перекрытие при нарезке длинного абзаца (симв.)",
        hint = "Сначала границы по пустым строкам; если абзац длиннее лимита — дополнительная нарезка с перекрытием.",
    )
    ChunkStrategy.SENTENCE -> ChunkParamLabels(
        chunkLabel = "Макс. длина блока предложений (симв.)",
        overlapLabel = "Перекрытие при нарезке длинного предложения (симв.)",
        hint = "Предложения склеиваются до лимита; если одно предложение длиннее — режется окном с перекрытием.",
    )
    ChunkStrategy.RECURSIVE -> ChunkParamLabels(
        chunkLabel = "Макс. длина части после разбиения (симв.)",
        overlapLabel = "Перекрытие при нарезке длинной части (симв.)",
        hint = "Сначала разделители: пустая строка, перевод строки, «. », пробел; куски больше лимита режутся окном.",
    )
}

@Composable
private fun ChunkSizeOverlapFields(
    strategy: ChunkStrategy,
    chunkSize: Int,
    overlap: Int,
    onChunkSize: (Int) -> Unit,
    onOverlap: (Int) -> Unit,
) {
    val labels = chunkParamLabels(strategy)
    Text("Параметры чанкинга", style = MaterialTheme.typography.titleSmall)
    Text(
        labels.hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
    )
    Spacer(Modifier.height(4.dp))
    key(strategy) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = chunkSize.toString(),
                onValueChange = { it.toIntOrNull()?.let(onChunkSize) },
                label = { Text(labels.chunkLabel) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = overlap.toString(),
                onValueChange = { it.toIntOrNull()?.let(onOverlap) },
                label = { Text(labels.overlapLabel) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
    }
}

@Composable
fun RagEmbeddingsContent(viewModel: RagEmbeddingsViewModel = koinViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val saved by viewModel.savedDocuments.collectAsState()
    val topScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    val selectedDoc = saved.find { it.id == ui.expandedDbDocId }
    var bottomPanelExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(topScroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RAG: эмбеддинги Ollama", style = MaterialTheme.typography.titleLarge)

            RagPipelineSettingsCard(ui = ui, viewModel = viewModel)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { viewModel.pickTextFile() }) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Выбрать файл")
                }
                Text(
                    ui.sourceFileName.ifBlank { "Файл не выбран" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                "Список моделей — тот же, что в редакторе агента (Ollama); при необходимости введите другое имя в поле.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            OllamaEmbedModelDropdown(
                models = OllamaUiModelNames,
                value = ui.ollamaModel,
                onValueChange = viewModel::setOllamaModel,
            )
            ChunkStrategyDropdown(
                value = ui.chunkStrategy,
                onValueChange = viewModel::setChunkStrategy,
            )
            ChunkSizeOverlapFields(
                strategy = ui.chunkStrategy,
                chunkSize = ui.chunkSize,
                overlap = ui.overlap,
                onChunkSize = viewModel::setChunkSize,
                onOverlap = viewModel::setOverlap,
            )

            ui.error?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::dismissMessages) { Text("Скрыть") }
            }
            ui.saveMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = viewModel::dismissMessages) { Text("Скрыть") }
            }

            Text("Превью текста и чанки", style = MaterialTheme.typography.titleMedium)
            val previewLen = minOf(ui.fullText.length, PREVIEW_MAX_CHARS)
            val previewBody = ui.fullText.take(previewLen)
            val previewChunks = ui.chunks.mapNotNull { ch ->
                if (ch.start >= previewLen) return@mapNotNull null
                val end = minOf(ch.end, previewLen)
                TextChunk(
                    index = ch.index,
                    start = ch.start,
                    end = end,
                    text = ui.fullText.substring(ch.start, end),
                )
            }
            if (ui.fullText.length > PREVIEW_MAX_CHARS) {
                Text(
                    "Показаны первые $PREVIEW_MAX_CHARS символов; чанкинг считается по полному тексту.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            SelectionContainer {
                Text(
                    text = buildChunkHighlightAnnotatedString(previewBody, previewChunks),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Чанков: ${ui.chunks.size}", style = MaterialTheme.typography.labelMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.generateEmbeddings() },
                    enabled = !ui.isGenerating && ui.chunks.isNotEmpty(),
                ) {
                    Text("Сгенерировать эмбеддинги")
                }
                if (ui.isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
                    Text("${ui.generationDone}/${ui.generationTotal}")
                }
            }
            if (ui.isGenerating && ui.generationTotal > 0) {
                LinearProgressIndicator(
                    progress = { ui.generationDone.toFloat() / ui.generationTotal.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val emb = ui.embeddings
            var generatedEmbeddingsExpanded by remember { mutableStateOf(false) }
            if (emb != null && emb.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    if (generatedEmbeddingsExpanded) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Векторы эмбеддингов (в памяти, до сохранения)",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { generatedEmbeddingsExpanded = false }) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Свернуть результат",
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            emb.forEachIndexed { idx, fa ->
                                val rangeLabel = ui.chunks.getOrNull(idx)?.let { ch ->
                                    " [${ch.start}..${ch.end})"
                                } ?: ""
                                EmbeddingVectorDisplay(
                                    label = "Чанк #${idx + 1}$rangeLabel",
                                    floats = fa,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { generatedEmbeddingsExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Векторы эмбеддингов (${emb.size} чанков) — в памяти, до сохранения",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Развернуть результат",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.saveToDatabase() },
                enabled = ui.embeddings != null && !ui.isGenerating,
            ) {
                Text("Сохранить в БД")
            }
        }

        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (bottomPanelExpanded) Modifier.weight(1.15f) else Modifier,
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            if (bottomPanelExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Сохранённые в базе: список и детализация",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { bottomPanelExpanded = false }) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Свернуть панель",
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .width(260.dp)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(saved, key = { it.id }) { doc ->
                                SavedDocumentListItem(
                                    doc = doc,
                                    selected = ui.expandedDbDocId == doc.id,
                                    onSelect = { viewModel.toggleDbDocumentExpand(doc.id) },
                                    onDelete = { viewModel.deleteDbDocument(doc.id) },
                                )
                            }
                        }
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(detailScroll),
                        ) {
                            RagDocumentDetailSection(
                                selectedDoc = selectedDoc,
                                previewText = ui.dbPreviewText,
                                previewChunks = ui.dbPreviewChunks,
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bottomPanelExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Сохранённые в базе (${saved.size}) — список и детализация",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Развернуть панель",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RagDocumentDetailSection(
    selectedDoc: RagDocumentSummary?,
    previewText: String?,
    previewChunks: List<RagStoredChunk>,
) {
    val key = selectedDoc?.id ?: ""
    var showFullText by remember(key) { mutableStateOf(true) }
    var showChunkText by remember(key) { mutableStateOf(true) }
    var showVectors by remember(key) { mutableStateOf(true) }

    if (selectedDoc == null || previewText == null) {
        Text(
            "Выберите документ в списке слева — здесь откроются полный текст и векторы чанков.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        return
    }

    Text(selectedDoc.title, style = MaterialTheme.typography.titleSmall)
    Text(
        "${selectedDoc.sourceFileName} · ${selectedDoc.ollamaModel} · chunk=${selectedDoc.chunkSize} overlap=${selectedDoc.overlap} · ${ChunkStrategy.fromId(selectedDoc.chunkStrategy).labelRu}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
    )
    Text(
        "Чанков: ${selectedDoc.chunkCount} · создано: ${selectedDoc.createdAt}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Полный текст", style = MaterialTheme.typography.labelMedium)
            Switch(checked = showFullText, onCheckedChange = { showFullText = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Тексты чанков", style = MaterialTheme.typography.labelMedium)
            Switch(checked = showChunkText, onCheckedChange = { showChunkText = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Векторы", style = MaterialTheme.typography.labelMedium)
            Switch(checked = showVectors, onCheckedChange = { showVectors = it })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (showFullText) {
        Text("Полный текст", style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(previewText, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
    }
    if (showChunkText || showVectors) {
        Text("По чанкам", style = MaterialTheme.typography.labelLarge)
        previewChunks.forEachIndexed { idx, ch ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    "Чанк #${ch.chunkIndex + 1} [${ch.startOffset}..${ch.endOffset})",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (showChunkText) {
                    Text(ch.text, style = MaterialTheme.typography.bodySmall)
                }
                if (showVectors) {
                    Spacer(Modifier.height(4.dp))
                    EmbeddingVectorFromBlob(
                        label = "Вектор эмбеддинга (float32)",
                        blob = ch.embeddingBlob,
                    )
                }
                if (idx < previewChunks.lastIndex) {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SavedDocumentListItem(
    doc: RagDocumentSummary,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect() },
            ) {
                Text(doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(
                    "${doc.sourceFileName} · ${doc.chunkCount} чанков",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить")
            }
        }
    }
}

fun buildChunkHighlightAnnotatedString(text: String, chunks: List<TextChunk>): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    if (chunks.isEmpty()) return AnnotatedString(text)
    val boundaries = mutableSetOf<Int>()
    boundaries.add(0)
    boundaries.add(text.length)
    chunks.forEach { ch ->
        boundaries.add(ch.start.coerceIn(0, text.length))
        boundaries.add(ch.end.coerceIn(0, text.length))
    }
    val sorted = boundaries.sorted()
    return buildAnnotatedString {
        for (k in 0 until sorted.lastIndex) {
            val a = sorted[k]
            val b = sorted[k + 1]
            if (a >= b) continue
            val i = a
            val active = chunks.filter { ch -> i >= ch.start && i < ch.end }
            val style = when {
                active.size >= 2 -> SpanStyle(background = overlapColor)
                active.size == 1 -> SpanStyle(background = chunkPalette[active[0].index % chunkPalette.size])
                else -> SpanStyle()
            }
            withStyle(style) {
                append(text.substring(a, b))
            }
        }
    }
}
