package com.example.ai_develop.presentation.mvi

data class TaskUiState(
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val isSending: Boolean = false,
    val streamingPreview: String = "",
)

sealed interface TaskUiResult {
    data class StreamingPreview(val text: String) : TaskUiResult
    data class SendingChanged(val isSending: Boolean) : TaskUiResult
    data class Error(val throwable: Throwable?) : TaskUiResult
}

fun reduceTaskUiState(state: TaskUiState, result: TaskUiResult): TaskUiState {
    return when (result) {
        is TaskUiResult.StreamingPreview -> state.copy(streamingPreview = result.text)
        is TaskUiResult.SendingChanged -> state.copy(isSending = result.isSending)
        is TaskUiResult.Error -> state.copy(error = result.throwable)
    }
}
