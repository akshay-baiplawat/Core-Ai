package com.stridetech.coreai.ml

import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ModelEngine {
    val isReady: Boolean
    val lastLoadError: String?

    fun activeModelName(): String?

    suspend fun load(modelPath: String, backend: Backend)

    suspend fun runInference(prompt: String): String

    // Default: collect all tokens and emit as a single item. GGUF overrides this.
    suspend fun runInferenceStream(prompt: String): Flow<String> =
        flow { emit(runInference(prompt)) }

    /**
     * Flush any in-memory conversation state (KV cache, token history) so the
     * next inference starts from a clean slate. Engines with no persistent
     * context may leave this as a no-op.
     */
    fun resetContext() {}

    fun close()
}
