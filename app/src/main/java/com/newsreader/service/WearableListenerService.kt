package com.newsreader.service

import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService
import com.newsreader.data.ApiConfig
import kotlinx.coroutines.*
import org.json.JSONObject

class WearableListenerService : WearableListenerService() {

    private val TAG = "WearableListenerSvc"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val command = String(messageEvent.data)
        Log.d(TAG, "Received message on path: $path, command: $command")

        if (path == ApiConfig.WEAR_PATH_CONTROL) {
            Log.d(TAG, "✅ Watch command received: $command")

            // Always handle commands directly via service — works whether Activity is alive or not
            when (command) {
                ApiConfig.CMD_PLAY_PAUSE -> sendServiceAction(AudioPlaybackService.ACTION_PLAY_TOGGLE)
                ApiConfig.CMD_NEXT -> sendServiceAction(AudioPlaybackService.ACTION_NEXT)
                ApiConfig.CMD_PREV -> sendServiceAction(AudioPlaybackService.ACTION_PREV)
                ApiConfig.CMD_VOLUME_UP -> adjustVolume(true)
                ApiConfig.CMD_VOLUME_DOWN -> adjustVolume(false)
                else -> {
                    if (command.startsWith(ApiConfig.CMD_VOLUME_SET)) {
                        val level = command.removePrefix(ApiConfig.CMD_VOLUME_SET).toIntOrNull()
                        if (level != null) setVolume(level)
                    } else if (command.startsWith(ApiConfig.CMD_TOPIC_CHANGE)) {
                        // Topic change requires ViewModel — launch activity
                        launchMainActivity(command)
                    }
                }
            }
            // Send updated state back to watch after a short delay
            // (allow AudioPlaybackService to process the command first)
            sendLiveStateToWatch(delayMs = 300)
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            this.action = action
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send action to service: $action", e)
        }
    }

    private fun adjustVolume(raise: Boolean) {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            if (raise) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
            0
        )
    }

    private fun setVolume(level: Int) {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        am.setStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            level.coerceIn(0, maxVol),
            0
        )
    }

    private fun launchMainActivity(command: String) {
        val intent = Intent(this, com.newsreader.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("wear_command", command)
        }
        startActivity(intent)
    }

    /**
     * Send live playback state back to the watch.
     * Reads current state from AudioPlaybackService + AudioManager.
     */
    private fun sendLiveStateToWatch(delayMs: Long = 0) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            try {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val volume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val maxVolume = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)

                // Read live state from AudioPlaybackService
                val service = AudioPlaybackService.instance
                val isPlaying = service?.isCurrentlyPlaying() == true

                // Start with cached state (has title, topic, track info)
                val stateJson = lastKnownStateJson?.let { cached ->
                    try {
                        val json = JSONObject(cached)
                        json.put("isPlaying", isPlaying)
                        json.put("volume", volume)
                        json.put("maxVolume", maxVolume)
                        json.toString()
                    } catch (e: Exception) { null }
                } ?: run {
                    // No cached state — send minimal state
                    JSONObject().apply {
                        put("isPlaying", isPlaying)
                        put("volume", volume)
                        put("maxVolume", maxVolume)
                    }.toString()
                }

                val nodes = Tasks.await(Wearable.getNodeClient(this@WearableListenerService).connectedNodes)
                nodes.forEach { node ->
                    Wearable.getMessageClient(this@WearableListenerService)
                        .sendMessage(node.id, ApiConfig.WEAR_PATH_STATE, stateJson.toByteArray())
                }
                Log.d(TAG, "Sent live state to watch (playing=$isPlaying, vol=$volume/$maxVolume)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send state to watch", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        /** Last known full state JSON, cached by MainActivity.doSendStateToWatch() */
        var lastKnownStateJson: String? = null
    }
}
