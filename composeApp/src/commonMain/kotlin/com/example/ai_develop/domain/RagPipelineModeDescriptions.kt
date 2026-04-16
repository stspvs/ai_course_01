package com.example.ai_develop.domain

/**
 * Тексты для UI: что делает режим пайплайна RAG (см. [RagContextRetriever]).
 */
fun ragPipelineModeDescription(mode: RagPipelineMode): String = when (mode) {
    RagPipelineMode.Baseline ->
        "Только косинусная близость: после recall и объединения чанков берётся top final-K. " +
            "Порог и rerank не применяются; обычно recall K и final K совпадают."
    RagPipelineMode.Threshold ->
        "После recall отсекаются чанки с косинусом ниже мин. similarity, затем из оставшихся берётся top final-K."
    RagPipelineMode.Hybrid ->
        "После порога кандидаты сортируются по combined = w·cosine + (1−w)·Jaccard: cosine — косинус эмбеддингов, Jaccard — пересечение токенов запроса и текста чанка (0…1). " +
            "Параметр w (0…1) из поля настроек: больше w — сильнее семантика; меньше — сильнее совпадение по словам. По умолчанию w≈0.35 — заметный вклад лексики."
    RagPipelineMode.LlmRerank ->
        "После порога до N кандидатов оценивается отдельной моделью Ollama; по оценке список сортируется, затем берётся top final-K."
}

/** Короткая строка для пункта выпадающего меню. */
fun ragPipelineModeMenuSubtitle(mode: RagPipelineMode): String = when (mode) {
    RagPipelineMode.Baseline -> "Только косинус, без порога/rerank"
    RagPipelineMode.Threshold -> "Отсечение по порогу, затем top-K"
    RagPipelineMode.Hybrid -> "Порог + лексический rerank"
    RagPipelineMode.LlmRerank -> "Порог + оценка моделью Ollama"
}
