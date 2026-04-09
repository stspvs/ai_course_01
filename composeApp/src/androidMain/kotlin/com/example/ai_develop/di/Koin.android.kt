package com.example.ai_develop.di

import com.example.ai_develop.data.SqlDelightChatRepository
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.database.DriverFactory
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
import com.example.ai_develop.database.AgentMessageEntity
import com.example.ai_develop.database.AgentStateEntity
import com.example.ai_develop.database.InvariantEntity
import io.ktor.client.*
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import app.cash.sqldelight.db.SqlDriver

actual val platformModule = module {
    // SqlDelight Driver Factory and Driver
    single { DriverFactory(androidContext()) }
    single<SqlDriver> { get<DriverFactory>().createDriver() }

    single<AgentDatabase> {
        AgentDatabase(
            driver = get(),
            AgentMessageEntityAdapter = AgentMessageEntity.Adapter(
                stageAdapter = stageAdapter
            ),
            AgentStateEntityAdapter = AgentStateEntity.Adapter(
                currentStageAdapter = stageAdapter
            ),
            InvariantEntityAdapter = InvariantEntity.Adapter(
                stageAdapter = stageAdapter
            )
        )
    }

    single<LocalChatRepository> { get<SqlDelightChatRepository>() }
}

actual fun HttpClientConfig<*>.configurePlatform() {
    // Для Android пока ничего не настраиваем
}
