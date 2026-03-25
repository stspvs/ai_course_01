package com.example.ai_develop.util

import kotlin.js.Date

actual fun currentTimeMillis(): Long = Date.now().toLong()
