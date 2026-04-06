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
    val memoryStrategy: ChatMemoryStrategy,
    val branches: List<ChatBranch> = emptyList(),
    val currentBranchId: String? = null,
    val userProfile: UserProfile? = null,
    val workingMemory: WorkingMemory = WorkingMemory()
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
    indices = [Index(value = ["agentId"]), Index(value = ["taskId"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val parentId: String? = null,
    val branchId: String? = null,
    val message: String,
    val source: SourceType,
    val tokenCount: Int,
    val timestamp: Long = 0L,
    val isSystemNotification: Boolean = false,
    val taskId: String? = null,
    val taskState: TaskState? = null
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val taskId: String,
    val title: String,
    val state: AgentTaskState,
    val isPaused: Boolean,
    val step: Int,
    val plan: List<String>,
    val planDone: List<String>,
    val currentPlanStep: String?,
    val totalCount: Int,
    val architectAgentId: String?,
    val executorAgentId: String?,
    val validatorAgentId: String?,
    val architectColor: Long,
    val executorColor: Long,
    val validatorColor: Long
)
