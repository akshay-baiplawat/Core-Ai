package com.stridetech.coreai.ml

import com.google.ai.edge.litertlm.Backend

interface ModelEngine {
    val isReady: Boolean
    val lastLoadError: String?

    fun activeModelName(): String?

    suspend fun load(modelPath: String, backend: Backend)

    suspend fun runInference(prompt: String): String

    fun close()
}
