package com.stridetech.coreai.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MediaPipeModelEngine"

internal interface InferenceRunner {
    fun generate(prompt: String): String
    fun release()
}

open class MediaPipeModelEngine(private val context: Context) : ModelEngine {

    @Volatile private var runner: InferenceRunner? = null
    @Volatile private var modelName: String? = null
    @Volatile override var lastLoadError: String? = null

    override val isReady: Boolean get() = runner != null

    override fun activeModelName(): String? = modelName

    // Seam for unit tests: override to supply a fake InferenceRunner without native libs.
    internal open fun createRunner(modelPath: String): InferenceRunner {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .build()
        val llm = LlmInference.createFromOptions(context, options)
        return object : InferenceRunner {
            override fun generate(prompt: String) = llm.generateResponse(prompt)
            override fun release() = llm.close()
        }
    }

    override suspend fun load(modelPath: String, backend: Backend) {
        withContext(Dispatchers.IO) {
            lastLoadError = null
            close()
            Log.d(TAG, "Loading: $modelPath")
            try {
                runner = createRunner(modelPath)
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
        val r = requireNotNull(runner) { "Engine not loaded. Call load() first." }
        r.generate(prompt)
    }

    // MediaPipe LlmInference.generateResponse is a stateless one-shot call with no
    // persistent session state; override provided for API symmetry.
    override fun resetContext() {
        Log.i(TAG, "resetContext called for $modelName — no persistent session state to clear")
    }

    override fun close() {
        runner?.release()
        runner = null
        modelName = null
    }
}
