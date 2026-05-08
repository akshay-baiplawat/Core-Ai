package com.stridetech.coreai.ml

import android.content.Context

/**
 * Selects and instantiates the correct [ModelEngine] implementation based on the model
 * file extension. Three backends are supported:
 *   - LiteRT (.litertlm) — Google's on-device inference runtime, GPU-accelerated via LiteRtLm.
 *   - MediaPipe (.bin / .task) — Google MediaPipe GenAI backend.
 *   - GGUF (.gguf) — llama.cpp-based backend for quantized GGUF models (JNI).
 */
object ModelEngineFactory {

    private val LITERTLM_EXTENSIONS = setOf("litertlm")
    private val MEDIAPIPE_EXTENSIONS = setOf("bin", "task")
    private val GGUF_EXTENSIONS = setOf("gguf")

    /**
     * Create a [ModelEngine] for the given [modelPath], dispatching on file extension:
     *   .litertlm → [LiteRtModelEngine] (uses [context].cacheDir for temp files)
     *   .bin / .task → [MediaPipeModelEngine]
     *   .gguf → [GgufModelEngine] (llama.cpp JNI)
     *
     * @throws IllegalArgumentException if the file extension is not in the supported set.
     */
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
