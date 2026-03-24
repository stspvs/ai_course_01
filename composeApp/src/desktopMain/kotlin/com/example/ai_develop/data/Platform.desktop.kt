package com.example.ai_develop.data

class DesktopPlatform : Platform {
    override val name: String = "Desktop"
    override val isWeb: Boolean = false
}

actual fun getPlatform(): Platform = DesktopPlatform()
