package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.domain.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultStrategyDelegateTest {

    private val updateWorkingMemoryUseCase = mockk<UpdateWorkingMemoryUseCase>()
    private val extractFactsUseCase = mockk<ExtractFactsUseCase>()
    private lateinit var delegate: DefaultStrategyDelegate

    @BeforeEach
    fun setup() {
        delegate = DefaultStrategyDelegate(updateWorkingMemoryUseCase, extractFactsUseCase)
        clearAllMocks()
    }

    @Test
    fun `onMessageReceived should NOT call forceUpdate if assistant messages count less than 5`() = runTest {
        val agent = createAgent(messages = createMessages(4)) // 2 assistant messages
        var updated = false
        
        delegate.onMessageReceived(agent) { updated = true }
        
        assertFalse(updated)
        verify { updateWorkingMemoryUseCase wasNot called }
    }

    @Test
    fun `onMessageReceived should call forceUpdate on 5th assistant message`() = runTest {
        val messages = createMessages(10) // 5 assistant messages
        val agent = createAgent(messages = messages)
        
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(WorkingMemory(currentTask = "Task 5"))
        coEvery { extractFactsUseCase(any(), any(), any(), any()) } returns Result.success(ChatFacts(facts = listOf("Fact 5")))

        var updated = false
        delegate.onMessageReceived(agent) { updated = true }
        
        assertTrue(updated, "onAgentUpdated should be called")
        coVerify(exactly = 1) { updateWorkingMemoryUseCase.update(any()) }
    }

    @Test
    fun `onMessageReceived should call forceUpdate on 10th assistant message`() = runTest {
        val messages = createMessages(20) // 10 assistant messages
        val agent = createAgent(messages = messages)
        
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(WorkingMemory(currentTask = "Task 10"))
        coEvery { extractFactsUseCase(any(), any(), any(), any()) } returns Result.success(ChatFacts(facts = listOf("Fact 10")))

        var updated = false
        delegate.onMessageReceived(agent) { updated = true }
        
        assertTrue(updated, "onAgentUpdated should be called")
        coVerify(exactly = 1) { updateWorkingMemoryUseCase.update(any()) }
    }

    @Test
    fun `forceUpdate updates working memory and facts`() = runTest {
        val agent = createAgent()
        val newWM = WorkingMemory(currentTask = "New Task")
        val newFacts = ChatFacts(facts = listOf("Fact A"))

        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(newWM)
        coEvery { extractFactsUseCase(any(), any(), any(), any()) } returns Result.success(newFacts)

        var updatedAgent: Agent? = null
        delegate.forceUpdate(agent) { updatedAgent = it }

        assertNotNull(updatedAgent)
        assertEquals("New Task", updatedAgent?.workingMemory?.currentTask)
        assertEquals("Fact A", updatedAgent?.workingMemory?.extractedFacts?.facts?.first())
    }

    @Test
    fun `forceUpdate does not call onAgentUpdated if no changes`() = runTest {
        val wm = WorkingMemory(currentTask = "Same")
        val facts = ChatFacts(facts = listOf("Same"))
        val agent = createAgent(workingMemory = wm.copy(extractedFacts = facts))

        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(wm)
        coEvery { extractFactsUseCase(any(), any(), any(), any()) } returns Result.success(facts)

        var updated = false
        delegate.forceUpdate(agent) { updated = true }

        assertFalse(updated)
    }

    @Test
    fun `forceUpdate handles useCase failure gracefully`() = runTest {
        val agent = createAgent()
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.failure(Exception("Error"))
        coEvery { extractFactsUseCase(any(), any(), any(), any()) } returns Result.success(ChatFacts(facts = listOf("Fact")))

        var updatedAgent: Agent? = null
        delegate.forceUpdate(agent) { updatedAgent = it }

        assertNotNull(updatedAgent)
        // WM should remain old, facts should be updated
        assertEquals(agent.workingMemory.currentTask, updatedAgent?.workingMemory?.currentTask)
        assertEquals("Fact", updatedAgent?.workingMemory?.extractedFacts?.facts?.first())
    }

    @Test
    fun `stress test with 1000 assistant messages`() = runTest {
        val agent = createAgent(messages = createMessages(2000))
        coEvery { updateWorkingMemoryUseCase.update(any()) } returns Result.success(WorkingMemory(currentTask = "Stress"))
        coEvery { extractFactsUseCase(any(), any(), any(), any()) } returns Result.success(ChatFacts())

        delegate.onMessageReceived(agent) {}

        coVerify(exactly = 1) { updateWorkingMemoryUseCase.update(any()) }
    }
}
