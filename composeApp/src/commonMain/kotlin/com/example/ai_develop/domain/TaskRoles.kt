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

enum class ValidatorInstructionKind {
    /** Full inspector prompt: plan, CURRENT STEP, execution, VERIFICATION RULES in user message. */
    PlanStep,

    /** Inspect the full structured plan before any execution (no executor output). */
    WholePlan,

    /** Narrow check: invariant text + execution DATA only in user message; no plan. */
    TaskInvariant,

    /** Narrow check: invariant text + structured plan (PlanResult JSON) as DATA. */
    TaskInvariantPlan
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
        includeUserProfile: Boolean = true,
        includeAgentWorkingMemoryInSystem: Boolean = true
    ): String {
        return memoryManager.wrapSystemPrompt(
            agent,
            includeUserProfile,
            includeAgentWorkingMemoryInSystem
        ) + instruction
    }
    
    fun handleResponse(response: String, sagaResponse: SagaResponse?): RoleResult
}

class ArchitectRole : TaskRole {
    override val taskState = TaskState.PLANNING

    override fun getSystemInstruction(context: TaskContext): String {
        val title = context.title
        val scopeHeader =
            "\n\n[SCOPE] This block is appended to your system prompt for the **next** LLM call only — the assistant reply you are generating in this step. It does not replace your base agent instructions; it adds task-stage rules for this inference.\n\n" +
            "IMPORTANT: You are currently in the PLANNING stage of the task: '$title'.\n" +
            "Your goal is to work with the user to produce a clear plan for the next execution phase — " +
            "concrete steps the executor will run one at a time (not a vague overview).\n" +
            "RULES:\n" +
            "1. Break down the task into logical steps or components.\n" +
            "2. Ask the user questions ONE BY ONE to clarify details. Do not ask multiple questions at once.\n" +
            "3. Wait for the user's response after each question before moving to the next one.\n"

        val plannerOutputShapeDetails =
            "{\"success\":true,\"plan\":{\"goal\":\"short goal\",\"steps\":[\"step 1 — concrete deliverable\",\"step 2 — ...\"],\"successCriteria\":\"how we know it is done\"},\"questions\":[],\"requiresUserConfirmation\":false}\n" +
                "- Field plan.steps MUST be a JSON array of strings: one string per step. Each step must be actionable (what to build or change), not a vague summary.\n" +
                "- For software tasks, include separate steps for shared code, platform-specific UI, Gradle/config, tests — so the executor can output real source code per step.\n" +
                "- Do NOT use the legacy format {\"status\":\"SUCCESS\",\"result\":\"...\"} for the final handoff; it loses structured steps and breaks execution.\n" +
                "- If you cannot produce plan.steps as an array, the automated pipeline will treat the whole plan as a single vague step."

        val jsonShapeNormal =
            "6. When the user has explicitly confirmed and is ready for execution, respond with ONE JSON object only — no markdown, no extra text — using this exact PlannerOutput shape:\n" +
                plannerOutputShapeDetails

        val v = context.runtimeState.lastVerification
        if (v == null) {
            return scopeHeader +
                "4. When the plan is ready, present it to the user and ask for explicit confirmation to proceed to execution.\n" +
                "5. Until the user explicitly confirms (e.g. \"готово\", \"да\", \"подтверждаю\"), communicate in plain text only — no planning JSON.\n" +
                jsonShapeNormal
        }

        return buildString {
            append(scopeHeader)
            appendLine("4. The **plan inspector** (automated check before execution) rejected the plan. See the block below — this is your source of truth for what to fix.")
            appendLine("5. Do **not** ask the user for permission or approval to apply these fixes. Revise the plan autonomously.")
            appendLine("6. Ask the user a question **only** if you still lack information about the **task requirements** (one question at a time). Do not ask \"may I fix\" / \"do you approve the changes\" — fix first.")
            appendLine("7. When the revised plan is ready to be submitted to **automated plan verification** (the next pipeline step), output ONE PlannerOutput JSON with `\"requiresUserConfirmation\": false` and `\"questions\": []` so the run can proceed without an extra confirmation round for inspector-driven edits.")
            appendLine("8. Plain text until you emit that JSON, unless you are asking one clarifying question (rule 6).")
            appendLine(
                "9. If the user asks to continue / start the next stage / run verification (e.g. \"продолжай\", " +
                    "\"начинай следующий этап\", \"приступай к проверке\", \"далее\") and you already have enough task requirements, " +
                    "respond with **only** that PlannerOutput JSON in this turn — no chaser questions, no \"уточните\" unless a blocking detail is missing."
            )
            appendLine("10. Use exactly this PlannerOutput shape for the JSON handoff (see rules 7–9):\n$plannerOutputShapeDetails")
            appendLine()
            appendLine("=== PLAN INSPECTOR FEEDBACK (from last automated plan verification) ===")
            appendLine("Inspector success flag: ${v.success}")
            val issues = v.issues.orEmpty()
            if (issues.isNotEmpty()) {
                appendLine("Issues:")
                issues.forEach { appendLine("- $it") }
            }
            val sugg = v.suggestions.orEmpty()
            if (sugg.isNotEmpty()) {
                appendLine("Suggestions:")
                sugg.forEach { appendLine("- $it") }
            }
            if (issues.isEmpty() && sugg.isEmpty() && !v.success) {
                appendLine("(No structured issues/suggestions; improve plan concreteness, step count, and alignment with success criteria.)")
            }
        }
    }

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
        "- Everything you produce is **text only** inside the JSON string \"output\". Nothing is written to disk; do NOT tell the user (or the app) to create, save, or write files — no commands like \"save as\", \"write to\", or \"create file X\".\n" +
        "- If the step requires implementation (code, Gradle, manifests, resources, Compose, XML, SQL, tests, config): \"output\" MUST contain the full deliverable as text — use markdown code fences inside the string (e.g. ```kotlin … ``` / ```xml … ```) with language tags where helpful. You may add a short comment line such as `// Foo.kt` **only** as a human-readable label, not as an instruction to write that path.\n" +
        "- Do NOT fill \"output\" with plans, rationales, \"I will…\", step-by-step reasoning, or summaries without the actual code/text.\n" +
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
        getValidatorInstruction(context, ValidatorInstructionKind.PlanStep)

