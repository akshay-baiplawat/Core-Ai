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
import com.stridetech.coreai.hub.LocalCatalogDataSource
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

private const val TAG = "CoreAiService"
private val MODEL_EXTENSIONS = setOf("bin", "litertlm", "gguf")
private const val BUFFER_SIZE = 8 * 1024
private const val DEFAULT_MODEL_ID = "gemma-3-1b-q4"

// Pre-allocated fallback JSON — returned when even Gson fails (e.g. extreme OOM).
private const val FALLBACK_MODELS_JSON = """{"models":[],"error":"Internal error."}"""
private const val FALLBACK_ACTIVE_JSON = """{"modelId":null,"isReady":false,"error":"Internal error."}"""
private const val FALLBACK_LOADED_JSON = """{"models":[],"error":"Internal error."}"""

/**
 * Headless background service that hosts the on-device LLM engine and exposes it to
 * client apps via the [ICoreAiInterface] AIDL contract.
 *
 * Security model:
 *   - Every AIDL call validates the caller's API key via [ApiKeyManager] before execution.
 *   - [downloadCatalogModel] enforces HTTPS-only URLs and an alphanumeric modelId regex
 *     ([a-zA-Z0-9_-]+) to prevent SSRF and path-traversal attacks.
 *   - [importLocalModel] enforces the same alphanumeric regex on targetModelId.
 *
 * Concurrency model:
 *   - A single [AtomicBoolean] (isEngineLocked) acts as a binary mutex for all
 *     state-mutating operations (load, unload, delete, inference). CAS acquisition
 *     fails fast and surfaces contention to the caller via [ICoreAiCallback.onError].
 *   - Context isolation mode (FULL_PROMPT / PER_CLIENT) is stored in an [AtomicReference]
 *     and can be changed at runtime without acquiring the engine lock.
 */
