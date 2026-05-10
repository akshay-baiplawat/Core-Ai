package com.stridetech.coreai.ml

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel

private const val TAG = "GgufModelEngine"
private const val DEFAULT_CONTEXT_SIZE = 8192
private const val DEFAULT_THREAD_COUNT = 4
private const val DEFAULT_GPU_LAYERS = 0

class GgufModelEngine : ModelEngine {

    // Single-threaded dispatcher for all JNI calls. llama.cpp's global C++ state (model,
    // context, sampler) is not thread-safe; this guarantees every native call runs on the
    // same dedicated thread regardless of which coroutine invokes it.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var llamaModel: LlamaModel? = null
    @Volatile private var modelName: String? = null
    @Volatile private var modelPath: String? = null
    @Volatile private var resolvedTemplate: ChatTemplate? = null
    @Volatile override var lastLoadError: String? = null

    override val isReady: Boolean get() = llamaModel != null

    override fun activeModelName(): String? = modelName

    override suspend fun load(modelPath: String, backend: Backend) {
        withContext(llamaDispatcher) {
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

            val architecture = GgufMetadataReader.readArchitecture(file)
            Log.d(TAG, "GGUF architecture: ${architecture ?: "(not detected)"}")

            runCatching {
                LlamaModel.load(modelPath) {
                    contextSize = DEFAULT_CONTEXT_SIZE
                    threads = DEFAULT_THREAD_COUNT
                    gpuLayers = DEFAULT_GPU_LAYERS
                }
            }.onSuccess { model ->
                llamaModel = model
                this@GgufModelEngine.modelPath = modelPath
                val name = modelPath.substringAfterLast('/').substringBeforeLast('.')
                modelName = name
                resolvedTemplate = ChatTemplateFormatter.templateFor(name, architecture)
                Log.i(TAG, "Loaded OK: $name (arch=$architecture)")
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

    override suspend fun runInferenceStream(prompt: String, stopSequences: List<String>): Flow<String> {
        val model = checkNotNull(llamaModel) { "Engine not loaded. Call load() first." }
        val template = resolvedTemplate ?: ChatTemplateFormatter.templateFor(modelName ?: "")
        val stopSeqs = stopSequences.ifEmpty { template.effectiveStopSequences }
        val rawStream = model.generateStream(prompt).flowOn(llamaDispatcher)
        return if (stopSeqs.isEmpty()) rawStream else truncateAtStop(rawStream, stopSeqs)
    }

    override suspend fun runInference(prompt: String): String =
        runInferenceStream(prompt).toList().joinToString("")

    // Buffers up to maxStopLen chars of the stream so we can detect a stop sequence
    // that arrives split across multiple tokens. Emits the safe prefix on each token
    // and flushes the buffer when the stream ends. Stops the flow entirely if a stop
    // sequence is matched, discarding everything from the match point onward.
    private fun truncateAtStop(source: Flow<String>, stopSequences: List<String>): Flow<String> = flow {
        val maxStopLen = stopSequences.maxOf { it.length }
        val buffer = StringBuilder()
        var stopped = false

        source.collect { token ->
            if (stopped) return@collect
            buffer.append(token)

            val stopIdx = stopSequences
                .mapNotNull { seq -> buffer.indexOf(seq).takeIf { it >= 0 } }
                .minOrNull()

            if (stopIdx != null) {
                if (stopIdx > 0) emit(buffer.substring(0, stopIdx))
                stopped = true
                return@collect
            }

            val safeLen = buffer.length - maxStopLen
            if (safeLen > 0) {
                emit(buffer.substring(0, safeLen))
                buffer.delete(0, safeLen)
            }
        }

        if (!stopped && buffer.isNotEmpty()) emit(buffer.toString())
    }

    // No direct JNI API to flush the KV cache, so close the native model and immediately
    // reload from the same file path to restore a clean context while keeping isReady=true.
    override fun resetContext() {
        val path = modelPath ?: run {
            Log.w(TAG, "resetContext: no model path stored, skipping reload")
            return
        }
        val name = modelName
        val template = resolvedTemplate
        llamaModel?.close()
        llamaModel = null
        Log.i(TAG, "Native KV cache cleared for $name — reloading from $path")
        runBlocking(llamaDispatcher) {
            runCatching {
                LlamaModel.load(path) {
                    contextSize = DEFAULT_CONTEXT_SIZE
                    threads = DEFAULT_THREAD_COUNT
                    gpuLayers = DEFAULT_GPU_LAYERS
                }
            }.onSuccess { model ->
                llamaModel = model
                modelName = name
                resolvedTemplate = template
                Log.i(TAG, "Reload after resetContext OK: $modelName")
            }.onFailure { ex ->
                lastLoadError = ex.message
                Log.e(TAG, "Reload after resetContext failed: ${ex.message}", ex)
            }
        }
    }

    override fun close() {
        llamaModel?.close()
        llamaModel = null
        modelName = null
        modelPath = null
        resolvedTemplate = null
    }
}
