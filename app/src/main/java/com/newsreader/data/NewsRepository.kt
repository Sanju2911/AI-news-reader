package com.newsreader.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class NewsRepository {

    private val TAG = "NewsRepository"
    var googleTTSHelper: GoogleTTSHelper? = null

    // ── 4-hour GNews cache ────────────────────────────────────────────
    private data class CachedNews(
        val articles: List<NewsArticle>,
        val items: List<NewsItem>,
        val timestamp: Long
    )

    private val newsCache = mutableMapOf<NewsTopic, CachedNews>()

    companion object {
        const val CACHE_DURATION_MS = 4 * 60 * 60 * 1000L  // 4 hours in milliseconds
    }

    private fun isCacheValid(topic: NewsTopic): Boolean {
        val cached = newsCache[topic] ?: return false
        return (System.currentTimeMillis() - cached.timestamp) < CACHE_DURATION_MS
    }

    // ── Step 1: Fetch news articles (cached for 4 hours) ──────────────
    suspend fun fetchNews(topic: NewsTopic): Result<List<NewsItem>> = withContext(Dispatchers.IO) {
        try {
            // Return cached data if less than 4 hours old
            if (isCacheValid(topic)) {
                val cached = newsCache[topic]!!
                Log.d(TAG, "Using cached news for ${topic.name} (${getCacheAgeMinutes(topic)} min old)")
                return@withContext Result.success(cached.items)
            }

            Log.d(TAG, "Fetching fresh news for ${topic.name} from GNews API")
            val gnewsTopic = mapTopicToGNews(topic)
            val response = RetrofitClient.gNewsApi.getTopHeadlines(gnewsTopic)

            if (response.isSuccessful) {
                val articles = response.body()?.articles ?: emptyList()
                val newsItems = articles.map { article ->
                    NewsItem(
                        id              = UUID.randomUUID().toString(),
                        originalTitle   = article.title,
                        simplifiedText  = "",
                        sourceUrl       = article.url,
                        imageUrl        = article.imageUrl,
                        sourceName      = article.source.name,
                        publishedAt     = article.publishedAt,
                        topic           = topic
                    )
                }

                // Cache the results with timestamp
                newsCache[topic] = CachedNews(
                    articles  = articles,
                    items     = newsItems,
                    timestamp = System.currentTimeMillis()
                )

                Result.success(newsItems)
            } else {
                // If API fails but we have stale cache, use it
                val staleCache = newsCache[topic]
                if (staleCache != null) {
                    Log.w(TAG, "GNews API failed (${response.code()}), using stale cache")
                    Result.success(staleCache.items)
                } else {
                    Result.failure(Exception("News fetch failed: ${response.code()}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchNews error", e)
            // If network fails but we have stale cache, use it
            val staleCache = newsCache[topic]
            if (staleCache != null) {
                Log.w(TAG, "Network error, using stale cache for ${topic.name}")
                Result.success(staleCache.items)
            } else {
                Result.failure(e)
            }
        }
    }

    /** Get cached articles for building the Gemini report */
    fun getCachedArticles(topic: NewsTopic): List<NewsArticle> {
        return newsCache[topic]?.articles ?: emptyList()
    }

    /** Clear cache for a specific topic (for force refresh) */
    fun clearCache(topic: NewsTopic) {
        newsCache.remove(topic)
    }

    private fun getCacheAgeMinutes(topic: NewsTopic): Long {
        val cached = newsCache[topic] ?: return -1
        return (System.currentTimeMillis() - cached.timestamp) / 60000
    }

    // ── Step 2: Generate full news report with Gemini ─────────────────
    // Sends ALL articles at once → Gemini returns a news-anchor-style
    // report that explains each story one after another.
    suspend fun generateNewsReport(articles: List<NewsArticle>, topic: NewsTopic): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                if (articles.isEmpty()) {
                    return@withContext Result.failure(Exception("No articles to summarize"))
                }

                // Build a numbered list of all articles for Gemini
                val articleList = articles.mapIndexed { i, article ->
                    buildString {
                        append("Article ${i + 1}:\n")
                        append("  Title: ${article.title}\n")
                        article.description?.let { append("  Description: $it\n") }
                        article.content?.take(400)?.let { append("  Content: $it\n") }
                        append("  Source: ${article.source.name}\n")
                    }
                }.joinToString("\n")

                val prompt = """
You are a professional news anchor presenting a news bulletin on the topic "${topic.displayName}".
Read through ALL of the following ${articles.size} news articles and create a complete news report.

For EACH article, write a clear 2-3 sentence explanation as if you are reading it aloud on air.
Separate each story with the marker "---NEXT---" on its own line.

Rules:
- Use conversational, easy-to-understand English suitable for text-to-speech
- Do NOT use bullet points, numbering, or markdown
- Make smooth transitions between stories (e.g. "Moving on to our next story...", "In other news...", "Meanwhile...")
- Start the report with a brief greeting like "Here are today's top ${topic.displayName} headlines."
- End with a brief sign-off like "That's all for now. Stay informed!"
- Each story segment should be 2-3 sentences only

Here are the articles:

$articleList

News report (separate each story with ---NEXT---):
                """.trimIndent()

                val request = GeminiRequest(
                    contents = listOf(
                        GeminiContent(parts = listOf(GeminiPart(text = prompt)))
                    )
                )

                val response = RetrofitClient.geminiApi.generateContent(request = request)

                if (response.isSuccessful) {
                    val fullReport = response.body()
                        ?.candidates?.firstOrNull()
                        ?.content?.parts?.firstOrNull()
                        ?.text ?: return@withContext Result.failure(Exception("Empty Gemini response"))

                    // Split report into individual story segments
                    val segments = fullReport
                        .split("---NEXT---")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    Log.d(TAG, "Gemini generated ${segments.size} report segments for ${articles.size} articles")
                    Result.success(segments)
                } else {
                    Log.e(TAG, "Gemini API failed: ${response.code()} ${response.message()}")
                    // Fallback: use titles as plain segments
                    val fallback = articles.map { "${it.title}. ${it.description ?: "No details available."}" }
                    Result.success(fallback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "generateNewsReport error", e)
                // Fallback: use titles
                val fallback = articles.map { "${it.title}. ${it.description ?: "No details available."}" }
                Result.success(fallback)
            }
        }

    // ── Step 3: Convert to speech with Sarvam AI (with retry on 429) ──
    suspend fun convertToSpeechSarvam(text: String): Result<String> = withContext(Dispatchers.IO) {
        val maxRetries = 5
        var lastException: Exception? = null

        for (attempt in 0 until maxRetries) {
            try {
                if (attempt > 0) {
                    val delayMs = 2000L * (1 shl (attempt - 1)) // 2s, 4s, 8s, 16s
                    Log.d(TAG, "TTS retry #$attempt after ${delayMs}ms delay")
                    delay(delayMs)
                }

                val request = SarvamTTSRequest(
                    text                = text,
                    targetLanguageCode  = ApiConfig.sarvamLanguage,
                    speaker             = ApiConfig.sarvamSpeaker,
                    pace                = ApiConfig.SARVAM_PACE,
                    speechSampleRate    = ApiConfig.SARVAM_SAMPLE_RATE,
                    enablePreprocessing = true,
                    model               = ApiConfig.SARVAM_MODEL
                )

                val response = RetrofitClient.sarvamApi.textToSpeech(request = request)

                if (response.isSuccessful) {
                    val audioChunks = response.body()?.audios ?: emptyList()
                    val combinedBase64 = audioChunks.joinToString("")
                    return@withContext Result.success(combinedBase64)
                } else if (response.code() == 429) {
                    Log.w(TAG, "TTS rate limited (429), attempt ${attempt + 1}/$maxRetries")
                    lastException = Exception("TTS rate limited: 429")
                    continue
                } else {
                    return@withContext Result.failure(Exception("TTS failed: ${response.code()} ${response.message()}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "convertToSpeech error (attempt ${attempt + 1})", e)
                lastException = e
                if (attempt < maxRetries - 1) continue
            }
        }
        Result.failure(lastException ?: Exception("TTS failed after $maxRetries retries"))
    }

    // ── Save base64 audio to temp file for playback ───────────────────
    fun saveAudioToFile(base64Audio: String, cacheDir: File): File {
        val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
        val audioFile  = File(cacheDir, "news_audio_${System.currentTimeMillis()}.wav")
        FileOutputStream(audioFile).use { it.write(audioBytes) }
        return audioFile
    }

    // ── Convert text using Google built-in TTS ────────────────────────
    suspend fun convertToSpeechGoogle(text: String, cacheDir: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val helper = googleTTSHelper ?: return@withContext Result.failure(Exception("Google TTS not initialized"))
            val outputFile = File(cacheDir, "gtts_${System.currentTimeMillis()}.wav")
            val result = helper.synthesizeToFile(text, outputFile)
            if (result != null && result.exists() && result.length() > 0) {
                // Convert WAV file to base64 so it fits the existing pipeline
                val bytes = result.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                Log.d(TAG, "Google TTS: ${text.take(30)}... → ${bytes.size} bytes")
                Result.success(base64)
            } else {
                Result.failure(Exception("Google TTS synthesis failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "convertToSpeechGoogle error", e)
            Result.failure(e)
        }
    }

    // ── Unified TTS — routes to the active engine ─────────────────────
    suspend fun convertToSpeech(text: String, cacheDir: File? = null): Result<String> {
        return when (ApiConfig.ttsEngine) {
            TTSEngine.GOOGLE -> {
                val dir = cacheDir ?: return Result.failure(Exception("cacheDir required for Google TTS"))
                convertToSpeechGoogle(text, dir)
            }
            TTSEngine.SARVAM -> convertToSpeechSarvam(text)
        }
    }

    // ── Map app topic to GNews topic slug ─────────────────────────────
    private fun mapTopicToGNews(topic: NewsTopic): String = when (topic) {
        NewsTopic.WORLD         -> "world"
        NewsTopic.TECHNOLOGY    -> "technology"
        NewsTopic.BUSINESS      -> "business"
        NewsTopic.SPORTS        -> "sports"
        NewsTopic.HEALTH        -> "health"
        NewsTopic.SCIENCE       -> "science"
        NewsTopic.ENTERTAINMENT -> "entertainment"
        NewsTopic.INDIA         -> "nation"
    }
}
