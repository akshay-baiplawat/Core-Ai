package com.stridetech.coreai;

import com.stridetech.coreai.ICoreAiCallback;

/**
 * Core AI Inter-Process Communication Interface
 *
 * Defines the AIDL contract for third-party applications to communicate
 * with the Core AI inference engine for on-device LLM inference.
 */
interface ICoreAiInterface {
    /**
     * Run inference with the currently active model.
     *
     * @param apiKey API key for authentication
     * @param prompt Input text prompt for the LLM
     * @return JSON response containing completion text and metadata
     *         Format: {"completion": "...", "latency_ms": 1234, "success": true, "error": null}
     *         On error: {"completion": null, "latency_ms": 0, "success": false, "error": "error message"}
     */
    String runInference(String apiKey, String prompt);

    /**
     * Check if the inference engine is ready (model loaded).
     *
     * @return true if model is loaded and ready, false otherwise
     */
    boolean isReady();

    /**
     * Get the name of the currently active model.
     *
     * @return Model name (e.g. "Gemma 2B") or null if no model loaded
     */
    String getActiveModelName();

    /**
     * Validate an API key without running inference.
     *
     * @param apiKey API key to validate
     * @return true if valid, false otherwise
     */
    boolean validateApiKey(String apiKey);

    /** Reload the model from disk. Call after importing a new model file. */
    void reloadModel();

    /**
     * Load a specific model into memory by absolute path.
     * Multiple models can be loaded simultaneously; use setActiveModel to switch inference target.
     *
     * @param modelPath absolute path to the model file
     */
    void loadModel(String modelPath);

    /**
     * Unload a specific model from memory to free RAM.
     * If the unloaded model was active, inference will return an error until a new active model is set.
     *
     * @param modelPath absolute path to the model file
     */
    void unloadModel(String modelPath);

    /**
     * Set which loaded model to use for inference.
     * The model must already be loaded via loadModel() before calling this.
     *
     * @param modelPath absolute path of the model to make active
     */
    void setActiveModel(String modelPath);

    /**
     * Get the names of all models currently loaded in memory.
     *
     * @return comma-separated list of loaded model names, or empty string if none
     */
    String getLoadedModelNames();

    /** Register a callback to receive model state change notifications. */
    void registerCallback(ICoreAiCallback callback);

    /** Unregister a previously registered callback. */
    void unregisterCallback(ICoreAiCallback callback);
}
