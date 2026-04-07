package com.example.ai_develop.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".ai_develop")
        if (!appDir.exists()) appDir.mkdirs()
        
        val dbFile = File(appDir, "agent.db")
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        
        var driver = JdbcSqliteDriver(url)
        
        val currentVersion = try {
            driver.executeQuery(
                identifier = null,
                sql = "PRAGMA user_version;",
                mapper = { cursor ->
                    val version = if (cursor.next().value) {
                        cursor.getLong(0) ?: 0L
                    } else {
                        0L
                    }
                    QueryResult.Value(version)
                },
                parameters = 0
            ).value
        } catch (e: Exception) {
            0L
        }

        val newVersion = AgentDatabase.Schema.version

        if (currentVersion == 0L) {
            // База только что создана или пуста. 
            // Благодаря IF NOT EXISTS в .sq, это не упадет, если таблицы уже есть.
            AgentDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version = $newVersion;", 0)
        } else if (currentVersion < newVersion) {
            // ДЕСТРУКТИВНАЯ МИГРАЦИЯ: Пытаемся удалить старую базу
            driver.close()
            
            val deleted = if (dbFile.exists()) {
                // Повторные попытки удаления (важно для Windows)
                var success = false
                for (i in 1..3) {
                    if (dbFile.delete()) {
                        success = true
                        break
                    }
                    Thread.sleep(100)
                }
                success
            } else {
                true
            }

            driver = JdbcSqliteDriver(url)
            if (deleted) {
                AgentDatabase.Schema.create(driver)
            } else {
                // Если удалить не удалось, пробуем создать недостающие таблицы
                // Новые колонки в существующих таблицах так не добавятся, 
                // но приложение хотя бы запустится без ошибки "table already exists".
                try {
                    AgentDatabase.Schema.create(driver)
                } catch (e: Exception) {
                    // Игнорируем ошибки создания, если таблицы уже есть
                }
            }
            driver.execute(null, "PRAGMA user_version = $newVersion;", 0)
        }
        
        // Оптимизация
        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        
        return driver
    }
}