    fun getValidatorInstruction(context: TaskContext, kind: ValidatorInstructionKind): String = when (kind) {
        ValidatorInstructionKind.PlanStep -> planStepVerificationInstruction(context)
        ValidatorInstructionKind.WholePlan -> wholePlanVerificationInstruction(context)
        ValidatorInstructionKind.TaskInvariant -> taskInvariantVerificationInstruction()
        ValidatorInstructionKind.TaskInvariantPlan -> taskInvariantPlanVerificationInstruction()
    }

    private fun wholePlanVerificationInstruction(context: TaskContext): String =
        "\n\nIMPORTANT: You are in **plan verification** (before any execution).\n" +
        "Your job is to review the **entire** structured plan in the user message (goal, steps, success criteria). " +
        "No executor output exists yet — judge only whether the plan is actionable, complete enough to execute step-by-step, " +
        "and consistent with the VERIFICATION RULES block.\n" +
        "If the plan is acceptable for execution, return success:true. If not, return success:false with concrete issues " +
        "for the **architect** to fix when revising the plan (not for a code executor).\n" +
        "Use only the blocks in the user message (structured plan, optional \"PLANNER LAST MESSAGE\", previous verdict if any, rules). " +
        "Do not assume extra planning-chat context beyond those blocks.\n" +
        "Do not refer to the task by its title.\n" +
        "Return JSON only in this shape:\n" +
        "{\"success\":true/false,\"issues\":[\"concrete problem 1\", \"...\"],\"suggestions\":[\"optional improvement\", \"...\"]}\n" +
        "When success is false, issues MUST be a non-empty list of actionable items.\n" +
        "Legacy {\"status\":\"SUCCESS\",\"result\":\"...\"} is discouraged."

