package com.example.ai_develop.data.database.mappers

import com.example.ai_develop.data.database.TaskEntity
import com.example.ai_develop.domain.TaskContext

fun TaskEntity.toDomain() = TaskContext(
    taskId = taskId,
    title = title,
    state = state,
    isPaused = isPaused,
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
    validatorColor = validatorColor
)

fun TaskContext.toEntity() = TaskEntity(
    taskId = taskId,
    title = title,
    state = state,
    isPaused = isPaused,
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
    validatorColor = validatorColor
)
