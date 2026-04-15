package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * UseCase-фасад для управления агентами.
 * Инкапсулирует бизнес-логику взаимодействия с репозиторием.
 */
open class AgentManagementUseCase(
    private val repository: ChatRepository,
    private val chatStreamingUseCase: ChatStreamingUseCase,
    private val updateWorkingMemoryUseCase: UpdateWorkingMemoryUseCase
) {

    open suspend fun saveAgentState(state: AgentState) {
        repository.saveAgentState(state)
    }

    open suspend fun getAgentState(agentId: String): AgentState? {
        return repository.getAgentState(agentId)
    }

    open suspend fun deleteAgent(agentId: String) {
        if (agentId == GENERAL_CHAT_ID) return
        repository.deleteAgent(agentId)
    }

    open suspend fun saveProfile(agentId: String, profile: UserProfile) {
        repository.saveProfile(agentId, profile)
        refreshAgent(agentId)
    }

    @OptIn(ExperimentalUuidApi::class)
    open suspend fun createAgent(): String {
        val newId = Uuid.random().toString()
        val newState = AgentState(
            agentId = newId,
            name = "Новый агент",
            systemPrompt = "You are a helpful assistant."
        )
        repository.saveAgentState(newState)
        return newId
    }

    open suspend fun updateAgent(params: UpdateAgentParams) {
        val state = repository.getAgentState(params.id) ?: AgentState(params.id)
        repository.saveAgentState(state.copy(
            name = params.name,
            systemPrompt = params.systemPrompt,
            temperature = params.temperature,
            provider = params.provider,
            maxTokens = params.maxTokens,
            stopWord = params.stopWord,
            memoryStrategy = params.memoryStrategy,
            ragEnabled = params.ragEnabled,
        ))
        refreshAgent(params.id)
    }

    @OptIn(ExperimentalUuidApi::class)
    open suspend fun duplicateAgent(agentId: String): String? {
        val original = repository.getAgentState(agentId) ?: return null
        val newId = Uuid.random().toString()
        repository.saveAgentState(original.copy(
            agentId = newId,
            name = "${original.name} (Copy)",
            messages = emptyList(),
            currentStage = AgentStage.PLANNING
        ))
        return newId
    }

    open suspend fun clearChat(agentId: String) {
        val state = repository.getAgentState(agentId) ?: AgentState(agentId = agentId)
        repository.saveAgentState(
            state.copy(
                messages = emptyList(),
                branches = emptyList(),
                currentBranchId = null,
                workingMemory = state.workingMemory.clearConversation(),
                memoryStrategy = state.memoryStrategy.clearConversationData()
            )
        )
        refreshAgent(agentId)
    }

    open suspend fun updateMemoryStrategy(agentId: String, strategy: ChatMemoryStrategy) {
        val state = repository.getAgentState(agentId) ?: return
        repository.saveAgentState(state.copy(memoryStrategy = strategy))
        refreshAgent(agentId)
    }

    @OptIn(ExperimentalUuidApi::class)
    open suspend fun createBranch(agentId: String, fromMessageId: String, branchName: String) {
        val state = repository.getAgentState(agentId) ?: return
        val branchId = Uuid.random().toString()
        val newBranch = ChatBranch(id = branchId, name = branchName, lastMessageId = fromMessageId)
        
        repository.saveAgentState(state.copy(
            branches = state.branches + newBranch,
            currentBranchId = branchId
        ))
        refreshAgent(agentId)
    }

    open suspend fun switchBranch(agentId: String, branchId: String?) {
        val state = repository.getAgentState(agentId) ?: return
        val normalizedId = if (branchId == "main_branch") null else branchId
        
        repository.saveAgentState(state.copy(currentBranchId = normalizedId))
        refreshAgent(agentId)
    }

    /**
     * Принудительное обновление памяти агента с использованием Flow для отслеживания прогресса.
     */
    open fun forceUpdateMemory(agentId: String): Flow<MemoryUpdateState> = flow {
        emit(MemoryUpdateState(isLoading = true))
        
        try {
            chatStreamingUseCase.ensureToolsLoaded()
            val autonomousAgent = chatStreamingUseCase.getOrCreateAgent(agentId)
            val currentAgent = autonomousAgent.agent.value
            
            if (currentAgent != null) {
                val wmResult = updateWorkingMemoryUseCase.update(currentAgent)
                val updatedWorkingMemory = wmResult.getOrNull()
                
                if (updatedWorkingMemory != null) {
                    val state = repository.getAgentState(agentId)
                    
                    if (state != null) {
                        repository.saveAgentState(state.copy(workingMemory = updatedWorkingMemory))
                        refreshAgent(agentId)
                        
                        emit(MemoryUpdateState(
                            isLoading = false,
                            agentUpdate = agentId to { it.copy(workingMemory = updatedWorkingMemory) }
                        ))
                    } else {
                        emit(MemoryUpdateState(isLoading = false))
                    }
                } else {
                    emit(MemoryUpdateState(isLoading = false))
                }
            } else {
                emit(MemoryUpdateState(isLoading = false))
            }
        } catch (e: Exception) {
            emit(MemoryUpdateState(isLoading = false))
        }
    }

    /**
     * Принудительное обновление состояния AutonomousAgent после изменений в БД.
     */
    private suspend fun refreshAgent(agentId: String) {
        chatStreamingUseCase.ensureToolsLoaded()
        chatStreamingUseCase.getOrCreateAgent(agentId).refreshAgent()
    }
}
