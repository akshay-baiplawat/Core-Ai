package com.stridetech.coreai.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LlmEngine"

class LlmEngine(private val context: Context) {

    private val engines = ConcurrentHashMap<String, ModelEngine>()
    private val mapMutex = Mutex()
    private val isShuttingDown = AtomicBoolean(false)

    @Volatile private var activeKey: String? = null

    val isReady: Boolean get() = activeEngine?.isReady == true
    val lastLoadError: String? get() = activeEngine?.lastLoadError

    private val activeEngine: ModelEngine? get() = activeKey?.let { engines[it] }

    fun activeModelName(): String? = activeKey

    fun loadedModelNames(): List<String> = engines.keys.toList()

    suspend fun load(modelPath: String, backend: Backend) = mapMutex.withLock {
        val key = modelPath.substringAfterLast('/').substringBeforeLast('.')
        val newEngine = ModelEngineFactory.create(modelPath, context)
        try {
            newEngine.load(modelPath, backend)
        } catch (ex: Exception) {
            newEngine.close()
            throw ex
        }
        engines.put(key, newEngine)?.close()
        if (activeKey == null) activeKey = key
    }

    suspend fun unload(modelPath: String) = mapMutex.withLock {
        val key = modelPath.substringAfterLast('/').substringBeforeLast('.')
        engines.remove(key)?.close()
        if (activeKey == key) activeKey = engines.keys.firstOrNull()
        Log.i(TAG, "Unloaded model: $key. Active: $activeKey")
    }

    suspend fun setActive(modelPath: String) = mapMutex.withLock {
        val key = modelPath.substringAfterLast('/').substringBeforeLast('.')
        require(engines.containsKey(key)) { "Model '$key' is not loaded. Call load() first." }
        activeKey = key
        Log.i(TAG, "Active model set to: $key")
    }

    suspend fun runInference(prompt: String): String {
        check(!isShuttingDown.get()) { "Engine is shutting down." }
        val engine = requireNotNull(activeEngine) { "No active model. Load and set a model first." }
        return engine.runInference(prompt)
    }

    suspend fun runInferenceStream(prompt: String): Flow<String> {
        check(!isShuttingDown.get()) { "Engine is shutting down." }
        val engine = requireNotNull(activeEngine) { "No active model. Load and set a model first." }
        return engine.runInferenceStream(prompt)
    }

    suspend fun close() = mapMutex.withLock {
        isShuttingDown.set(true)
        engines.values.forEach { it.close() }
        engines.clear()
        activeKey = null
    }
}
