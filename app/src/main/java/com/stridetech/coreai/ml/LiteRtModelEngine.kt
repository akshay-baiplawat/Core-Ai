package com.stridetech.coreai.ml

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

private const val TAG = "LiteRtModelEngine"
private const val MAX_TOKENS = 1024
private const val TEMPERATURE = 0.8
private const val TOP_K = 40
private const val TOP_P = 0.95

class LiteRtModelEngine(private val cacheDir: String) : ModelEngine {

    @Volatile private var engine: Engine? = null
    @Volatile private var modelName: String? = null
    @Volatile override var lastLoadError: String? = null

    override val isReady: Boolean get() = engine != null

    override fun activeModelName(): String? = modelName

    override suspend fun load(modelPath: String, backend: Backend) {
        withContext(Dispatchers.IO) {
            lastLoadError = null
            close()
            Log.d(TAG, "Loading: $modelPath (backend=${backend::class.java.simpleName})")
            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = MAX_TOKENS,
                    cacheDir = cacheDir
                )
                val newEngine = Engine(config)
                newEngine.initialize()
                engine = newEngine
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
        val e = requireNotNull(engine) { "Engine not loaded. Call load() first." }
        val conversationConfig = ConversationConfig(
            samplerConfig = SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE)
        )
        e.createConversation(conversationConfig).use { conversation ->
            conversation.sendMessageAsync(prompt)
                .catch { ex -> throw ex }
                .toList()
                .joinToString("")
        }
    }

    // LiteRT creates a fresh Conversation per runInference call so there is no
    // persistent session state to clear. Override provided for API symmetry.
    override fun resetContext() {
        Log.i(TAG, "resetContext called for $modelName — no persistent session state to clear")
    }

    override fun close() {
        engine?.close()
        engine = null
        modelName = null
    }
}
