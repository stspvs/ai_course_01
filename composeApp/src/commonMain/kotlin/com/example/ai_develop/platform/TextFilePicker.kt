package com.example.ai_develop.platform

/**
 * Desktop: opens a file dialog; returns absolute path or null if cancelled.
 */
expect fun openTextFileDialog(): String?

expect fun readTextFileUtf8(path: String): String
