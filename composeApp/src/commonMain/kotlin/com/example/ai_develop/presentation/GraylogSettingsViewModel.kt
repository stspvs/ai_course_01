package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.GraylogSettings
import com.example.ai_develop.data.GraylogSettingsRepository
import com.example.ai_develop.platform.GraylogPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GraylogSettingsUiState(
    val webUrl: String = "http://127.0.0.1:9000",
    val startCommand: String = "",
    val message: String? = null,
)

class GraylogSettingsViewModel(
    private val repository: GraylogSettingsRepository,
    private val platform: GraylogPlatform,
) : ViewModel() {

    private val _ui = MutableStateFlow(GraylogSettingsUiState())
    val uiState: StateFlow<GraylogSettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSettings().collect { s ->
                _ui.update {
                    it.copy(
                        webUrl = s.webUrl,
                        startCommand = s.startCommand,
                    )
                }
            }
        }
    }

    fun setWebUrl(value: String) {
        _ui.update { it.copy(webUrl = value, message = null) }
    }

    fun setStartCommand(value: String) {
        _ui.update { it.copy(startCommand = value, message = null) }
    }

    fun save() {
        viewModelScope.launch {
            val s = _ui.value
            repository.saveSettings(
                GraylogSettings(
                    webUrl = s.webUrl,
                    startCommand = s.startCommand,
                ),
            )
            _ui.update { it.copy(message = "Настройки Graylog сохранены") }
        }
    }

    fun openWebUi() {
        val r = platform.openWebUi(_ui.value.webUrl)
        _ui.update {
            it.copy(
                message = r.fold(
                    onSuccess = { "Открыт веб-интерфейс" },
                    onFailure = { e -> "Не удалось открыть: ${e.message}" },
                ),
            )
        }
    }

    fun startGraylog() {
        val r = platform.runStartCommand(_ui.value.startCommand)
        _ui.update {
            it.copy(
                message = r.fold(
                    onSuccess = { msg -> msg },
                    onFailure = { e -> "Ошибка: ${e.message}" },
                ),
            )
        }
    }

    fun clearMessage() {
        _ui.update { it.copy(message = null) }
    }
}