    private fun planStepVerificationInstruction(context: TaskContext): String =
        "\n\nIMPORTANT: You are currently in the VERIFICATION stage.\n" +
        "Your job is to check whether the Executor's deliverable matches the structured plan and CURRENT STEP in the user message. " +
        "If it is acceptable, return success:true. If not, return success:false with concrete issues for the Executor to fix.\n" +
        "You do NOT use the planning chat transcript; only the blocks in the user message (plan, step, optional previous verification, executor output).\n" +
        "Verify the Executor's output against the CURRENT STEP (same step the Executor was told to run), " +
        "not against the entire plan at once — unless the user message marks the final plan step and asks you to check overall success criteria.\n" +
        "Strictly follow the VERIFICATION RULES block in the user message.\n" +
        "Do not refer to the task by its title.\n" +
        "Return JSON only in this shape (so the executor can read issues on retry):\n" +
        "{\"success\":true/false,\"issues\":[\"concrete problem 1\", \"...\"],\"suggestions\":[\"optional improvement\", \"...\"]}\n" +
        "When success is false, issues MUST be a non-empty list of actionable items the executor must fix.\n" +
        "Legacy {\"status\":\"SUCCESS\",\"result\":\"...\"} is discouraged."

    private fun taskInvariantVerificationInstruction(): String =
        "\n\nIMPORTANT: VERIFICATION mode — **task invariant** check (this request has NO plan and NO CURRENT STEP block).\n" +
        "The user message contains **POLARITY** (POSITIVE vs NEGATIVE), then:\n" +
        "- **INVARIANT**: a short description of a property or constraint.\n" +
        "- **DATA**: the Executor's deliverable (JSON ExecutionResult), especially the \"output\" field.\n" +
        "Interpretation:\n" +
        "- **POSITIVE**: return success:true only if DATA satisfies the invariant (the described thing should hold).\n" +
        "- **NEGATIVE**: return success:true only if DATA does **not** exhibit the forbidden thing described " +
        "(e.g. text says \"MVI\" — success:true means the output is not MVI / does not match that forbidden case).\n" +
        "Do not judge plan steps or full-plan success criteria. Use only POLARITY + INVARIANT + DATA.\n" +
        "This call checks **one** invariant only — do not mention or compare other invariants, and do not claim " +
        "this rule contradicts other task rules.\n" +
        "If the check passes, return success:true. If it fails, return success:false and explain briefly in \"reason\".\n" +
        "Do not refer to the task title.\n" +
        "Return JSON only, no markdown outside JSON:\n" +
        "{\"success\":true/false,\"reason\":\"...\"}\n" +
        "When success is false, \"reason\" MUST be a non-empty actionable explanation for the Executor.\n" +
        "Legacy {\"status\":\"SUCCESS\",\"result\":\"...\"} is discouraged."

    private fun taskInvariantPlanVerificationInstruction(): String =
        "\n\nIMPORTANT: **Plan-level invariant** check (this request has NO executor output).\n" +
        "The user message contains **POLARITY** (POSITIVE vs NEGATIVE), then:\n" +
        "- **INVARIANT**: a short description of a property or constraint.\n" +
        "- **DATA**: the structured plan as JSON ([PlanResult]: goal, steps, successCriteria, …).\n" +
        "Interpretation:\n" +
        "- **POSITIVE**: return success:true only if the plan (as a whole) satisfies the invariant.\n" +
        "- **NEGATIVE**: return success:true only if the plan does **not** violate the forbidden description.\n" +
        "Evaluate the plan text and structure — not hypothetical code the executor might write later.\n" +
        "This call checks **one** invariant only — do not mention or compare other invariants.\n" +
        "If the check passes, return success:true. If it fails, return success:false and explain briefly in \"reason\".\n" +
        "Do not refer to the task title.\n" +
        "Return JSON only, no markdown outside JSON:\n" +
        "{\"success\":true/false,\"reason\":\"...\"}\n" +
        "When success is false, \"reason\" MUST be a non-empty actionable explanation for the architect revising the plan.\n" +
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
