@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.McpDiscoveredTool
import com.example.ai_develop.data.McpRepository
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.McpToolBindingRecord
import com.example.ai_develop.domain.AgentToolRegistry
import com.example.ai_develop.domain.ChatStreamingUseCase
import com.example.ai_develop.domain.McpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

data class McpServersUiState(
    val servers: List<McpServerRecord> = emptyList(),
    val selectedServerId: String? = null,
    val bindings: List<McpToolBindingRecord> = emptyList(),
    val discoveredTools: List<McpDiscoveredTool> = emptyList(),
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
        val server = mcpRepository.getServer(serverId)
        val discovered = parseDiscovered(server?.lastSyncToolsJson.orEmpty())
        _ui.update {
            it.copy(bindings = bindings, discoveredTools = discovered)
        }
    }

    private fun parseDiscovered(jsonStr: String): List<McpDiscoveredTool> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(McpDiscoveredTool.serializer()), jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun syncToolsFromServer(serverId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(isBusy = true, message = null) }
            val server = mcpRepository.getServer(serverId) ?: run {
                _ui.update { it.copy(isBusy = false, message = "Сервер не найден") }
                return@launch
            }
            val result = transport.listTools(server.baseUrl, server.headersJson)
            val now = System.currentTimeMillis()
            if (result.isSuccess) {
                val tools = result.getOrThrow().tools
                val payload = json.encodeToString(
                    ListSerializer(McpDiscoveredTool.serializer()),
                    tools.map { McpDiscoveredTool(it.name, it.description.orEmpty()) },
                )
                mcpRepository.updateServerSyncState(serverId, payload, null, now)
                _ui.update {
                    it.copy(
                        isBusy = false,
                        message = "Обновлено: ${tools.size} инструментов",
                        discoveredTools = parseDiscovered(payload),
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message
                mcpRepository.updateServerSyncState(serverId, server.lastSyncToolsJson, err, now)
                _ui.update { it.copy(isBusy = false, message = "Ошибка: $err") }
            }
            refreshBindingsForServer(serverId)
            applyRegistrySuspend()
        }
    }

    fun saveServer(record: McpServerRecord) {
        viewModelScope.launch {
            mcpRepository.upsertServer(record)
            applyRegistrySuspend()
            _ui.update { it.copy(message = "Сервер сохранён") }
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
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

    fun deleteBinding(bindingId: String, serverId: String) {
        viewModelScope.launch {
            mcpRepository.deleteBinding(bindingId)
            refreshBindingsForServer(serverId)
            applyRegistrySuspend()
        }
    }

    fun setTestInput(value: String) {
        _ui.update { it.copy(testInput = value) }
    }

    fun testBinding(binding: McpToolBindingRecord) {
        viewModelScope.launch {
            val server = mcpRepository.getServer(binding.serverId) ?: return@launch
            _ui.update { it.copy(isBusy = true, testResult = null) }
            val args = mapOf(
                binding.inputArgumentKey.ifBlank { "query" } to JsonPrimitive(_ui.value.testInput),
            )
            val r = transport.callTool(server.baseUrl, server.headersJson, binding.mcpToolName, args)
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
