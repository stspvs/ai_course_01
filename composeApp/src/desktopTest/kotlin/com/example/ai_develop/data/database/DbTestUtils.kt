package com.example.ai_develop.data.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

actual fun createTestDatabase(): AppDatabase {
    return Room.inMemoryDatabaseBuilder<AppDatabase>(
        factory = AppDatabaseConstructor::initialize
    )
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()
}
