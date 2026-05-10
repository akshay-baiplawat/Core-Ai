package com.stridetech.coreai.hub

import retrofit2.http.GET

interface ModelApiService {
    @GET("models")
    suspend fun fetchCatalog(): List<ModelCatalogItem>
}
