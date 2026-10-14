package com.newsreader.service

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.newsreader.ui.MainActivity

class AudioPlaybackService : Service() {

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var callback: PlaybackCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentTitle: String = "AI News Reader"
    private var currentTopic: String = ""

    interface PlaybackCallback {
        fun onPlaybackStateChanged(isPlaying: Boolean, progress: Int, elapsedMs: Long, durationMs: Long)
        fun onTrackFinished()
        fun onNextRequested()
        fun onPreviousRequested()
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        setupMediaSession()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> { if (isCurrentlyPaused()) resume() }
            ACTION_PAUSE -> pause()
            ACTION_PLAY_TOGGLE -> { if (isCurrentlyPaused()) resume() else pause() }
            ACTION_NEXT -> callback?.onNextRequested()
            ACTION_PREV -> callback?.onPreviousRequested()
            else -> {
                // Initial start — must call startForeground immediately (Android 12+ requirement)
                showMediaNotification(false)
            }
        }
        return START_STICKY
    }

    fun setPlaybackCallback(cb: PlaybackCallback) { callback = cb }

    /** Update the notification & MediaSession metadata */
    fun updateMetadata(title: String, topic: String) {
        currentTitle = title
        currentTopic = topic
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "AI News Reader · $topic")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer?.duration?.toLong() ?: 0)
                .build()
        )
    }

    fun play(filePath: String) {
        requestAudioFocus()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.apply {
            setOnCompletionListener(null)
            setOnErrorListener(null)
            stop()
            release()
        }
        mediaPlayer = MediaPlayer().apply {
            setDataSource(filePath)
            prepare()
            start()
            setOnCompletionListener {
                updateSessionState(PlaybackStateCompat.STATE_STOPPED)
                callback?.onPlaybackStateChanged(false, 0, 0, 0)
                callback?.onTrackFinished()
            }
        }
        startProgressUpdates()
        updateSessionState(PlaybackStateCompat.STATE_PLAYING)
        showMediaNotification(true)
        callback?.onPlaybackStateChanged(true, 0, 0, mediaPlayer?.duration?.toLong() ?: 0)
    }

    fun pause() {
        mediaPlayer?.pause()
        val elapsed = mediaPlayer?.currentPosition?.toLong() ?: 0
        val duration = mediaPlayer?.duration?.toLong() ?: 0
        updateSessionState(PlaybackStateCompat.STATE_PAUSED)
        showMediaNotification(false)
        callback?.onPlaybackStateChanged(false, getProgress(), elapsed, duration)
    }

    fun resume() {
        requestAudioFocus()
        mediaPlayer?.start()
        startProgressUpdates()
        updateSessionState(PlaybackStateCompat.STATE_PLAYING)
        showMediaNotification(true)
        val elapsed = mediaPlayer?.currentPosition?.toLong() ?: 0
        val duration = mediaPlayer?.duration?.toLong() ?: 0
        callback?.onPlaybackStateChanged(true, getProgress(), elapsed, duration)
    }

    fun isCurrentlyPlaying(): Boolean = mediaPlayer?.isPlaying == true
    fun isCurrentlyPaused(): Boolean = mediaPlayer != null && mediaPlayer?.isPlaying == false

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        handler.removeCallbacksAndMessages(null)
        updateSessionState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ── MediaSession ──────────────────────────────────────────────────
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "NewsReaderSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (isCurrentlyPaused()) resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { callback?.onNextRequested() }
                override fun onSkipToPrevious() { callback?.onPreviousRequested() }
                override fun onStop() { stop() }
            })
            isActive = true
        }
    }

    private fun updateSessionState(state: Int) {
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0
        val psb = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, position, 1.0f)
            .build()
        mediaSession.setPlaybackState(psb)
    }

    // ── Audio focus ───────────────────────────────────────────────────
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Abandon previous focus request if exists
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                        AudioManager.AUDIOFOCUS_GAIN -> { if (isCurrentlyPaused()) resume() }
                    }
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        }
    }

    // ── Wake lock ─────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NewsReader:Audio")
        wakeLock?.acquire(4 * 60 * 60 * 1000L)
    }

    // ── Progress ──────────────────────────────────────────────────────
    private fun getProgress(): Int {
        val mp = mediaPlayer ?: return 0
        return if (mp.duration > 0) (mp.currentPosition * 100 / mp.duration) else 0
    }

    private fun startProgressUpdates() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                if (mp.isPlaying) {
                    val elapsed = mp.currentPosition.toLong()
                    val duration = mp.duration.toLong()
                    val progress = if (duration > 0) (elapsed * 100 / duration).toInt() else 0
                    callback?.onPlaybackStateChanged(true, progress, elapsed, duration)
                    handler.postDelayed(this, 250)
                }
            }
        })
    }

    // ── Notification with media controls ──────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "News Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for news audio playback"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun showMediaNotification(isPlaying: Boolean) {
        try {
            val contentIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val prevAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_previous, "Previous",
                actionPendingIntent(ACTION_PREV)
            ).build()

            val ppIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val ppAction = NotificationCompat.Action.Builder(
                ppIcon, if (isPlaying) "Pause" else "Play",
                actionPendingIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY)
            ).build()

            val nextAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_next, "Next",
                actionPendingIntent(ACTION_NEXT)
            ).build()

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText("AI News Reader · $currentTopic")
                .setContentIntent(contentIntent)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .addAction(prevAction)
                .addAction(ppAction)
                .addAction(nextAction)
                .setStyle(
                    MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("AudioPlaybackService", "Failed to show notification", e)
        }
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        instance = null
        mediaSession.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
        stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID      = "news_reader_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY     = "com.newsreader.action.PLAY"
        const val ACTION_PAUSE    = "com.newsreader.action.PAUSE"
        const val ACTION_NEXT     = "com.newsreader.action.NEXT"
        const val ACTION_PREV     = "com.newsreader.action.PREV"
        const val ACTION_PLAY_TOGGLE = "com.newsreader.action.PLAY_TOGGLE"

        /** Static instance for querying live playback state from other services */
        var instance: AudioPlaybackService? = null
            private set
    }
}
