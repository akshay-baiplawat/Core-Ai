package com.stridetech.coreai.ui.modelhub

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stridetech.coreai.ICoreAiCallback
import com.stridetech.coreai.ICoreAiInterface
import com.stridetech.coreai.hub.DownloadStatus
import com.stridetech.coreai.hub.LocalCatalogDataSource
import com.stridetech.coreai.hub.ModelApiService
import com.stridetech.coreai.hub.ModelCatalogItem
import com.stridetech.coreai.hub.ModelDownloader
import com.stridetech.coreai.security.ApiKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

private const val TAG = "ModelHubViewModel"
private const val BIND_ACTION = "com.stridetech.coreai.BIND_LLM_SERVICE"
private const val SERVICE_PKG = "com.stridetech.coreai"
private const val SERVICE_CLASS = "com.stridetech.coreai.service.CoreAiService"
private const val MODELS_DIR = "models"
private val MODEL_EXTENSIONS = setOf("bin", "litertlm", "gguf")

data class ModelInfo(
    val fileName: String,
    val absolutePath: String,
    val fileSizeBytes: Long,
    val lastModified: Long,
    val isLoaded: Boolean,
    val isActive: Boolean
)

data class ModelHubUiState(
    val models: List<ModelInfo> = emptyList(),
    val isImporting: Boolean = false,
    val importError: String? = null,
    val importSuccess: Boolean = false,
    val serviceError: String? = null,
    val catalogItems: List<ModelCatalogItem> = emptyList(),
    val isFetchingCatalog: Boolean = false,
    val catalogError: String? = null,
    val downloadProgress: Map<String, DownloadStatus> = emptyMap()
)

