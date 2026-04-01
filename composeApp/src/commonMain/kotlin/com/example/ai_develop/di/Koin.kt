package com.example.ai_develop.di

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.data.KtorChatRepository
import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.domain.ChatStreamingUseCase
import com.example.ai_develop.domain.ExtractFactsUseCase
import com.example.ai_develop.presentation.LLMViewModel
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

    single { ChatStreamingUseCase(get()) }
    single { ExtractFactsUseCase(get()) }

    viewModelOf(::LLMViewModel)
}
