package com.stridetech.coreai.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import com.google.ai.edge.litertlm.Backend
import com.stridetech.coreai.ICoreAiInterface
import com.stridetech.coreai.ml.LlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "CoreAiService"
private const val MODEL_PATH = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"

class CoreAiService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var engine: LlmEngine
    private val gson = Gson()

    @Volatile private var isModelLoading = false
    @Volatile private var loadFailed = false

    override fun onCreate() {
        super.onCreate()
        engine = LlmEngine(applicationContext)
        loadWithFallback()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        engine.close()
        super.onDestroy()
    }

    private fun loadWithFallback() {
        isModelLoading = true
        loadFailed = false
        serviceScope.launch {
            val modelExists = java.io.File(MODEL_PATH).exists()
            Log.i(TAG, "Model file check — path: $MODEL_PATH exists=$modelExists")

            if (!modelExists) {
                loadFailed = true
                isModelLoading = false
                Log.e(TAG, "No model file found. Push model with: adb push <model.litertlm> $MODEL_PATH")
                return@launch
            }

            runCatching { engine.load(MODEL_PATH, Backend.GpuArtisan()) }
                .onFailure { artisanEx ->
                    Log.w(TAG, "GpuArtisan backend failed (${artisanEx.message}), falling back to CPU")
                    runCatching { engine.load(MODEL_PATH, Backend.CPU()) }
                        .onFailure { cpuEx ->
                            loadFailed = true
                            Log.e(TAG, "CPU fallback also failed: ${cpuEx.message}", cpuEx)
                        }
                        .onSuccess {
                            Log.i(TAG, "Model loaded successfully on CPU")
                        }
                }
                .onSuccess {
                    Log.i(TAG, "Model loaded successfully on GpuArtisan")
                }
            isModelLoading = false
        }
    }

    private val binder = object : ICoreAiInterface.Stub() {

        override fun runInference(apiKey: String?, prompt: String?): String {
            val startMs = System.currentTimeMillis()
            if (isModelLoading) {
                return errorResponse("Model is currently loading into memory, please wait a moment.", startMs)
            }
            if (loadFailed) {
                val reason = engine.lastLoadError ?: "unknown error"
                return errorResponse("Model failed to load: $reason", startMs)
            }
            if (!engine.isReady) {
                return errorResponse("Model is not ready. Please restart the service.", startMs)
            }
            if (prompt.isNullOrBlank()) {
                return errorResponse("Prompt must not be empty", startMs)
            }
            return runBlocking(Dispatchers.IO) {
                runCatching { engine.runInference(prompt) }
                    .fold(
                        onSuccess = { completion ->
                            val latency = System.currentTimeMillis() - startMs
                            successResponse(completion, latency)
                        },
                        onFailure = { ex ->
                            Log.e(TAG, "Inference failed", ex)
                            errorResponse(ex.message ?: "Unknown error", startMs)
                        }
                    )
            }
        }

        override fun isReady(): Boolean = engine.isReady

        override fun getActiveModelName(): String? = engine.activeModelName()

        override fun validateApiKey(apiKey: String?): Boolean = !apiKey.isNullOrBlank()
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