@HiltViewModel
class ModelHubViewModel @Inject constructor(
    application: Application,
    private val localCatalogDataSource: LocalCatalogDataSource,
    private val modelApiService: ModelApiService,
    private val modelDownloader: ModelDownloader,
    private val apiKeyManager: ApiKeyManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ModelHubUiState())
    val uiState: StateFlow<ModelHubUiState> = _uiState.asStateFlow()

    private var coreAiService: ICoreAiInterface? = null

    private val modelCallback = object : ICoreAiCallback.Stub() {
        override fun onModelStateChanged(isReady: Boolean, activeModelName: String?) {
            refresh()
        }

        override fun onError(errorMessage: String?) {
            _uiState.update { it.copy(serviceError = errorMessage) }
        }

        override fun onInferenceResult(resultJson: String?) {}
        override fun onInferenceToken(token: String?) {}
        override fun onInferenceComplete(latencyMs: Long) {}

        override fun onModelTransferProgress(modelId: String?, percent: Int) {}
        override fun onModelTransferComplete(modelId: String?, filePath: String?) { refresh() }
        override fun onModelTransferError(modelId: String?, errorMessage: String?) {
            _uiState.update { it.copy(serviceError = errorMessage) }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = ICoreAiInterface.Stub.asInterface(binder)
            coreAiService = service
            runCatching { service.registerCallback(modelCallback) }
                .onFailure { Log.w(TAG, "registerCallback failed: ${it.message}") }
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            coreAiService = null
            refresh()
        }
    }

    init {
        bindToService()
        refresh()
        fetchCatalog()
    }

    private fun bindToService() {
        val intent = Intent(BIND_ACTION).apply { setClassName(SERVICE_PKG, SERVICE_CLASS) }
        runCatching {
            getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val models = withContext(Dispatchers.IO) { scanModelsDir() }
            _uiState.update { it.copy(models = models) }
        }
    }

    private suspend fun scanModelsDir(): List<ModelInfo> {
        val modelsDir = getApplication<Application>().getExternalFilesDir(MODELS_DIR) ?: return emptyList()
        val service = coreAiService
        val apiKey = apiKeyManager.getExistingKeys().firstOrNull()

        val activeModelId = apiKey?.let {
            runCatching {
                withTimeoutOrNull(2_000) {
                    service?.getActiveModel(it)?.let { json ->
                        org.json.JSONObject(json).optString("modelId", "").takeIf { s -> s.isNotEmpty() }
                    }
                }
            }.getOrNull()
        }

        val loadedIds: Set<String>? = apiKey?.let {
            runCatching {
                withTimeoutOrNull(2_000) {
                    service?.getLoadedModels(it)?.let { json ->
                        val arr = org.json.JSONObject(json).optJSONArray("models")
                        (0 until (arr?.length() ?: 0)).mapNotNull { i -> arr?.optString(i) }.toSet()
                    }
                }
            }.getOrNull()
        }

        return modelsDir.listFiles()
            ?.filter { it.isFile && it.extension in MODEL_EXTENSIONS }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val id = file.nameWithoutExtension
                ModelInfo(
                    fileName = file.name,
                    absolutePath = file.absolutePath,
                    fileSizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    isLoaded = loadedIds?.contains(id) == true,
                    isActive = id == activeModelId
                )
            }
            ?: emptyList()
    }

    fun onModelPicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importError = null, importSuccess = false) }
            val result = withContext(Dispatchers.IO) { copyModelFromUri(uri) }
            result.fold(
                onSuccess = {
                    refresh()
                    _uiState.update { it.copy(isImporting = false, importSuccess = true) }
                },
                onFailure = { ex ->
                    Log.e(TAG, "Import failed", ex)
                    _uiState.update {
                        it.copy(isImporting = false, importError = ex.message ?: "Import failed")
                    }
                }
            )
        }
    }

    private fun ensureApiKey(): String =
        apiKeyManager.getExistingKeys().firstOrNull() ?: apiKeyManager.generateKey()

    fun loadModel(model: ModelInfo) {
        val apiKey = ensureApiKey()
        viewModelScope.launch {
            if (coreAiService == null) {
                Log.w(TAG, "loadModel: service not bound, retrying bind")
                bindToService()
                _uiState.update { it.copy(serviceError = "Service not connected — please wait a moment and try again.") }
                return@launch
            }
            runCatching { coreAiService?.loadModel(apiKey, model.fileName.substringBeforeLast('.'), modelCallback) }
                .onFailure { ex ->
                    Log.e(TAG, "loadModel AIDL call failed", ex)
                    _uiState.update { it.copy(serviceError = ex.message ?: "Load failed") }
                }
        }
    }

    fun unloadModel(model: ModelInfo) {
        val apiKey = apiKeyManager.getExistingKeys().firstOrNull() ?: return
        viewModelScope.launch {
            runCatching { coreAiService?.unloadModel(apiKey, model.fileName.substringBeforeLast('.'), modelCallback) }
                .onFailure { Log.e(TAG, "unloadModel failed", it) }
        }
    }

    fun setActiveModel(model: ModelInfo) {
        val apiKey = apiKeyManager.getExistingKeys().firstOrNull() ?: return
        viewModelScope.launch {
            runCatching { coreAiService?.setActiveModel(apiKey, model.fileName.substringBeforeLast('.')) }
                .onFailure { Log.e(TAG, "setActiveModel failed", it) }
        }
    }

    fun deleteModel(model: ModelInfo) {
        val apiKey = apiKeyManager.getExistingKeys().firstOrNull() ?: return
        val modelId = model.fileName.substringBeforeLast('.')
        viewModelScope.launch {
            runCatching {
                suspendCancellableCoroutine { cont ->
                    val deleteCallback = object : ICoreAiCallback.Stub() {
                        override fun onModelStateChanged(isReady: Boolean, activeModelName: String?) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onInferenceResult(resultJson: String?) {}
                        override fun onInferenceToken(token: String?) {}
                        override fun onInferenceComplete(latencyMs: Long) {}
                        override fun onModelTransferProgress(modelId: String?, percent: Int) {}
                        override fun onModelTransferComplete(modelId: String?, filePath: String?) {}
                        override fun onModelTransferError(modelId: String?, errorMessage: String?) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    coreAiService?.deleteModel(apiKey, modelId, deleteCallback)
                        ?: cont.resume(Unit)
                }
            }.onFailure { Log.e(TAG, "deleteModel IPC call failed", it) }
            refresh()
        }
    }

    private fun copyModelFromUri(uri: Uri): Result<Unit> = runCatching {
        val app = getApplication<Application>()
        val displayName = resolveDisplayName(uri) ?: "model.bin"

        val modelsDir = requireNotNull(app.getExternalFilesDir(MODELS_DIR)) {
            "External storage is not available"
        }
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val tmpFile = File(modelsDir, "$displayName.tmp")
        val destFile = File(modelsDir, displayName)

        tmpFile.delete()
        app.contentResolver.openInputStream(uri)?.use { input ->
            tmpFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open input stream for selected file")

        if (!tmpFile.renameTo(destFile)) {
            tmpFile.delete()
            error("Failed to finalize imported file")
        }
        Log.i(TAG, "Model copied to ${destFile.absolutePath} (${destFile.length()} bytes)")
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val cursor = getApplication<Application>().contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        ) ?: return null
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun fetchCatalog() {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingCatalog = true, catalogError = null) }
            runCatching { withContext(Dispatchers.IO) { localCatalogDataSource.load() } }
                .onSuccess { items ->
                    _uiState.update { it.copy(catalogItems = items, isFetchingCatalog = false) }
                }
                .onFailure { ex ->
                    Log.e(TAG, "fetchCatalog failed", ex)
                    _uiState.update {
                        it.copy(isFetchingCatalog = false, catalogError = ex.message ?: "Failed to load catalog")
                    }
                }
        }
    }

    fun downloadModel(item: ModelCatalogItem) {
        viewModelScope.launch {
            modelDownloader.download(item).collect { status ->
                _uiState.update { state ->
                    state.copy(downloadProgress = state.downloadProgress + (item.id to status))
                }
                if (status is DownloadStatus.Success) refresh()
            }
        }
    }

    fun dismissCatalogError() {
        _uiState.update { it.copy(catalogError = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(importError = null) }
    }

    fun dismissSuccess() {
        _uiState.update { it.copy(importSuccess = false) }
    }

    override fun onCleared() {
        runCatching { coreAiService?.unregisterCallback(modelCallback) }
            .onFailure { Log.w(TAG, "unregisterCallback failed: ${it.message}") }
        if (coreAiService != null) {
            runCatching { getApplication<Application>().unbindService(serviceConnection) }
            coreAiService = null
        }
        super.onCleared()
    }
}
