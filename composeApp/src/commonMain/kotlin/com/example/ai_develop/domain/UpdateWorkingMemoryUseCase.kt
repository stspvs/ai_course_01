package com.example.ai_develop.domain

import com.example.ai_develop.data.PromptBuilder
import kotlinx.serialization.json.Json

class UpdateWorkingMemoryUseCase(
    private val repository: ChatRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun update(agent: Agent): Result<WorkingMemory> {
        val prompt = PromptBuilder.buildWorkingMemoryPrompt(
            currentTask = agent.workingMemory.currentTask,
            progress = agent.workingMemory.progress,
            messages = agent.messages
        )
        
        return repository.analyzeWorkingMemory(agent.messages, prompt, agent.provider).map { result ->
            agent.workingMemory.copy(
                currentTask = result.currentTask ?: agent.workingMemory.currentTask,
                progress = result.progress ?: agent.workingMemory.progress
            )
        }
    }
}
