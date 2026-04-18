package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.chat.Agent
import com.example.ai_develop.domain.chat.ChatFacts
import com.example.ai_develop.domain.chat.ChatMemoryStrategy
import com.example.ai_develop.domain.chat.ExtractFactsUseCase
import com.example.ai_develop.domain.chat.UpdateWorkingMemoryUseCase
import com.example.ai_develop.domain.chat.WorkingMemory
import io.mockk.called
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StickyFactsStrategyDelegateTest {

    private val extractFactsUseCase = mockk<ExtractFactsUseCase>()
    private val updateWorkingMemoryUseCase = mockk<UpdateWorkingMemoryUseCase>()
    private lateinit var delegate: StickyFactsStrategyDelegate

    @BeforeEach
    fun setup() {
        delegate = StickyFactsStrategyDelegate(extractFactsUseCase, updateWorkingMemoryUseCase)
        clearAllMocks()
    }

    @Test
    fun `onMessageReceived should NOT update if not matching interval`() = runTest {
        val strategy = ChatMemoryStrategy.StickyFacts(windowSize = 10, updateInterval = 5)
        // 4 assistant messages (index 2, 4, 6, 8 in 1..8 range)
        val agent = createAgent(memoryStrategy = strategy, messages = createMessages(8))

        delegate.onMessageReceived(agent) { }

        verify { extractFactsUseCase wasNot called }
    }

    @Test
    fun `onMessageReceived should update if matching interval`() = runTest {
        val strategy = ChatMemoryStrategy.StickyFacts(windowSize = 10, updateInterval = 5)
        // 10 assistant messages
        val agent = createAgent(memoryStrategy = strategy, messages = createMessages(20))

        coEvery {
            extractFactsUseCase(
                any(),
                any(),
                any(),
                any()
            )
        } returns Result.success(ChatFacts(facts = listOf("New Fact")))
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(WorkingMemory())

        var updated = false
        delegate.onMessageReceived(agent) { updated = true }

        assertTrue(updated)
        coVerify(exactly = 1) { extractFactsUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `forceUpdate updates facts in both strategy and working memory`() = runTest {
        val strategy = ChatMemoryStrategy.StickyFacts(windowSize = 10)
        val agent = createAgent(memoryStrategy = strategy)
        val newFacts = ChatFacts(facts = listOf("Sticky Fact 1"))

        coEvery { extractFactsUseCase(any(), any(), any(), any()) } returns Result.success(newFacts)
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(WorkingMemory())

        var updatedAgent: Agent? = null
        delegate.forceUpdate(agent) { updatedAgent = it }

        assertNotNull(updatedAgent)
        val finalStrategy = updatedAgent?.memoryStrategy as? ChatMemoryStrategy.StickyFacts
        assertEquals("Sticky Fact 1", finalStrategy?.facts?.facts?.first())
        assertEquals("Sticky Fact 1", updatedAgent?.workingMemory?.extractedFacts?.facts?.first())
    }

    @Test
    fun `forceUpdate updates working memory progress`() = runTest {
        val strategy = ChatMemoryStrategy.StickyFacts(windowSize = 10)
        val agent = createAgent(memoryStrategy = strategy)
        val newWM = WorkingMemory(progress = "Step 2 completed")

        coEvery {
            extractFactsUseCase(
                any(),
                any(),
                any(),
                any()
            )
        } returns Result.success(ChatFacts())
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(newWM)

        var updatedAgent: Agent? = null
        delegate.forceUpdate(agent) { updatedAgent = it }

        assertEquals("Step 2 completed", updatedAgent?.workingMemory?.progress)
    }
}
