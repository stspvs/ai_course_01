package com.example.ai_develop.data

class AndroidPlatform : Platform {
    override val name: String = "Android ${android.os.Build.VERSION.SDK_INT}"
    override val isWeb: Boolean = false
}

actual fun getPlatform(): Platform = AndroidPlatform()
