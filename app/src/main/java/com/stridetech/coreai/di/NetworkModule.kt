package com.stridetech.coreai.di

import com.stridetech.coreai.BuildConfig
import com.stridetech.coreai.hub.ModelApiService
import com.stridetech.coreai.security.ApiKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private const val CONNECT_TIMEOUT_S = 30L
private const val READ_TIMEOUT_S = 120L
private const val HF_HOST = "huggingface.co"

internal class HuggingFaceInterceptor(
    private val apiKeyManager: ApiKeyManager
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = apiKeyManager.getHuggingFaceToken()
        return if (original.url.host == HF_HOST && token != null) {
            chain.proceed(
                original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            )
        } else {
            chain.proceed(original)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    @Provides
    @Singleton
    fun provideOkHttpClient(apiKeyManager: ApiKeyManager): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(HuggingFaceInterceptor(apiKeyManager))
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.CATALOG_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideModelApiService(retrofit: Retrofit): ModelApiService =
        retrofit.create(ModelApiService::class.java)
}
