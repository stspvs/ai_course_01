package com.example.ai_develop.domain

import com.example.ai_develop.util.currentTimeMillis
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Возвращает провайдера для задач памяти (суммаризация, факты, рабочая память).
 * Если в профиле пользователя указана модель для памяти, используется она.
 * В противном случае используется основной провайдер агента.
 */
val Agent.memoryProvider: LLMProvider
    get() = userProfile?.memoryModelProvider ?: provider

/** Уникальные имена моделей Ollama из основного провайдера и модели памяти в профиле. */
fun ollamaModelNamesFromAgents(agents: List<Agent>): List<String> {
    val set = linkedSetOf<String>()
    for (a in agents) {
        when (val p = a.provider) {
            is LLMProvider.Ollama -> p.model.trim().takeIf { it.isNotEmpty() }?.let { set.add(it) }
            else -> {}
        }
        a.userProfile?.memoryModelProvider?.let { mp ->
            when (mp) {
                is LLMProvider.Ollama -> mp.model.trim().takeIf { it.isNotEmpty() }?.let { set.add(it) }
                else -> {}
            }
        }
    }
    return set.toList()
}

fun Agent.assistantMessagesCount(): Int {
    return messages.count { it.source == SourceType.ASSISTANT }
}

fun List<Agent>.updateAgent(
    agentId: String,
    update: (Agent) -> Agent
): List<Agent> {
    val index = indexOfFirst { it.id == agentId }
    if (index == -1) return this

    return toMutableList().apply {
        set(index, update(get(index)))
    }
}

fun Agent.mergeWith(db: Agent): Agent {
    val dbIds = db.messages.map { it.id }.toSet()
    val pendingMessages = this.messages.filter { it.id !in dbIds }
    val allMessages = (db.messages + pendingMessages).distinctBy { it.id }.sortedBy { it.timestamp }

    val mergedBranches = db.branches.map { dbBranch ->
        val localBranch = this.branches.find { it.id == dbBranch.id }
        if (localBranch != null && localBranch.lastMessageId != dbBranch.lastMessageId) {
            val hasLocalMsg = allMessages.any { it.id == localBranch.lastMessageId }
            if (hasLocalMsg) localBranch else dbBranch
        } else {
            dbBranch
        }
    }
    
    val finalBranches = mergedBranches + this.branches.filter { l -> db.branches.none { it.id == l.id } }

    // Важно: настройки (name, systemPrompt и т.д.) берем из текущего объекта (this), 
    // так как они могли быть изменены в UI, а БД может прислать старое состояние
    return this.copy(
        messages = allMessages,
        branches = finalBranches,
        currentBranchId = this.currentBranchId ?: db.currentBranchId,
        userProfile = this.userProfile ?: db.userProfile,
        workingMemory = this.workingMemory,
        // Поля, которые приходят из БД (метаданные могут обновиться в БД и мы хотим их видеть)
        // Но если мы редактируем их в UI, 'this' приоритетнее.
        totalTokensUsed = db.totalTokensUsed
    )
}

fun List<ChatBranch>.updatePointer(branchId: String, lastMsgId: String): List<ChatBranch> {
    return if (this.any { it.id == branchId }) {
        this.map { if (it.id == branchId) it.copy(lastMessageId = lastMsgId) else it }
    } else {
        this + ChatBranch(
            id = branchId, 
            name = if (branchId == "main_branch") "Основная" else "Ветка", 
            lastMessageId = lastMsgId
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
fun createChatMessage(
    message: String,
    source: SourceType,
    parentId: String?,
    branchId: String,
    id: String = Uuid.random().toString()
) = ChatMessage(
    id = id,
    parentId = parentId,
    branchId = branchId,
    message = message,
    role = when(source) {
        SourceType.USER -> "user"
        SourceType.AI, SourceType.ASSISTANT -> "assistant"
        SourceType.SYSTEM -> "system"
    },
    tokensUsed = estimateTokens(message),
    timestamp = currentTimeMillis(),
    source = source
)

fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

/**
 * Оценка токенов для UI: сохранённое значение из БД, иначе эвристика по тексту (как в [createChatMessage]).
 */
fun ChatMessage.estimatedTokenCount(): Int {
    val stored = tokensUsed
    return if (stored != null && stored > 0) stored else estimateTokens(message)
}
