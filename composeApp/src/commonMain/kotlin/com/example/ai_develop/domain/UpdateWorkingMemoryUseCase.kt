package com.example.ai_develop.domain

import com.example.ai_develop.data.PromptBuilder
import kotlinx.serialization.json.Json

open class UpdateWorkingMemoryUseCase(
    private val repository: ChatRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun update(agent: Agent): Result<WorkingMemory> {
        val prompt = PromptBuilder.buildWorkingMemoryPrompt(
            currentTask = agent.workingMemory.currentTask,
            progress = agent.workingMemory.progress,
            dialogueGoal = agent.workingMemory.dialogueGoal,
            clarifications = agent.workingMemory.userClarifications,
            fixedTermsAndConstraints = agent.workingMemory.fixedTermsAndConstraints,
            messages = agent.messages
        )

        return repository.analyzeWorkingMemory(agent.messages, prompt, agent.memoryProvider).map { result ->
            agent.workingMemory.copy(
                currentTask = result.currentTask ?: agent.workingMemory.currentTask,
                progress = result.progress ?: agent.workingMemory.progress,
                dialogueGoal = result.dialogueGoal ?: agent.workingMemory.dialogueGoal,
                userClarifications = result.clarifications.ifEmpty { agent.workingMemory.userClarifications },
                fixedTermsAndConstraints = result.fixedTermsAndConstraints.ifEmpty {
                    agent.workingMemory.fixedTermsAndConstraints
                },
            )
        }
    }
}
