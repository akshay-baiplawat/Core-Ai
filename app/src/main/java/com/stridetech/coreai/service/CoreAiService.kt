package com.stridetech.coreai.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.gson.Gson
import com.stridetech.coreai.ICoreAiCallback
import com.stridetech.coreai.ICoreAiInterface
import com.stridetech.coreai.ml.LlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "CoreAiService"
private val MODEL_EXTENSIONS = setOf("bin", "litertlm")

class CoreAiService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var engine: LlmEngine
    private val gson = Gson()

    private val callbacks = RemoteCallbackList<ICoreAiCallback>()

    @Volatile private var isModelLoading = false
    @Volatile private var loadFailed = false
    @Volatile private var modelNotFound = false

    override fun onCreate() {
        super.onCreate()
        engine = LlmEngine(applicationContext)
        loadWithFallback()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!engine.isReady && !isModelLoading) loadWithFallback()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        engine.close()
        broadcastModelState()
        callbacks.kill()
        super.onDestroy()
    }

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
                .onFailure { Log.w(TAG, "Callback error delivery failed: ${it.message}") }
        }
        callbacks.finishBroadcast()
    }

    private fun loadWithFallback() {
        isModelLoading = true
        loadFailed = false
        modelNotFound = false
        serviceScope.launch {
            val modelsDir = getExternalFilesDir("models")
            val modelFile = modelsDir?.listFiles()
                ?.firstOrNull { it.extension in MODEL_EXTENSIONS }

            if (modelFile == null) {
                modelNotFound = true
                isModelLoading = false
                val msg = "No model file found in ${modelsDir?.absolutePath ?: "external storage unavailable"}."
                Log.e(TAG, msg)
                broadcastError(msg)
                return@launch
            }

            loadModelPath(modelFile.absolutePath)
        }
    }

    private suspend fun loadModelPath(modelPath: String) {
        isModelLoading = true
        loadFailed = false
        modelNotFound = false
        Log.i(TAG, "Loading model: $modelPath")

        runCatching { engine.load(modelPath, Backend.GpuArtisan()) }
            .onFailure { artisanEx ->
                Log.w(TAG, "GpuArtisan backend failed (${artisanEx.message}), falling back to CPU")
                runCatching { engine.load(modelPath, Backend.CPU()) }
                    .onFailure { cpuEx ->
                        loadFailed = true
                        Log.e(TAG, "CPU fallback also failed: ${cpuEx.message}", cpuEx)
                        broadcastError("Model failed to load: ${cpuEx.message ?: "unknown error"}")
                    }
                    .onSuccess {
                        Log.i(TAG, "Model loaded on CPU: $modelPath")
                        broadcastModelState()
                    }
            }
            .onSuccess {
                Log.i(TAG, "Model loaded on GpuArtisan: $modelPath")
                broadcastModelState()
            }

        isModelLoading = false
    }

    private val binder = object : ICoreAiInterface.Stub() {

        override fun runInference(apiKey: String?, prompt: String?): String {
            val startMs = System.currentTimeMillis()
            return when {
                isModelLoading -> errorResponse("Model is currently loading, please wait.", startMs)
                modelNotFound -> errorResponse("No model loaded. Use Model Hub to load a model.", startMs)
                loadFailed -> errorResponse("Model failed to load: ${engine.lastLoadError ?: "unknown error"}", startMs)
                !engine.isReady -> errorResponse("No active model. Load and activate a model in Model Hub.", startMs)
                prompt.isNullOrBlank() -> errorResponse("Prompt must not be empty", startMs)
                else -> runBlocking(Dispatchers.IO) {
                    runCatching { engine.runInference(prompt) }
                        .fold(
                            onSuccess = { completion ->
                                successResponse(completion, System.currentTimeMillis() - startMs)
                            },
                            onFailure = { ex ->
                                Log.e(TAG, "Inference failed", ex)
                                errorResponse(ex.message ?: "Unknown error", startMs)
                            }
                        )
                }
            }
        }

        override fun isReady(): Boolean = engine.isReady

        override fun getActiveModelName(): String? = engine.activeModelName()

        override fun validateApiKey(apiKey: String?): Boolean = !apiKey.isNullOrBlank()

        override fun reloadModel() {
            engine.close()
            broadcastModelState()
            loadWithFallback()
        }

        override fun loadModel(modelPath: String?) {
            if (modelPath.isNullOrBlank()) return
            serviceScope.launch { loadModelPath(modelPath) }
        }

        override fun unloadModel(modelPath: String?) {
            if (modelPath.isNullOrBlank()) return
            engine.unload(modelPath)
            broadcastModelState()
        }

        override fun setActiveModel(modelPath: String?) {
            if (modelPath.isNullOrBlank()) return
            runCatching { engine.setActive(modelPath) }
                .onFailure { Log.e(TAG, "setActiveModel failed: ${it.message}") }
                .onSuccess { broadcastModelState() }
        }

        override fun getLoadedModelNames(): String =
            engine.loadedModelNames().joinToString(",")

        override fun registerCallback(callback: ICoreAiCallback?) {
            if (callback != null) callbacks.register(callback)
        }

        override fun unregisterCallback(callback: ICoreAiCallback?) {
            if (callback != null) callbacks.unregister(callback)
        }
    }

    private fun successResponse(completion: String, latencyMs: Long): String =
        gson.toJson(
            mapOf(
                "completion" to completion,
                "latency_ms" to latencyMs,
                "success" to true,
                "error" to null
            )
        )

    private fun errorResponse(message: String, startMs: Long): String =
        gson.toJson(
            mapOf(
                "completion" to null,
                "latency_ms" to (System.currentTimeMillis() - startMs),
                "success" to false,
                "error" to message
            )
        )
}
