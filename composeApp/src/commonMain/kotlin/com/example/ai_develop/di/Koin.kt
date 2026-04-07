package com.example.ai_develop.di

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.data.KtorChatRepository
import com.example.ai_develop.data.SqlDelightChatRepository
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.*
import com.example.ai_develop.presentation.strategy.StrategyDelegateFactory
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
import com.example.ai_develop.database.booleanAdapter
import com.example.aidevelop.database.AgentMessageEntity
import com.example.aidevelop.database.AgentStateEntity
import com.example.aidevelop.database.InvariantEntity
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.compose.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.factoryOf
import app.cash.sqldelight.db.SqlDriver

expect val platformModule: Module

expect fun HttpClientConfig<*>.configurePlatform()

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(commonModule, platformModule)
    }

val commonModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            encodeDefaults = true
        }
    }

    single {
        HttpClient {
            configurePlatform()
            
            install(ContentNegotiation) {
                json(get())
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        println("[HTTP] $message")
                    }
                }
                level = LogLevel.ALL
            }
        }
    }

    // Создаем сетевой репозиторий
    single<KtorChatRepository> {
        KtorChatRepository(
            httpClient = get(),
            deepSeekKey = BuildConfig.DEEPSEEK_KEY, 
            yandexKey = BuildConfig.YANDEX_KEY,
            yandexFolderId = BuildConfig.YANDEX_FOLDER_ID,
            openRouterKey = BuildConfig.OPENROUTER_KEY
        )
    }

    // AgentDatabase (SqlDelight)
    single {
        AgentDatabase(
            driver = get(),
            AgentMessageEntityAdapter = AgentMessageEntity.Adapter(stageAdapter),
            AgentStateEntityAdapter = AgentStateEntity.Adapter(stageAdapter),
            InvariantEntityAdapter = InvariantEntity.Adapter(stageAdapter)
        )
    }

    // Создаем основной репозиторий, который объединяет БД (SqlDelight) и Сеть
    single<ChatRepository> {
        SqlDelightChatRepository(
            db = get(),
            networkRepository = get<KtorChatRepository>()
        )
    }

    single<CoroutineDispatcher> { Dispatchers.Default }
    single { CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>()) }

    singleOf(::ChatStreamingUseCase)
    singleOf(::ExtractFactsUseCase)
    singleOf(::SummarizeChatUseCase)
    singleOf(::UpdateWorkingMemoryUseCase)
    singleOf(::ChatMemoryManager)
    singleOf(::StrategyDelegateFactory)
    
    factoryOf(::ChatInteractor)

    viewModelOf(::LLMViewModel)
    viewModelOf(::TaskViewModel)
}
