package com.example.ai_develop.data.database

import androidx.room.TypeConverter
import com.example.ai_develop.domain.*
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
}
