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
            // Database is new or empty
            AgentDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version = $newVersion;", 0)
        } else if (currentVersion < newVersion) {
            try {
                // Try to migrate using .sqm files
                AgentDatabase.Schema.migrate(driver, currentVersion, newVersion)
                driver.execute(null, "PRAGMA user_version = $newVersion;", 0)
            } catch (e: Exception) {
                // If migration fails, attempt destructive migration (delete and recreate)
                driver.close()
                
                var deleted = false
                if (dbFile.exists()) {
                    for (i in 1..5) {
                        if (dbFile.delete()) {
                            deleted = true
                            break
                        }
                        Thread.sleep(200)
                    }
                } else {
                    deleted = true
                }

                driver = JdbcSqliteDriver(url)
                if (deleted) {
                    AgentDatabase.Schema.create(driver)
                    driver.execute(null, "PRAGMA user_version = $newVersion;", 0)
                } else {
                    // Last resort: try to create tables if they don't exist
                    // This won't add new columns to existing tables, but avoids "table already exists"
                    try {
                        AgentDatabase.Schema.create(driver)
                        driver.execute(null, "PRAGMA user_version = $newVersion;", 0)
                    } catch (ignore: Exception) {}
                }
            }
        }
        
        // Optimizations
        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        
        return driver
    }
}
