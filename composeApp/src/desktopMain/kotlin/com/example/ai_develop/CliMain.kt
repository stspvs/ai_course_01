package com.example.ai_develop

import com.example.ai_develop.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class CliAgentManager(
    private val useCase: ChatStreamingUseCase,
    private val repository: ChatRepository,
    private val scope: CoroutineScope
) {
    fun start() {
        println("🚀 Stateful Agent CLI Ready.")
        println("Commands: /state, /plan, /invariants, /add-invariant \"rule\", /force-back, /exit")
        
        while (true) {
            print("> ")
            val input = readLine()
            
            if (input == null) {
                println("\n👋 CLI input stream closed.")
                break
            }

            val trimmedInput = input.trim()
            if (trimmedInput.isEmpty()) continue
            if (trimmedInput == "/exit") break

            runBlocking {
                try {
                    when {
                        trimmedInput == "/state" -> printState()
                        trimmedInput == "/plan" -> printPlan()
                        trimmedInput == "/invariants" -> printInvariants()
                        trimmedInput.startsWith("/add-invariant") -> addInvariant(trimmedInput)
                        trimmedInput == "/force-back" -> forceBack()
                        trimmedInput.startsWith("/") -> println("❌ Unknown command")
                        else -> handleUserMessage(trimmedInput)
                    }
                } catch (e: Exception) {
                    println("❌ Error: ${e.message}")
                }
            }
        }
    }

    private suspend fun printState() {
        val state = repository.getAgentState("default")
        println("📊 Stage: ${state?.currentStage ?: "PLANNING"}")
    }

    private suspend fun printPlan() {
        val state = repository.getAgentState("default")
        println("📋 Plan:")
        state?.plan?.steps?.forEach { println("  [${if (it.isCompleted) "x" else " "}] ${it.description}") }
    }

    private suspend fun printInvariants() {
        val state = repository.getAgentState("default")
        val stage = state?.currentStage ?: AgentStage.PLANNING
        val invs = repository.getInvariants("default", stage)
        println("🛡️ Invariants for $stage:")
        invs.forEach { println("  - ${it.rule}") }
    }

    private suspend fun addInvariant(input: String) {
        val rule = input.removePrefix("/add-invariant").trim().removeSurrounding("\"")
        if (rule.isEmpty()) {
            println("❌ Rule cannot be empty")
            return
        }
        val state = repository.getAgentState("default")
        val stage = state?.currentStage ?: AgentStage.PLANNING
        repository.saveInvariant(Invariant(Uuid.random().toString(), rule, stage, true))
        println("✅ Added invariant to $stage: $rule")
    }

    private suspend fun forceBack() {
        val state = repository.getAgentState("default")
        state?.let {
            repository.saveAgentState(it.copy(currentStage = AgentStage.PLANNING))
            println("🔄 Forced back to PLANNING")
        }
    }

    private suspend fun handleUserMessage(message: String) {
        println("🤖 Agent is thinking...")
        useCase.invokeWithState("default", message, LLMProvider.Yandex()).collect { result ->
            result.onSuccess { chunk ->
                print(chunk)
            }.onFailure { error ->
                println("\n❌ Error: ${error.message}")
            }
        }
        println("\n")
    }
}
