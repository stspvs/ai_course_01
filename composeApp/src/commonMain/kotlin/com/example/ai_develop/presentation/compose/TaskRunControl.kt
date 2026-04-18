package com.example.ai_develop.presentation.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ai_develop.domain.task.TaskContext

/** Иконка кнопки запуска / паузы в чате задачи (▶ пока не запущена или на паузе, ⏸ во время работы). */
fun taskRunControlIcon(task: TaskContext): ImageVector =
    when {
        !task.isStarted || task.isPaused -> Icons.Default.PlayArrow
        else -> Icons.Default.Pause
    }

fun taskRunControlContentDescription(task: TaskContext): String =
    when {
        !task.isStarted -> "Запустить задачу"
        task.isPaused -> "Продолжить задачу"
        else -> "Поставить на паузу"
    }

/** Primary tint (▶ / продолжить); иначе secondary (пауза). */
fun taskRunControlUsePrimaryTint(task: TaskContext): Boolean =
    !task.isStarted || task.isPaused
