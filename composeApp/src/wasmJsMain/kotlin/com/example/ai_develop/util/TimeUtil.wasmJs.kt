package com.example.ai_develop.util

@JsFun("() => Date.now()")
external fun jsDateNow(): Double

actual fun currentTimeMillis(): Long = jsDateNow().toLong()
