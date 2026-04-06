package com.example.ai_develop.domain

import kotlinx.serialization.Serializable

@Serializable
data class SagaResponse(
    val status: String,
    val result: String
)

sealed class RoleResult {
    data class Success(val result: String) : RoleResult()
    data class Failure(val reason: String) : RoleResult()
    object Partial : RoleResult() // Intermediate response, no transition
}

interface TaskRole {
    val taskState: TaskState
    
    fun getSystemInstruction(context: TaskContext): String
    
    fun isJsonMode(): Boolean
    
    fun processHistory(
        messages: List<ChatMessage>, 
        agent: Agent, 
        memoryManager: ChatMemoryManager
    ): List<ChatMessage>

    fun buildSystemPrompt(
        agent: Agent, 
        instruction: String, 
        memoryManager: ChatMemoryManager
    ): String
    
    fun handleResponse(response: String, sagaResponse: SagaResponse?): RoleResult
}

class ArchitectRole : TaskRole {
    override val taskState = TaskState.PLANNING
    
    override fun getSystemInstruction(context: TaskContext): String = 
        "\n\nIMPORTANT: You are currently in the PLANNING stage of the task: '${context.title}'.\n" +
        "Your goal is to work with the user to create a detailed plan and architecture.\n" +
        "RULES:\n" +
        "1. Break down the task into logical steps or components.\n" +
        "2. Ask the user questions ONE BY ONE to clarify details. Do not ask multiple questions at once.\n" +
        "3. Wait for the user's response after each question before moving to the next one.\n" +
        "4. When the plan is ready, present it to the user and ask for explicit confirmation to proceed to execution.\n" +
        "5. ONLY return a JSON response with SUCCESS status when the user has explicitly confirmed the plan and is ready to start execution.\n" +
        "6. Until then, communicate with the user in plain text.\n" +
        "Final JSON format: {\"status\": \"SUCCESS\", \"result\": \"Summary of the agreed plan\"}"

    override fun isJsonMode(): Boolean = false

    override fun processHistory(
        messages: List<ChatMessage>, 
        agent: Agent, 
        memoryManager: ChatMemoryManager
    ): List<ChatMessage> {
        return memoryManager.processMessages(messages, agent.memoryStrategy)
    }

    override fun buildSystemPrompt(
        agent: Agent, 
        instruction: String, 
        memoryManager: ChatMemoryManager
    ): String {
        return memoryManager.wrapSystemPrompt(agent) + instruction
    }

    override fun handleResponse(response: String, sagaResponse: SagaResponse?): RoleResult {
        return when {
            sagaResponse?.status == "SUCCESS" -> RoleResult.Success(sagaResponse.result)
            sagaResponse?.status == "FAILED" -> RoleResult.Failure(sagaResponse.result)
            else -> RoleResult.Partial
        }
    }
}

class ExecutorRole : TaskRole {
    override val taskState = TaskState.EXECUTION
    
    override fun getSystemInstruction(context: TaskContext): String = 
        "\n\nIMPORTANT: You are currently in the EXECUTION stage.\n" +
        "Your goal is to strictly follow and execute the instructions provided in your system prompt.\n" +
        "Focus on the technical implementation of the current step.\n" +
        "Return a JSON response upon completion.\n" +
        "JSON format: {\"status\": \"SUCCESS\"/\"FAILED\", \"result\": \"description\"}"

    override fun isJsonMode(): Boolean = true

    override fun processHistory(
        messages: List<ChatMessage>, 
        agent: Agent, 
        memoryManager: ChatMemoryManager
    ): List<ChatMessage> = messages

    override fun buildSystemPrompt(
        agent: Agent, 
        instruction: String, 
        memoryManager: ChatMemoryManager
    ): String = agent.systemPrompt + instruction

    override fun handleResponse(response: String, sagaResponse: SagaResponse?): RoleResult {
        return when {
            sagaResponse?.status == "SUCCESS" -> RoleResult.Success(sagaResponse.result)
            sagaResponse?.status == "FAILED" -> RoleResult.Failure(sagaResponse.result)
            else -> RoleResult.Failure("Invalid JSON response from executor.")
        }
    }
}

class ValidatorRole : TaskRole {
    override val taskState = TaskState.VALIDATION
    
    override fun getSystemInstruction(context: TaskContext): String = 
        "\n\nIMPORTANT: You are currently in the VALIDATION stage.\n" +
        "Your goal is to verify that the task performed by the Executor matches the requirements and plan defined during the PLANNING stage.\n" +
        "Strictly use the instructions and criteria provided in your system prompt for this validation.\n" +
        "Do not refer to the task by its title.\n" +
        "Return a JSON response with your verdict.\n" +
        "JSON format: {\"status\": \"SUCCESS\"/\"FAILED\", \"result\": \"detailed validation report\"}"

    override fun isJsonMode(): Boolean = true

    override fun processHistory(
        messages: List<ChatMessage>, 
        agent: Agent, 
        memoryManager: ChatMemoryManager
    ): List<ChatMessage> = messages

    override fun buildSystemPrompt(
        agent: Agent, 
        instruction: String, 
        memoryManager: ChatMemoryManager
    ): String = agent.systemPrompt + instruction

    override fun handleResponse(response: String, sagaResponse: SagaResponse?): RoleResult {
        return when {
            sagaResponse?.status == "SUCCESS" -> RoleResult.Success(sagaResponse.result)
            sagaResponse?.status == "FAILED" -> RoleResult.Failure(sagaResponse.result)
            else -> RoleResult.Failure("Invalid JSON response from validator.")
        }
    }
}
