package com.stridetech.coreai.hub

import com.google.gson.annotations.SerializedName

data class ModelCatalogItem(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("download_url") val downloadUrl: String,
    @SerializedName("file_size") val fileSize: Long,
    @SerializedName("engine_type") val engineType: EngineType
)

enum class EngineType {
    @SerializedName("litertlm") LITERTLM,
    @SerializedName("bin") BIN
}
