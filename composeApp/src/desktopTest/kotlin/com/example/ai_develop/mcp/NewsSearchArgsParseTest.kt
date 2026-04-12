package com.example.ai_develop.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class NewsSearchArgsParseTest {

    @Test
    fun parseKeyValue_mergedQueryAndPageSize() {
        val p = parseKeyValueStyleToolInput("query=world news, pageSize=5")
        assertEquals("world news", p.query)
        assertEquals(5, p.pageSize)
    }

    @Test
    fun parseKeyValue_queryOnly() {
        val p = parseKeyValueStyleToolInput("query=Paris")
        assertEquals("Paris", p.query)
        assertEquals(null, p.pageSize)
    }

    @Test
    fun parseKeyValue_plainKeywords_noEquals() {
        val p = parseKeyValueStyleToolInput("Japan economy")
        assertEquals(null, p.query)
    }

    @Test
    fun resolve_fromJsonAndMergedString() {
        val args = buildJsonObject {
            put("query", JsonPrimitive("query=world news, pageSize=3"))
        }
        val (q, ps) = resolveNewsSearchArguments(args)
        assertEquals("world news", q)
        assertEquals(3, ps)
    }

    @Test
    fun resolve_jsonPageSizeOverriddenByMergedString() {
        val args = buildJsonObject {
            put("query", JsonPrimitive("query=test, pageSize=8"))
            put("pageSize", JsonPrimitive(5))
        }
        val (q, ps) = resolveNewsSearchArguments(args)
        assertEquals("test", q)
        assertEquals(8, ps)
    }

    @Test
    fun resolve_onlyPageSizeInQueryField() {
        val args = buildJsonObject {
            put("query", JsonPrimitive("pageSize=5"))
        }
        val (q, ps) = resolveNewsSearchArguments(args)
        assertEquals("", q)
        assertEquals(5, ps)
    }
}
