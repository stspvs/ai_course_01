package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface ChatStrategyDelegate {
    fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    )

    fun forceUpdate(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {}
}

class DefaultStrategyDelegate : ChatStrategyDelegate {
    override fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        scope.launch {
            repository.saveAgentMetadata(agent)
        }
    }
}

class StickyFactsStrategyDelegate(
    private val extractFactsUseCase: ExtractFactsUseCase
) : ChatStrategyDelegate {
    override fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.StickyFacts ?: return
        
        scope.launch {
            repository.saveAgentMetadata(agent)
            
            val assistantMessagesCount = agent.messages.count { it.source == SourceType.ASSISTANT }
            if (assistantMessagesCount > 0 && assistantMessagesCount % strategy.updateInterval == 0) {
                forceUpdate(scope, agent, repository, onAgentUpdated)
            }
        }
    }

    override fun forceUpdate(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.StickyFacts ?: return
        scope.launch {
            extractFactsUseCase(
                messages = agent.messages,
                currentFacts = strategy.facts,
                provider = agent.provider,
                windowSize = strategy.windowSize
            ).onSuccess { newFacts ->
                val updatedAgent = agent.copy(
                    memoryStrategy = strategy.copy(facts = newFacts)
                )
                onAgentUpdated(updatedAgent)
                repository.saveAgentMetadata(updatedAgent)
            }
        }
    }
}

class SummarizationStrategyDelegate(
    private val summarizeChatUseCase: SummarizeChatUseCase
) : ChatStrategyDelegate {
    override fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.Summarization ?: return
        
        scope.launch {
            repository.saveAgentMetadata(agent)
            
            val assistantMessagesCount = agent.messages.count { it.source == SourceType.ASSISTANT }
            if (assistantMessagesCount > 0 && assistantMessagesCount % strategy.windowSize == 0) {
                forceUpdate(scope, agent, repository, onAgentUpdated)
            }
        }
    }

    override fun forceUpdate(
        scope: CoroutineScope,
        agent: Agent,
        repository: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.Summarization ?: return
        scope.launch {
            summarizeChatUseCase(
                messages = agent.messages,
                previousSummary = strategy.summary,
                instruction = strategy.summaryPrompt,
                provider = agent.provider
            ).onSuccess { newSummary ->
                val updatedAgent = agent.copy(
                    memoryStrategy = strategy.copy(summary = newSummary)
                )
                onAgentUpdated(updatedAgent)
                repository.saveAgentMetadata(updatedAgent)
            }
        }
    }
}

/**
 * Реализация Working Memory: автоматическое обновление задачи и прогресса.
 */
class TaskOrientedStrategyDelegate(
    private val repository: ChatRepository // Используем доменный интерфейс для LLM задач
) : ChatStrategyDelegate {
    override fun onMessageReceived(
        scope: CoroutineScope,
        agent: Agent,
        repositoryLocal: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        scope.launch {
            repositoryLocal.saveAgentMetadata(agent)
            
            val strategy = agent.memoryStrategy as? ChatMemoryStrategy.TaskOriented ?: return
            val assistantMessagesCount = agent.messages.count { it.source == SourceType.ASSISTANT }
            
            if (assistantMessagesCount > 0 && assistantMessagesCount % strategy.updateInterval == 0) {
                forceUpdate(scope, agent, repositoryLocal, onAgentUpdated)
            }
        }
    }

    override fun forceUpdate(
        scope: CoroutineScope,
        agent: Agent,
        repositoryLocal: LocalChatRepository,
        onAgentUpdated: (Agent) -> Unit
    ) {
        val strategy = agent.memoryStrategy as? ChatMemoryStrategy.TaskOriented ?: return
        
        scope.launch {
            // Анализируем сообщения для обновления статуса задачи
            val window = if (strategy.analysisWindowSize > 0) strategy.analysisWindowSize else 10
            val recentMessages = agent.messages.takeLast(window)
            
            val prompt = """
                На основе последних сообщений диалога, определи текущее состояние задачи.
                Текущая задача: ${strategy.currentTask ?: "Не определена"}
                Текущий прогресс: ${strategy.progress ?: "Не определен"}
                
                Проанализируй диалог и ответь В ТОЧНОСТИ в формате JSON (без Markdown):
                {
                  "task": "краткое описание текущей цели",
                  "progress": "что сделано / что осталось",
                  "facts": {"ключ": "значение"}
                }
            """.trimIndent()

            repository.chatCompletion(
                messages = recentMessages,
                systemPrompt = prompt,
                provider = agent.provider,
                isJsonMode = true
            ).collect { result ->
                result.onSuccess { jsonResponse ->
                    try {
                        // Упрощенный парсинг для примера. В идеале использовать kotlinx.serialization
                        val updatedStrategy = parseTaskResponse(jsonResponse, strategy)
                        val updatedAgent = agent.copy(memoryStrategy = updatedStrategy)
                        onAgentUpdated(updatedAgent)
                        repositoryLocal.saveAgentMetadata(updatedAgent)
                    } catch (e: Exception) {
                        println("Error parsing task oriented update: ${e.message}")
                    }
                }
            }
        }
    }

    private fun parseTaskResponse(json: String, current: ChatMemoryStrategy.TaskOriented): ChatMemoryStrategy.TaskOriented {
        // Очень простой парсинг, так как мы ожидаем JSON
        // В реальном проекте здесь должен быть полноценный Json.decodeFromString
        val task = json.substringAfter("\"task\": \"").substringBefore("\"")
        val progress = json.substringAfter("\"progress\": \"").substringBefore("\"")
        // Для фактов логика чуть сложнее, оставим пока текущие или пустые
        return current.copy(currentTask = task, progress = progress)
    }
}

class StrategyDelegateFactory(
    private val extractFactsUseCase: ExtractFactsUseCase,
    private val summarizeChatUseCase: SummarizeChatUseCase,
    private val chatRepository: ChatRepository
) {
    fun getDelegate(strategy: ChatMemoryStrategy): ChatStrategyDelegate {
        return when (strategy) {
            is ChatMemoryStrategy.StickyFacts -> StickyFactsStrategyDelegate(extractFactsUseCase)
            is ChatMemoryStrategy.Summarization -> SummarizationStrategyDelegate(summarizeChatUseCase)
            is ChatMemoryStrategy.TaskOriented -> TaskOrientedStrategyDelegate(chatRepository)
            else -> DefaultStrategyDelegate()
        }
    }
}
