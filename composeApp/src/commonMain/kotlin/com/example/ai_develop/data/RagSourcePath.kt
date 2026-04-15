package com.example.ai_develop.data

/**
 * Единый вид пути для ключа источника в БД (избегаем дублей из-за `\`/`/` и регистра на Windows).
 */
fun normalizeRagSourcePath(path: String): String {
    if (path.isBlank()) return ""
    return path.trim()
        .replace('\\', '/')
        .lowercase()
}
