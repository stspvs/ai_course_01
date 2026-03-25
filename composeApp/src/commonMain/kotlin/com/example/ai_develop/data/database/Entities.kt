package com.example.ai_develop.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.presentation.SourceType

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String,
    val temperature: Double,
    val provider: LLMProvider, // Will use TypeConverter
    val stopWord: String,
    val maxTokens: Int,
    val totalTokensUsed: Int
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
    val message: String,
    val source: SourceType, // Will use TypeConverter
    val tokenCount: Int,
    val timestamp: Long = 0L // Useful for sorting
)
