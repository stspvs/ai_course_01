package com.example.ai_develop.domain

enum class ChunkStrategy(val id: String, val labelRu: String) {
    FIXED_WINDOW("FIXED_WINDOW", "Фиксированное окно (overlap)"),
    PARAGRAPH("PARAGRAPH", "По абзацам"),
    SENTENCE("SENTENCE", "По предложениям"),
    RECURSIVE("RECURSIVE", "Рекурсивное (разделители)"),
    ;

    companion object {
        fun fromId(id: String?): ChunkStrategy =
            entries.find { it.id == id } ?: FIXED_WINDOW
    }
}
