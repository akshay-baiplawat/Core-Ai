package com.stridetech.coreai.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

private const val TAG = "LlmEngine"
private const val MAX_TOKENS = 1024
private const val TEMPERATURE = 0.8
private const val TOP_K = 40
private const val TOP_P = 0.95

class LlmEngine(private val context: Context) {

    @Volatile private var engine: Engine? = null
    @Volatile private var modelName: String? = null
    @Volatile private var loadError: String? = null

    val isReady: Boolean get() = engine != null
    val lastLoadError: String? get() = loadError

    fun activeModelName(): String? = modelName

    suspend fun load(modelPath: String, backend: Backend = Backend.CPU()) = withContext(Dispatchers.IO) {
        loadError = null
        close()
        Log.d(TAG, "Attempting to load model: $modelPath with backend: ${backend::class.java.simpleName}")
        try {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = MAX_TOKENS,
                cacheDir = context.cacheDir.path
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            modelName = modelPath.substringAfterLast('/').substringBeforeLast('.')
            Log.i(TAG, "Model loaded OK: $modelName (backend=${backend::class.java.simpleName})")
        } catch (ex: Exception) {
            val detail = "${ex::class.java.name}: ${ex.message ?: "(no message)"}"
            loadError = detail
            Log.e(TAG, "Model load FAILED — $detail", ex)
            throw ex
        }
    }

    suspend fun runInference(prompt: String): String = withContext(Dispatchers.IO) {
        val e = requireNotNull(engine) { "LlmEngine is not loaded. Call load() first." }

        val conversationConfig = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = TOP_K,
                topP = TOP_P,
                temperature = TEMPERATURE
            )
        )

        e.createConversation(conversationConfig).use { conversation ->
            val chunks = conversation.sendMessageAsync(prompt)
                .catch { ex -> throw ex }
                .toList()
            chunks.joinToString("")
        }
    }

    fun close() {
        engine?.close()
        engine = null
        modelName = null
    }
}
