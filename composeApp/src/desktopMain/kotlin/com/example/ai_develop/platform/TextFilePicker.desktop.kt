package com.example.ai_develop.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.charset.Charset

actual fun openTextFileDialog(): String? {
    val dialog = FileDialog(null as Frame?, "Выберите текстовый файл", FileDialog.LOAD)
    dialog.isMultipleMode = false
    dialog.setFilenameFilter { _, name ->
        name.endsWith(".txt", ignoreCase = true) || name.endsWith(".md", ignoreCase = true)
    }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, file).absolutePath
}

actual fun readTextFileUtf8(path: String): String =
    File(path).readText(Charset.forName("UTF-8"))
