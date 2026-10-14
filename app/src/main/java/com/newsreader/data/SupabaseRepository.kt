package com.newsreader.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URLEncoder

/**
 * Repository for Supabase news_reports table.
 * Uses raw HTTP via SupabaseClient — matches web server's approach exactly.
 */
class SupabaseRepository {

    private val TAG = "SupabaseRepo"
    private val gson = Gson()

    /**
     * Get latest report for a topic (most recent, no batch filter).
     * Matches web: topic=eq.{T}&order=batch_time.desc&limit=1
     */
    suspend fun getLatestReport(topic: NewsTopic): SupabaseReport? {
        val json = SupabaseClient.select(
            "news_reports",
            "topic=eq.${topic.name}&order=batch_time.desc&limit=1"
        ) ?: return null
        val reports = parseReportList(json)
        val report = reports.firstOrNull()
        if (report != null) {
            Log.d(TAG, "✅ Found report for ${topic.name} (batch: ${report.batch_time})")
        }
        return report
    }

    /** Check if report exists for topic + batchTime */
    suspend fun checkExisting(topic: NewsTopic, batchTime: String): SupabaseReport? {
        val encoded = URLEncoder.encode(batchTime, "UTF-8")
        val json = SupabaseClient.select(
            "news_reports",
            "topic=eq.${topic.name}&batch_time=eq.$encoded&limit=1"
        ) ?: return null
        return parseReportList(json).firstOrNull()
    }

    /** Save a new report — returns the report ID for audio caching */
    suspend fun saveReport(
        topic: NewsTopic, reportText: String,
        articlesJson: String, batchTime: String
    ): String? {
        val row = mapOf(
            "topic" to topic.name,
            "report_text" to reportText,
            "articles_json" to articlesJson,
            "batch_time" to batchTime
        )
        val response = SupabaseClient.insert("news_reports", gson.toJson(row))
        if (response != null) {
            try {
                val reports = parseReportList(response)
                return reports.firstOrNull()?.id
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse insert response for ID: ${e.message}")
            }
        }
        return null
    }

    /** Cache TTS audio back to Supabase */
    suspend fun cacheAudio(reportId: String, audioBase64: String): Boolean {
        return SupabaseClient.update(
            "news_reports",
            "id=eq.$reportId",
            gson.toJson(mapOf("audio_base64" to audioBase64))
        )
    }

    /**
     * Delete all news reports older than 48 hours.
     * Uses PostgREST filter: created_at < (now - 48h) in ISO-8601 format.
     */
    suspend fun deleteOldReports(): Boolean {
        val cutoff = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
            .minusHours(48)
            .format(java.time.format.DateTimeFormatter.ISO_INSTANT)
        val encoded = URLEncoder.encode(cutoff, "UTF-8")
        val result = SupabaseClient.delete("news_reports", "created_at=lt.$encoded")
        if (result) {
            Log.d(TAG, "✅ Deleted old reports (before $cutoff)")
        }
        return result
    }

    fun parseSegments(reportText: String): List<String> =
        reportText.split("---NEXT---").map { it.trim() }.filter { it.isNotEmpty() }

    fun parseArticlesInfo(articlesJson: String): List<ArticleInfo> = try {
        val type = object : TypeToken<List<ArticleInfo>>() {}.type
        gson.fromJson(articlesJson, type) ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "parseArticlesInfo: ${e.message}")
        emptyList()
    }

    private fun parseReportList(json: String): List<SupabaseReport> = try {
        val type = object : TypeToken<List<SupabaseReport>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "parseReportList: ${e.message}")
        emptyList()
    }
}
