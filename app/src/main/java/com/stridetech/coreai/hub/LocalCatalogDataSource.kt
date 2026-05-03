package com.stridetech.coreai.hub

import android.app.Application
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

private const val CATALOG_ASSET = "catalog.json"

@Singleton
class LocalCatalogDataSource @Inject constructor(
    private val application: Application,
    private val gson: Gson
) {
    fun load(): List<ModelCatalogItem> {
        val json = application.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        val type = object : TypeToken<List<ModelCatalogItem>>() {}.type
        return gson.fromJson(json, type)
    }
}
