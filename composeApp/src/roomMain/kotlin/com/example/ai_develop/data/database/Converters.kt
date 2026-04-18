package com.example.ai_develop.data.database

import androidx.room.TypeConverter
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromProvider(provider: LLMProvider): String = json.encodeToString(provider)

    @TypeConverter
    fun toProvider(value: String): LLMProvider = json.decodeFromString(value)

    @TypeConverter
    fun fromSourceType(sourceType: SourceType): String = sourceType.name

    @TypeConverter
    fun toSourceType(value: String): SourceType = SourceType.valueOf(value)

    @TypeConverter
    fun fromMemoryStrategy(strategy: ChatMemoryStrategy): String = json.encodeToString(strategy)

    @TypeConverter
    fun toMemoryStrategy(value: String): ChatMemoryStrategy = json.decodeFromString(value)

    @TypeConverter
    fun fromBranches(branches: List<ChatBranch>): String = json.encodeToString(branches)

    @TypeConverter
    fun toBranches(value: String): List<ChatBranch> = json.decodeFromString(value)

    @TypeConverter
    fun fromSummaryDepth(depth: SummaryDepth): String = depth.name

    @TypeConverter
    fun toSummaryDepth(value: String): SummaryDepth = SummaryDepth.valueOf(value)

    @TypeConverter
    fun fromUserProfile(profile: UserProfile?): String? = profile?.let { json.encodeToString(it) }

    @TypeConverter
    fun toUserProfile(value: String?): UserProfile? = value?.let { json.decodeFromString(it) }

    @TypeConverter
    fun fromWorkingMemory(wm: WorkingMemory): String = json.encodeToString(wm)

    @TypeConverter
    fun toWorkingMemory(value: String): WorkingMemory = json.decodeFromString(value)

    @TypeConverter
    fun fromTaskState(state: TaskState): String = state.name

    @TypeConverter
    fun toTaskState(value: String): TaskState = TaskState.fromPersisted(value)

    @TypeConverter
    fun fromStringList(list: List<String>): String = json.encodeToString(list)

    @TypeConverter
    fun toStringList(value: String): List<String> = json.decodeFromString(value)

    @TypeConverter
    fun fromAgentTaskState(state: AgentTaskState): String = json.encodeToString(state)

    @TypeConverter
    fun toAgentTaskState(value: String): AgentTaskState = json.decodeFromString(value)
}
