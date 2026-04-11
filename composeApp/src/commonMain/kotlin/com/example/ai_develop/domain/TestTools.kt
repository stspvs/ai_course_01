package com.example.ai_develop.domain

import kotlinx.coroutines.delay

/** Демо-погода по городу (без внешнего API). Имя `weather` должно совпадать с тем, что выводит модель в `[TOOL: weather(...)]`. */
class WeatherTool : AgentTool {
    override val name: String = "weather"
    override val description: String =
        "Returns the current weather for a city. Input: city name (e.g. Paris)."

    override suspend fun execute(input: String): String {
        delay(300)
        val city = input.trim().ifEmpty { "unknown city" }
        return "The weather in $city is 22°C, Sunny. (demo)"
    }
}

class CalculatorTool : AgentTool {
    override val name: String = "calc"
    override val description: String = "Performs basic math. Input: expression like 2+2."

    override suspend fun execute(input: String): String {
        return try {
            val result = input.split("+").sumOf { it.trim().toInt() }
            "Result: $result"
        } catch (e: Exception) {
            "Error: could not calculate $input"
        }
    }
}
