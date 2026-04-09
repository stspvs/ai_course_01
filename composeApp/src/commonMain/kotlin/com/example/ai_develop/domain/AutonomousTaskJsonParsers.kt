package com.example.ai_develop.domain

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object AutonomousTaskJsonParsers {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Конец сбалансированного JSON-объекта, начинающегося с [start] (`{`), с учётом строк и экранирования.
     */
    private fun balancedJsonObjectEndIndex(text: String, start: Int): Int? {
        if (start >= text.length || text[start] != '{') return null
        var depth = 0
        var i = start
        var inString = false
        var escape = false
        while (i < text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                i++
                continue
            }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Все верхнеуровневые JSON-объекты по порядку (после каждого `{` — сбалансированный блок).
     * Нужно, когда модель выводит несколько JSON подряд: для EXECUTION/VERIFICATION берём последний валидный.
     */
    fun extractAllTopLevelJsonObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start == -1) break
            val end = balancedJsonObjectEndIndex(text, start) ?: break
            results.add(text.substring(start, end + 1))
            i = end + 1
        }
        return results
    }

    fun extractJsonSubstring(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    fun parsePlannerOutput(text: String): PlannerOutput? {
        val jsonStr = extractJsonSubstring(text) ?: return null
        return try {
            json.decodeFromString<PlannerOutput>(jsonStr)
        } catch (_: Exception) {
            try {
                val legacy = json.decodeFromString<SagaResponse>(jsonStr)
                when (legacy.status.uppercase()) {
                    "SUCCESS" -> PlannerOutput(
                        success = true,
                        plan = null,
                        questions = null,
                        requiresUserConfirmation = false
                    )
                    "FAILED" -> PlannerOutput(success = false, plan = null, questions = null, requiresUserConfirmation = false)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun parsePlanResult(text: String): PlanResult? {
        val jsonStr = extractJsonSubstring(text) ?: return null
        return try {
            json.decodeFromString<PlanResult>(jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Если модель положила в [PlanResult.steps] один элемент — markdown-блок с legacy JSON
     * `{"status":"SUCCESS","result":"..."}`, подменяем шаги на читаемый текст из [SagaResponse.result].
     */
    fun normalizePlanResult(plan: PlanResult): PlanResult {
        if (plan.steps.size != 1) return plan
        val step = plan.steps.first()
        val inner = unwrapMarkdownFence(step) ?: step
        val jsonStr = extractJsonSubstring(inner) ?: return plan
        return try {
            val saga = json.decodeFromString<SagaResponse>(jsonStr)
            if (saga.status.equals("SUCCESS", ignoreCase = true) && saga.result.isNotBlank()) {
                plan.copy(
                    steps = listOf(saga.result.trim()),
                    contextSummary = plan.contextSummary ?: inner.trim().take(4000)
                )
            } else {
                plan
            }
        } catch (_: Exception) {
            plan
        }
    }

    private fun unwrapMarkdownFence(text: String): String? {
        val t = text.trim()
        if (!t.startsWith("```")) return null
        val afterOpen = t.indexOf('\n').takeIf { it >= 0 }?.let { t.drop(it + 1) } ?: t.drop(3)
        val close = afterOpen.lastIndexOf("```")
        if (close < 0) return null
        return afterOpen.take(close).trim()
    }

    fun parseExecutionResult(text: String): ExecutionResult? {
        val candidates = extractAllTopLevelJsonObjects(text.trim())
        val toTry = if (candidates.isNotEmpty()) {
            candidates.asReversed()
        } else {
            val legacy = extractJsonSubstring(text) ?: return null
            listOf(legacy)
        }
        for (jsonStr in toTry) {
            decodeExecutionResult(jsonStr)?.let { return it }
        }
        return null
    }

    private fun decodeExecutionResult(jsonStr: String): ExecutionResult? =
        try {
            json.decodeFromString<ExecutionResult>(jsonStr)
        } catch (_: Exception) {
            try {
                val s = json.decodeFromString<SagaResponse>(jsonStr)
                ExecutionResult(
                    success = s.status.equals("SUCCESS", ignoreCase = true),
                    output = s.result,
                    errors = if (s.status.equals("FAILED", ignoreCase = true)) listOf(s.result) else null
                )
            } catch (_: Exception) {
                null
            }
        }

    fun parseVerificationResult(text: String): VerificationResult? {
        val candidates = extractAllTopLevelJsonObjects(text.trim())
        val toTry = if (candidates.isNotEmpty()) {
            candidates.asReversed()
        } else {
            val legacy = extractJsonSubstring(text) ?: return null
            listOf(legacy)
        }
        for (jsonStr in toTry) {
            decodeVerificationResult(jsonStr)?.let { return it }
        }
        return null
    }

    private fun decodeVerificationResult(jsonStr: String): VerificationResult? =
        try {
            json.decodeFromString<VerificationResult>(jsonStr)
        } catch (_: Exception) {
            try {
                val s = json.decodeFromString<SagaResponse>(jsonStr)
                VerificationResult(
                    success = s.status.equals("SUCCESS", ignoreCase = true),
                    issues = if (!s.status.equals("SUCCESS", ignoreCase = true)) listOf(s.result) else null,
                    suggestions = null
                )
            } catch (_: Exception) {
                null
            }
        }
}
