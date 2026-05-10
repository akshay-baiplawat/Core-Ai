package com.stridetech.coreai;

/**
 * One-way (fire-and-forget) callback interface delivered from CoreAiService to bound clients.
 * All methods are dispatched on a Binder thread-pool thread — post to the main thread
 * before touching UI state.
 */
oneway interface ICoreAiCallback {

    /** Fires when the active model changes or its ready state transitions. */
    void onModelStateChanged(boolean isReady, String activeModelName);

    /** Fires when an operation fails; errorMessage contains a human-readable description. */
    void onError(String errorMessage);

    /**
     * Fires once inference completes with the full result JSON:
     * {"completion":"...","latency_ms":1234,"success":true,"pending":false,"error":null}
     */
    void onInferenceResult(String resultJson);

    /**
     * Fires for each streamed token during inference.
     * Append tokens to a buffer for live-streaming UI updates.
     */
    void onInferenceToken(String token);

    /**
     * Fires after the last token when inference finishes.
     * latencyMs is the total wall-clock time for the full inference run.
     */
    void onInferenceComplete(long latencyMs);

    /** Progress update during a model download or import; percent is in range 0–100. */
    void onModelTransferProgress(String modelId, int percent);

    /** Fires when a download or import succeeds; filePath is the absolute path on device. */
    void onModelTransferComplete(String modelId, String filePath);

    /** Fires when a download or import fails; errorMessage contains the cause. */
    void onModelTransferError(String modelId, String errorMessage);
}
