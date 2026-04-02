package com.example.ai_develop.di

import com.example.ai_develop.data.database.AppDatabase
import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.data.database.getDatabaseBuilder
import io.ktor.client.*
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

actual val platformModule = module {
    single<AppDatabase> {
        getDatabaseBuilder(androidContext()).build()
    }
    single { get<AppDatabase>().agentDao() }
    single<LocalChatRepository> { DatabaseChatRepository(get()) }
}

actual fun HttpClientConfig<*>.configurePlatform() {
    // Для Android пока ничего не настраиваем
}
