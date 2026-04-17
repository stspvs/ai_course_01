package com.example.ai_develop

import com.example.ai_develop.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class CliAgentManager(
    private val useCase: ChatStreamingUseCase,
    private val repository: ChatRepository,
    private val scope: CoroutineScope
) {
    private val agentId = "default"
    private val agent: AutonomousAgent = runBlocking {
        useCase.ensureToolsLoaded()
        useCase.getOrCreateAgent(agentId)
    }

    fun start() {
        println("🚀 Autonomous Agent CLI Ready.")
        println("Commands: /state, /plan, /invariants, /add-invariant \"rule\", /next, /exit")
        println("(После каждого сообщения в консоль выводится блок «Источники (RAG)», если применимо.)")
        
        // Подписка на обновления состояния агента для вывода в консоль (опционально)
        scope.launch {
            agent.agent.collect { state ->
                // Можно выводить уведомления о смене стадии здесь
            }
        }

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
                        trimmedInput == "/next" -> handleNextStage()
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
        val currentState = agent.agent.value
        println("📊 Current Stage: ${currentState?.workingMemory?.currentTask ?: "N/A"}")
        // В AutonomousAgent мы можем получить доступ к FSM или через Snapshot
        val dbState = repository.getAgentState(agentId)
        println("📊 Stage from DB: ${dbState?.currentStage ?: "PLANNING"}")
    }

    private suspend fun printPlan() {
        val state = repository.getAgentState(agentId)
        println("📋 Plan:")
        state?.plan?.steps?.forEach { println("  [${if (it.isCompleted) "x" else " "}] ${it.description}") }
    }

    private suspend fun printInvariants() {
        val state = repository.getAgentState(agentId)
        val stage = state?.currentStage ?: AgentStage.PLANNING
        val invs = repository.getInvariants(agentId, stage)
        println("🛡️ Invariants for $stage:")
        invs.forEach { println("  - ${it.rule}") }
    }

    private suspend fun addInvariant(input: String) {
        val rule = input.removePrefix("/add-invariant").trim().removeSurrounding("\"")
        if (rule.isEmpty()) {
            println("❌ Rule cannot be empty")
            return
        }
        val state = repository.getAgentState(agentId)
        val stage = state?.currentStage ?: AgentStage.PLANNING
        repository.saveInvariant(Invariant(Uuid.random().toString(), rule, stage, true))
        println("✅ Added invariant to $stage: $rule")
    }

    private suspend fun handleNextStage() {
        val dbState = repository.getAgentState(agentId)
        val nextStage = when(dbState?.currentStage) {
            AgentStage.PLANNING -> AgentStage.EXECUTION
            AgentStage.EXECUTION -> AgentStage.REVIEW
            AgentStage.REVIEW -> AgentStage.DONE
            else -> AgentStage.PLANNING
        }
        agent.transitionTo(nextStage).onSuccess {
            println("🔄 Transitioned to $nextStage")
        }.onFailure {
            println("❌ Transition failed: ${it.message}")
        }
    }

    private suspend fun handleUserMessage(message: String) {
        println("🤖 Agent is processing...")
        
        // Запускаем отправку в фоне через агента
        val job = scope.launch {
            agent.sendMessage(message)
        }

        // Слушаем поток токенов для вывода в консоль
        agent.partialResponse.takeWhile { agent.isProcessing.value }.collect { chunk ->
            print(chunk)
        }
        
        job.join()
        println("\n")
        printRagSourcesFromLastAssistant()
    }

    private fun printRagSourcesFromLastAssistant() {
        val state = agent.agent.value
        val lastAssistant = state?.messages?.lastOrNull { it.role.equals("assistant", ignoreCase = true) }
        val attr = lastAssistant?.llmRequestSnapshot?.ragAttribution
        println("--- Источники (RAG) ---")
        if (attr == null) {
            println("RAG не использовался для этого ответа (нет атрибуции в снимке запроса).")
            return
        }
        println(
            "База использована: ${attr.used}; низкая релевантность: ${attr.insufficientRelevance}",
        )
        if (attr.sources.isEmpty()) {
            println(
                "(Список чанков пуст — контекст из базы не подмешивался в промпт или фрагменты недоступны.)",
            )
        } else {
            attr.sources.forEachIndexed { i, s ->
                val score = s.score?.let { " · cos ${"%.3f".format(it)}" } ?: ""
                val fin = s.finalScore?.let { " · итог ${"%.3f".format(it)}" } ?: ""
                println(
                    "${i + 1}. ${s.documentTitle} · ${s.sourceFileName} · чанк ${s.chunkIndex}$score$fin",
                )
                if (s.chunkId.isNotBlank()) {
                    println("   id: ${s.chunkId}")
                }
            }
        }
        println()
    }
}
