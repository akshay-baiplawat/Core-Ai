package com.stridetech.coreai.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.RemoteCallbackList
import android.provider.OpenableColumns
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.gson.Gson
import com.stridetech.coreai.ICoreAiCallback
import com.stridetech.coreai.ICoreAiInterface
import com.stridetech.coreai.ml.LlmEngine
import com.stridetech.coreai.security.ApiKeyManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "CoreAiService"
private val MODEL_EXTENSIONS = setOf("bin", "litertlm", "gguf")
private const val BUFFER_SIZE = 8 * 1024

@AndroidEntryPoint
open class CoreAiService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var engine: LlmEngine
    private val gson = Gson()

    @Inject lateinit var apiKeyManager: ApiKeyManager
    @Inject lateinit var okHttpClient: OkHttpClient

    private val callbacks = RemoteCallbackList<ICoreAiCallback>()

    // Legacy flags kept for test-suite reflection compatibility.
    private val isModelLoading = AtomicBoolean(false)
    private val loadFailed = AtomicBoolean(false)
    private val modelNotFound = AtomicBoolean(false)

    // EngineLock: prevents concurrent load/unload/inference.
    // Acquired via compareAndSet(false, true); released in finally blocks.
    private val isEngineLocked = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        engine = LlmEngine(applicationContext)
        loadWithFallback()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!engine.isReady && !isModelLoading.get()) loadWithFallback()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        callbacks.kill()
        runBlocking { engine.close() }
        super.onDestroy()
    }

    // ── Lock helpers ──────────────────────────────────────────────────────────

    private fun tryAcquireLock(callback: ICoreAiCallback?): Boolean {
        if (!isEngineLocked.compareAndSet(false, true)) {
            callback?.onError("Engine is currently locked by an active process.")
            return false
        }
        return true
    }

    private fun releaseLock() = isEngineLocked.set(false)

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    private fun broadcastModelState() {
        val isReady = engine.isReady
        val modelName = engine.activeModelName() ?: ""
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            runCatching { callbacks.getBroadcastItem(i).onModelStateChanged(isReady, modelName) }
                .onFailure { Log.w(TAG, "Callback delivery failed: ${it.message}") }
        }
        callbacks.finishBroadcast()
    }

    private fun broadcastError(message: String) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            runCatching { callbacks.getBroadcastItem(i).onError(message) }
                .onFailure { Log.w(TAG, "Error callback failed: ${it.message}") }
        }
        callbacks.finishBroadcast()
    }

    private fun broadcastInferenceResult(result: String) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            runCatching { callbacks.getBroadcastItem(i).onInferenceResult(result) }
                .onFailure { Log.w(TAG, "Inference callback failed: ${it.message}") }
        }
        callbacks.finishBroadcast()
    }

    private fun broadcastToken(token: String) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            runCatching { callbacks.getBroadcastItem(i).onInferenceToken(token) }
                .onFailure { Log.w(TAG, "Token callback failed: ${it.message}") }
        }
        callbacks.finishBroadcast()
    }

    private fun broadcastInferenceComplete(latencyMs: Long) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            runCatching { callbacks.getBroadcastItem(i).onInferenceComplete(latencyMs) }
                .onFailure { Log.w(TAG, "Complete callback failed: ${it.message}") }
        }
        callbacks.finishBroadcast()
    }

    // ── Auto-load (first model found on disk) ─────────────────────────────────

    private fun loadWithFallback() {
        isModelLoading.set(true)
        loadFailed.set(false)
        modelNotFound.set(false)
        serviceScope.launch {
            try {
                val dir = getExternalFilesDir("models")
                val modelFile = dir?.listFiles()?.firstOrNull { it.extension in MODEL_EXTENSIONS }

                if (modelFile == null) {
                    modelNotFound.set(true)
                    val msg = if (dir == null)
                        "No model file found — external storage unavailable."
                    else
                        "No model file found in ${dir.absolutePath}."
                    Log.e(TAG, msg)
                    broadcastError(msg)
                    return@launch
                }
                loadModelFile(modelFile, callback = null)
            } finally {
                isModelLoading.set(false)
            }
        }
    }

    // ── Core load ─────────────────────────────────────────────────────────────

    private suspend fun loadModelFile(modelFile: File, callback: ICoreAiCallback?) {
        Log.i(TAG, "Loading model: ${modelFile.nameWithoutExtension}")
        runCatching { engine.load(modelFile.absolutePath, Backend.GpuArtisan()) }
            .onFailure { gpuEx ->
                Log.w(TAG, "GpuArtisan failed (${gpuEx.message}), falling back to CPU")
                runCatching { engine.load(modelFile.absolutePath, Backend.CPU()) }
                    .onFailure { cpuEx ->
                        loadFailed.set(true)
                        val msg = "Model failed to load: ${cpuEx.message ?: "unknown error"}"
                        Log.e(TAG, msg, cpuEx)
                        callback?.onError(msg) ?: broadcastError(msg)
                        return
                    }
                    .onSuccess { Log.i(TAG, "Model loaded on CPU: ${modelFile.nameWithoutExtension}") }
            }
            .onSuccess { Log.i(TAG, "Model loaded on GpuArtisan: ${modelFile.nameWithoutExtension}") }

        callback?.onModelStateChanged(engine.isReady, engine.activeModelName() ?: "")
        broadcastModelState()
    }

    // ── Directory / lookup helpers ────────────────────────────────────────────

    private fun modelsDir(): File? = getExternalFilesDir("models")?.also { it.mkdirs() }

    private fun findModelFile(modelId: String): File? =
        modelsDir()?.listFiles()?.firstOrNull {
            it.nameWithoutExtension == modelId && it.extension in MODEL_EXTENSIONS
        }

    private fun checkApiKey(apiKey: String?, callback: ICoreAiCallback?): Boolean {
        if (apiKeyManager.isValidKey(apiKey ?: "")) return true
        callback?.onError("Invalid or missing API key.")
        return false
    }

    // ── Binder ────────────────────────────────────────────────────────────────

    private val binder = object : ICoreAiInterface.Stub() {

        // ── Inference ─────────────────────────────────────────────────────────

        override fun runInference(apiKey: String?, prompt: String?): String {
            val startMs = System.currentTimeMillis()
            if (!apiKeyManager.isValidKey(apiKey ?: ""))
                return errorResponse("Invalid or missing API key.", startMs)
            if (!isEngineLocked.compareAndSet(false, true))
                return errorResponse("Engine is currently locked by an active process.", startMs)
            if (!engine.isReady) {
                releaseLock()
                return errorResponse("No active model. Load a model first.", startMs)
            }
            if (prompt.isNullOrBlank()) {
                releaseLock()
                return errorResponse("Prompt must not be empty.", startMs)
            }

            serviceScope.launch {
                val startMs = System.currentTimeMillis()
                try {
                    engine.runInferenceStream(prompt).collect { token ->
                        broadcastToken(token)
                    }
                    broadcastInferenceComplete(System.currentTimeMillis() - startMs)
                } catch (ex: Exception) {
                    Log.e(TAG, "Inference failed", ex)
                    broadcastInferenceResult(errorResponse(ex.message ?: "Unknown error", startMs))
                } finally {
                    releaseLock()
                }
            }
            return pendingResponse(startMs)
        }

        override fun isReady(): Boolean = engine.isReady

        override fun validateApiKey(apiKey: String?): Boolean =
            apiKeyManager.isValidKey(apiKey ?: "")

        // ── Model lifecycle ───────────────────────────────────────────────────

        override fun loadModel(apiKey: String?, modelId: String?, callback: ICoreAiCallback?) {
            if (!checkApiKey(apiKey, callback)) return
            if (modelId.isNullOrBlank()) { callback?.onError("modelId must not be empty."); return }

            if (!tryAcquireLock(callback)) return
            serviceScope.launch {
                try {
                    // Smart cache check inside lock — prevents TOCTOU race on active model state
                    if (engine.activeModelName() == modelId && engine.isReady) {
                        callback?.onModelStateChanged(true, modelId)
                        return@launch
                    }

                    // Unload a different active model before loading the new one
                    val currentActive = engine.activeModelName()
                    if (currentActive != null && currentActive != modelId) {
                        findModelFile(currentActive)?.let { engine.unload(it.absolutePath) }
                        broadcastModelState()
                    }

                    val modelFile = findModelFile(modelId) ?: run {
                        callback?.onError("Model file not found for id: $modelId")
                        return@launch
                    }
                    loadModelFile(modelFile, callback)
                } finally {
                    releaseLock()
                }
            }
        }

        override fun unloadModel(apiKey: String?, modelId: String?, callback: ICoreAiCallback?) {
            if (!checkApiKey(apiKey, callback)) return
            if (modelId.isNullOrBlank()) { callback?.onError("modelId must not be empty."); return }
            if (!tryAcquireLock(callback)) return
            serviceScope.launch {
                try {
                    val modelFile = findModelFile(modelId) ?: run {
                        callback?.onError("Model file not found for id: $modelId")
                        return@launch
                    }
                    engine.unload(modelFile.absolutePath)
                    callback?.onModelStateChanged(engine.isReady, engine.activeModelName() ?: "")
                    broadcastModelState()
                } finally {
                    releaseLock()
                }
            }
        }

        override fun deleteModel(apiKey: String?, modelId: String?, callback: ICoreAiCallback?) {
            if (!checkApiKey(apiKey, callback)) return
            if (modelId.isNullOrBlank()) { callback?.onError("modelId must not be empty."); return }
            if (!modelId.matches(Regex("[a-zA-Z0-9_-]+"))) { callback?.onError("modelId contains invalid characters."); return }
            if (!tryAcquireLock(callback)) return
            serviceScope.launch {
                try {
                    val modelFile = findModelFile(modelId)
                    if (modelFile != null) {
                        engine.unload(modelFile.absolutePath)
                        if (!modelFile.delete()) {
                            callback?.onError("Failed to delete model file for id: $modelId")
                            return@launch
                        }
                    }
                    callback?.onModelStateChanged(engine.isReady, engine.activeModelName() ?: "")
                    broadcastModelState()
                } finally {
                    releaseLock()
                }
            }
        }

        override fun setActiveModel(apiKey: String?, modelId: String?) {
            if (!apiKeyManager.isValidKey(apiKey ?: "")) return
            if (modelId.isNullOrBlank()) return
            val modelFile = findModelFile(modelId) ?: return
            serviceScope.launch {
                runCatching { engine.setActive(modelFile.absolutePath) }
                    .onSuccess { broadcastModelState() }
                    .onFailure { Log.e(TAG, "setActiveModel failed: ${it.message}") }
            }
        }

        // ── Catalog download ──────────────────────────────────────────────────

        override fun downloadCatalogModel(
            apiKey: String?,
            modelId: String?,
            downloadUrl: String?,
            callback: ICoreAiCallback?
        ) {
            if (!checkApiKey(apiKey, callback)) return
            if (modelId.isNullOrBlank()) { callback?.onError("modelId must not be empty."); return }
            if (downloadUrl.isNullOrBlank()) { callback?.onError("downloadUrl must not be empty."); return }
            if (!downloadUrl.startsWith("https://")) { callback?.onError("downloadUrl must use HTTPS."); return }
            if (!modelId.matches(Regex("[a-zA-Z0-9_-]+"))) { callback?.onError("modelId contains invalid characters."); return }

            serviceScope.launch {
                val dir = modelsDir() ?: run {
                    callback?.onModelTransferError(modelId!!, "External storage unavailable.")
                    return@launch
                }

                // Smart cache: file already on disk — skip download entirely
                val cachedFile = dir.listFiles()?.firstOrNull {
                    it.nameWithoutExtension == modelId!! && it.extension in MODEL_EXTENSIONS
                }
                if (cachedFile != null) {
                    Log.i(TAG, "Cache hit for $modelId, skipping download")
                    callback?.onModelTransferComplete(modelId!!, cachedFile.absolutePath)
                    return@launch
                }

                val tmpFile = File(dir, "$modelId.tmp")
                try {
                    val request = Request.Builder().url(downloadUrl!!).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            tmpFile.delete()
                            callback?.onModelTransferError(modelId!!, "HTTP ${response.code}: ${response.message}")
                            return@launch
                        }
                        val body = response.body ?: run {
                            tmpFile.delete()
                            callback?.onModelTransferError(modelId!!, "Empty response body.")
                            return@launch
                        }
                        val totalBytes = body.contentLength()
                        var downloaded = 0L
                        val buffer = ByteArray(BUFFER_SIZE)

                        tmpFile.outputStream().use { out ->
                            body.byteStream().use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    out.write(buffer, 0, read)
                                    downloaded += read
                                    if (totalBytes > 0) {
                                        val percent = ((downloaded * 100) / totalBytes).toInt()
                                        callback?.onModelTransferProgress(modelId!!, percent)
                                    }
                                }
                            }
                        }
                    }

                    val ext = downloadUrl.substringAfterLast('.', "litertlm")
                        .takeIf { it in MODEL_EXTENSIONS } ?: "litertlm"
                    val finalFile = File(dir, "$modelId.$ext")
                    if (tmpFile.renameTo(finalFile)) {
                        callback?.onModelTransferProgress(modelId!!, 100)
                        callback?.onModelTransferComplete(modelId!!, finalFile.absolutePath)
                        Log.i(TAG, "Download complete: ${finalFile.absolutePath}")
                    } else {
                        tmpFile.delete()
                        callback?.onModelTransferError(modelId!!, "Failed to finalize downloaded file.")
                    }
                } catch (e: Exception) {
                    tmpFile.delete()
                    Log.e(TAG, "Download failed for $modelId", e)
                    callback?.onModelTransferError(modelId!!, e.message ?: "Unknown download error.")
                }
            }
        }

        // ── Uri import (Option B) ─────────────────────────────────────────────

        override fun importLocalModel(
            apiKey: String?,
            uri: Uri?,
            targetModelId: String?,
            engineType: String?,
            callback: ICoreAiCallback?
        ) {
            if (!checkApiKey(apiKey, callback)) return
            if (uri == null) { callback?.onError("uri must not be null."); return }
            if (targetModelId.isNullOrBlank()) { callback?.onError("targetModelId must not be empty."); return }
            if (!targetModelId.matches(Regex("[a-zA-Z0-9_-]+"))) { callback?.onError("targetModelId contains invalid characters."); return }

            serviceScope.launch {
                val dir = modelsDir() ?: run {
                    callback?.onModelTransferError(targetModelId!!, "External storage unavailable.")
                    return@launch
                }
                val tmpFile = File(dir, "$targetModelId.tmp")
                try {
                    // Resolve display name and byte count via ContentResolver
                    var displayName: String? = null
                    var totalBytes = -1L
                    contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            displayName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                            totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                        }
                    }

                    val ext = displayName?.substringAfterLast('.', "")
                        ?.takeIf { it in MODEL_EXTENSIONS }
                        ?: engineType?.lowercase()?.takeIf { it in MODEL_EXTENSIONS }
                        ?: "litertlm"

                    val inputStream = contentResolver.openInputStream(uri) ?: run {
                        callback?.onModelTransferError(targetModelId!!, "Cannot open URI stream.")
                        return@launch
                    }

                    var copied = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    // inputStream is the outer .use so it is closed even if outputStream() throws.
                    inputStream.use { input ->
                        tmpFile.outputStream().use { out ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                copied += read
                                if (totalBytes > 0) {
                                    val percent = ((copied * 100) / totalBytes).toInt()
                                    callback?.onModelTransferProgress(targetModelId!!, percent)
                                }
                            }
                            out.flush()
                        }
                    }

                    val finalFile = File(dir, "$targetModelId.$ext")
                    if (tmpFile.renameTo(finalFile)) {
                        callback?.onModelTransferProgress(targetModelId!!, 100)
                        callback?.onModelTransferComplete(targetModelId!!, finalFile.absolutePath)
                        Log.i(TAG, "Import complete: ${finalFile.absolutePath}")
                    } else {
                        tmpFile.delete()
                        callback?.onModelTransferError(targetModelId!!, "Failed to finalize imported file.")
                    }
                } catch (e: Exception) {
                    tmpFile.delete()
                    Log.e(TAG, "Import failed for $targetModelId", e)
                    callback?.onModelTransferError(targetModelId!!, e.message ?: "Unknown import error.")
                }
            }
        }

        // ── State queries ─────────────────────────────────────────────────────

        override fun getActiveModel(apiKey: String?): String {
            if (!apiKeyManager.isValidKey(apiKey ?: ""))
                return gson.toJson(mapOf("modelId" to null, "isReady" to false, "error" to "Invalid API key."))
            return gson.toJson(
                mapOf("modelId" to engine.activeModelName(), "isReady" to engine.isReady, "error" to null)
            )
        }

        override fun getDownloadedModels(apiKey: String?): String {
            if (!apiKeyManager.isValidKey(apiKey ?: ""))
                return gson.toJson(mapOf("models" to emptyList<Any>(), "error" to "Invalid API key."))
            val files = modelsDir()?.listFiles()
                ?.filter { it.extension in MODEL_EXTENSIONS }
                ?.map { mapOf("modelId" to it.nameWithoutExtension, "path" to it.absolutePath, "sizeBytes" to it.length()) }
                ?: emptyList()
            return gson.toJson(mapOf("models" to files, "error" to null))
        }

        override fun getLoadedModels(apiKey: String?): String {
            if (!apiKeyManager.isValidKey(apiKey ?: ""))
                return gson.toJson(mapOf("models" to emptyList<String>(), "error" to "Invalid API key."))
            return gson.toJson(mapOf("models" to engine.loadedModelNames(), "error" to null))
        }

        // ── Callback registration ─────────────────────────────────────────────

        override fun registerCallback(callback: ICoreAiCallback?) {
            if (callback != null) callbacks.register(callback)
        }

        override fun unregisterCallback(callback: ICoreAiCallback?) {
            if (callback != null) callbacks.unregister(callback)
        }
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private fun pendingResponse(startMs: Long): String =
        gson.toJson(mapOf("completion" to null, "latency_ms" to (System.currentTimeMillis() - startMs), "success" to true, "pending" to true, "error" to null))

    private fun errorResponse(message: String, startMs: Long): String =
        gson.toJson(mapOf("completion" to null, "latency_ms" to (System.currentTimeMillis() - startMs), "success" to false, "pending" to false, "error" to message))
}
