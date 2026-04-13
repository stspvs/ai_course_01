package com.example.ai_develop.data

/**
 * Результат последней проверки MCP (обычно listTools).
 * [UNKNOWN] — ещё не проверяли или меняли URL/заголовки без новой проверки.
 */
enum class McpServerLinkStatus {
    UNKNOWN,
    CONNECTED,
    /** Нет TCP/сеть: процесс не слушает, таймаут, отказ в подключении. */
    NOT_RUNNING,
    /** Ответ есть, но сбой протокола, 4xx/5xx, ошибка MCP и т.п. */
    ERROR,
    ;

    companion object {
        fun fromStored(value: String): McpServerLinkStatus =
            entries.find { it.name == value } ?: UNKNOWN
    }
}

fun classifyMcpLinkFailure(message: String?): McpServerLinkStatus {
    if (message.isNullOrBlank()) return McpServerLinkStatus.ERROR
    val m = message.lowercase()
    val unreachable = listOf(
        "connection refused",
        "connectexception",
        "failed to connect",
        "connection reset",
        "timed out",
        "timeout",
        "unknownhost",
        "no route to host",
        "network is unreachable",
        "actively refused",
        "econnrefused",
        "unreachable",
        "couldn't connect",
        "could not connect",
        // Ktor / CIO
        "connect timed out",
        "read timed out",
    )
    return if (unreachable.any { m.contains(it) }) McpServerLinkStatus.NOT_RUNNING else McpServerLinkStatus.ERROR
}

fun McpServerLinkStatus.displayTitle(): String = when (this) {
    McpServerLinkStatus.UNKNOWN -> "Статус неизвестен"
    McpServerLinkStatus.CONNECTED -> "Подключен"
    McpServerLinkStatus.NOT_RUNNING -> "Не запущен или недоступен"
    McpServerLinkStatus.ERROR -> "Ошибка"
}

fun McpServerLinkStatus.displayHint(): String = when (this) {
    McpServerLinkStatus.UNKNOWN -> "Нажмите «Обновить» на карточке для проверки."
    McpServerLinkStatus.CONNECTED -> "Последняя проверка listTools прошла успешно."
    McpServerLinkStatus.NOT_RUNNING -> "Нет соединения с endpoint (процесс не запущен, неверный адрес или сеть)."
    McpServerLinkStatus.ERROR -> "Сервер ответил с ошибкой — см. текст ниже."
}
