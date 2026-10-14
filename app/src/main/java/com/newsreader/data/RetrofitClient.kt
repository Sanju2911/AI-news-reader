package com.newsreader.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // Use HEADERS level to avoid logging massive base64 audio bodies
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS
    }

    // Shared client for GNews and Gemini (text-only, moderate timeouts)
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Separate client for Sarvam TTS (larger payloads, longer timeouts)
    private val ttsHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val gNewsApi: GNewsApi = Retrofit.Builder()
        .baseUrl(ApiConfig.GNEWS_BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GNewsApi::class.java)

    val geminiApi: GeminiApi = Retrofit.Builder()
        .baseUrl(ApiConfig.GEMINI_BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GeminiApi::class.java)

    val sarvamApi: SarvamApi = Retrofit.Builder()
        .baseUrl(ApiConfig.SARVAM_BASE_URL)
        .client(ttsHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SarvamApi::class.java)
}
