package com.newsreader.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Wrapper around Android's built-in TextToSpeech engine.
 * Produces WAV files from text — free, offline, no API key needed.
 */
class GoogleTTSHelper(private val context: Context) {

    private val TAG = "GoogleTTSHelper"
    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false

    /** Initialize the TTS engine (call once, e.g. from ViewModel init) */
    suspend fun initialize(): Boolean = suspendCoroutine { cont ->
        if (isReady && tts != null) {
            cont.resume(true)
            return@suspendCoroutine
        }
        tts = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
                Log.d(TAG, "Google TTS initialized successfully")
            } else {
                Log.e(TAG, "Google TTS init failed with status: $status")
            }
            cont.resume(isReady)
        }
    }

    /** Set language for Google TTS */
    fun setLanguage(languageCode: String) {
        val locale = when {
            languageCode.startsWith("en") -> Locale.US
            languageCode.startsWith("hi") -> Locale("hi", "IN")
            languageCode.startsWith("ta") -> Locale("ta", "IN")
            languageCode.startsWith("te") -> Locale("te", "IN")
            languageCode.startsWith("kn") -> Locale("kn", "IN")
            languageCode.startsWith("ml") -> Locale("ml", "IN")
            languageCode.startsWith("mr") -> Locale("mr", "IN")
            languageCode.startsWith("bn") -> Locale("bn", "IN")
            languageCode.startsWith("gu") -> Locale("gu", "IN")
            languageCode.startsWith("pa") -> Locale("pa", "IN")
            else -> Locale.US
        }
        tts?.language = locale
    }

    /**
     * Synthesize text to a WAV file. Returns the file on success, null on failure.
     */
    suspend fun synthesizeToFile(text: String, outputFile: File): File? = suspendCoroutine { cont ->
        val engine = tts
        if (engine == null || !isReady) {
            Log.e(TAG, "TTS not initialized")
            cont.resume(null)
            return@suspendCoroutine
        }

        val utteranceId = "gTTS_${System.currentTimeMillis()}"

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    Log.d(TAG, "Google TTS file created: ${outputFile.length()} bytes")
                    cont.resume(outputFile)
                }
            }
            @Deprecated("Deprecated in API level 21")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    Log.e(TAG, "Google TTS synthesis error")
                    cont.resume(null)
                }
            }
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    Log.e(TAG, "Google TTS synthesis error: $errorCode")
                    cont.resume(null)
                }
            }
        })

        val result = engine.synthesizeToFile(text, null, outputFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "synthesizeToFile returned error: $result")
            cont.resume(null)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
