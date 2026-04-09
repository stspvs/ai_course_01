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
    
    /**
     * Возвращает историю сообщений для промпта, полагаясь исключительно на внутреннюю логику Агента.
     * Никакой внешней фильтрации — только то, что прописано в стратегии памяти Агента.
     */
    fun processHistory(
        agent: Agent, 
        memoryManager: ChatMemoryManager
    ): List<ChatMessage> {
        return memoryManager.processMessages(
            messages = agent.messages,
            strategy = agent.memoryStrategy,
            currentBranchId = agent.currentBranchId,
            agentBranches = agent.branches
        )
    }

    /**
     * Формирует системный промпт, используя стандартный механизм оборачивания Агента.
     */
    fun buildSystemPrompt(
        agent: Agent,
        instruction: String,
        memoryManager: ChatMemoryManager,
        includeUserProfile: Boolean = true
    ): String {
        return memoryManager.wrapSystemPrompt(agent, includeUserProfile) + instruction
    }
    
    fun handleResponse(response: String, sagaResponse: SagaResponse?): RoleResult
}

class ArchitectRole : TaskRole {
    override val taskState = TaskState.PLANNING

    override fun getSystemInstruction(context: TaskContext): String =
        "\n\n[SCOPE] This block is appended to your system prompt for the **next** LLM call only — the assistant reply you are generating in this step. It does not replace your base agent instructions; it adds task-stage rules for this inference.\n\n" +
        "IMPORTANT: You are currently in the PLANNING stage of the task: '${context.title}'.\n" +
        "Your goal is to work with the user to create a detailed plan and architecture.\n" +
        "RULES:\n" +
        "1. Break down the task into logical steps or components.\n" +
        "2. Ask the user questions ONE BY ONE to clarify details. Do not ask multiple questions at once.\n" +
        "3. Wait for the user's response after each question before moving to the next one.\n" +
        "4. When the plan is ready, present it to the user and ask for explicit confirmation to proceed to execution.\n" +
        "5. Until the user explicitly confirms (e.g. \"готово\", \"да\", \"подтверждаю\"), communicate in plain text only — no planning JSON.\n" +
        "6. When the user has explicitly confirmed and is ready for execution, respond with ONE JSON object only — no markdown, no extra text — using this exact PlannerOutput shape:\n" +
        "{\"success\":true,\"plan\":{\"goal\":\"short goal\",\"steps\":[\"step 1 — concrete deliverable\",\"step 2 — ...\"],\"successCriteria\":\"how we know it is done\"},\"questions\":[],\"requiresUserConfirmation\":false}\n" +
        "- Field plan.steps MUST be a JSON array of strings: one string per step. Each step must be actionable (what to build or change), not a vague summary.\n" +
        "- For software tasks, include separate steps for shared code, platform-specific UI, Gradle/config, tests — so the executor can output real source code per step.\n" +
        "- Do NOT use the legacy format {\"status\":\"SUCCESS\",\"result\":\"...\"} for the final handoff; it loses structured steps and breaks execution.\n" +
        "- If you cannot produce plan.steps as an array, the automated pipeline will treat the whole plan as a single vague step."

    override fun isJsonMode(): Boolean = false

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
        "\n\n[SCOPE] This block is appended to your system prompt for the **next** LLM call only. It adds task-stage rules for this inference; it does not replace your base developer instructions.\n\n" +
        "IMPORTANT: You are in the EXECUTION stage of task: '${context.title}'.\n" +
        "Execute ONLY the single step in the user message under \"CURRENT STEP\". Ignore other steps of the plan for this reply.\n\n" +
        "PRIMARY RULE — WHAT GOES IN \"output\":\n" +
        "- If the step requires implementation (code, Gradle, manifests, resources, Compose, XML, SQL, tests, config): \"output\" MUST be the deliverable itself — real file bodies in markdown fences (e.g. ```kotlin / ```xml) with file path as the first line inside the block or in a comment. Do NOT fill \"output\" with plans, rationales, \"I will…\", step-by-step reasoning, or summaries of what the code would do without the actual code.\n" +
        "- Put reasoning or meta-commentary OUTSIDE the required deliverable only when the step is explicitly non-code (documentation/analysis). For implementation steps, at most one short sentence in \"output\" is allowed if the step demands a stated assumption; default is code-first, no essay.\n" +
        "- If the step is purely documentation or analysis, prose in \"output\" is OK.\n\n" +
        "Do not summarize future steps or claim you completed later steps.\n" +
        "Respond with a single JSON object only (no markdown outside JSON, no preamble): exactly this shape:\n" +
        "{\"success\":true/false,\"output\":\"...\",\"errors\":[\"...\"]}\n"

    override fun isJsonMode(): Boolean = true

    override fun handleResponse(response: String, sagaResponse: SagaResponse?): RoleResult {
        return when {
            sagaResponse?.status == "SUCCESS" -> RoleResult.Success(sagaResponse.result)
            sagaResponse?.status == "FAILED" -> RoleResult.Failure(sagaResponse.result)
            else -> RoleResult.Failure("Invalid JSON response from executor.")
        }
    }
}

class ValidatorRole : TaskRole {
    override val taskState = TaskState.VERIFICATION

    override fun getSystemInstruction(context: TaskContext): String =
        "\n\nIMPORTANT: You are currently in the VERIFICATION stage.\n" +
        "Your goal is to verify that the task performed by the Executor matches the requirements and plan defined during the PLANNING stage.\n" +
        "Strictly use the instructions and criteria provided in your system prompt for this validation.\n" +
        "Do not refer to the task by its title.\n" +
        "Return JSON only in this shape (so the executor can read issues on retry):\n" +
        "{\"success\":true/false,\"issues\":[\"concrete problem 1\", \"...\"],\"suggestions\":[\"optional improvement\", \"...\"]}\n" +
        "When success is false, issues MUST be a non-empty list of actionable items the executor must fix.\n" +
        "Legacy {\"status\":\"SUCCESS\",\"result\":\"...\"} is discouraged."

    override fun isJsonMode(): Boolean = true

    override fun handleResponse(response: String, sagaResponse: SagaResponse?): RoleResult {
        return when {
            sagaResponse?.status == "SUCCESS" -> RoleResult.Success(sagaResponse.result)
            sagaResponse?.status == "FAILED" -> RoleResult.Failure(sagaResponse.result)
            else -> RoleResult.Failure("Invalid JSON response from validator.")
        }
    }
}
