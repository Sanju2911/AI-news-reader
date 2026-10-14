package com.newsreader.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.newsreader.data.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker that:
 * 1. Fetches news from GNews API
 * 2. Generates report with Gemini API
 * 3. Saves report to Supabase
 *
 * Scheduled to run every 4 hours starting at 12:00 AM
 */
class NewsRefreshWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val TAG = "NewsRefreshWorker"
    private val supabaseRepo = SupabaseRepository()
    private val newsRepo = NewsRepository()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting scheduled news refresh...")

            // Get current batch time (rounded to nearest 4-hour slot)
            val batchTime = getCurrentBatchTime()
            Log.d(TAG, "Batch time: $batchTime")

            // Process each topic
            NewsTopic.entries.forEach { topic ->
                try {
                    processTopicNews(topic, batchTime)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process topic ${topic.name}", e)
                    // Continue with other topics even if one fails
                }
            }

            Log.d(TAG, "News refresh completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "News refresh failed", e)
            Result.retry()
        }
    }

    private suspend fun processTopicNews(topic: NewsTopic, batchTime: String) {
        Log.d(TAG, "Processing topic: ${topic.name}")

        // 1. Check if we already have a report for this batch time
        val existing = supabaseRepo.checkExisting(topic, batchTime)
        if (existing != null) {
            Log.d(TAG, "Report already exists for ${topic.name} at $batchTime")
            return
        }

        // 2. Fetch news from GNews
        val gnewsTopic = mapTopicToGNews(topic)
        val response = RetrofitClient.gNewsApi.getTopHeadlines(gnewsTopic)

        if (!response.isSuccessful) {
            Log.e(TAG, "GNews API failed for ${topic.name}: ${response.code()}")
            return
        }

        val articles = response.body()?.articles ?: emptyList()
        if (articles.isEmpty()) {
            Log.w(TAG, "No articles found for ${topic.name}")
            return
        }

        Log.d(TAG, "Fetched ${articles.size} articles for ${topic.name}")

        // 3. Generate Gemini report (with fallback if Gemini fails)
        var reportText = generateGeminiReport(articles, topic)
        if (reportText.isNullOrEmpty()) {
            Log.w(TAG, "Gemini failed for ${topic.name}, using fallback article summaries")
            reportText = articles.joinToString("\n---NEXT---\n") { article ->
                "${article.title}. ${article.description ?: "No further details available."}"
            }
        }

        // 4. Prepare articles JSON summary
        val articlesJson = Gson().toJson(
            articles.map { mapOf("title" to it.title, "source" to it.source.name) }
        )

        // 5. Save to Supabase via raw HTTP
        val reportId = supabaseRepo.saveReport(topic, reportText, articlesJson, batchTime)
        if (reportId != null) {
            Log.d(TAG, "Saved report for ${topic.name} to Supabase (id: $reportId)")

            // 6. Pre-generate TTS for all segments and cache to Supabase
            generateAndCacheTTS(reportId, reportText)
        } else {
            Log.e(TAG, "Failed to save report for ${topic.name}")
        }
    }

    /**
     * Generate TTS audio for every segment in the report and cache to Supabase.
     * This runs in the background worker so audio is ready when user opens the app.
     */
    private suspend fun generateAndCacheTTS(reportId: String, reportText: String) {
        val segments = supabaseRepo.parseSegments(reportText)
        if (segments.isEmpty()) return

        Log.d(TAG, "🎤 Pre-generating TTS for ${segments.size} segments...")
        val audios = mutableListOf<String>()

        for ((i, segment) in segments.withIndex()) {
            val result = newsRepo.convertToSpeech(segment)
            val audio = result.getOrNull()
            audios.add(audio ?: "")
            if (audio != null) {
                Log.d(TAG, "  ✅ TTS segment ${i + 1}/${segments.size} done")
            } else {
                Log.w(TAG, "  ⚠ TTS segment ${i + 1}/${segments.size} failed")
            }
        }

        // Only cache if we got at least some audio
        if (audios.any { it.isNotEmpty() }) {
            val cacheJson = Gson().toJson(mapOf(
                "lang" to ApiConfig.sarvamLanguage,
                "speaker" to ApiConfig.sarvamSpeaker,
                "audios" to audios
            ))
            val sizeMB = cacheJson.length / (1024.0 * 1024.0)
            if (sizeMB > 5.0) {
                Log.w(TAG, "Audio cache too large (${String.format("%.1f", sizeMB)}MB) — skipping Supabase cache")
                return
            }
            val cached = supabaseRepo.cacheAudio(reportId, cacheJson)
            if (cached) {
                Log.d(TAG, "Cached TTS audio to Supabase (${audios.count { it.isNotEmpty() }}/${segments.size} segments, ${String.format("%.1f", sizeMB)}MB)")
            } else {
                Log.w(TAG, "Failed to cache TTS audio to Supabase (${String.format("%.1f", sizeMB)}MB)")
            }
        }
    }

    private suspend fun generateGeminiReport(articles: List<NewsArticle>, topic: NewsTopic): String? {
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

        return try {
            val response = RetrofitClient.geminiApi.generateContent(request = request)
            if (response.isSuccessful) {
                response.body()
                    ?.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()
                    ?.text
            } else {
                Log.e(TAG, "Gemini API failed: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API error", e)
            null
        }
    }

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

    companion object {
        const val WORK_NAME = "news_refresh_work"

        /**
         * Calculate the current 4-hour batch time slot.
         * Slots: 00:00, 04:00, 08:00, 12:00, 16:00, 20:00
         */
        fun getCurrentBatchTime(): String {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val batchHour = (hour / 4) * 4 // Round down to nearest 4-hour slot

            calendar.set(Calendar.HOUR_OF_DAY, batchHour)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(calendar.time)
        }

        /**
         * Calculate delay until next 4-hour slot starting from midnight
         */
        fun calculateInitialDelay(): Long {
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)

            // Next 4-hour slot
            val nextSlotHour = ((currentHour / 4) + 1) * 4
            val hoursUntilNext = if (nextSlotHour >= 24) {
                24 - currentHour + (nextSlotHour - 24)
            } else {
                nextSlotHour - currentHour
            }

            val minutesDelay = (hoursUntilNext * 60) - currentMinute
            return minutesDelay.toLong()
        }

        /**
         * Schedule the periodic news refresh work
         */
        fun schedulePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val initialDelay = calculateInitialDelay()
            Log.d("NewsRefreshWorker", "Scheduling work with initial delay: $initialDelay minutes")

            val workRequest = PeriodicWorkRequestBuilder<NewsRefreshWorker>(
                4, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInitialDelay(initialDelay, TimeUnit.MINUTES)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        /**
         * Trigger immediate refresh (for testing or manual refresh)
         */
        fun triggerImmediateRefresh(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<NewsRefreshWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
