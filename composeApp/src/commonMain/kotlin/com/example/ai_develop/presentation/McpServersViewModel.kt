@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.McpDiscoveredTool
import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.McpServerLinkStatus
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.McpWireKind
import com.example.ai_develop.data.McpToolBindingRecord
import com.example.ai_develop.data.classifyMcpLinkFailure
import com.example.ai_develop.data.buildMcpPrimaryArgumentMap
import com.example.ai_develop.data.inferPrimaryArgument
import com.example.ai_develop.domain.agent.AgentToolRegistry
import com.example.ai_develop.domain.chat.ChatStreamingUseCase
import com.example.ai_develop.domain.llm.McpTransport
import com.example.ai_develop.platform.GraylogPlatform
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** UI State для тестирования binding - вынесен отдельно для чистоты */
data class BindingTestState(
    val input: String = "kotlin",
    val result: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class McpServersUiState(
    val servers: List<McpServerRecord> = emptyList(),
    val selectedServerId: String? = null,
    val bindings: List<McpToolBindingRecord> = emptyList(),
    val isBusy: Boolean = false,
    val isLoadingBindings: Boolean = false,
    val message: String? = null,
    val bindingTest: BindingTestState = BindingTestState(),
)

class McpServersViewModel(
    private val mcpRepository: McpRepository,
    private val transport: McpTransport,
    private val agentToolRegistry: AgentToolRegistry,
    private val chatStreamingUseCase: ChatStreamingUseCase,
    private val processPlatform: GraylogPlatform,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _ui = MutableStateFlow(McpServersUiState())
    val uiState: StateFlow<McpServersUiState> = _ui.asStateFlow()

    /** Job для отслеживания потока серверов - отменяется в onCleared() */
    private var serversObserverJob: Job? = null

    /** Job для загрузки bindings - позволяет отменить при смене сервера */
    private var bindingsLoadJob: Job? = null

    /** Exception handler для корректной обработки ошибок */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        _ui.update {
            it.copy(
                isBusy = false,
                message = "Ошибка: ${throwable.message ?: "Неизвестная ошибка"}",
            )
        }
    }

    init {
        observeServers()
    }

    private fun observeServers() {
        serversObserverJob?.cancel()
        serversObserverJob = viewModelScope.launch {
            mcpRepository.observeServers().collect { list ->
                _ui.update { it.copy(servers = list) }
            }
        }
    }

    fun selectServer(id: String?) {
        // Отменяем предыдущую загрузку bindings
        bindingsLoadJob?.cancel()

        _ui.update {
            it.copy(
                selectedServerId = id,
                message = null,
                bindingTest = BindingTestState(), // сбрасываем тест при смене сервера
            )
        }

        if (id != null) {
            refreshBindingsForServer(id)
        } else {
            _ui.update { it.copy(bindings = emptyList()) }
        }
    }

    private fun refreshBindingsForServer(serverId: String) {
        bindingsLoadJob?.cancel()
        bindingsLoadJob = viewModelScope.launch(exceptionHandler) {
            _ui.update { it.copy(isLoadingBindings = true) }

            val bindings = mcpRepository.getBindingsForServer(serverId)

            // Проверяем, что сервер всё ещё выбран (race condition protection)
            if (_ui.value.selectedServerId == serverId) {
                _ui.update { it.copy(bindings = bindings, isLoadingBindings = false) }
            }
            // Если сервер уже сменён - просто игнорируем результат
        }
    }

    fun syncToolsFromServer(serverId: String) {
        viewModelScope.launch(exceptionHandler) {
            _ui.update { it.copy(isBusy = true, message = null) }

            val server = mcpRepository.getServer(serverId) ?: run {
                _ui.update { it.copy(isBusy = false, message = "Сервер не найден") }
                return@launch
            }

            val result = transport.listTools(server)
            val now = System.currentTimeMillis()

            if (result.isSuccess) {
                val tools = result.getOrThrow().tools
                val discovered = tools.map {
                    McpDiscoveredTool(
                        name = it.name,
                        description = it.description.orEmpty(),
                        inputSchemaJson = it.inputSchemaJson,
                    )
                }
                val payload = json.encodeToString(
                    ListSerializer(McpDiscoveredTool.serializer()),
                    discovered,
                )
                mcpRepository.updateServerSyncState(
                    serverId,
                    payload,
                    null,
                    now,
                    McpServerLinkStatus.CONNECTED,
                )
                mcpRepository.replaceToolsFromSync(serverId, discovered)

                _ui.update {
                    it.copy(
                        isBusy = false,
                        message = "Обновлено: ${tools.size} инструментов",
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message
                val linkStatus = classifyMcpLinkFailure(err)
                mcpRepository.updateServerSyncState(
                    serverId,
                    server.lastSyncToolsJson,
                    err,
                    now,
                    linkStatus,
                )
                _ui.update {
                    it.copy(
                        isBusy = false,
                        message = "Ошибка: $err",
                    )
                }
            }

            // Проверяем актуальность перед обновлением
            if (_ui.value.selectedServerId == serverId) {
                refreshBindingsForServer(serverId)
            }
            applyRegistrySuspend()
        }
    }

    fun saveServer(record: McpServerRecord) {
        viewModelScope.launch(exceptionHandler) {
            val existing = mcpRepository.getServer(record.id)
            val existingId = existing?.id ?: record.id // Используем существующий ID если есть

            val connectionUnchanged =
                existing != null &&
                    existing.baseUrl == record.baseUrl &&
                    existing.headersJson == record.headersJson &&
                    existing.wireKind == record.wireKind &&
                    existing.startCommand == record.startCommand

            if (existing != null && !connectionUnchanged) {
                transport.disposeServer(existingId)
            }

            val toSave = when {
                existing == null ->
                    record.copy(linkStatus = McpServerLinkStatus.UNKNOWN, lastSyncError = null)
                connectionUnchanged ->
                    record.copy(
                        linkStatus = existing.linkStatus,
                        lastSyncError = existing.lastSyncError,
                        lastSyncAt = existing.lastSyncAt,
                        lastSyncToolsJson = existing.lastSyncToolsJson,
                    )
                else ->
                    record.copy(
                        linkStatus = McpServerLinkStatus.UNKNOWN,
                        lastSyncError = null,
                    )
            }

            mcpRepository.upsertServer(toSave)
            applyRegistrySuspend()
            _ui.update { it.copy(message = "Сервер сохранён") }
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch(exceptionHandler) {
            transport.disposeServer(serverId)
            mcpRepository.deleteServer(serverId)

            if (_ui.value.selectedServerId == serverId) {
                _ui.update { it.copy(selectedServerId = null, bindings = emptyList()) }
            }
            applyRegistrySuspend()
        }
    }

    fun saveBinding(record: McpToolBindingRecord) {
        viewModelScope.launch(exceptionHandler) {
            mcpRepository.upsertBinding(record)
            val sid = record.serverId

            // Проверяем актуальность перед обновлением
            if (_ui.value.selectedServerId == sid) {
                refreshBindingsForServer(sid)
            }
            applyRegistrySuspend()
            _ui.update { it.copy(message = "Привязка сохранена") }
        }
    }

    fun setTestInput(value: String) {
        _ui.update {
            it.copy(
                bindingTest = it.bindingTest.copy(
                    input = value,
                    error = null, // сбрасываем ошибку при вводе
                )
            )
        }
    }

    /** Запуск [McpServerRecord.startCommand] для выбранного сервера (как у Graylog: фоновый процесс). */
    fun runServerStartCommand(serverId: String) {
        viewModelScope.launch(exceptionHandler) {
            val server = mcpRepository.getServer(serverId) ?: return@launch
            val cmd = server.startCommand.trim()

            if (cmd.isEmpty()) {
                _ui.update { it.copy(message = "Команда запуска не задана — укажите её в редактировании сервера") }
                return@launch
            }

            // Для STDIO - требуется команда запуска для перезапуска
            if (server.wireKind == McpWireKind.STDIO) {
                transport.disposeServer(serverId)
                _ui.update {
                    it.copy(message = "Сессия stdio остановлена. Нажмите «Обновить список tools» для нового процесса.")
                }
                return@launch
            }

            val r = processPlatform.runStartCommand(cmd)
            _ui.update {
                it.copy(
                    message = r.fold(
                        onSuccess = { msg -> msg },
                        onFailure = { e -> "Ошибка запуска: ${e.message}" },
                    ),
                )
            }
        }
    }

    fun testBinding(binding: McpToolBindingRecord) {
        viewModelScope.launch(exceptionHandler) {
            val server = mcpRepository.getServer(binding.serverId) ?: return@launch

            _ui.update {
                it.copy(
                    bindingTest = it.bindingTest.copy(
                        isLoading = true,
                        result = null,
                        error = null,
                    )
                )
            }

            val kind = inferPrimaryArgument(binding.inputSchemaJson)
            val args = buildMcpPrimaryArgumentMap(kind, _ui.value.bindingTest.input).getOrElse { e ->
                _ui.update {
                    it.copy(
                        bindingTest = it.bindingTest.copy(
                            isLoading = false,
                            error = e.message ?: e.toString(),
                        )
                    )
                }
                return@launch
            }

            val r = transport.callTool(server, binding.mcpToolName, args)
            _ui.update {
                it.copy(
                    bindingTest = it.bindingTest.copy(
                        isLoading = false,
                        result = r.getOrElse { e -> null },
                        error = r.exceptionOrNull()?.message?.let { "Ошибка: $it" },
                    )
                )
            }
        }
    }

    private suspend fun applyRegistrySuspend() {
        agentToolRegistry.reloadFromDatabase()
        chatStreamingUseCase.evictAllAgents()
    }

    override fun onCleared() {
        super.onCleared()
        serversObserverJob?.cancel()
        bindingsLoadJob?.cancel()
    }

    companion object {
        fun newId(): String = Uuid.random().toString()
    }
}
