package com.example.ai_develop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Как интерпретировать одну строку `input` из `[TOOL: name(input)]` для MCP callTool.
 */
sealed class McpPrimaryArgumentKind {
    abstract val key: String

    /** Один параметр-строка → [JsonPrimitive]. */
    data class SingleString(override val key: String) : McpPrimaryArgumentKind()

    /** Массив строк (например коды валют) → [JsonArray] из [JsonPrimitive]. */
    data class StringArray(override val key: String) : McpPrimaryArgumentKind()
}

fun inferPrimaryStringArgumentKey(inputSchemaJson: String): String =
    inferPrimaryArgument(inputSchemaJson).key

/**
 * Выбирает основной параметр по JSON Schema: сначала массив строк в `required`,
 * затем строка в `required`, затем первый массив строк / строка в `properties`.
 */
fun inferPrimaryArgument(inputSchemaJson: String): McpPrimaryArgumentKind {
    if (inputSchemaJson.isBlank() || inputSchemaJson == "{}") {
        return McpPrimaryArgumentKind.SingleString("query")
    }
    return try {
        val root = json.parseToJsonElement(inputSchemaJson).jsonObject
        val props = root["properties"]?.jsonObject
            ?: return McpPrimaryArgumentKind.SingleString("query")
        val required = root["required"]?.jsonArray?.mapNotNull { el ->
            runCatching { el.jsonPrimitive.content }.getOrNull()
        } ?: emptyList()

        required.firstOrNull { key ->
            props[key]?.jsonObject?.let { isStringArrayProperty(it) } == true
        }?.let { return McpPrimaryArgumentKind.StringArray(it) }

        required.firstOrNull { key ->
            props[key]?.jsonObject?.get("type")?.jsonPrimitive?.content == "string"
        }?.let { return McpPrimaryArgumentKind.SingleString(it) }

        props.keys.firstOrNull { key ->
            props[key]?.jsonObject?.let { isStringArrayProperty(it) } == true
        }?.let { return McpPrimaryArgumentKind.StringArray(it) }

        props.keys.firstOrNull { key ->
            props[key]?.jsonObject?.get("type")?.jsonPrimitive?.content == "string"
        }?.let { return McpPrimaryArgumentKind.SingleString(it) }

        McpPrimaryArgumentKind.SingleString("query")
    } catch (_: Exception) {
        McpPrimaryArgumentKind.SingleString("query")
    }
}

private fun isStringArrayProperty(prop: JsonObject): Boolean {
    if (!hasTypeArray(prop)) return false
    val items = prop["items"]?.jsonObject ?: return false
    val itemType = items["type"]?.jsonPrimitive?.content
    return itemType == "string"
}

private fun hasTypeArray(prop: JsonObject): Boolean {
    val typeEl = prop["type"] ?: return false
    val typeStr = runCatching { typeEl.jsonPrimitive.content }.getOrNull()
    if (typeStr == "array") return true
    val arr = runCatching { typeEl.jsonArray }.getOrNull() ?: return false
    return arr.any { runCatching { it.jsonPrimitive.content }.getOrNull() == "array" }
}

/**
 * Парсит ввод модели в список строк: JSON-массив `["a","b"]` или `a, b` / один токен.
 */
fun parseInputToStringList(raw: String): List<String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        runCatching {
            val el = json.parseToJsonElement(trimmed)
            if (el is JsonArray) {
                return el.mapNotNull { elem ->
                    when (elem) {
                        is JsonPrimitive -> if (elem.isString) elem.content else null
                        else -> null
                    }
                }
            }
        }
    }
    return trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Собирает аргументы для [com.example.ai_develop.domain.McpTransport.callTool] из одной строки ввода.
 */
fun buildMcpPrimaryArgumentMap(
    kind: McpPrimaryArgumentKind,
    input: String,
): kotlin.Result<Map<String, JsonElement>> {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return kotlin.Result.failure(IllegalArgumentException("Error: empty input for tool argument."))
    }
    return when (kind) {
        is McpPrimaryArgumentKind.SingleString -> {
            kotlin.Result.success(mapOf(kind.key to JsonPrimitive(trimmed)))
        }
        is McpPrimaryArgumentKind.StringArray -> {
            val list = parseInputToStringList(trimmed)
            if (list.isEmpty()) {
                return kotlin.Result.failure(
                    IllegalArgumentException(
                        "Error: specify a non-empty list for \"${kind.key}\" (e.g. USD or USD,EUR or [\"USD\"]).",
                    ),
                )
            }
            kotlin.Result.success(
                mapOf(
                    kind.key to JsonArray(list.map { JsonPrimitive(it) }),
                ),
            )
        }
    }
}

/**
 * Если модель передала в скобках целый JSON-объект аргументов MCP (например `{"currencies":["JPY"],"hours":24}`),
 * используем его напрямую — иначе при пустой схеме в БД уходил бы только `{"query": "…"}` с неверным ключом.
 */
fun tryParseFullMcpToolArgumentsObject(input: String): JsonObject? {
    val t = input.trim()
    if (!t.startsWith('{')) return null
    return runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
}
