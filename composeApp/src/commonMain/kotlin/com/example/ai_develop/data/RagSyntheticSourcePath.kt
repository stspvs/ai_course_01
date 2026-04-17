package com.example.ai_develop.data

/**
 * Префикс для [RagDocumentEntity.sourcePath], когда файла с диска нет (вставка без диалога).
 * Нужен, чтобы ключ не совпадал с id документа и не срабатывало старое условие «sourcePath = id» при замене.
 */
internal const val RagSyntheticSourcePathPrefix = "kmp-rag:"

internal fun ragSyntheticSourcePath(docId: String): String =
    RagSyntheticSourcePathPrefix + docId
