package com.example.ai_develop.data.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

fun getDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val userHome = System.getProperty("user.home")
    val appDir = File(userHome, ".ai_develop")
    if (!appDir.exists()) {
        appDir.mkdirs()
    }
    val dbFile = File(appDir, DATABASE_NAME)
    return Room.databaseBuilder<AppDatabase>(
        name = dbFile.absolutePath,
    )
    .setDriver(BundledSQLiteDriver())
    .addMigrations(MIGRATION_11_20)
    .fallbackToDestructiveMigration(false)
}
