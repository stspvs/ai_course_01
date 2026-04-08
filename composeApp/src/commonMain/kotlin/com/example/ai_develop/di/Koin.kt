package com.example.ai_develop.di

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.data.KtorChatRepository
import com.example.ai_develop.data.SqlDelightChatRepository
import com.example.ai_develop.data.database.DatabaseAgentRepository
import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.data.database.DatabaseMessageRepository
import com.example.ai_develop.data.database.DatabaseTaskRepository
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.AgentManagementUseCase
import com.example.ai_develop.domain.AgentRepository
import com.example.ai_develop.domain.ChatMemoryManager
import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.domain.ChatStreamingUseCase
import com.example.ai_develop.domain.CreateTaskUseCase
import com.example.ai_develop.domain.DefaultAgentFactory
import com.example.ai_develop.domain.DeleteTaskUseCase
import com.example.ai_develop.domain.ExtractFactsUseCase
import com.example.ai_develop.domain.GetAgentsUseCase
import com.example.ai_develop.domain.GetMessagesUseCase
import com.example.ai_develop.domain.GetTasksUseCase
import com.example.ai_develop.domain.MessageRepository
import com.example.ai_develop.domain.ResetTaskUseCase
import com.example.ai_develop.domain.SummarizeChatUseCase
import com.example.ai_develop.domain.TaskRepository
import com.example.ai_develop.domain.UpdateTaskUseCase
import com.example.ai_develop.domain.UpdateWorkingMemoryUseCase
import com.example.ai_develop.presentation.AgentManager
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
import org.koin.core.qualifier.named
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
    single<ChatRepository>(named("network")) {
        KtorChatRepository(
            httpClient = get(),
            deepSeekKey = BuildConfig.DEEPSEEK_KEY,
            yandexKey = BuildConfig.YANDEX_KEY,
            yandexFolderId = BuildConfig.YANDEX_FOLDER_ID,
            openRouterKey = BuildConfig.OPENROUTER_KEY
        )
    }

// Основной репозиторий
    single<ChatRepository> {
        SqlDelightChatRepository(
            db = get(),
            networkRepository = get(named("network"))
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
    singleOf(::AgentManager)

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

    // Agent Management UseCase Facade
    singleOf(::AgentManagementUseCase)

    // Interactor
    singleOf(::ChatInteractor)

    // ViewModels
    viewModelOf(::LLMViewModel)
    viewModelOf(::TaskViewModel)
}

expect val platformModule: Module

expect fun io.ktor.client.HttpClientConfig<*>.configurePlatform()
