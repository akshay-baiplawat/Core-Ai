package com.stridetech.coreai;

oneway interface ICoreAiCallback {
    void onModelStateChanged(boolean isReady, String activeModelName);
    void onError(String errorMessage);
    void onInferenceResult(String resultJson);
    void onInferenceToken(String token);
    void onInferenceComplete(long latencyMs);

    void onModelTransferProgress(String modelId, int percent);
    void onModelTransferComplete(String modelId, String filePath);
    void onModelTransferError(String modelId, String errorMessage);
}
