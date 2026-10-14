package com.newsreader.data

import com.google.gson.annotations.SerializedName

// ── GNews API models ──────────────────────────────────────────────────
data class GNewsResponse(
    @SerializedName("articles") val articles: List<NewsArticle>,
    @SerializedName("totalArticles") val totalArticles: Int
)

data class NewsArticle(
    @SerializedName("title")       val title: String,
    @SerializedName("description") val description: String?,
    @SerializedName("content")     val content: String?,
    @SerializedName("url")         val url: String,
    @SerializedName("image")       val imageUrl: String?,
    @SerializedName("publishedAt") val publishedAt: String,
    @SerializedName("source")      val source: NewsSource
)

data class NewsSource(
    @SerializedName("name") val name: String,
    @SerializedName("url")  val url: String
)

// ── Gemini API models ─────────────────────────────────────────────────
data class GeminiRequest(
    @SerializedName("contents") val contents: List<GeminiContent>
)

data class GeminiContent(
    @SerializedName("parts") val parts: List<GeminiPart>
)

data class GeminiPart(
    @SerializedName("text") val text: String
)

data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<GeminiCandidate>
)

data class GeminiCandidate(
    @SerializedName("content") val content: GeminiContent
)

// ── Sarvam TTS models ─────────────────────────────────────────────────
data class SarvamTTSRequest(
    @SerializedName("text")                val text: String,
    @SerializedName("target_language_code") val targetLanguageCode: String,
    @SerializedName("speaker")             val speaker: String,
    @SerializedName("pace")                val pace: Float,
    @SerializedName("speech_sample_rate")  val speechSampleRate: Int,
    @SerializedName("enable_preprocessing") val enablePreprocessing: Boolean,
    @SerializedName("model")               val model: String
)

data class SarvamTTSResponse(
    @SerializedName("audios") val audios: List<String>  // Base64 encoded audio chunks
)

// ── App UI state ──────────────────────────────────────────────────────
data class NewsItem(
    val id: String,
    val originalTitle: String,
    val simplifiedText: String,
    val sourceUrl: String,
    val imageUrl: String?,
    val sourceName: String,
    val publishedAt: String,
    val topic: NewsTopic,
    val audioBase64: String? = null,    // cached audio (immutable for DiffUtil)
    val isLoading: Boolean = false,
    val isCurrentlyPlaying: Boolean = false
)

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0,
    val totalItems: Int = 0,
    val currentTitle: String = "",
    val currentTopic: NewsTopic = NewsTopic.WORLD,
    val progress: Int = 0,
    val elapsedMs: Long = 0,
    val durationMs: Long = 0
)

// ── Supabase report model (for raw HTTP / Gson parsing) ───────────────
data class SupabaseReport(
    val id: String? = null,
    val topic: String = "",
    val report_text: String = "",
    val articles_json: String = "",
    val batch_time: String = "",
    val audio_base64: String? = null,
    val created_at: String? = null
)

data class ArticleInfo(
    val title: String = "Untitled",
    val source: String = "News"
)
