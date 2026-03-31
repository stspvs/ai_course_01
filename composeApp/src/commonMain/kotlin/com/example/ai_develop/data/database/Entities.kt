package com.example.ai_develop.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.ai_develop.domain.*

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String,
    val temperature: Double,
    val provider: LLMProvider,
    val stopWord: String,
    val maxTokens: Int,
    val totalTokensUsed: Int,
    val summary: String? = null,
    val summaryPrompt: String = "Кратко суммируй ключевые моменты этого диалога, чтобы сохранить контекст для продолжения беседы. Пиши только саму суть.",
    val summaryDepth: SummaryDepth = SummaryDepth.LOW,
    val memoryStrategy: ChatMemoryStrategy,
    val branches: List<ChatBranch> = emptyList(),
    val currentBranchId: String? = null,
    val keepLastMessagesCount: Int = 10
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["agentId"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val parentId: String? = null,
    val branchId: String? = null, // Добавлено поле для идентификатора ветки
    val message: String,
    val source: SourceType,
    val tokenCount: Int,
    val timestamp: Long = 0L,
    val isSystemNotification: Boolean = false
)
