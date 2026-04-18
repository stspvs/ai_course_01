package com.example.ai_develop.domain.task

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first

/**
 * Keeps one [TaskSaga] per task id for the app lifecycle. Call [evict] on reset/delete to cancel work.
 */
class TaskSagaCoordinator(
    private val chatRepository: ChatRepository,
    private val localRepository: LocalChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val dispatcher: CoroutineDispatcher
) {
    private val sagas = mutableMapOf<String, TaskSaga>()

    suspend fun getOrCreateSaga(context: TaskContext): TaskSaga {
        sagas[context.taskId]?.let { return it }
        val agents = localRepository.getAgents().first()
        fun pick(id: String?) = id?.let { aid -> agents.find { it.id == aid } }
        val saga = TaskSaga(
            repository = chatRepository,
            localRepository = localRepository,
            architect = pick(context.architectAgentId),
            executor = pick(context.executorAgentId),
            validator = pick(context.validatorAgentId),
            initialContext = context,
            memoryManager = memoryManager,
            dispatcher = dispatcher
        )
        sagas[context.taskId] = saga
        return saga
    }

    fun sagaFor(taskId: String): TaskSaga? = sagas[taskId]

    /**
     * Вызывать после [UpdateTaskUseCase] / сохранения задачи из UI, чтобы активная сага
     * сразу использовала новые лимиты [TaskRuntimeState], а не старый снимок из памяти.
     */
    fun applyRuntimeLimitsAfterTaskSaved(task: TaskContext) {
        sagas[task.taskId]?.applyRuntimeLimitsFrom(task)
    }

    fun evict(taskId: String) {
        sagas.remove(taskId)?.dispose()
    }
}
