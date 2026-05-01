package com.stridetech.coreai;

oneway interface ICoreAiCallback {
    void onModelStateChanged(boolean isReady, String activeModelName);
    void onError(String errorMessage);
}
