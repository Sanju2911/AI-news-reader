package com.newsreader.data

import retrofit2.Response
import retrofit2.http.*

// ── GNews API ─────────────────────────────────────────────────────────
interface GNewsApi {
    @GET("search")
    suspend fun searchNews(
        @Query("q")        query: String,
        @Query("lang")     lang: String = "en",
        @Query("country")  country: String = "in",
        @Query("max")      max: Int = 10,
        @Query("apikey")   apiKey: String = ApiConfig.GNEWS_API_KEY
    ): Response<GNewsResponse>

    @GET("top-headlines")
    suspend fun getTopHeadlines(
        @Query("topic")    topic: String,
        @Query("lang")     lang: String = "en",
        @Query("country")  country: String = "in",
        @Query("max")      max: Int = 10,
        @Query("apikey")   apiKey: String = ApiConfig.GNEWS_API_KEY
    ): Response<GNewsResponse>
}

// ── Gemini API ────────────────────────────────────────────────────────
interface GeminiApi {
    @POST("v1beta/models/gemini-2.0-flash:generateContent")
    suspend fun generateContent(
        @Query("key")  apiKey: String = ApiConfig.GEMINI_API_KEY,
        @Body         request: GeminiRequest
    ): Response<GeminiResponse>
}

// ── Sarvam TTS API ────────────────────────────────────────────────────
interface SarvamApi {
    @POST("text-to-speech")
    suspend fun textToSpeech(
        @Header("api-subscription-key") apiKey: String = ApiConfig.sarvamApiKey,
        @Body request: SarvamTTSRequest
    ): Response<SarvamTTSResponse>
}
