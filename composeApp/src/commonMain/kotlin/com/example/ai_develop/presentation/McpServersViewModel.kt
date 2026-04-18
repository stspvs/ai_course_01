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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

data class McpServersUiState(
    val servers: List<McpServerRecord> = emptyList(),
    val selectedServerId: String? = null,
    val bindings: List<McpToolBindingRecord> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
    val testInput: String = "kotlin",
    val testResult: String? = null,
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

    init {
        viewModelScope.launch {
            mcpRepository.observeServers().collect { list ->
                _ui.update { it.copy(servers = list) }
            }
        }
    }

    fun selectServer(id: String?) {
        _ui.update { it.copy(selectedServerId = id, message = null, testResult = null) }
        if (id != null) {
            viewModelScope.launch { refreshBindingsForServer(id) }
        }
    }

    private suspend fun refreshBindingsForServer(serverId: String) {
        val bindings = mcpRepository.getBindingsForServer(serverId)
        _ui.update {
            it.copy(bindings = bindings)
        }
    }

    fun syncToolsFromServer(serverId: String) {
        viewModelScope.launch {
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
                _ui.update { it.copy(isBusy = false, message = "Ошибка: $err") }
            }
            refreshBindingsForServer(serverId)
            applyRegistrySuspend()
        }
    }

    fun saveServer(record: McpServerRecord) {
        viewModelScope.launch {
            val existing = mcpRepository.getServer(record.id)
            val connectionUnchanged =
                existing != null &&
                    existing.baseUrl == record.baseUrl &&
                    existing.headersJson == record.headersJson &&
                    existing.wireKind == record.wireKind &&
                    existing.startCommand == record.startCommand
            if (existing != null && !connectionUnchanged) {
                transport.disposeServer(record.id)
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
        viewModelScope.launch {
            transport.disposeServer(serverId)
            mcpRepository.deleteServer(serverId)
            if (_ui.value.selectedServerId == serverId) {
                _ui.update { it.copy(selectedServerId = null, bindings = emptyList()) }
            }
            applyRegistrySuspend()
        }
    }

    fun saveBinding(record: McpToolBindingRecord) {
        viewModelScope.launch {
            mcpRepository.upsertBinding(record)
            val sid = record.serverId
            refreshBindingsForServer(sid)
            applyRegistrySuspend()
            _ui.update { it.copy(message = "Привязка сохранена") }
        }
    }

    fun setTestInput(value: String) {
        _ui.update { it.copy(testInput = value) }
    }

    /** Запуск [McpServerRecord.startCommand] для выбранного сервера (как у Graylog: фоновый процесс). */
    fun runServerStartCommand(serverId: String) {
        viewModelScope.launch {
            val server = mcpRepository.getServer(serverId) ?: return@launch
            val cmd = server.startCommand.trim()
            if (cmd.isEmpty()) {
                _ui.update { it.copy(message = "Команда запуска не задана — укажите её в редактировании сервера") }
                return@launch
            }
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
        viewModelScope.launch {
            val server = mcpRepository.getServer(binding.serverId) ?: return@launch
            _ui.update { it.copy(isBusy = true, testResult = null) }
            val kind = inferPrimaryArgument(binding.inputSchemaJson)
            val args = buildMcpPrimaryArgumentMap(kind, _ui.value.testInput).getOrElse { e ->
                _ui.update {
                    it.copy(
                        isBusy = false,
                        testResult = e.message ?: e.toString(),
                    )
                }
                return@launch
            }
            val r = transport.callTool(server, binding.mcpToolName, args)
            _ui.update {
                it.copy(
                    isBusy = false,
                    testResult = r.getOrElse { e -> "Ошибка: ${e.message}" },
                )
            }
        }
    }

    private suspend fun applyRegistrySuspend() {
        agentToolRegistry.reloadFromDatabase()
        chatStreamingUseCase.evictAllAgents()
    }

    companion object {
        fun newId(): String = Uuid.random().toString()
    }
}
