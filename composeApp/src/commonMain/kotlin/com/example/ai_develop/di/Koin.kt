package com.example.ai_develop.di

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.data.KtorChatRepository
import com.example.ai_develop.data.OllamaEmbeddingClient
import com.example.ai_develop.data.OllamaModelsClient
import com.example.ai_develop.data.OllamaRagRerankClient
import com.example.ai_develop.data.RagContextRetriever
import com.example.ai_develop.data.RagEmbeddingRepository
import com.example.ai_develop.data.RagPipelineSettingsRepository
import com.example.ai_develop.data.SqlDelightRagPipelineSettingsRepository
import com.example.ai_develop.data.GraylogSettingsRepository
import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.SqlDelightChatRepository
import com.example.ai_develop.data.SqlDelightGraylogSettingsRepository
import com.example.ai_develop.data.SqlDelightMcpRepository
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import com.example.ai_develop.presentation.AgentManager
import com.example.ai_develop.presentation.ChatInteractor
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.GraylogSettingsViewModel
import com.example.ai_develop.presentation.McpServersViewModel
import com.example.ai_develop.platform.GraylogPlatform
import com.example.ai_develop.presentation.RagEmbeddingsViewModel
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
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(commonModule, platformModule)
    }

/** После холодного старта все задачи в паузе (автономный конвейер не продолжается сам). Синхронно до UI. */
fun Koin.pauseAllTasksOnAppLaunch() {
    runBlocking {
        get<PauseAllTasksOnAppLaunchUseCase>()().onFailure { it.printStackTrace() }
    }
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
                // Стриминг LLM / RAG-JSON может дольше минуты; connect/socket оставляем короче.
                requestTimeoutMillis = 180_000
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = 120_000
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
            openRouterKey = BuildConfig.OPENROUTER_KEY,
            ollamaBaseUrl = BuildConfig.OLLAMA_BASE_URL
        )
    }

    // Основной репозиторий (SqlDelight) — одна реализация на все интерфейсы
    single {
        SqlDelightChatRepository(
            db = get(),
            networkRepository = get(named("network"))
        )
    }
    single<ChatRepository> { get<SqlDelightChatRepository>() }
    single<AgentRepository> { get<SqlDelightChatRepository>() }
    single<TaskRepository> { get<SqlDelightChatRepository>() }
    single<MessageRepository> { get<SqlDelightChatRepository>() }

    single<CoroutineDispatcher> { Dispatchers.Default }
    single { CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>()) }

    // Managers & Factories
    singleOf(::ChatMemoryManager)
    singleOf(::TaskSagaCoordinator)
    singleOf(::DefaultAgentFactory)
    singleOf(::StrategyDelegateFactory)
    singleOf(::AgentManager)

    // Use Cases
    singleOf(::GetTasksUseCase)
    singleOf(::GetTaskUseCase)
    singleOf(::CreateTaskUseCase)
    singleOf(::UpdateTaskUseCase)
    singleOf(::DeleteTaskUseCase)
    singleOf(::PauseAllTasksOnAppLaunchUseCase)
    singleOf(::ResetTaskUseCase)
    singleOf(::GetMessagesUseCase)
    singleOf(::GetAgentsUseCase)

    single(named("agentTools")) {
        listOf<AgentTool>()
    }

    single<McpRepository> { SqlDelightMcpRepository(get()) }
    single { OllamaEmbeddingClient(get()) }
    single { OllamaRagRerankClient(get(), get()) }
    single { OllamaModelsClient(get(), get()) }
    single { RagEmbeddingRepository(get()) }
    single { RagContextRetriever(get(), get(), get()) }
    single<RagPipelineSettingsRepository> { SqlDelightRagPipelineSettingsRepository(get(), get()) }
    single<GraylogSettingsRepository> { SqlDelightGraylogSettingsRepository(get()) }
    single<GraylogPlatform> { GraylogPlatform() }

    single {
        AgentToolRegistry(
            baseTools = get(named("agentTools")),
            mcpRepository = get(),
            transport = get(),
        )
    }

    single {
        ChatStreamingUseCase(
            repository = get(),
            memoryManager = get(),
            scope = get(),
            agentToolRegistry = get(),
            mcpRepository = get(),
            ragContextRetriever = get(),
            ragPipelineSettingsRepository = get(),
        )
    }
    single<AgentChatSessionPort> { get<ChatStreamingUseCase>() }
    singleOf(::SummarizeChatUseCase)
    singleOf(::ExtractFactsUseCase)
    singleOf(::UpdateWorkingMemoryUseCase)

    // Agent Management UseCase Facade
    singleOf(::AgentManagementUseCase)

    // Interactor
    singleOf(::ChatInteractor)

    // ViewModels
    viewModelOf(::LLMViewModel)
    viewModelOf(::McpServersViewModel)
    viewModelOf(::GraylogSettingsViewModel)
    viewModel {
        RagEmbeddingsViewModel(
            ollamaEmbeddingClient = get(),
            ragRepository = get(),
            ragContextRetriever = get(),
            ragPipelineSettingsRepository = get(),
            ollamaModelsClient = get(),
            chatRepository = get(),
        )
    }
    viewModel {
        TaskViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get<AgentChatSessionPort>(),
            get(),
            get(),
            get<TaskSagaCoordinator>()
        )
    }
}

expect val platformModule: Module

expect fun io.ktor.client.HttpClientConfig<*>.configurePlatform()
