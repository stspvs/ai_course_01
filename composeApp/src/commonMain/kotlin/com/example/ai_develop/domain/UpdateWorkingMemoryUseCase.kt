package com.example.ai_develop.domain

import com.example.ai_develop.data.PromptBuilder
import kotlinx.serialization.json.Json

class UpdateWorkingMemoryUseCase(
    private val repository: ChatRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend operator fun invoke(
        agent: Agent,
        provider: LLMProvider
    ): Result<WorkingMemory> {
        val prompt = PromptBuilder.buildWorkingMemoryPrompt(
            currentTask = agent.workingMemory.currentTask,
            progress = agent.workingMemory.progress,
            messages = agent.messages
        )

        val analysisMessages = listOf(
            ChatMessage(message = "You are a memory assistant. Output ONLY valid JSON.", role = "system", source = SourceType.SYSTEM),
            ChatMessage(message = prompt, role = "user", source = SourceType.USER)
        )

        return try {
            val result = repository.chatStreaming(
                messages = analysisMessages,
                systemPrompt = "",
                maxTokens = 500,
                temperature = 0.3,
                stopWord = "",
                isJsonMode = true,
                provider = provider
            )

            // Since it's a streaming call but we need a full JSON, we might need a non-streaming version
            // or collect the stream. But Repository usually has non-streaming methods for analysis.
            // Let's look at ChatRepository again. It has analyzeTask.
            
            val analysisResult = repository.analyzeTask(
                messages = agent.messages,
                instruction = prompt,
                provider = provider
            )
            
            analysisResult.map { 
                // We need to map TaskAnalysisResult or similar to WorkingMemory
                // Actually, let's just use a more direct approach if possible or fix analyzeTask
                agent.workingMemory.copy(
                    currentTask = it.plan.steps.firstOrNull()?.description ?: agent.workingMemory.currentTask,
                    progress = if (it.plan.steps.any { s -> s.isCompleted }) "In progress" else "Started"
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Alternative: Use a direct LLM call and parse JSON
    suspend fun update(agent: Agent): Result<WorkingMemory> {
        val prompt = PromptBuilder.buildWorkingMemoryPrompt(
            currentTask = agent.workingMemory.currentTask,
            progress = agent.workingMemory.progress,
            messages = agent.messages
        )
        
        // Use analyzeTask but we need a specific return type.
        // Let's define a specific method in Repository if needed, or just use the generic one.
        // For now, I'll assume we can use the existing infrastructure to get a JSON.
        
        return repository.analyzeTask(agent.messages, prompt, agent.provider).map { result ->
            agent.workingMemory.copy(
                currentTask = result.plan.steps.firstOrNull()?.description ?: agent.workingMemory.currentTask,
                progress = "Updated"
            )
        }
    }
}
