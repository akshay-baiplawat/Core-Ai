package com.stridetech.coreai.ui.playground

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stridetech.coreai.ICoreAiCallback
import com.stridetech.coreai.ICoreAiInterface
import com.stridetech.coreai.security.ApiKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

private const val TAG = "PlaygroundViewModel"
private const val BIND_ACTION = "com.stridetech.coreai.BIND_LLM_SERVICE"
private const val SERVICE_CLASS = "com.stridetech.coreai.service.CoreAiService"

data class PlaygroundUiState(
    val prompt: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val isServiceBound: Boolean = false,
    val isLoading: Boolean = false,
    val activeModelName: String? = null,
    val error: String? = null
)

@HiltViewModel
class PlaygroundViewModel @Inject constructor(
    application: Application,
    private val apiKeyManager: ApiKeyManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlaygroundUiState())
    val uiState: StateFlow<PlaygroundUiState> = _uiState.asStateFlow()

    private var coreAiService: ICoreAiInterface? = null

    private val masterApiKey: String by lazy {
        apiKeyManager.getExistingKeys().firstOrNull() ?: apiKeyManager.generateKey()
    }

    private val modelCallback = object : ICoreAiCallback.Stub() {
        override fun onModelStateChanged(isReady: Boolean, activeModelName: String?) {
            _uiState.update { it.copy(activeModelName = activeModelName?.takeIf { name -> name.isNotEmpty() }) }
        }

        override fun onError(errorMessage: String?) {
            _uiState.update { it.copy(activeModelName = null) }
        }

        override fun onInferenceResult(resultJson: String?) {
            parseAndApply(resultJson ?: "")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = ICoreAiInterface.Stub.asInterface(binder)
            coreAiService = service
            _uiState.update { it.copy(isServiceBound = true) }
            runCatching { service.registerCallback(modelCallback) }
                .onFailure { Log.w(TAG, "registerCallback failed: ${it.message}") }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            coreAiService = null
            _uiState.update { it.copy(isServiceBound = false, activeModelName = null) }
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

        val userMessage = ChatMessage(role = MessageRole.USER, content = prompt)
        _uiState.update { state ->
            state.copy(
                prompt = "",
                messages = state.messages + userMessage,
                isLoading = true,
                error = null
            )
        }

        val contextString = buildContextString(_uiState.value.messages)

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { service.runInference(masterApiKey, contextString) }
                .onFailure { ex ->
                    val message = when (ex) {
                        is RemoteException -> "Service error: ${ex.message}"
                        else -> ex.message ?: "Unknown error"
                    }
                    _uiState.update { it.copy(isLoading = false, error = message) }
                }
        }
    }

    fun clearHistory() {
        _uiState.update { it.copy(messages = emptyList(), error = null) }
    }

    private fun buildContextString(history: List<ChatMessage>): String =
        history.filter { it.role != MessageRole.SYSTEM }
            .joinToString("\n") { msg ->
                when (msg.role) {
                    MessageRole.USER -> "User: ${msg.content}"
                    MessageRole.MODEL -> "Model: ${msg.content}"
                    MessageRole.SYSTEM -> ""
                }
            }

    private fun parseAndApply(json: String) {
        runCatching {
            val obj = JSONObject(json)
            if (obj.optBoolean("success", false)) {
                val modelMessage = ChatMessage(
                    role = MessageRole.MODEL,
                    content = obj.optString("completion", ""),
                    latencyMs = obj.optLong("latency_ms", 0L)
                )
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        messages = state.messages + modelMessage,
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
        runCatching { coreAiService?.unregisterCallback(modelCallback) }
            .onFailure { Log.w(TAG, "unregisterCallback failed: ${it.message}") }
        runCatching { getApplication<Application>().unbindService(serviceConnection) }
        coreAiService = null
        super.onCleared()
    }
}
