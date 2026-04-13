package com.example.ai_develop.platform

expect class GraylogPlatform() {
    fun openWebUi(url: String): Result<Unit>
    fun runStartCommand(command: String): Result<String>
}
