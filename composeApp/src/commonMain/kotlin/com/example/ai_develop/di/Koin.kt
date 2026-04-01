package com.example.ai_develop.di

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.data.KtorChatRepository
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.ChatInteractor
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.strategy.StrategyDelegateFactory
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.compose.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.factoryOf

expect val platformModule: Module

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
            install(ContentNegotiation) {
                json(get())
            }
            install(Logging) {
                level = LogLevel.HEADERS
            }
        }
    }

    single<ChatRepository> {
        KtorChatRepository(
            httpClient = get(),
            deepSeekKey = BuildConfig.DEEPSEEK_KEY, 
            yandexKey = BuildConfig.YANDEX_KEY,
            yandexFolderId = BuildConfig.YANDEX_FOLDER_ID,
            openRouterKey = BuildConfig.OPENROUTER_KEY
        )
    }

    singleOf(::ChatStreamingUseCase)
    singleOf(::ExtractFactsUseCase)
    singleOf(::SummarizeChatUseCase)
    singleOf(::ChatMemoryManager)
    singleOf(::StrategyDelegateFactory)
    
    factoryOf(::ChatInteractor)

    viewModelOf(::LLMViewModel)
}
