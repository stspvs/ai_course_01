package com.example.ai_develop.di

import com.example.ai_develop.data.database.AppDatabase
import com.example.ai_develop.data.database.getDatabaseBuilder
import com.example.ai_develop.database.DriverFactory
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
import com.example.aidevelop.database.AgentMessageEntity
import com.example.aidevelop.database.AgentStateEntity
import com.example.aidevelop.database.InvariantEntity
import io.ktor.client.*
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import app.cash.sqldelight.db.SqlDriver

actual val platformModule = module {
    single<AppDatabase> {
        getDatabaseBuilder(androidContext()).build()
    }
    single { get<AppDatabase>().agentDao() }
    
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
}

actual fun HttpClientConfig<*>.configurePlatform() {
    // Для Android пока ничего не настраиваем
}
