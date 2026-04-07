package com.example.ai_develop.domain

import kotlinx.coroutines.delay

class WeatherTool : AgentTool {
    override val name: String = "weather"
    override val description: String = "Returns the current weather for a city. Input: city name."

    override suspend fun execute(input: String): String {
        delay(1000) // Симуляция сетевого запроса
        return "The weather in $input is 22°C, Sunny."
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
