package com.example.ai_develop.domain

/**
 * Границы слоёв саги задачи (только документация).
 *
 * **FSM и персист задачи** — [TaskContext] и [TaskRuntimeState]. Смена этапа только через
 * [AutonomousTaskStateMachine] и [TaskSagaReducer]; запись в БД задачи — [TaskSaga] / [LocalChatRepository.saveTask].
 *
 * **Лента чата** — сообщения задачи в [LocalChatRepository] ([ChatMessage]): диалог, системные баннеры,
 * ответы агентов. Они нужны для UI и контекста LLM, но переходы FSM опираются на структурированные поля
 * ([PlanResult], [ExecutionResult], [VerificationResult] в runtime), а не на повторный парсинг всей ленты.
 */
@Suppress("unused")
object TaskSagaDomainLayer
