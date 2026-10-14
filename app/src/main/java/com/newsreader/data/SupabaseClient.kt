package com.newsreader.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Raw HTTP client for Supabase PostgREST — matches web server's approach.
 * Bypasses the Supabase SDK for direct, reliable HTTP access.
 */
object SupabaseClient {

    private const val TAG = "SupabaseClient"
    const val SUPABASE_URL = "https://your-project.supabase.co"
    const val SUPABASE_KEY = ""

    private val JSON_TYPE = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** PostgREST SELECT: GET /rest/v1/{table}?{query} */
    suspend fun select(table: String, query: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$table?$query")
                .header("apikey", SUPABASE_KEY)
                .header("Authorization", "Bearer $SUPABASE_KEY")
                .get()
                .build()
            val response = http.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                Log.e(TAG, "SELECT ${response.code}: ${body?.take(300)}")
                return@withContext null
            }
            body
        } catch (e: Exception) {
            Log.e(TAG, "SELECT error: ${e.message}")
            null
        }
    }

    /** PostgREST INSERT: POST /rest/v1/{table} */
    suspend fun insert(table: String, jsonBody: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$table")
                .header("apikey", SUPABASE_KEY)
                .header("Authorization", "Bearer $SUPABASE_KEY")
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .post(jsonBody.toRequestBody(JSON_TYPE))
                .build()
            val response = http.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                Log.e(TAG, "INSERT ${response.code}: ${body?.take(300)}")
                return@withContext null
            }
            Log.d(TAG, "✅ INSERT into $table")
            body
        } catch (e: Exception) {
            Log.e(TAG, "INSERT error: ${e.message}")
            null
        }
    }

    /** PostgREST UPDATE: PATCH /rest/v1/{table}?{matchQuery} */
    suspend fun update(table: String, matchQuery: String, jsonBody: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$table?$matchQuery")
                .header("apikey", SUPABASE_KEY)
                .header("Authorization", "Bearer $SUPABASE_KEY")
                .header("Content-Type", "application/json")
                .patch(jsonBody.toRequestBody(JSON_TYPE))
                .build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "UPDATE ${response.code}: ${response.body?.string()?.take(300)}")
                return@withContext false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "UPDATE error: ${e.message}")
            false
        }
    }

    /** PostgREST DELETE: DELETE /rest/v1/{table}?{matchQuery} */
    suspend fun delete(table: String, matchQuery: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$table?$matchQuery")
                .header("apikey", SUPABASE_KEY)
                .header("Authorization", "Bearer $SUPABASE_KEY")
                .delete()
                .build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "DELETE ${response.code}: ${response.body?.string()?.take(300)}")
                return@withContext false
            }
            Log.d(TAG, "✅ DELETE from $table where $matchQuery")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DELETE error: ${e.message}")
            false
        }
    }
}

