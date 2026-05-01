package com.stridetech.coreai;

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
}
