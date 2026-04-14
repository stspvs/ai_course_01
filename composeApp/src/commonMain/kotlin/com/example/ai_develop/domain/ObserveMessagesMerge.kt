package com.example.ai_develop.domain

/**
 * Слияние снимка из [observeAgentState] с локальной историей при гонке с сохранением.
 * Тестируется в [com.example.ai_develop.domain.AutonomousAgentTest].
 */
internal fun mergeObserveMessages(
    isProcessing: Boolean,
    localMessages: List<ChatMessage>,
    observed: AgentState,
    fallbackWhenObservedEmpty: List<ChatMessage>
): List<ChatMessage> {
    // Пока идёт ответ, локальная история — единственный источник правды: observe может прислать
    // отстающий снимок (например больше сообщений до strip дубликата [TOOL: …]).
    if (isProcessing && localMessages.isNotEmpty()) {
        return localMessages
    }
    if (observed.messages.isNotEmpty()) return observed.messages
    return if (fallbackWhenObservedEmpty.isNotEmpty()) fallbackWhenObservedEmpty else observed.messages
}
