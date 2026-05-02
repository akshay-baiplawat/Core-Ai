package com.stridetech.coreai.hub

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import javax.inject.Inject

private const val MODELS_DIR = "models"

class ModelDownloader @Inject constructor(
    private val application: Application,
    private val okHttpClient: OkHttpClient
) {
    fun download(item: ModelCatalogItem): Flow<DownloadStatus> = flow {
        emit(DownloadStatus.Progress(0))

        val modelsDir = requireNotNull(application.getExternalFilesDir(MODELS_DIR)) {
            "External storage unavailable"
        }
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val finalName = "${item.id}.${item.engineType.extension}"
        val tmpFile = File(modelsDir, "$finalName.tmp")
        val finalFile = File(modelsDir, finalName)

        val request = Request.Builder().url(item.downloadUrl).build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                tmpFile.delete()
                emit(DownloadStatus.Error("HTTP ${response.code}: ${response.message}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadStatus.Error("Empty response body"))
                return@flow
            }

            val contentLength = if (item.fileSize > 0) item.fileSize else body.contentLength()

            body.source().use { source ->
                tmpFile.sink().buffer().use { sink ->
                    var bytesRead = 0L
                    val bufferSize = 8 * 1024L
                    var lastReportedPercent = -1

                    while (true) {
                        val read = source.read(sink.buffer, bufferSize)
                        if (read == -1L) break
                        sink.emitCompleteSegments()
                        bytesRead += read

                        if (contentLength > 0) {
                            val percent = ((bytesRead * 100) / contentLength).toInt().coerceIn(0, 99)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                emit(DownloadStatus.Progress(percent))
                            }
                        }
                    }
                }
            }
        }

        if (finalFile.exists()) finalFile.delete()
        if (!tmpFile.renameTo(finalFile)) {
            throw java.io.IOException("Failed to rename ${tmpFile.path} to ${finalFile.path}")
        }
        emit(DownloadStatus.Success(finalFile))
    }.flowOn(Dispatchers.IO)
}

private val EngineType.extension: String
    get() = when (this) {
        EngineType.LITERTLM -> "litertlm"
        EngineType.BIN -> "bin"
    }
