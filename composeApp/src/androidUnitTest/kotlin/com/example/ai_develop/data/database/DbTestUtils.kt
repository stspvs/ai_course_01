package com.example.ai_develop.data.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import android.content.Context
import androidx.test.core.app.ApplicationProvider

actual fun createTestDatabase(): AppDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder<AppDatabase>(
        context = context,
        factory = AppDatabaseConstructor::initialize
    )
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()
}
