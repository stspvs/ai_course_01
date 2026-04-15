package com.example.ai_develop.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import com.example.aidevelop.database.AgentMessageEntity
import com.example.aidevelop.database.AgentStateEntity
import com.example.aidevelop.database.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class SqlDelightChatRepository(
    private val db: AgentDatabase,
    private val networkRepository: ChatRepository
) : ChatRepository by networkRepository,
    AgentRepository,
    TaskRepository,
    MessageRepository,
    LocalChatRepository {

    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    private val queries = db.agentDatabaseQueries

    // --- ChatRepository implementation ---

    override suspend fun saveAgentState(state: AgentState) {
        db.transaction {
            // Сначала сообщения, потом строка агента: иначе observeAgentState(getAgentState)
            // срабатывает на UPDATE агента, а чтение messages попадает между delete и insert — список пустой и UI «съедает» историю.
            queries.deleteMessagesForAgent(state.agentId)
            val taskRow = queries.getTask(state.agentId).executeAsOneOrNull()
            val defaultTaskState = taskRow?.let { TaskState.fromPersisted(it.taskState) }
            val defaultStage = taskStateToAgentStage(defaultTaskState) ?: state.currentStage
            val taskScopedId = if (taskRow != null) state.agentId else null
            state.messages.forEach { msg ->
                val stage = taskStateToAgentStage(msg.taskState) ?: defaultStage
                val rowTaskId = msg.taskId ?: taskScopedId
                queries.insertMessage(
                    id = msg.id.takeIf { it.isNotBlank() } ?: "${state.agentId}_${msg.timestamp}_${msg.role}",
                    agentId = state.agentId,
                    stage = stage,
                    stepId = null,
                    role = msg.role,
                    content = msg.content,
                    timestamp = msg.timestamp,
                    taskId = rowTaskId,
                    llmSnapshotJson = encodeLlmSnapshot(msg.llmRequestSnapshot)
                )
            }
            persistAgentStateRow(state)
        }
    }

    private fun persistAgentStateRow(state: AgentState) {
        queries.saveAgentState(
            agentId = state.agentId,
            name = state.name,
            systemPrompt = state.systemPrompt,
            temperature = state.temperature,
            maxTokens = state.maxTokens.toLong(),
            stopWord = state.stopWord,
            currentStage = state.currentStage,
            currentStepId = state.currentStepId,
            planJson = json.encodeToString(AgentPlan.serializer(), state.plan),
            memoryStrategyJson = json.encodeToString(ChatMemoryStrategy.serializer(), state.memoryStrategy),
            workingMemoryJson = json.encodeToString(WorkingMemory.serializer(), state.workingMemory),
            llmProviderJson = json.encodeToString(LLMProvider.serializer(), state.provider),
            ragEnabled = state.ragEnabled,
        )
    }

    override suspend fun getAgentState(agentId: String): AgentState? {
        return queries.getAgentState(agentId).executeAsOneOrNull()?.let {
            agentStateWithMessages(it)
        }
    }

    override suspend fun deleteAgent(agentId: String) {
        queries.deleteAgent(agentId)
    }

    override fun observeAgentState(agentId: String): Flow<AgentState?> {
        return queries.getAgentState(agentId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { entity ->
                entity?.let { agentStateWithMessages(it) }
            }
    }

    override fun observeAllAgents(): Flow<List<AgentState>> {
        return queries.getAllAgents()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { mapToDomain(it) }
            }
    }

    // --- AgentRepository implementation ---

    override fun getAgents(): Flow<List<Agent>> {
        return observeAllAgents().mapLatest { states ->
            states.map { state ->
                val msgs = queries.getMessagesForAgent(state.agentId).executeAsList().map { mapMessageEntityToChat(it) }
                val profile = getProfile(state.agentId)
                Agent(
                    id = state.agentId,
                    name = state.name,
                    systemPrompt = state.systemPrompt,
                    temperature = state.temperature,
                    provider = state.provider,
                    stopWord = state.stopWord,
                    maxTokens = state.maxTokens,
                    memoryStrategy = state.memoryStrategy,
                    workingMemory = state.workingMemory,
                    messages = msgs,
                    userProfile = profile,
                    ragEnabled = state.ragEnabled,
                )
            }
        }
    }

    override fun getAgentWithMessages(agentId: String): Flow<Agent?> {
        return observeAgentState(agentId).mapLatest { state ->
            state?.let {
                val profile = getProfile(it.agentId)
                Agent(
                    id = it.agentId,
                    name = it.name,
                    systemPrompt = it.systemPrompt,
                    temperature = it.temperature,
                    provider = it.provider,
                    stopWord = it.stopWord,
                    maxTokens = it.maxTokens,
                    memoryStrategy = it.memoryStrategy,
                    workingMemory = it.workingMemory,
                    messages = it.messages,
                    userProfile = profile,
                    ragEnabled = it.ragEnabled,
                )
            }
        }
    }

    override suspend fun saveAgent(agent: Agent): Result<Unit> = runCatching {
        val state = AgentState(
            agentId = agent.id,
            name = agent.name,
            systemPrompt = agent.systemPrompt,
            temperature = agent.temperature,
            provider = agent.provider,
            maxTokens = agent.maxTokens,
            stopWord = agent.stopWord,
            memoryStrategy = agent.memoryStrategy,
            workingMemory = agent.workingMemory,
            messages = agent.messages,
            ragEnabled = agent.ragEnabled,
        )
        saveAgentState(state)
    }

    override suspend fun saveAgentMetadata(agent: Agent): Result<Unit> = runCatching {
        val state = AgentState(
            agentId = agent.id,
            name = agent.name,
            systemPrompt = agent.systemPrompt,
            temperature = agent.temperature,
            provider = agent.provider,
            maxTokens = agent.maxTokens,
            stopWord = agent.stopWord,
            memoryStrategy = agent.memoryStrategy,
            workingMemory = agent.workingMemory,
            ragEnabled = agent.ragEnabled,
        )
        persistAgentStateRow(state)
    }

    // --- TaskRepository implementation ---

    override fun getTasks(): Flow<List<TaskContext>> {
        return queries.getAllTasks()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list -> list.map { mapTaskToDomain(it) } }
    }

    override suspend fun saveTask(task: TaskContext): Result<Unit> = runCatching {
        queries.saveTask(
            taskId = task.taskId,
            title = task.title,
            taskState = task.state.taskState.name,
            isPaused = task.isPaused,
            isStarted = task.isStarted,
            step = task.step.toLong(),
            planJson = json.encodeToString(ListSerializer(String.serializer()), task.plan),
            planDoneJson = json.encodeToString(ListSerializer(String.serializer()), task.planDone),
            currentPlanStep = task.currentPlanStep,
            totalCount = task.totalCount.toLong(),
            architectAgentId = task.architectAgentId,
            executorAgentId = task.executorAgentId,
            validatorAgentId = task.validatorAgentId,
            architectColor = task.architectColor,
            executorColor = task.executorColor,
            validatorColor = task.validatorColor,
            createdAt = System.currentTimeMillis(),
            runtimeStateJson = TaskRuntimeStatePersistence.encode(task.runtimeState)
        )
    }

    override suspend fun deleteTask(task: TaskContext): Result<Unit> = runCatching {
        queries.deleteTask(task.taskId)
    }

    override suspend fun pauseAllTasksOnAppLaunch(): Result<Unit> = runCatching {
        withContext(Dispatchers.Default) {
            queries.pauseAllTasksOnAppLaunch()
        }
    }

    override suspend fun getTask(taskId: String): TaskContext? {
        return queries.getTask(taskId).executeAsOneOrNull()?.let { mapTaskToDomain(it) }
    }

    // --- MessageRepository implementation ---

    override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> {
        return queries.getMessagesForTask(taskId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { mapMessageEntityToChat(it) }
            }
    }

    override suspend fun saveMessage(
        agentId: String,
        message: ChatMessage,
        taskId: String?,
        taskState: TaskState?
    ): Result<Unit> = runCatching {
        val stage = taskStateToAgentStage(taskState) ?: AgentStage.PLANNING
        queries.insertMessage(
            id = message.id ?: "${agentId}_${message.timestamp}",
            agentId = agentId,
            stage = stage,
            stepId = null,
            role = message.role,
            content = message.content,
            timestamp = message.timestamp,
            taskId = taskId,
            llmSnapshotJson = encodeLlmSnapshot(message.llmRequestSnapshot)
        )
    }

    override suspend fun deleteMessagesForTask(taskId: String): Result<Unit> = runCatching {
        queries.deleteMessagesForTask(taskId)
    }

    override suspend fun resetTaskConversation(taskId: String): Result<Unit> = runCatching {
        deleteMessagesForTask(taskId).getOrThrow()
        val task = getTask(taskId)
        val agentIds = buildSet {
            add(taskId)
            task?.architectAgentId?.let { add(it) }
            task?.executorAgentId?.let { add(it) }
            task?.validatorAgentId?.let { add(it) }
        }
        for (agentId in agentIds) {
            clearAgentConversationStateAfterTaskMessagesDeleted(agentId)
        }
    }

    /**
     * После [deleteMessagesForTask] перезагружает сообщения агента из БД и сбрасывает разговорную память
     * (working memory, summarization и т.д.), не трогая системный промпт и профиль.
     * Нужно для ролей (архитектор/исполнитель/валидатор), у которых [agentId] не совпадает с [taskId].
     */
    private suspend fun clearAgentConversationStateAfterTaskMessagesDeleted(agentId: String) {
        val state = getAgentState(agentId) ?: return
        val cleared = state.copy(
            workingMemory = state.workingMemory.clearConversation(),
            memoryStrategy = state.memoryStrategy.clearConversationData(),
            plan = AgentPlan(),
            currentStepId = null,
            currentStage = AgentStage.PLANNING
        )
        saveAgentState(cleared)
    }

    // --- Helpers ---

    private fun agentStateWithMessages(entity: AgentStateEntity): AgentState {
        val base = mapToDomain(entity)
        val msgs = queries.getMessagesForAgent(entity.agentId).executeAsList().map { mapMessageEntityToChat(it) }
        return base.copy(messages = msgs)
    }

    private fun mapMessageEntityToChat(msg: AgentMessageEntity): ChatMessage {
        return ChatMessage(
            id = msg.id,
            role = msg.role,
            message = msg.content,
            timestamp = msg.timestamp,
            source = roleToSourceType(msg.role),
            taskId = msg.taskId,
            taskState = agentStageToTaskState(msg.stage),
            llmRequestSnapshot = decodeLlmSnapshot(msg.llmSnapshotJson)
        )
    }

    private fun encodeLlmSnapshot(snapshot: LlmRequestSnapshot?): String? =
        snapshot?.let { json.encodeToString(LlmRequestSnapshot.serializer(), it) }

    private fun decodeLlmSnapshot(jsonStr: String?): LlmRequestSnapshot? =
        jsonStr?.takeIf { it.isNotBlank() }?.let {
            try {
                json.decodeFromString(LlmRequestSnapshot.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }

    private fun roleToSourceType(role: String): SourceType = when (role.lowercase()) {
        "user" -> SourceType.USER
        "assistant" -> SourceType.AI
        else -> SourceType.SYSTEM
    }

    private fun agentStageToTaskState(stage: AgentStage): TaskState? = when (stage) {
        AgentStage.PLANNING -> TaskState.PLANNING
        AgentStage.EXECUTION -> TaskState.EXECUTION
        AgentStage.REVIEW -> TaskState.VERIFICATION
        AgentStage.DONE -> TaskState.DONE
    }

    private fun taskStateToAgentStage(taskState: TaskState?): AgentStage? = when (taskState) {
        null -> null
        TaskState.PLANNING -> AgentStage.PLANNING
        TaskState.PLAN_VERIFICATION -> AgentStage.REVIEW
        TaskState.EXECUTION -> AgentStage.EXECUTION
        TaskState.VERIFICATION -> AgentStage.REVIEW
        TaskState.DONE -> AgentStage.DONE
    }

    private fun mapToDomain(it: AgentStateEntity): AgentState {
        val providerFromDb = it.llmProviderJson?.takeIf { j -> j.isNotBlank() }?.let { jsonStr ->
            try {
                json.decodeFromString(LLMProvider.serializer(), jsonStr)
            } catch (_: Exception) {
                null
            }
        }
        return AgentState(
            agentId = it.agentId,
            name = it.name,
            systemPrompt = it.systemPrompt,
            temperature = it.temperature,
            provider = providerFromDb ?: LLMProvider.Yandex(),
            maxTokens = it.maxTokens.toInt(),
            stopWord = it.stopWord,
            currentStage = it.currentStage,
            currentStepId = it.currentStepId,
            plan = if (it.planJson.isNotEmpty()) {
                try {
                    json.decodeFromString(AgentPlan.serializer(), it.planJson)
                } catch (e: Exception) {
                    AgentPlan()
                }
            } else {
                AgentPlan()
            },
            memoryStrategy = if (it.memoryStrategyJson.isNotEmpty()) {
                try {
                    json.decodeFromString(ChatMemoryStrategy.serializer(), it.memoryStrategyJson)
                } catch (e: Exception) {
                    ChatMemoryStrategy.SlidingWindow(10)
                }
            } else {
                ChatMemoryStrategy.SlidingWindow(10)
            },
            workingMemory = if (it.workingMemoryJson.isNotEmpty()) {
                try {
                    json.decodeFromString(WorkingMemory.serializer(), it.workingMemoryJson)
                } catch (e: Exception) {
                    WorkingMemory()
                }
            } else {
                WorkingMemory()
            },
            ragEnabled = it.ragEnabled,
        )
    }

    private fun mapTaskToDomain(it: TaskEntity): TaskContext {
        val persistedTaskState = TaskState.fromPersisted(it.taskState)
        val runtimeState = TaskRuntimeStatePersistence.decode(it.taskId, persistedTaskState, it.runtimeStateJson)
        return TaskContext(
            taskId = it.taskId,
            title = it.title,
            state = AgentTaskState(
                taskState = persistedTaskState,
                agent = Agent(id = "temp", name = "Temp", systemPrompt = "", temperature = 0.7, provider = LLMProvider.Yandex(), stopWord = "", maxTokens = 2000, memoryStrategy = ChatMemoryStrategy.SlidingWindow(10), workingMemory = WorkingMemory(), messages = emptyList())
            ),
            isPaused = it.isPaused,
            isStarted = it.isStarted,
            step = it.step.toInt(),
            plan = try { json.decodeFromString(ListSerializer(String.serializer()), it.planJson) } catch(e: Exception) { emptyList() },
            planDone = try { json.decodeFromString(ListSerializer(String.serializer()), it.planDoneJson) } catch(e: Exception) { emptyList() },
            currentPlanStep = it.currentPlanStep,
            totalCount = it.totalCount.toInt(),
            architectAgentId = it.architectAgentId,
            executorAgentId = it.executorAgentId,
            validatorAgentId = it.validatorAgentId,
            architectColor = it.architectColor,
            executorColor = it.executorColor,
            validatorColor = it.validatorColor,
            runtimeState = runtimeState
        )
    }

    override suspend fun getProfile(agentId: String): UserProfile? {
        return queries.getProfile(agentId).executeAsOneOrNull()?.let {
            UserProfile(
                preferences = it.preferences,
                constraints = it.constraints,
                memoryModelProvider = it.memoryModelProviderJson?.let { jsonStr ->
                    try {
                        json.decodeFromString<LLMProvider>(jsonStr)
                    } catch (e: Exception) {
                        null
                    }
                }
            )
        }
    }

    override suspend fun saveProfile(agentId: String, profile: UserProfile) {
        queries.saveProfile(
            agentId = agentId,
            preferences = profile.preferences,
            constraints = profile.constraints,
            memoryModelProviderJson = profile.memoryModelProvider?.let { json.encodeToString(it) }
        )
    }

    override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> {
        return queries.getInvariantsForStage(agentId, stage).executeAsList().map {
            Invariant(it.id, it.rule, it.stage, it.isActive)
        }
    }

    override suspend fun saveInvariant(invariant: Invariant) {
        queries.insertInvariant(
            id = invariant.id,
            agentId = "default",
            rule = invariant.rule,
            stage = invariant.stage,
            isActive = invariant.isActive
        )
    }
}
