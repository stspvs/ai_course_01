package com.example.ai_develop.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

fun getDatabaseBuilder(context: Context): RoomDatabase.Builder<AppDatabase> {
    val dbFile = context.getDatabasePath(DATABASE_NAME)
    return Room.databaseBuilder<AppDatabase>(
        context = context.applicationContext,
        name = dbFile.absolutePath
    )
    .setDriver(BundledSQLiteDriver())
    .addMigrations(MIGRATION_11_20, MIGRATION_20_21, MIGRATION_21_22)
    .fallbackToDestructiveMigration(false)
}
