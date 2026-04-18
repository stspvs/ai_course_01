package com.example.ai_develop.domain.task

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

/**
 * Текст системного пузыря при входе в этап [TaskState.EXECUTION]: заголовок, счётчик шагов и полный список
 * пунктов плана с выделением текущего.
 */
object TaskStageExecutionBanner {

    fun executionEntryMessage(plan: PlanResult?, currentStepIndex: Int): String {
        val steps = plan?.steps.orEmpty()
        if (steps.isEmpty()) {
            return buildString {
                appendLine("▶ Начинается этап: исполнение (исполнитель)")
                appendLine()
                appendLine("(В плане нет шагов — ориентируйтесь на цель и контекст задачи.)")
                val g = plan?.goal?.trim().orEmpty()
                if (g.isNotEmpty()) appendLine("Цель: $g")
            }
        }
        val lastIdx = steps.lastIndex
        val idx = currentStepIndex.coerceIn(0, lastIdx)
        return buildString {
            appendLine("▶ Начинается этап: исполнение (исполнитель)")
            appendLine()
            appendLine("Всего пунктов плана: ${steps.size}")
            appendLine("Сейчас выполняется: пункт ${idx + 1} из ${steps.size} (индекс 0-based: $idx)")
            appendLine()
            appendLine("Пункты плана:")
            steps.forEachIndexed { i, raw ->
                val line = raw.trim().ifBlank { "(пусто)" }
                if (i == idx) {
                    appendLine("  ${i + 1}. ▶ $line  ◀")
                } else {
                    appendLine("  ${i + 1}. $line")
                }
            }
        }
    }
}
