package com.stridetech.coreai.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MediaPipeModelEngine"

class MediaPipeModelEngine(private val context: Context) : ModelEngine {

    @Volatile private var inference: LlmInference? = null
    @Volatile private var modelName: String? = null
    @Volatile override var lastLoadError: String? = null

    override val isReady: Boolean get() = inference != null

    override fun activeModelName(): String? = modelName

    override suspend fun load(modelPath: String, backend: Backend) {
        withContext(Dispatchers.IO) {
            lastLoadError = null
            close()
            Log.d(TAG, "Loading: $modelPath")
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    .build()
                inference = LlmInference.createFromOptions(context, options)
                modelName = modelPath.substringAfterLast('/').substringBeforeLast('.')
                Log.i(TAG, "Loaded OK: $modelName")
            } catch (ex: Exception) {
                val detail = "${ex::class.java.name}: ${ex.message ?: "(no message)"}"
                lastLoadError = detail
                Log.e(TAG, "Load FAILED — $detail", ex)
                throw ex
            }
        }
    }

    override suspend fun runInference(prompt: String): String = withContext(Dispatchers.IO) {
        val inf = requireNotNull(inference) { "Engine not loaded. Call load() first." }
        inf.generateResponse(prompt)
    }

    override fun close() {
        inference?.close()
        inference = null
        modelName = null
    }
}
