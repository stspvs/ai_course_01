package com.example.ai_develop.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object TaskRuntimeStatePersistence {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(state: TaskRuntimeState): String = json.encodeToString(TaskRuntimeState.serializer(), state)

    fun decode(taskId: String, rowTaskState: TaskState, jsonStr: String?): TaskRuntimeState {
        val raw = jsonStr?.takeIf { it.isNotBlank() && it != "{}" } ?: return TaskRuntimeState.defaultFor(taskId).copy(stage = rowTaskState)
        return try {
            json.decodeFromString(TaskRuntimeState.serializer(), raw).copy(
                taskId = taskId,
                stage = rowTaskState
            )
        } catch (_: Exception) {
            TaskRuntimeState.defaultFor(taskId).copy(stage = rowTaskState)
        }
    }
}
