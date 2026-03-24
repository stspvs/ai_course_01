package com.example.ai_develop.data

class WasmPlatform : Platform {
    override val name: String = "Web (Wasm)"
    override val isWeb: Boolean = true
}

actual fun getPlatform(): Platform = WasmPlatform()
