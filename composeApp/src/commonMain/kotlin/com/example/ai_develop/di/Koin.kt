package com.example.ai_develop.di

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.data.KtorChatRepository
import com.example.ai_develop.data.database.DatabaseAgentRepository
import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.data.database.DatabaseMessageRepository
import com.example.ai_develop.data.database.DatabaseTaskRepository
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.ChatInteractor
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.TaskViewModel
import com.example.ai_develop.presentation.strategy.StrategyDelegateFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

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
        }
    }

    single {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }

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

    // Сетевой репозиторий
    single<ChatRepository> {
        KtorChatRepository(
            httpClient = get(),
            deepSeekKey = BuildConfig.DEEPSEEK_KEY,
            yandexKey = BuildConfig.YANDEX_KEY,
            yandexFolderId = BuildConfig.YANDEX_FOLDER_ID,
            openRouterKey = BuildConfig.OPENROUTER_KEY
        )
    }

    // Room Database provided in platformModule

    // Database Repositories
    single<AgentRepository> { DatabaseAgentRepository(get()) }
    single<TaskRepository> { DatabaseTaskRepository(get()) }
    single<MessageRepository> { DatabaseMessageRepository(get()) }

    single<DatabaseChatRepository> {
        DatabaseChatRepository(
            agentRepository = get(),
            taskRepository = get(),
            messageRepository = get()
        )
    }

    single<LocalChatRepository> { get<DatabaseChatRepository>() }

    single<CoroutineDispatcher> { Dispatchers.Default }
    single { CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>()) }

    // Managers & Factories
    singleOf(::ChatMemoryManager)
    singleOf(::DefaultAgentFactory)
    singleOf(::StrategyDelegateFactory)

    // Use Cases
    singleOf(::GetTasksUseCase)
    singleOf(::CreateTaskUseCase)
    singleOf(::UpdateTaskUseCase)
    singleOf(::DeleteTaskUseCase)
    singleOf(::ResetTaskUseCase)
    singleOf(::GetMessagesUseCase)
    singleOf(::GetAgentsUseCase)

    singleOf(::ChatStreamingUseCase)
    singleOf(::SummarizeChatUseCase)
    singleOf(::ExtractFactsUseCase)
    singleOf(::UpdateWorkingMemoryUseCase)

    // Interactor
    singleOf(::ChatInteractor)

    // ViewModels
    viewModelOf(::LLMViewModel)
    viewModelOf(::TaskViewModel)
}

expect val platformModule: Module

expect fun io.ktor.client.HttpClientConfig<*>.configurePlatform()
