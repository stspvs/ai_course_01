package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SummarizationStrategyDelegateTest {

    private val summarizeChatUseCase = mockk<SummarizeChatUseCase>()
    private val updateWorkingMemoryUseCase = mockk<UpdateWorkingMemoryUseCase>()
    private lateinit var delegate: SummarizationStrategyDelegate

    @BeforeEach
    fun setup() {
        delegate = SummarizationStrategyDelegate(summarizeChatUseCase, updateWorkingMemoryUseCase)
        clearAllMocks()
    }

    @Test
    fun `onMessageReceived should NOT update if messages count less than windowSize`() = runTest {
        val strategy = ChatMemoryStrategy.Summarization(windowSize = 10)
        val agent = createAgent(memoryStrategy = strategy, messages = createMessages(18)) // 9 assistant msgs
        
        delegate.onMessageReceived(agent) { }
        
        verify { summarizeChatUseCase wasNot called }
    }

    @Test
    fun `onMessageReceived should update if messages count reach windowSize`() = runTest {
        val strategy = ChatMemoryStrategy.Summarization(windowSize = 10)
        val agent = createAgent(memoryStrategy = strategy, messages = createMessages(20)) // 10 assistant msgs
        
        coEvery { summarizeChatUseCase(any(), any(), any(), any()) } returns Result.success("New Summary")
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(WorkingMemory())

        var updated = false
        delegate.onMessageReceived(agent) { updated = true }
        
        assertTrue(updated)
        coVerify(exactly = 1) { summarizeChatUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `forceUpdate updates summary and strategy`() = runTest {
        val strategy = ChatMemoryStrategy.Summarization(windowSize = 10, summary = "Old")
        val agent = createAgent(memoryStrategy = strategy)
        
        coEvery { summarizeChatUseCase(any(), any(), any(), any()) } returns Result.success("New Summary")
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(WorkingMemory())

        var updatedAgent: Agent? = null
        delegate.forceUpdate(agent) { updatedAgent = it }

        assertNotNull(updatedAgent)
        val finalStrategy = updatedAgent?.memoryStrategy as? ChatMemoryStrategy.Summarization
        assertEquals("New Summary", finalStrategy?.summary)
    }

    @Test
    fun `forceUpdate updates working memory`() = runTest {
        val strategy = ChatMemoryStrategy.Summarization(windowSize = 10)
        val agent = createAgent(memoryStrategy = strategy)
        val newWM = WorkingMemory(currentTask = "Task from Summarization")

        coEvery { summarizeChatUseCase(any(), any(), any(), any()) } returns Result.success("Summary")
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(newWM)

        var updatedAgent: Agent? = null
        delegate.forceUpdate(agent) { updatedAgent = it }

        assertEquals("Task from Summarization", updatedAgent?.workingMemory?.currentTask)
    }
}
