package com.example.ai_develop.presentation

/**
 * Превью текста в пузырьке чата задачи (см. `TaskChatContent`): свёрнутый вид и суффикс «…».
 */
const val DEFAULT_TASK_BUBBLE_PREVIEW_CHARS = 300

const val TASK_BUBBLE_COLLAPSE_SUFFIX = "…"

fun taskBubbleCollapsible(fullText: String, previewChars: Int = DEFAULT_TASK_BUBBLE_PREVIEW_CHARS): Boolean =
    fullText.length > previewChars

fun taskBubbleDisplayText(
    fullText: String,
    expanded: Boolean,
    previewChars: Int = DEFAULT_TASK_BUBBLE_PREVIEW_CHARS
): String {
    if (!taskBubbleCollapsible(fullText, previewChars)) return fullText
    return if (expanded) fullText else fullText.take(previewChars) + TASK_BUBBLE_COLLAPSE_SUFFIX
}
