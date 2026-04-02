package com.example.ai_develop.data.database

import com.example.ai_develop.domain.ChatFacts
import com.example.ai_develop.domain.ChatMemoryStrategy
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.domain.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun testProviderConversion() {
        val provider = LLMProvider.DeepSeek("deepseek-chat")
        val string = converters.fromProvider(provider)
        val result = converters.toProvider(string)
        assertEquals(provider, result)
    }

    @Test
    fun testMemoryStrategyConversion() {
        val strategy = ChatMemoryStrategy.SlidingWindow(10)
        val string = converters.fromMemoryStrategy(strategy)
        val result = converters.toMemoryStrategy(string)
        assertEquals(strategy, result)
    }

    @Test
    fun testStickyFactsStrategyConversion() {
        val strategy = ChatMemoryStrategy.StickyFacts(windowSize = 20, facts = ChatFacts(listOf("key: value")))
        val string = converters.fromMemoryStrategy(strategy)
        val result = converters.toMemoryStrategy(string)
        assertEquals(strategy, result)
    }

    @Test
    fun testUserProfileConversion() {
        val profile = UserProfile(preferences = "pref", constraints = "cons")
        val string = converters.fromUserProfile(profile)
        val result = converters.toUserProfile(string)
        assertEquals(profile, result)
    }
}
