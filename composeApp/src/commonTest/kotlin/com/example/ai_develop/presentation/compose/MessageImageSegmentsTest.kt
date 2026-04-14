package com.example.ai_develop.presentation.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageImageSegmentsTest {

    @Test
    fun bareImageUrlOnly() {
        val s = parseMessageBodySegments("https://example.com/a.png")
        assertEquals(1, s.size)
        assertTrue(s[0] is MessageBodySegment.Image)
        assertEquals("https://example.com/a.png", (s[0] as MessageBodySegment.Image).url)
    }

    @Test
    fun textAndBareUrl() {
        val s = parseMessageBodySegments("see https://x.com/i.jpg end")
        assertEquals(3, s.size)
        assertEquals("see ", (s[0] as MessageBodySegment.Text).content)
        assertEquals("https://x.com/i.jpg", (s[1] as MessageBodySegment.Image).url)
        assertEquals(" end", (s[2] as MessageBodySegment.Text).content)
    }

    @Test
    fun markdownImage() {
        val s = parseMessageBodySegments("![alt](https://x.com/b.png)")
        assertEquals(1, s.size)
        assertEquals("https://x.com/b.png", (s[0] as MessageBodySegment.Image).url)
    }

    @Test
    fun mixedTextAndMarkdown() {
        val s = parseMessageBodySegments("hello ![x](https://a.com/b.png) world")
        assertEquals(3, s.size)
        assertEquals("hello ", (s[0] as MessageBodySegment.Text).content)
        assertEquals("https://a.com/b.png", (s[1] as MessageBodySegment.Image).url)
        assertEquals(" world", (s[2] as MessageBodySegment.Text).content)
    }

    @Test
    fun nonImageHttpUrlStaysText() {
        val raw = "open https://a.com/page please"
        val s = parseMessageBodySegments(raw)
        assertEquals(1, s.size)
        assertEquals(raw, (s[0] as MessageBodySegment.Text).content)
    }

    @Test
    fun imageUrlsInOrderMatchesImagesFromParse() {
        val raw = "a https://x.com/1.png b https://y.com/2.jpg"
        val fromHelper = imageUrlsInOrder(raw)
        val fromParse = parseMessageBodySegments(raw).filterIsInstance<MessageBodySegment.Image>().map { it.url }
        assertEquals(listOf("https://x.com/1.png", "https://y.com/2.jpg"), fromHelper)
        assertEquals(fromHelper, fromParse)
    }

    @Test
    fun queryStringInBareUrl() {
        val s = parseMessageBodySegments("https://cdn.test/x.png?w=100&h=100")
        assertEquals(1, s.size)
        assertEquals("https://cdn.test/x.png?w=100&h=100", (s[0] as MessageBodySegment.Image).url)
    }
}
