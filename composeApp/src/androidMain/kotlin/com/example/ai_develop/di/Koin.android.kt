package com.example.ai_develop.di

import com.example.ai_develop.data.database.AppDatabase
import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.data.database.getDatabaseBuilder
import com.example.ai_develop.database.DriverFactory
import io.ktor.client.*
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import app.cash.sqldelight.db.SqlDriver

actual val platformModule = module {
    single<AppDatabase> {
        getDatabaseBuilder(androidContext()).build()
    }
    single { get<AppDatabase>().agentDao() }
    single<LocalChatRepository> { DatabaseChatRepository(get()) }
    
    // SqlDelight Driver Factory and Driver
    single { DriverFactory(androidContext()) }
    single<SqlDriver> { get<DriverFactory>().createDriver() }
}

actual fun HttpClientConfig<*>.configurePlatform() {
    // Для Android пока ничего не настраиваем
}
