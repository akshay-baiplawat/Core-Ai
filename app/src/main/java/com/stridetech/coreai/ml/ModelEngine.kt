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

    fun close()
}
