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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.ai_develop.domain.OllamaDefaultModelName
import com.example.ai_develop.domain.OllamaUiModelNames
import com.example.ai_develop.domain.TextChunk
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
            label = { Text("Модель Ollama для embed") },
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

@Composable
fun RagEmbeddingsContent(viewModel: RagEmbeddingsViewModel = koinViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val saved by viewModel.savedDocuments.collectAsState()
    val topScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    val selectedDoc = saved.find { it.id == ui.expandedDbDocId }
    var bottomPanelExpanded by remember { mutableStateOf(true) }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ui.chunkSize.toString(),
                    onValueChange = { it.toIntOrNull()?.let(viewModel::setChunkSize) },
                    label = { Text("Размер чанка (симв.)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = ui.overlap.toString(),
                    onValueChange = { it.toIntOrNull()?.let(viewModel::setOverlap) },
                    label = { Text("Overlap") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

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
        "${selectedDoc.sourceFileName} · ${selectedDoc.ollamaModel} · chunk=${selectedDoc.chunkSize} overlap=${selectedDoc.overlap}",
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
