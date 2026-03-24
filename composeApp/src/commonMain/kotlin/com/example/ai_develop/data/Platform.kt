package com.example.ai_develop.data

interface Platform {
    val name: String
    val isWeb: Boolean
}

expect fun getPlatform(): Platform
