package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun createAgent(
    id: String = Uuid.random().toString(),
    memoryStrategy: ChatMemoryStrategy = ChatMemoryStrategy.SlidingWindow(10),
    messages: List<ChatMessage> = emptyList(),
    workingMemory: WorkingMemory = WorkingMemory()
) = Agent(
    id = id,
    name = "Test Agent",
    systemPrompt = "You are a helpful assistant.",
    temperature = 0.7,
    provider = LLMProvider.Yandex(),
    stopWord = "",
    maxTokens = 2000,
    messages = messages,
    memoryStrategy = memoryStrategy,
    workingMemory = workingMemory
)

fun createMessages(count: Int, branchId: String = "main_branch"): List<ChatMessage> {
    return (1..count).map { i ->
        ChatMessage(
            id = "msg_$i",
            role = if (i % 2 == 0) "assistant" else "user",
            message = "Message $i",
            source = if (i % 2 == 0) SourceType.ASSISTANT else SourceType.USER,
            branchId = branchId
        )
    }
}
