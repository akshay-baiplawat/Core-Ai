package com.stridetech.coreai.ml

import android.content.Context

object ModelEngineFactory {

    private val LITERTLM_EXTENSIONS = setOf("litertlm")
    private val MEDIAPIPE_EXTENSIONS = setOf("bin", "task")
    private val GGUF_EXTENSIONS = setOf("gguf")

    fun create(modelPath: String, context: Context): ModelEngine {
        val ext = modelPath.substringAfterLast('.', "").lowercase()
        return when {
            ext in LITERTLM_EXTENSIONS -> LiteRtModelEngine(context.cacheDir.path)
            ext in MEDIAPIPE_EXTENSIONS -> MediaPipeModelEngine(context)
            ext in GGUF_EXTENSIONS -> GgufModelEngine()
            else -> throw IllegalArgumentException(
                "Unsupported model format '.$ext'. Please use a supported model file."
            )
        }
    }
}
