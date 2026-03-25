package com.example.ai_develop.data.database

import androidx.room.TypeConverter
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.presentation.SourceType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromProvider(provider: LLMProvider): String {
        return json.encodeToString(provider)
    }

    @TypeConverter
    fun toProvider(value: String): LLMProvider {
        return json.decodeFromString(value)
    }

    @TypeConverter
    fun fromSourceType(sourceType: SourceType): String {
        return sourceType.name
    }

    @TypeConverter
    fun toSourceType(value: String): SourceType {
        return SourceType.valueOf(value)
    }
}
