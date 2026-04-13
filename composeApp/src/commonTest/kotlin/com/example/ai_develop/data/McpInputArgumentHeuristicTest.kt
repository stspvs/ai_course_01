package com.example.ai_develop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpInputArgumentHeuristicTest {

    @Test
    fun inferPrimaryArgument_currencyArraySchema_returnsStringArray() {
        val schema = """
            {
              "type": "object",
              "properties": {
                "currencies": {
                  "type": "array",
                  "items": { "type": "string" }
                }
              },
              "required": ["currencies"]
            }
        """.trimIndent()
        val k = inferPrimaryArgument(schema)
        assertIs<McpPrimaryArgumentKind.StringArray>(k)
        assertEquals("currencies", k.key)
    }

    @Test
    fun inferPrimaryArgument_stringQuery_returnsSingleString() {
        val schema = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string" }
              },
              "required": ["query"]
            }
        """.trimIndent()
        val k = inferPrimaryArgument(schema)
        assertIs<McpPrimaryArgumentKind.SingleString>(k)
        assertEquals("query", k.key)
    }

    @Test
    fun inferPrimaryStringArgumentKey_delegatesToKindKey() {
        val schema = """{"properties":{"currencies":{"type":"array","items":{"type":"string"}}},"required":["currencies"]}"""
        assertEquals("currencies", inferPrimaryStringArgumentKey(schema))
    }

    @Test
    fun parseInputToStringList_singleToken() {
        assertEquals(listOf("USD"), parseInputToStringList("USD"))
    }

    @Test
    fun parseInputToStringList_commaSeparated() {
        assertEquals(listOf("USD", "EUR"), parseInputToStringList(" USD , EUR "))
    }

    @Test
    fun parseInputToStringList_jsonArray() {
        assertEquals(listOf("USD", "EUR"), parseInputToStringList("""["USD","EUR"]"""))
    }

    @Test
    fun tryParseFullMcpToolArgumentsObject_parsesObject() {
        val o = tryParseFullMcpToolArgumentsObject("""{"currencies":["JPY"],"hours":24}""")
        assertIs<JsonObject>(o)
        assertEquals(JsonPrimitive(24), o["hours"])
        val arr = o["currencies"]
        assertIs<JsonArray>(arr)
        assertEquals(JsonPrimitive("JPY"), arr[0])
    }

    @Test
    fun tryParseFullMcpToolArgumentsObject_plainCode_returnsNull() {
        assertEquals(null, tryParseFullMcpToolArgumentsObject("JPY"))
    }

    @Test
    fun buildMcpPrimaryArgumentMap_stringArray_encodesJsonArray() {
        val kind = McpPrimaryArgumentKind.StringArray("currencies")
        val map = buildMcpPrimaryArgumentMap(kind, "USD,EUR").getOrThrow()
        val el = map["currencies"]
        assertIs<JsonArray>(el)
        assertEquals(2, el.size)
        assertEquals(JsonPrimitive("USD"), el[0])
        assertEquals(JsonPrimitive("EUR"), el[1])
    }

    @Test
    fun buildMcpPrimaryArgumentMap_stringArray_emptyInput_fails() {
        val kind = McpPrimaryArgumentKind.StringArray("currencies")
        val r = buildMcpPrimaryArgumentMap(kind, "   ")
        assertTrue(r.isFailure)
    }

    @Test
    fun buildMcpPrimaryArgumentMap_singleString_unchanged() {
        val kind = McpPrimaryArgumentKind.SingleString("q")
        val map = buildMcpPrimaryArgumentMap(kind, "hello").getOrThrow()
        assertEquals(JsonPrimitive("hello"), map["q"])
    }

    @Test
    fun stripLeadingJsonColonLabel_removesMcpPrefixes() {
        assertEquals("{\"a\":1}", stripLeadingJsonColonLabel("JSON:\n{\"a\":1}"))
        assertEquals("x", stripLeadingJsonColonLabel("JSON:\nJSON:\nx"))
    }

    @Test
    fun stripLeadingJsonColonLabel_removesJsonLineBetweenProseAndArray() {
        val raw =
            """
            Последние курсы:
            USD: 76

            JSON:
            [{"charCode":"USD"}]
            """.trimIndent()
        val out = stripLeadingJsonColonLabel(raw)
        assertTrue(!out.contains("JSON:"), out)
        assertTrue(out.contains("Последние курсы:"), out)
        assertTrue(!out.contains("charCode"), out)
        assertTrue(!out.contains('['), out)
    }

    @Test
    fun stripLeadingJsonColonLabel_removesTrailingJsonAfterProseLine() {
        val prose = "Последние курсы по валютам:\nUSD: 76 RUB"
        val jsonLine = """[{"charCode":"USD","valuePerUnit":76.2}]"""
        val out = stripLeadingJsonColonLabel("$prose\n$jsonLine")
        assertEquals(prose, out)
    }
}
