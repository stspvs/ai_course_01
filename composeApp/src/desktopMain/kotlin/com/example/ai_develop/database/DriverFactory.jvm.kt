package com.example.ai_develop.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".ai_develop")
        if (!appDir.exists()) appDir.mkdirs()
        
        val dbFile = File(appDir, "agent.db")
        val isNew = !dbFile.exists() || dbFile.length() == 0L
        
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        
        // Создаем таблицы, только если база данных новая
        if (isNew) {
            AgentDatabase.Schema.create(driver)
        }
        
        // Включаем WAL режим для параллельного доступа
        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)
        
        return driver
    }
}