@AndroidEntryPoint
open class CoreAiService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var engine: LlmEngine
    private val gson = Gson()

    @Inject lateinit var apiKeyManager: ApiKeyManager
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var localCatalogDataSource: LocalCatalogDataSource

    private val callbacks = RemoteCallbackList<ICoreAiCallback>()

    // Legacy flags kept for test-suite reflection compatibility.
    private val isModelLoading = AtomicBoolean(false)
    private val loadFailed = AtomicBoolean(false)
    private val modelNotFound = AtomicBoolean(false)

    // EngineLock: prevents concurrent load/unload/inference.
    // Acquired via compareAndSet(false, true); released in finally blocks.
    private val isEngineLocked = AtomicBoolean(false)

    // Context isolation mode — default FULL_PROMPT (stateless, backward-compatible).
    private val contextMode = AtomicReference(ContextMode.FULL_PROMPT)

    // Per-UID session history used only when mode == PER_CLIENT.
    // Key: Binder.getCallingUid(), Value: mutable turn list ("User: ...\nModel: ...").
    private val clientSessions = ConcurrentHashMap<Int, MutableList<String>>()

    // Resolved once on the main thread in onCreate() to avoid null returns from
    // getExternalFilesDir() when called on Binder pool threads early in the lifecycle.
    @Volatile private var modelsDirCache: File? = null

    override fun onCreate() {
        super.onCreate()
        modelsDirCache = getExternalFilesDir("models")?.also { it.mkdirs() }
        Log.d(TAG, "onCreate: modelsDirCache=${modelsDirCache?.absolutePath}, exists=${modelsDirCache?.exists()}")
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
        clientSessions.clear()
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

        // Always start with a clean KV cache after loading. The native model instance is
        // fresh but the service may have accumulated state from a prior session if it was
        // kept alive by Android between app launches.
        engine.resetContext()
        clientSessions.clear()

        callback?.onModelStateChanged(engine.isReady, engine.activeModelName() ?: "")
        broadcastModelState()
    }

    // ── Directory / lookup helpers ────────────────────────────────────────────

    private fun modelsDir(): File? =
        modelsDirCache ?: getExternalFilesDir("models")?.also { it.mkdirs(); modelsDirCache = it }

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

            val callerUid = android.os.Binder.getCallingUid()
            val effectivePrompt = when (contextMode.get()) {
                ContextMode.PER_CLIENT -> {
                    val history = clientSessions.getOrPut(callerUid) { mutableListOf() }
                    history.add("User: $prompt")
                    history.joinToString("\n")
                }
                ContextMode.FULL_PROMPT -> prompt
            }

            serviceScope.launch {
                val startMs = System.currentTimeMillis()
                val responseBuilder = StringBuilder()
                try {
                    engine.runInferenceStream(effectivePrompt).collect { token ->
                        responseBuilder.append(token)
                        broadcastToken(token)
                    }
                    if (contextMode.get() == ContextMode.PER_CLIENT) {
                        val history = clientSessions.getOrPut(callerUid) { mutableListOf() }
                        history.add("Model: ${responseBuilder.toString().trim()}")
                    }
                    val latencyMs = System.currentTimeMillis() - startMs
                    broadcastInferenceResult(successResponse(responseBuilder.toString(), latencyMs))
                    broadcastInferenceComplete(latencyMs)
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
            val targetModel = if (modelId.isNullOrBlank()) DEFAULT_MODEL_ID else modelId

            if (!tryAcquireLock(callback)) return
            serviceScope.launch {
                try {
                    // Smart cache check inside lock — prevents TOCTOU race on active model state
                    if (engine.activeModelName() == targetModel && engine.isReady) {
                        callback?.onModelStateChanged(true, targetModel)
                        return@launch
                    }

                    // Unload a different active model before loading the new one
                    val currentActive = engine.activeModelName()
                    if (currentActive != null && currentActive != targetModel) {
                        findModelFile(currentActive)?.let { engine.unload(it.absolutePath) }
                        broadcastModelState()
                    }

                    val modelFile = findModelFile(targetModel) ?: run {
                        callback?.onError("Model file not found for id: $targetModel")
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
            val targetModel = if (modelId.isNullOrBlank()) DEFAULT_MODEL_ID else modelId
            if (!targetModel.matches(Regex("[a-zA-Z0-9_-]+"))) { callback?.onError("modelId contains invalid characters."); return }
            if (!tryAcquireLock(callback)) return
            serviceScope.launch {
                try {
                    val modelFile = findModelFile(targetModel)
                    if (modelFile != null) {
                        engine.unload(modelFile.absolutePath)
                        if (!modelFile.delete()) {
                            callback?.onError("Failed to delete model file for id: $targetModel")
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

        override fun resetChatContext(apiKey: String?, modelId: String?) {
            if (!apiKeyManager.isValidKey(apiKey ?: "")) return
            val callerUid = android.os.Binder.getCallingUid()
            clientSessions.remove(callerUid)
            if (!isEngineLocked.compareAndSet(false, true)) {
                Log.w(TAG, "resetChatContext: engine locked by active process, skipping KV flush")
                return
            }
            try {
                if (!engine.isReady) {
                    Log.w(TAG, "resetChatContext: no active engine, nothing to reset")
                    return
                }
                engine.resetContext()
                Log.i(TAG, "resetChatContext: context flushed uid=$callerUid model=${engine.activeModelName()}")
            } finally {
                releaseLock()
            }
        }

        override fun setContextMode(apiKey: String?, mode: String?) {
            if (!apiKeyManager.isValidKey(apiKey ?: "")) return
            val newMode = ContextMode.fromString(mode ?: "")
            contextMode.set(newMode)
            if (newMode == ContextMode.FULL_PROMPT) clientSessions.clear()
            Log.i(TAG, "Context mode set to $newMode")
        }

        override fun getContextMode(apiKey: String?): String {
            if (!apiKeyManager.isValidKey(apiKey ?: "")) return ContextMode.FULL_PROMPT.name
            return contextMode.get().name
        }

        // ── Catalog download ──────────────────────────────────────────────────

        override fun downloadCatalogModel(
            apiKey: String?,
            modelId: String?,
            downloadUrl: String?,
            callback: ICoreAiCallback?
        ) {
            if (!checkApiKey(apiKey, callback)) return
            val targetModel = if (modelId.isNullOrBlank()) DEFAULT_MODEL_ID else modelId
            if (downloadUrl.isNullOrBlank()) { callback?.onError("downloadUrl must not be empty."); return }
            if (!downloadUrl.startsWith("https://")) { callback?.onError("downloadUrl must use HTTPS."); return }
            if (!targetModel.matches(Regex("[a-zA-Z0-9_-]+"))) { callback?.onError("modelId contains invalid characters."); return }

            serviceScope.launch {
                val dir = modelsDir() ?: run {
                    callback?.onModelTransferError(targetModel, "External storage unavailable.")
                    return@launch
                }

                // Smart cache: file already on disk — skip download entirely
                val cachedFile = dir.listFiles()?.firstOrNull {
                    it.nameWithoutExtension == targetModel && it.extension in MODEL_EXTENSIONS
                }
                if (cachedFile != null) {
                    Log.i(TAG, "Cache hit for $targetModel, skipping download")
                    callback?.onModelTransferComplete(targetModel, cachedFile.absolutePath)
                    return@launch
                }

                val tmpFile = File(dir, "$targetModel.tmp")
                try {
                    val request = Request.Builder().url(downloadUrl).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            tmpFile.delete()
                            callback?.onModelTransferError(targetModel, "HTTP ${response.code}: ${response.message}")
                            return@launch
                        }
                        val body = response.body ?: run {
                            tmpFile.delete()
                            callback?.onModelTransferError(targetModel, "Empty response body.")
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
                                        callback?.onModelTransferProgress(targetModel, percent)
                                    }
                                }
                            }
                        }
                    }

                    val ext = downloadUrl.substringAfterLast('.', "litertlm")
                        .takeIf { it in MODEL_EXTENSIONS } ?: "litertlm"
                    val finalFile = File(dir, "$targetModel.$ext")
                    if (tmpFile.renameTo(finalFile)) {
                        callback?.onModelTransferProgress(targetModel, 100)
                        callback?.onModelTransferComplete(targetModel, finalFile.absolutePath)
                        Log.i(TAG, "Download complete: ${finalFile.absolutePath}")
                    } else {
                        tmpFile.delete()
                        callback?.onModelTransferError(targetModel, "Failed to finalize downloaded file.")
                    }
                } catch (e: Exception) {
                    tmpFile.delete()
                    Log.e(TAG, "Download failed for $targetModel", e)
                    callback?.onModelTransferError(targetModel, e.message ?: "Unknown download error.")
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
            Log.d(TAG, "getActiveModel: entered, apiKey=${apiKey?.take(6)}")
            return try {
                if (!apiKeyManager.isValidKey(apiKey ?: ""))
                    return gson.toJson(mapOf("modelId" to null, "isReady" to false, "error" to "Invalid API key."))
                gson.toJson(mapOf("modelId" to engine.activeModelName(), "isReady" to engine.isReady, "error" to null))
            } catch (t: Throwable) {
                Log.e(TAG, "getActiveModel failed [${t::class.java.simpleName}]", t)
                runCatching { gson.toJson(mapOf("modelId" to null, "isReady" to false, "error" to (t.message ?: "Unknown error."))) }
                    .getOrDefault(FALLBACK_ACTIVE_JSON)
            }
        }

        override fun getDownloadedModels(apiKey: String?): String {
            Log.d(TAG, "getDownloadedModels: entered, apiKey=${apiKey?.take(6)}, modelsDirCache=$modelsDirCache")
            return try {
                if (!apiKeyManager.isValidKey(apiKey ?: ""))
                    return gson.toJson(mapOf("models" to emptyList<Any>(), "error" to "Invalid API key."))
                val dir = modelsDir()
                Log.d(TAG, "getDownloadedModels: dir=${dir?.absolutePath}, exists=${dir?.exists()}, fileCount=${dir?.listFiles()?.size}")
                val files = dir?.listFiles()
                    ?.filter { it.isFile && it.extension in MODEL_EXTENSIONS }
                    ?.map { mapOf("modelId" to it.nameWithoutExtension, "path" to it.absolutePath, "sizeBytes" to it.length()) }
                    ?: emptyList()
                val result = gson.toJson(mapOf("models" to files, "error" to if (dir == null) "External storage unavailable." else null))
                Log.d(TAG, "getDownloadedModels: returning ${files.size} model(s)")
                result
            } catch (t: Throwable) {
                Log.e(TAG, "getDownloadedModels failed [${t::class.java.simpleName}]", t)
                runCatching { gson.toJson(mapOf("models" to emptyList<Any>(), "error" to (t.message ?: "Unknown error listing models."))) }
                    .getOrDefault(FALLBACK_MODELS_JSON)
            }
        }

        override fun getLoadedModels(apiKey: String?): String {
            Log.d(TAG, "getLoadedModels: entered, apiKey=${apiKey?.take(6)}")
            return try {
                if (!apiKeyManager.isValidKey(apiKey ?: ""))
                    return gson.toJson(mapOf("models" to emptyList<String>(), "error" to "Invalid API key."))
                gson.toJson(mapOf("models" to engine.loadedModelNames(), "error" to null))
            } catch (t: Throwable) {
                Log.e(TAG, "getLoadedModels failed [${t::class.java.simpleName}]", t)
                runCatching { gson.toJson(mapOf("models" to emptyList<String>(), "error" to (t.message ?: "Unknown error."))) }
                    .getOrDefault(FALLBACK_LOADED_JSON)
            }
        }

        // ── Callback registration ─────────────────────────────────────────────

        override fun registerCallback(callback: ICoreAiCallback?) {
            if (callback != null) {
                callbacks.register(callback)
                val uid = android.os.Binder.getCallingUid()
                clientSessions.remove(uid)
                // Flush the native KV cache whenever a new client connects so stale
                // conversation state from a prior session never bleeds into this one.
                // The engine may still be loaded from a previous app launch without
                // ever calling loadModelFile again, so this is the only reliable place.
                if (engine.isReady && !isEngineLocked.get()) {
                    serviceScope.launch { engine.resetContext() }
                }
            }
        }

        override fun unregisterCallback(callback: ICoreAiCallback?) {
            if (callback != null) callbacks.unregister(callback)
        }

        override fun getCatalog(apiKey: String?): String {
            if (!apiKeyManager.isValidKey(apiKey ?: ""))
                return gson.toJson(mapOf("models" to emptyList<Any>(), "error" to "Invalid API key."))
            return try {
                val items = localCatalogDataSource.load()
                gson.toJson(mapOf("models" to items, "error" to null))
            } catch (t: Throwable) {
                Log.e(TAG, "getCatalog failed", t)
                gson.toJson(mapOf("models" to emptyList<Any>(), "error" to (t.message ?: "Unknown error.")))
            }
        }
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private fun pendingResponse(startMs: Long): String =
        gson.toJson(mapOf("completion" to null, "latency_ms" to (System.currentTimeMillis() - startMs), "success" to true, "pending" to true, "error" to null))

    private fun successResponse(completion: String, latencyMs: Long): String =
        gson.toJson(mapOf("completion" to completion, "latency_ms" to latencyMs, "success" to true, "pending" to false, "error" to null))

    private fun errorResponse(message: String, startMs: Long): String =
        gson.toJson(mapOf("completion" to null, "latency_ms" to (System.currentTimeMillis() - startMs), "success" to false, "pending" to false, "error" to message))
}
