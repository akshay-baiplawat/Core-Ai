package com.stridetech.coreai;

import android.net.Uri;
import com.stridetech.coreai.ICoreAiCallback;

interface ICoreAiInterface {

    // ── Inference ────────────────────────────────────────────────────────────

    /**
     * Run inference against the active model. Returns immediately with a pending
     * JSON envelope; the real result arrives via ICoreAiCallback#onInferenceResult.
     * Format: {"completion":null,"latency_ms":0,"success":true,"pending":true,"error":null}
     */
    String runInference(String apiKey, String prompt);

    boolean isReady();
    boolean validateApiKey(String apiKey);

    // ── Model lifecycle ───────────────────────────────────────────────────────

    /**
     * Load a model by ID from the local models directory.
     * No-op (fires onModelStateChanged immediately) when the model is already active.
     * Unloads the current model first if a different one is in memory.
     * Result delivered via onModelStateChanged or onError on the supplied callback.
     */
    void loadModel(String apiKey, String modelId, ICoreAiCallback callback);

    /**
     * Unload a model from memory by ID.
     * Result delivered via onModelStateChanged or onError on the supplied callback.
     */
    void unloadModel(String apiKey, String modelId, ICoreAiCallback callback);

    /**
     * Atomically unload a model (if loaded) then delete its file from disk.
     * Acquires the EngineLock to prevent concurrent inference during deletion.
     * Result delivered via onModelStateChanged or onError on the supplied callback.
     */
    void deleteModel(String apiKey, String modelId, ICoreAiCallback callback);

    /** Promote an already-loaded model to the active inference slot. */
    void setActiveModel(String apiKey, String modelId);

    // ── Catalog / transfer ────────────────────────────────────────────────────

    /**
     * Download a model from a URL into the local models directory.
     * Fires onModelTransferComplete immediately (cache hit) if the file already
     * exists. Progress reported via onModelTransferProgress (0–100).
     */
    void downloadCatalogModel(String apiKey, String modelId, String downloadUrl, ICoreAiCallback callback);

    /**
     * Import a model from a content URI (e.g. Storage Access Framework picker).
     * Streams bytes to a .tmp file, then atomically renames on success.
     * engineType hint: "litertlm" | "gguf" — resolves the output file extension.
     */
    void importLocalModel(String apiKey, in Uri uri, String targetModelId, String engineType, ICoreAiCallback callback);

    // ── State queries (JSON strings) ──────────────────────────────────────────

    /** Returns {"modelId":"...","isReady":true} or {"modelId":null,"isReady":false}. */
    String getActiveModel(String apiKey);

    /** Returns {"models":[{"modelId":"...","path":"...","sizeBytes":0}],"error":null}. */
    String getDownloadedModels(String apiKey);

    /** Returns {"models":["modelId1","modelId2"],"error":null}. */
    String getLoadedModels(String apiKey);

    // ── Context management ────────────────────────────────────────────────────

    /**
     * Flush the native KV cache / conversation history for the active model so
     * the next inference starts from a clean slate. Call this after the UI clears
     * its message list to keep the service in sync.
     */
    void resetChatContext(String apiKey, String modelId);

    /**
     * Set the context isolation mode for inference:
     *   "FULL_PROMPT"  — service is stateless; client sends full conversation
     *                    history as the prompt on every call.
     *   "PER_CLIENT"   — service tracks conversation history per caller UID;
     *                    client sends only the latest user turn.
     */
    void setContextMode(String apiKey, String mode);

    /** Returns the current context mode ("FULL_PROMPT" or "PER_CLIENT"). */
    String getContextMode(String apiKey);

    // ── Callback registration ─────────────────────────────────────────────────

    void registerCallback(ICoreAiCallback callback);
    void unregisterCallback(ICoreAiCallback callback);
}
