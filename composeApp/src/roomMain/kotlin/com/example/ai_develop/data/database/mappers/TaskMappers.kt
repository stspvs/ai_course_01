package com.example.ai_develop.data.database.mappers

import com.example.ai_develop.data.database.TaskEntity
import com.example.ai_develop.domain.TaskContext
import com.example.ai_develop.domain.TaskRuntimeStatePersistence

fun TaskEntity.toDomain() = TaskContext(
    taskId = taskId,
    title = title,
    state = state,
    isPaused = isPaused,
    isStarted = isStarted,
    step = step,
    plan = plan,
    planDone = planDone,
    currentPlanStep = currentPlanStep,
    totalCount = totalCount,
    architectAgentId = architectAgentId,
    executorAgentId = executorAgentId,
    validatorAgentId = validatorAgentId,
    architectColor = architectColor,
    executorColor = executorColor,
    validatorColor = validatorColor,
    runtimeState = TaskRuntimeStatePersistence.decode(taskId, state.taskState, runtimeStateJson)
)

fun TaskContext.toEntity() = TaskEntity(
    taskId = taskId,
    title = title,
    state = state,
    isPaused = isPaused,
    isStarted = isStarted,
    step = step,
    plan = plan,
    planDone = planDone,
    currentPlanStep = currentPlanStep,
    totalCount = totalCount,
    architectAgentId = architectAgentId,
    executorAgentId = executorAgentId,
    validatorAgentId = validatorAgentId,
    architectColor = architectColor,
    executorColor = executorColor,
    validatorColor = validatorColor,
    runtimeStateJson = TaskRuntimeStatePersistence.encode(runtimeState)
)
