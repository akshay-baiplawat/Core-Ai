package com.stridetech.coreai.hub

import java.io.File

sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data class Progress(val percent: Int) : DownloadStatus
    data class Success(val file: File) : DownloadStatus
    data class Error(val message: String) : DownloadStatus
}
