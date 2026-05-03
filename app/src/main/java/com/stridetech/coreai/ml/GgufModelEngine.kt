package com.stridetech.coreai.ml

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel

private const val TAG = "GgufModelEngine"
private const val DEFAULT_CONTEXT_SIZE = 2048
private const val DEFAULT_THREAD_COUNT = 4
private const val DEFAULT_GPU_LAYERS = 0

class GgufModelEngine : ModelEngine {

    @Volatile private var llamaModel: LlamaModel? = null
    @Volatile private var modelName: String? = null
    @Volatile override var lastLoadError: String? = null

    override val isReady: Boolean get() = llamaModel != null

    override fun activeModelName(): String? = modelName

    override suspend fun load(modelPath: String, backend: Backend) {
        withContext(Dispatchers.IO) {
            lastLoadError = null
            close()
            Log.d(TAG, "Attempting to load model at path: $modelPath")

            val file = java.io.File(modelPath)
            if (!file.exists()) {
                val msg = "File does not exist: $modelPath"
                lastLoadError = msg
                Log.e(TAG, msg)
                throw IllegalArgumentException(msg)
            }
            if (!file.canRead()) {
                val msg = "File is not readable (permissions?): $modelPath"
                lastLoadError = msg
                Log.e(TAG, msg)
                throw IllegalArgumentException(msg)
            }
            Log.d(TAG, "File check OK — size=${file.length()} bytes, path=$modelPath")

            runCatching {
                LlamaModel.load(modelPath) {
                    contextSize = DEFAULT_CONTEXT_SIZE
                    threads = DEFAULT_THREAD_COUNT
                    gpuLayers = DEFAULT_GPU_LAYERS
                }
            }.onSuccess { model ->
                llamaModel = model
                modelName = modelPath.substringAfterLast('/').substringBeforeLast('.')
                Log.i(TAG, "Loaded OK: $modelName")
            }.onFailure { ex ->
                val rawMsg = ex.message ?: "(no message)"
                val userMsg = if (rawMsg.contains("Failed to load model from")) {
                    "Model architecture is not supported by the built-in llama.cpp version. " +
                    "Try a Llama 3, Mistral, Gemma 3, Phi-3, or Qwen 2/3 .gguf file instead. " +
                    "(Error: $rawMsg)"
                } else {
                    "${ex::class.java.simpleName}: $rawMsg"
                }
                lastLoadError = userMsg
                Log.e(TAG, "load failed for $modelPath — $userMsg", ex)
                throw IllegalStateException(userMsg, ex)
            }
        }
    }

    override suspend fun runInferenceStream(prompt: String): Flow<String> {
        val model = checkNotNull(llamaModel) { "Engine not loaded. Call load() first." }
        return model.generateStream(prompt)
    }

    override suspend fun runInference(prompt: String): String =
        runInferenceStream(prompt).toList().joinToString("")

    override fun close() {
        llamaModel?.close()
        llamaModel = null
        modelName = null
    }
}
