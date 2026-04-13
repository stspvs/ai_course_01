package com.example.ai_develop.platform

import java.awt.Desktop
import java.net.URI

actual class GraylogPlatform actual constructor() {
    actual fun openWebUi(url: String): Result<Unit> = runCatching {
        val d = Desktop.getDesktop()
        if (!d.isSupported(Desktop.Action.BROWSE)) {
            error("Открытие браузера не поддерживается в этой среде")
        }
        d.browse(URI(url.trim()))
    }

    actual fun runStartCommand(command: String): Result<String> {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "Укажите команду запуска (например: docker compose up -d)",
                ),
            )
        }
        return runCatching {
            val pb = if (System.getProperty("os.name").lowercase().contains("win")) {
                ProcessBuilder("cmd.exe", "/c", trimmed)
            } else {
                ProcessBuilder("sh", "-c", trimmed)
            }
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            pb.start()
            "Команда запущена в фоне"
        }
    }
}
