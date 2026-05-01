package com.stridetech.coreai.ui.playground

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stridetech.coreai.ICoreAiInterface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

private const val BIND_ACTION = "com.stridetech.coreai.BIND_LLM_SERVICE"
private const val SERVICE_CLASS = "com.stridetech.coreai.service.CoreAiService"

data class PlaygroundUiState(
    val prompt: String = "",
    val output: String = "",
    val latencyMs: Long = 0L,
    val isServiceBound: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class PlaygroundViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlaygroundUiState())
    val uiState: StateFlow<PlaygroundUiState> = _uiState.asStateFlow()

    private var coreAiService: ICoreAiInterface? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            coreAiService = ICoreAiInterface.Stub.asInterface(binder)
            _uiState.update { it.copy(isServiceBound = true) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            coreAiService = null
            _uiState.update { it.copy(isServiceBound = false) }
        }
    }

    init {
        bindToService()
    }

    private fun bindToService() {
        val intent = Intent(BIND_ACTION).apply {
            setClassName(getApplication<Application>().packageName, SERVICE_CLASS)
        }
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun onPromptChange(value: String) {
        _uiState.update { it.copy(prompt = value) }
    }

    fun runInference() {
        val service = coreAiService ?: return
        val prompt = _uiState.value.prompt.trim()
        if (prompt.isBlank()) return

        _uiState.update { it.copy(isLoading = true, error = null, output = "") }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { service.runInference("", prompt) }
            }
            result.fold(
                onSuccess = { json -> parseAndApply(json) },
                onFailure = { ex ->
                    val message = when (ex) {
                        is RemoteException -> "Service error: ${ex.message}"
                        else -> ex.message ?: "Unknown error"
                    }
                    _uiState.update { it.copy(isLoading = false, error = message) }
                }
            )
        }
    }

    private fun parseAndApply(json: String) {
        runCatching {
            val obj = JSONObject(json)
            if (obj.optBoolean("success", false)) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        output = obj.optString("completion", ""),
                        latencyMs = obj.optLong("latency_ms", 0L),
                        error = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(isLoading = false, error = obj.optString("error", "Inference failed"))
                }
            }
        }.onFailure { ex ->
            _uiState.update { it.copy(isLoading = false, error = "Failed to parse response: ${ex.message}") }
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unbindService(serviceConnection) }
        coreAiService = null
        super.onCleared()
    }
}
