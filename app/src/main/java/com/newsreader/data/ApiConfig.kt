package com.newsreader.data

import android.content.Context
import android.content.SharedPreferences

enum class TTSEngine(val displayName: String) {
    GOOGLE("Google TTS (Free)"),
    SARVAM("Sarvam AI")
}

object ApiConfig {
    // ─────────────────────────────────────────────────────────────────
    // 🔑 REPLACE THESE WITH YOUR FREE API KEYS:
    //
    // 1. GNews API (Free: 100 req/day)
    //    Sign up at: https://gnews.io/  → copy your API key below
    //
    // 2. Gemini API (Free: 1500 req/day)
    //    Sign up at: https://aistudio.google.com/ → Get API Key
    //
    // 3. Sarvam AI API
    //    Sign up at: https://dashboard.sarvam.ai/
    // ─────────────────────────────────────────────────────────────────

    const val GNEWS_API_KEY      = ""
    const val GEMINI_API_KEY     = ""
    const val SARVAM_API_KEY_DEFAULT = ""

    // Custom Sarvam API key (mutable, persisted in SharedPreferences)
    var sarvamApiKey: String = SARVAM_API_KEY_DEFAULT

    // API Base URLs
    const val GNEWS_BASE_URL     = "https://gnews.io/api/v4/"
    const val GEMINI_BASE_URL    = "https://generativelanguage.googleapis.com/"
    const val SARVAM_BASE_URL    = "https://api.sarvam.ai/"

    // Sarvam TTS settings — now mutable so user can change them
    var sarvamSpeaker:    String = "ritu"
    var sarvamLanguage:   String = "en-IN"
    const val SARVAM_MODEL       = "bulbul:v3"
    const val SARVAM_PACE        = 1.1f
    const val SARVAM_SAMPLE_RATE = 48000

    // TTS Engine selection
    var ttsEngine: TTSEngine = TTSEngine.GOOGLE

    // Wearable message paths
    const val WEAR_PATH_CONTROL  = "/news/control"   // watch → phone
    const val WEAR_PATH_STATE    = "/news/state"     // phone → watch
    const val WEAR_PATH_TOPICS   = "/news/topics"    // phone → watch

    // Control commands from watch
    const val CMD_PLAY_PAUSE     = "play_pause"
    const val CMD_NEXT           = "next"
    const val CMD_PREV           = "previous"
    const val CMD_TOPIC_CHANGE   = "topic:"          // e.g. "topic:SPORTS"
    const val CMD_VOLUME_UP      = "volume_up"
    const val CMD_VOLUME_DOWN    = "volume_down"
    const val CMD_VOLUME_SET     = "volume_set:"     // e.g. "volume_set:10"

    // ── SharedPreferences persistence ─────────────────────────────────
    private const val PREFS_NAME = "newsreader_prefs"
    private const val KEY_TTS_ENGINE = "tts_engine"
    private const val KEY_CUSTOM_API_KEY = "custom_sarvam_api_key"
    private const val KEY_LANGUAGE = "sarvam_language"
    private const val KEY_SPEAKER = "sarvam_speaker"

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ttsEngine = try {
            TTSEngine.valueOf(prefs.getString(KEY_TTS_ENGINE, TTSEngine.GOOGLE.name) ?: TTSEngine.GOOGLE.name)
        } catch (_: Exception) { TTSEngine.GOOGLE }
        sarvamApiKey = prefs.getString(KEY_CUSTOM_API_KEY, SARVAM_API_KEY_DEFAULT) ?: SARVAM_API_KEY_DEFAULT
        sarvamLanguage = prefs.getString(KEY_LANGUAGE, "en-IN") ?: "en-IN"
        sarvamSpeaker = prefs.getString(KEY_SPEAKER, "ritu") ?: "ritu"
    }

    fun savePreferences(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_TTS_ENGINE, ttsEngine.name)
            putString(KEY_CUSTOM_API_KEY, sarvamApiKey)
            putString(KEY_LANGUAGE, sarvamLanguage)
            putString(KEY_SPEAKER, sarvamSpeaker)
            apply()
        }
    }
}

// Available news topics
enum class NewsTopic(val displayName: String, val query: String) {
    WORLD("World",        "world news"),
    TECHNOLOGY("Tech",    "technology"),
    BUSINESS("Business",  "business finance"),
    SPORTS("Sports",      "sports"),
    HEALTH("Health",      "health medicine"),
    SCIENCE("Science",    "science"),
    ENTERTAINMENT("Entertainment", "entertainment"),
    INDIA("India",        "India news")
}
