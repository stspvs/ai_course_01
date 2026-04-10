package com.example.ai_develop.presentation.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import com.example.ai_develop.domain.AgentTaskState
import com.example.ai_develop.domain.DefaultAgentFactory
import com.example.ai_develop.domain.TaskContext
import com.example.ai_develop.domain.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskRunControlTest {

    private val factory = DefaultAgentFactory()

    private fun task(
        isStarted: Boolean = false,
        isPaused: Boolean = false,
        taskState: TaskState = TaskState.PLANNING
    ) = TaskContext(
        taskId = "t",
        title = "Title",
        state = AgentTaskState(taskState, factory.create()),
        isStarted = isStarted,
        isPaused = isPaused
    )

    @Test
    fun icon_isPlayArrow_whenNotStarted() {
        assertSame(Icons.Default.PlayArrow, taskRunControlIcon(task(isStarted = false, isPaused = false)))
    }

    @Test
    fun icon_isPlayArrow_whenPausedAfterStart() {
        assertSame(Icons.Default.PlayArrow, taskRunControlIcon(task(isStarted = true, isPaused = true)))
    }

    @Test
    fun icon_isPause_whenRunning() {
        assertSame(Icons.Default.Pause, taskRunControlIcon(task(isStarted = true, isPaused = false)))
    }

    @Test
    fun contentDescription_notStarted() {
        assertEquals("Запустить задачу", taskRunControlContentDescription(task(isStarted = false)))
    }

    @Test
    fun contentDescription_paused() {
        assertEquals("Продолжить задачу", taskRunControlContentDescription(task(isStarted = true, isPaused = true)))
    }

    @Test
    fun contentDescription_running() {
        assertEquals("Поставить на паузу", taskRunControlContentDescription(task(isStarted = true, isPaused = false)))
    }

    @Test
    fun usePrimaryTint_whenNotStarted() {
        assertTrue(taskRunControlUsePrimaryTint(task(isStarted = false)))
    }

    @Test
    fun usePrimaryTint_whenPaused() {
        assertTrue(taskRunControlUsePrimaryTint(task(isStarted = true, isPaused = true)))
    }

    @Test
    fun useSecondaryTint_whenRunning() {
        assertFalse(taskRunControlUsePrimaryTint(task(isStarted = true, isPaused = false)))
    }
}
