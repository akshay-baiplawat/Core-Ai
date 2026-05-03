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
private const val CONTEXT_WINDOW_MESSAGES = 10
private const val CONTEXT_WINDOW_WORDS = 1500

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

    @Volatile private var pendingOwnInference = false

    private val modelCallback = object : ICoreAiCallback.Stub() {
        override fun onModelStateChanged(isReady: Boolean, activeModelName: String?) {
            _uiState.update { it.copy(activeModelName = activeModelName?.takeIf { name -> name.isNotEmpty() }) }
        }

        override fun onError(errorMessage: String?) {
            _uiState.update { it.copy(activeModelName = null) }
        }

        override fun onInferenceResult(resultJson: String?) {
            // Used by non-streaming engines (LiteRT, MediaPipe); GGUF streams via onInferenceToken.
            parseAndApply(resultJson ?: "")
        }

        override fun onInferenceToken(token: String?) {
            token ?: return
            _uiState.update { state ->
                val last = state.messages.lastOrNull()
                val updatedMessages = if (last?.role == MessageRole.MODEL && last.isOwnResponse) {
                    val updatedMessage = last.copy(content = last.content + token)
                    state.messages.dropLast(1) + updatedMessage
                } else {
                    state.messages + ChatMessage(role = MessageRole.MODEL, content = token, isOwnResponse = pendingOwnInference)
                }
                state.copy(messages = updatedMessages, isLoading = true)
            }
        }

        override fun onInferenceComplete(latencyMs: Long) {
            pendingOwnInference = false
            _uiState.update { it.copy(isLoading = false) }
        }

        override fun onModelTransferProgress(modelId: String?, percent: Int) {}
        override fun onModelTransferComplete(modelId: String?, filePath: String?) {}
        override fun onModelTransferError(modelId: String?, errorMessage: String?) {}
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
            pendingOwnInference = true
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

    private fun buildContextString(history: List<ChatMessage>): String {
        val window = history
            .filter { it.role != MessageRole.SYSTEM }
            .takeLast(CONTEXT_WINDOW_MESSAGES)

        // Trim further if the tail still exceeds the word budget.
        var wordCount = 0
        val trimmed = window.toMutableList()
        for (i in trimmed.indices.reversed()) {
            wordCount += trimmed[i].content.split(Regex("\\s+")).size
            if (wordCount > CONTEXT_WINDOW_WORDS) {
                trimmed.subList(0, i + 1).clear()
                break
            }
        }

        return trimmed.joinToString("\n") { msg ->
            when (msg.role) {
                MessageRole.USER -> "User: ${msg.content}"
                MessageRole.MODEL -> "Model: ${msg.content}"
                MessageRole.SYSTEM -> ""
            }
        }
    }

    private fun parseAndApply(json: String) {
        val isOwn = pendingOwnInference
        pendingOwnInference = false
        runCatching {
            val obj = JSONObject(json)
            if (obj.optBoolean("success", false)) {
                val modelMessage = ChatMessage(
                    role = MessageRole.MODEL,
                    content = obj.optString("completion", ""),
                    latencyMs = obj.optLong("latency_ms", 0L),
                    isOwnResponse = isOwn
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
