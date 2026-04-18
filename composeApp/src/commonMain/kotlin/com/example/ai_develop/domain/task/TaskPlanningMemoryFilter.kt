package com.example.ai_develop.domain.task

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

/**
 * Формирует ленту сообщений для контекста **архитектора** (планирование):
 * - только пользователь и планировщик на этапе [TaskState.PLANNING] (и legacy `taskState == null`);
 * - без системных пузырей чата (переходы этапов, ошибки API и т.п.);
 * - сообщения исполнителя и пошагового инспектора ([TaskState.EXECUTION], [TaskState.VERIFICATION]) не попадают в память;
 * - цикл проверки плана ([TaskState.PLAN_VERIFICATION]) не дублируется целиком: из каждого такого блока берётся
 *   только **последний** ответ инспектора; последний ответ планировщика перед этим блоком уже присутствует
 *   в истории PLANNING.
 * - несколько подряд ответов ассистента с валидным JSON [PlannerOutput] схлопываются до **одного** — последнего
 *   (повторные выдачи плана не засоряют контекст).
 * - если [stalePlannerJsonAfterInspectorRejection] (режим правок после отката инспектора): из ленты убираются ответы
 *   с JSON [PlannerOutput], на которые **уже ответили пользователем** позже (например «продолжай», «начинай этап»),
 *   чтобы модель не опиралась на отклонённый план и выдавала новый JSON для повторной проверки.
 */
object TaskPlanningMemoryFilter {

    fun filterForArchitect(
        messages: List<ChatMessage>,
        stalePlannerJsonAfterInspectorRejection: Boolean = false
    ): List<ChatMessage> {
        val sorted = messages.sortedWith(compareBy({ it.timestamp }, { it.id }))
            .filter { !it.isExcludedChatSystemBubble() }
            .filter {
                when (it.taskState) {
                    TaskState.EXECUTION, TaskState.VERIFICATION, TaskState.DONE -> false
                    else -> true
                }
            }

        val result = mutableListOf<ChatMessage>()
        var i = 0
        while (i < sorted.size) {
            val m = sorted[i]
            if (m.taskState == TaskState.PLAN_VERIFICATION) {
                val start = i
                while (i < sorted.size && sorted[i].taskState == TaskState.PLAN_VERIFICATION) i++
                val block = sorted.subList(start, i)
                val lastInspector = block
                    .filter { it.isAssistantModelReply() }
                    .maxWithOrNull(compareBy({ it.timestamp }, { it.id }))
                lastInspector?.let(result::add)
            } else {
                if (m.isPlanningStageUserOrArchitect() && (m.taskState == null || m.taskState == TaskState.PLANNING)) {
                    result.add(m)
                }
                i++
            }
        }
        val collapsed = collapseToLastPlannerJsonOnly(result)
        return if (stalePlannerJsonAfterInspectorRejection) {
            stripPlannerJsonPrecedingLastUserMessage(collapsed)
        } else {
            collapsed
        }
    }

    /**
     * Удаляет все ответы архитектора, распознанные как [PlannerOutput], кроме **последнего** по порядку в списке.
     * Обычный текст и вопросы без парсящегося PlannerOutput не трогаем.
     */
    private fun collapseToLastPlannerJsonOnly(messages: List<ChatMessage>): List<ChatMessage> {
        val plannerJsonIndices = messages.mapIndexedNotNull { idx, m ->
            if (m.isPlannerOutputJsonAssistant()) idx else null
        }
        if (plannerJsonIndices.size <= 1) return messages
        val keepIdx = plannerJsonIndices.last()
        return messages.filterIndexed { idx, _ -> idx !in plannerJsonIndices || idx == keepIdx }
    }

    private fun ChatMessage.isPlannerOutputJsonAssistant(): Boolean {
        if (role != "assistant" || !isAssistantModelReply()) return false
        when (taskState) {
            null, TaskState.PLANNING -> {}
            else -> return false
        }
        return AutonomousTaskJsonParsers.parsePlannerOutput(message.trim()) != null
    }

    /**
     * Убирает JSON-планы, идущие **до** последнего сообщения пользователя: после отката инспектора пользователь
     * часто пишет «продолжай» / «начинай этап», а старый PlannerOutput в истории мешает выдать новый handoff.
     */
    private fun stripPlannerJsonPrecedingLastUserMessage(messages: List<ChatMessage>): List<ChatMessage> {
        val lastUserIdx = messages.indexOfLast { it.role == "user" || it.source == SourceType.USER }
        if (lastUserIdx < 0) return messages
        val removeIdx = messages.mapIndexedNotNull { i, m ->
            if (i < lastUserIdx && m.isPlannerOutputJsonAssistant()) i else null
        }.toSet()
        if (removeIdx.isEmpty()) return messages
        return messages.filterIndexed { i, _ -> i !in removeIdx }
    }

    private fun ChatMessage.isExcludedChatSystemBubble(): Boolean =
        isSystemNotification || role == "system"

    private fun ChatMessage.isPlanningStageUserOrArchitect(): Boolean =
        role == "user" || source == SourceType.USER ||
            (role == "assistant" && isAssistantModelReply())

    private fun ChatMessage.isAssistantModelReply(): Boolean =
        source == SourceType.AI || source == SourceType.ASSISTANT
}
