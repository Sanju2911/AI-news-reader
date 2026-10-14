package com.newsreader.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.newsreader.data.*
import com.newsreader.service.AudioPlaybackService
import com.newsreader.service.NewsRefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"
    private val newsRepo = NewsRepository()
    private val supabaseRepo = SupabaseRepository()
    private val gson = Gson()
    private val googleTTSHelper = GoogleTTSHelper(application)

    // Expose TTS engine state for UI
    private val _ttsEngine = MutableLiveData(ApiConfig.ttsEngine)
    val ttsEngine: LiveData<TTSEngine> = _ttsEngine

    // ── LiveData (reflects the currently viewed topic) ────────────────
    private val _newsItems = MutableLiveData<List<NewsItem>>(emptyList())
    val newsItems: LiveData<List<NewsItem>> = _newsItems

    private val _playbackState = MutableLiveData(PlaybackState())
    val playbackState: LiveData<PlaybackState> = _playbackState

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _currentTopic = MutableLiveData(NewsTopic.WORLD)
    val currentTopic: LiveData<NewsTopic> = _currentTopic

    private val _ttsProgress = MutableLiveData<String?>(null)
    val ttsProgress: LiveData<String?> = _ttsProgress

    // ── Auto-play ──────────────────────────────────────────────────────
    private val _autoPlayEnabled = MutableLiveData(false)
    val autoPlayEnabled: LiveData<Boolean> = _autoPlayEnabled
    private val autoPlayHandler = Handler(Looper.getMainLooper())

    // ─────────────────────────────────────────────────────────────────
    //  PER-TOPIC STATE — each section is an independent "page"
    //  Switching topics does NOT cancel TTS generation for other topics.
    //  TTS continues generating in background for all topics.
    // ─────────────────────────────────────────────────────────────────
    private data class TopicState(
        var items: List<NewsItem> = emptyList(),
        var reportGenerated: Boolean = false,
        var reportId: String? = null,
        var playbackIndex: Int = 0
    )

    private val topicStates = mutableMapOf<NewsTopic, TopicState>()
    private val topicTtsJobs = mutableMapOf<NewsTopic, Job>()
    private val topicLoadJobs = mutableMapOf<NewsTopic, Job>()

    private var isPaused = false
    private var pendingPlay: Pair<NewsTopic, Int>? = null   // (topic, index) queued for service binding

    // Track identity stored separately to avoid postValue race condition
    // (service callback reads these directly instead of stale LiveData)
    private var currentPlayIndex = 0
    private var currentPlayTitle = ""
    private var currentPlayTopic = NewsTopic.WORLD
    private var currentPlayTotal = 0

    private fun getTopicState(topic: NewsTopic): TopicState =
        topicStates.getOrPut(topic) { TopicState() }

    // ── Audio service ─────────────────────────────────────────────────
    private var audioService: AudioPlaybackService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            audioService = (binder as? AudioPlaybackService.LocalBinder)?.getService()
            audioService?.setPlaybackCallback(object : AudioPlaybackService.PlaybackCallback {
                override fun onPlaybackStateChanged(isPlaying: Boolean, progress: Int, elapsedMs: Long, durationMs: Long) {
                    // Use stored track identity (always current) instead of
                    // _playbackState.value which may be stale due to postValue race
                    _playbackState.postValue(PlaybackState(
                        currentIndex = currentPlayIndex,
                        totalItems = currentPlayTotal,
                        currentTitle = currentPlayTitle,
                        currentTopic = currentPlayTopic,
                        isPlaying = isPlaying,
                        progress = progress,
                        elapsedMs = elapsedMs,
                        durationMs = durationMs
                    ))
                }
                override fun onTrackFinished() {
                    isPaused = false
                    if (_autoPlayEnabled.value == true) {
                        handleAutoPlay()
                    }
                }
                override fun onNextRequested() { playNext() }
                override fun onPreviousRequested() { playPrevious() }
            })
            // Play any queued audio that was waiting for service binding
            pendingPlay?.let { (topic, index) ->
                pendingPlay = null
                if (_currentTopic.value == topic) {
                    playAudio(index)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { audioService = null }
    }

    init {
        val ctx = getApplication<Application>()
        // Load saved preferences
        ApiConfig.loadPreferences(ctx)
        _ttsEngine.value = ApiConfig.ttsEngine

        // Initialize Google TTS helper
        newsRepo.googleTTSHelper = googleTTSHelper
        viewModelScope.launch {
            googleTTSHelper.initialize()
            googleTTSHelper.setLanguage(ApiConfig.sarvamLanguage)
        }

        ctx.bindService(
            Intent(ctx, AudioPlaybackService::class.java),
            serviceConnection, Context.BIND_AUTO_CREATE
        )

        // Clean up Supabase news older than 48 hours (fire-and-forget)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                supabaseRepo.deleteOldReports()
            } catch (e: Exception) {
                Log.e(TAG, "Old report cleanup failed: ${e.message}")
            }
        }

        loadNews(NewsTopic.WORLD)
    }

    /** Toggle between Google TTS and Sarvam AI */
    fun toggleTTSEngine() {
        val ctx = getApplication<Application>()
        ApiConfig.ttsEngine = if (ApiConfig.ttsEngine == TTSEngine.GOOGLE) TTSEngine.SARVAM else TTSEngine.GOOGLE
        _ttsEngine.value = ApiConfig.ttsEngine
        ApiConfig.savePreferences(ctx)
        Log.d(TAG, "TTS engine switched to: ${ApiConfig.ttsEngine.displayName}")
        // Clear audio so it regenerates with new engine
        clearAudioCache()
    }

    // ─────────────────────────────────────────────────────────────────
    //  Save/restore current topic state
    // ─────────────────────────────────────────────────────────────────
    private fun saveCurrentTopicState() {
        val topic = _currentTopic.value ?: return
        val state = getTopicState(topic)
        state.items = _newsItems.value ?: emptyList()
        state.playbackIndex = _playbackState.value?.currentIndex ?: 0
    }

    // ─────────────────────────────────────────────────────────────────
    //  LOAD NEWS — Per-topic isolation, Supabase-first, then GNews
    //  Switching topics does NOT cancel TTS for other topics.
    // ─────────────────────────────────────────────────────────────────
    fun loadNews(topic: NewsTopic) {
        // Save current topic state before switching
        saveCurrentTopicState()

        // Stop audio playback (only one player at a time)
        // but do NOT cancel TTS jobs for other topics
        autoPlayHandler.removeCallbacksAndMessages(null)
        audioService?.stop()
        isPaused = false

        _currentTopic.value = topic
        _error.value = null

        // Check if we already have data for this topic (instant switch)
        val existing = topicStates[topic]
        if (existing != null && existing.items.isNotEmpty()) {
            Log.d(TAG, "Restoring ${topic.name} (${existing.items.size} items)")
            _newsItems.value = existing.items
            _isLoading.value = false
            _ttsProgress.value = if (topicTtsJobs[topic]?.isActive == true) "Generating audio..." else null

            val idx = existing.playbackIndex.coerceIn(0, existing.items.size - 1)
            markCurrentlyPlaying(idx)
            _playbackState.value = PlaybackState(
                currentIndex = idx,
                totalItems = existing.items.size,
                currentTitle = existing.items[idx].originalTitle,
                currentTopic = topic
            )

            // Auto-play if audio is ready for the restored item
            if (existing.items[idx].audioBase64 != null) {
                playAudio(idx)
            }
            return
        }

        // Fresh load from Supabase/GNews
        _isLoading.value = true
        _ttsProgress.value = null
        _newsItems.value = emptyList()
        _playbackState.value = PlaybackState(currentTopic = topic)

        topicLoadJobs[topic]?.cancel()
        topicLoadJobs[topic] = viewModelScope.launch {
            try {
                val report = supabaseRepo.getLatestReport(topic)

                if (report != null && report.report_text.isNotEmpty()) {
                    Log.d(TAG, "Supabase cache hit for ${topic.name}")
                    loadFromSupabase(report, topic)
                } else {
                    Log.d(TAG, "🌐 No cache — fetching from GNews for ${topic.name}")
                    loadFromGNews(topic)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadNews error", e)
                if (_currentTopic.value == topic) {
                    _isLoading.postValue(false)
                    _error.postValue("Failed to load: ${e.message}")
                }
            }
        }
    }

    // ── Load from Supabase (cached report) ────────────────────────────
    private fun loadFromSupabase(report: SupabaseReport, topic: NewsTopic) {
        val state = getTopicState(topic)
        val segments = supabaseRepo.parseSegments(report.report_text)
        val infos = supabaseRepo.parseArticlesInfo(report.articles_json)
        state.reportId = report.id
        state.reportGenerated = true

        val items = segments.mapIndexed { i, seg ->
            NewsItem(
                id = "$i",
                originalTitle = infos.getOrNull(i)?.title ?: "Story ${i + 1}",
                simplifiedText = seg,
                sourceUrl = "",
                imageUrl = null,
                sourceName = infos.getOrNull(i)?.source ?: "News",
                publishedAt = report.batch_time,
                topic = topic
            )
        }

        // Check for cached TTS audio — prefer local cache (may be more complete), then Supabase
        val supabaseAudios = parseCachedAudio(report.audio_base64)
        val localAudios = report.id?.let { loadLocalAudioCache(it) }
        val cachedAudios = when {
            supabaseAudios != null && localAudios != null -> {
                val sCount = supabaseAudios.count { it.isNotEmpty() }
                val lCount = localAudios.count { it.isNotEmpty() }
                if (lCount >= sCount) localAudios else supabaseAudios
            }
            localAudios != null -> localAudios
            supabaseAudios != null -> supabaseAudios
            else -> null
        }

        val finalItems: List<NewsItem>
        val missingCount: Int

        if (cachedAudios != null) {
            finalItems = items.mapIndexed { i, item ->
                val audio = cachedAudios.getOrNull(i)?.takeIf { it.isNotEmpty() }
                item.copy(audioBase64 = audio)
            }
            missingCount = finalItems.count { it.audioBase64 == null && it.simplifiedText.isNotEmpty() }
        } else {
            finalItems = items
            missingCount = finalItems.count { it.simplifiedText.isNotEmpty() }
        }

        // Store in per-topic state
        state.items = finalItems
        val isCurrent = _currentTopic.value == topic
        val cachedCount = finalItems.count { it.audioBase64 != null }

        if (isCurrent) {
            _newsItems.value = finalItems
            _isLoading.value = false
        }

        if (missingCount == 0) {
            // Full cache — instant playback
            Log.d(TAG, "Using cached TTS audio for ${topic.name} ($cachedCount segments, all complete)")
            if (isCurrent && finalItems.isNotEmpty()) {
                updatePlaybackState(0)
                markCurrentlyPlaying(0)
                playAudio(0)
            }
        } else {
            // Missing segments — generate TTS in background (regardless of whether topic is viewed)
            Log.d(TAG, "${topic.name}: $cachedCount cached, $missingCount need TTS generation")
            if (isCurrent && finalItems.isNotEmpty()) {
                updatePlaybackState(0)
            }
            autoGenerateAllTTS(topic)
        }
    }

    // ── Load from GNews (fresh) → Gemini → Supabase ──────────────────
    private suspend fun loadFromGNews(topic: NewsTopic) {
        val result = newsRepo.fetchNews(topic)
        result.onSuccess { items ->
            val state = getTopicState(topic)
            state.items = items

            if (_currentTopic.value == topic) {
                _newsItems.postValue(items)
                _isLoading.postValue(false)
            }
            if (items.isNotEmpty()) {
                if (_currentTopic.value == topic) updatePlaybackState(0)
                generateReport(topic)
            }
        }.onFailure { e ->
            if (_currentTopic.value == topic) {
                _isLoading.postValue(false)
                _error.postValue("No news available: ${e.message}")
            }
        }
    }

    // ── Generate AI report ────────────────────────────────────────────
    private fun generateReport(topic: NewsTopic) {
        val state = getTopicState(topic)
        if (state.reportGenerated) return

        topicLoadJobs[topic] = viewModelScope.launch {
            val items = state.items
            val articles = newsRepo.getCachedArticles(topic)
            if (articles.isEmpty()) return@launch

            if (_currentTopic.value == topic) {
                _newsItems.postValue(items.map { it.copy(isLoading = true) })
            }

            val result = newsRepo.generateNewsReport(articles, topic)
            result.onSuccess { segments ->
                state.reportGenerated = true
                val updated = state.items.toMutableList()
                for (i in updated.indices) {
                    updated[i] = updated[i].copy(
                        simplifiedText = segments.getOrElse(i) { updated[i].originalTitle },
                        isLoading = false
                    )
                }
                state.items = updated

                if (_currentTopic.value == topic) {
                    _newsItems.postValue(updated)
                    updatePlaybackState(0)
                }

                // Save to Supabase for future fast loads
                saveToSupabase(topic, segments, articles)

                // Auto-generate TTS in background
                autoGenerateAllTTS(topic)
            }.onFailure {
                val restored = state.items.map { it.copy(isLoading = false) }
                state.items = restored
                if (_currentTopic.value == topic) {
                    _newsItems.postValue(restored)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  AUTO TTS — Generate per-topic, 3 concurrent, runs in background
    //  even when the topic is not being viewed.
    // ─────────────────────────────────────────────────────────────────
    private fun autoGenerateAllTTS(topic: NewsTopic) {
        topicTtsJobs[topic]?.cancel()
        topicTtsJobs[topic] = viewModelScope.launch {
            val state = getTopicState(topic)
            val items = state.items.toList()
            if (items.isEmpty()) return@launch

            val total = items.count { it.audioBase64 == null && it.simplifiedText.isNotEmpty() }
            if (total == 0) {
                Log.d(TAG, "All segments already have audio for ${topic.name}")
                return@launch
            }

            val isViewed = { _currentTopic.value == topic }

            Log.d(TAG, "Auto-generating TTS for $total segments (${topic.name})")
            if (isViewed()) _ttsProgress.postValue("Preparing audio: 0 / $total")
            var completed = 0

            // Generate first segment and start playing if this topic is being viewed
            val first = items[0]
            if (first.audioBase64 == null && first.simplifiedText.isNotEmpty()) {
                updateTopicItem(topic, 0) { it.copy(isLoading = true) }
                val result = newsRepo.convertToSpeech(first.simplifiedText, getApplication<Application>().cacheDir)
                val audio = result.getOrNull()
                updateTopicItem(topic, 0) { it.copy(audioBase64 = audio, isLoading = false) }
                completed++
                if (isViewed()) _ttsProgress.postValue("Preparing audio: $completed / $total")

                if (audio != null && isViewed()) {
                    markCurrentlyPlaying(0)
                    updatePlaybackState(0)
                    val file = newsRepo.saveAudioToFile(audio, getApplication<Application>().cacheDir)
                    ensureForegroundService()
                    audioService?.let { service ->
                        service.updateMetadata(first.originalTitle, first.topic.displayName)
                        service.play(file.absolutePath)
                    } ?: run {
                        pendingPlay = topic to 0
                    }
                }
            } else if (first.audioBase64 != null && isViewed()) {
                // First item already has cached audio — play it immediately
                markCurrentlyPlaying(0)
                updatePlaybackState(0)
                playAudio(0)
            }

            // Generate remaining segments sequentially (avoid 429 rate limit)
            val semaphore = Semaphore(1)
            val remaining = (1 until items.size).filter { i ->
                items[i].audioBase64 == null && items[i].simplifiedText.isNotEmpty()
            }

            remaining.map { index ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    semaphore.withPermit {
                        delay(1500) // Pace requests to avoid 429
                        val currentItems = getTopicState(topic).items
                        val item = currentItems.getOrNull(index) ?: return@withPermit
                        if (item.audioBase64 != null) return@withPermit

                        updateTopicItem(topic, index) { it.copy(isLoading = true) }
                        val result = newsRepo.convertToSpeech(item.simplifiedText, getApplication<Application>().cacheDir)
                        updateTopicItem(topic, index) { it.copy(audioBase64 = result.getOrNull(), isLoading = false) }

                        completed++
                        if (isViewed()) _ttsProgress.postValue("Preparing audio: $completed / $total")
                    }
                }
            }.awaitAll()

            if (isViewed()) _ttsProgress.postValue(null)
            Log.d(TAG, "✅ All TTS generated for ${topic.name}")

            // Cache all audio
            cacheAllAudioToSupabase(topic)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  SUPABASE AUDIO CACHING (topic-aware)
    // ─────────────────────────────────────────────────────────────────

    /** Parse cached audio JSON from Supabase. Returns the full list (may include empty strings). */
    private fun parseCachedAudio(audioJson: String?): List<String>? {
        if (audioJson.isNullOrEmpty()) return null
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val obj: Map<String, Any> = gson.fromJson(audioJson, type) ?: return null
            val lang = obj["lang"] as? String
            val speaker = obj["speaker"] as? String

            if (lang != ApiConfig.sarvamLanguage || speaker != ApiConfig.sarvamSpeaker) {
                Log.d(TAG, "Cached audio voice mismatch (cached: $lang/$speaker, current: ${ApiConfig.sarvamLanguage}/${ApiConfig.sarvamSpeaker})")
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val audios = obj["audios"] as? List<String>
            audios?.takeIf { list -> list.any { it.isNotEmpty() } }
        } catch (e: Exception) {
            Log.e(TAG, "parseCachedAudio error: ${e.message}")
            null
        }
    }

    /** Cache all segment audios to Supabase with voice settings metadata */
    private suspend fun cacheAllAudioToSupabase(topic: NewsTopic) {
        val state = getTopicState(topic)
        val reportId = state.reportId ?: run {
            Log.w(TAG, "No report ID for ${topic.name} — cannot cache audio")
            return
        }
        val items = state.items
        val audios = items.map { it.audioBase64 ?: "" }

        if (audios.all { it.isEmpty() }) return

        val cacheJson = gson.toJson(mapOf(
            "lang" to ApiConfig.sarvamLanguage,
            "speaker" to ApiConfig.sarvamSpeaker,
            "audios" to audios
        ))

        // Save locally first (always works, survives app restart)
        saveLocalAudioCache(reportId, cacheJson)

        // Also try Supabase (may fail for large payloads on free tier)
        val sizeMB = cacheJson.length / (1024.0 * 1024.0)
        if (sizeMB <= 10.0) {
            val success = supabaseRepo.cacheAudio(reportId, cacheJson)
            if (success) {
                Log.d(TAG, "Cached TTS to Supabase for ${topic.name} (${audios.count { it.isNotEmpty() }} segments, ${String.format("%.1f", sizeMB)}MB)")
            } else {
                Log.w(TAG, "Supabase cache failed for ${topic.name} — local cache available")
            }
        } else {
            Log.d(TAG, "Audio too large for Supabase (${String.format("%.1f", sizeMB)}MB) — using local cache only")
        }
    }

    /** Save audio cache to local file */
    private fun saveLocalAudioCache(reportId: String, cacheJson: String) {
        try {
            val cacheDir = getApplication<Application>().cacheDir
            val file = java.io.File(cacheDir, "audio_cache_$reportId.json")
            file.writeText(cacheJson)
            Log.d(TAG, "Saved local audio cache: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save local audio cache", e)
        }
    }

    /** Load audio cache from local file */
    private fun loadLocalAudioCache(reportId: String): List<String>? {
        try {
            val cacheDir = getApplication<Application>().cacheDir
            val file = java.io.File(cacheDir, "audio_cache_$reportId.json")
            if (!file.exists()) return null
            val json = file.readText()
            return parseCachedAudio(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load local audio cache", e)
            return null
        }
    }

    // ── Card click handler ────────────────────────────────────────────
    fun prepareItem(index: Int) {
        val items = _newsItems.value ?: return
        if (index < 0 || index >= items.size) return
        val item = items[index]
        val topic = _currentTopic.value ?: return

        // Stop current playback and reset pause state
        isPaused = false
        audioService?.stop()

        markCurrentlyPlaying(index)
        updatePlaybackState(index)

        // Save playback index for this topic
        getTopicState(topic).playbackIndex = index

        if (item.simplifiedText.isEmpty() && !getTopicState(topic).reportGenerated) {
            generateReport(topic)
            return
        }

        if (item.audioBase64 != null) {
            playAudio(index)
            return
        }

        // Generate TTS then auto-play
        viewModelScope.launch {
            updateTopicItem(topic, index) { it.copy(isLoading = true) }
            val text = item.simplifiedText.ifEmpty { item.originalTitle }
            val result = newsRepo.convertToSpeech(text, getApplication<Application>().cacheDir)
            val audio = result.getOrNull()
            updateTopicItem(topic, index) { it.copy(audioBase64 = audio, isLoading = false) }
            if (audio != null && _currentTopic.value == topic) playAudio(index)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  PLAYBACK CONTROLS (play/pause/resume + next/prev)
    // ─────────────────────────────────────────────────────────────────
    fun togglePlayPause() {
        val items = _newsItems.value ?: return
        val item = items.getOrNull(currentPlayIndex) ?: return
        val isPlaying = audioService?.isCurrentlyPlaying() == true

        if (item.audioBase64 == null) {
            prepareItem(currentPlayIndex)
            return
        }

        if (isPlaying) {
            audioService?.pause()
            isPaused = true
        } else if (isPaused) {
            audioService?.resume()
            isPaused = false
        } else {
            playAudio(currentPlayIndex)
        }
    }

    fun playNext() {
        val items = _newsItems.value ?: return
        val nextIndex = currentPlayIndex + 1
        if (nextIndex < items.size) {
            Log.d(TAG, "playNext: $currentPlayIndex → $nextIndex")
            prepareItem(nextIndex)
        }
    }

    fun playPrevious() {
        val prevIndex = currentPlayIndex - 1
        if (prevIndex >= 0) {
            Log.d(TAG, "playPrevious: $currentPlayIndex → $prevIndex")
            prepareItem(prevIndex)
        }
    }

    fun toggleAutoPlay() {
        _autoPlayEnabled.value = !(_autoPlayEnabled.value ?: false)
    }

    /** Auto-play: 2-sec gap then next item, or advance to next topic */
    private fun handleAutoPlay() {
        autoPlayHandler.removeCallbacksAndMessages(null)
        val items = _newsItems.value ?: return
        val nextIndex = currentPlayIndex + 1

        Log.d(TAG, "handleAutoPlay: currentIndex=$currentPlayIndex, nextIndex=$nextIndex, totalItems=${items.size}")

        if (nextIndex < items.size) {
            // More items in current topic → play next after 2 sec
            autoPlayHandler.postDelayed({ playNext() }, 2000)
        } else {
            // End of topic → advance to next topic
            val topic = _currentTopic.value ?: return
            val topics = NewsTopic.entries
            val idx = topics.indexOf(topic)
            if (idx + 1 < topics.size) {
                autoPlayHandler.postDelayed({
                    loadNews(topics[idx + 1])
                }, 2000)
            }
            // At last topic → stop (full cycle complete)
        }
    }

    /** Start the service as foreground so audio survives backgrounding */
    private fun ensureForegroundService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, AudioPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun playAudio(index: Int) {
        isPaused = false
        val item = _newsItems.value?.getOrNull(index) ?: return
        val audio = item.audioBase64 ?: return

        ensureForegroundService()

        val service = audioService
        if (service == null) {
            val topic = _currentTopic.value ?: return
            Log.d(TAG, "Service not bound yet, queuing play for ${topic.name}:$index")
            pendingPlay = topic to index
            return
        }

        Log.d(TAG, "Playing audio for item $index: ${item.originalTitle.take(30)}")
        val file = newsRepo.saveAudioToFile(audio, getApplication<Application>().cacheDir)
        service.updateMetadata(item.originalTitle, item.topic.displayName)
        service.play(file.absolutePath)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Update a specific item in a topic's state, and sync to UI if topic is current */
    private fun updateTopicItem(topic: NewsTopic, index: Int, transform: (NewsItem) -> NewsItem) {
        val state = getTopicState(topic)
        val items = state.items.toMutableList()
        if (index < items.size) {
            items[index] = transform(items[index])
            state.items = items
            if (_currentTopic.value == topic) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    _newsItems.value = items
                } else {
                    _newsItems.postValue(items)
                }
            }
        }
    }

    private fun markCurrentlyPlaying(index: Int) {
        val items = _newsItems.value?.toMutableList() ?: return
        for (i in items.indices) items[i] = items[i].copy(isCurrentlyPlaying = i == index)
        _newsItems.value = items
    }

    private fun updatePlaybackState(index: Int) {
        val items = _newsItems.value ?: return
        val item = items.getOrNull(index) ?: return
        // Store track identity in fields (immune to postValue race)
        currentPlayIndex = index
        currentPlayTitle = item.originalTitle
        currentPlayTopic = item.topic
        currentPlayTotal = items.size
        _playbackState.value = PlaybackState(
            currentIndex = index,
            totalItems = items.size,
            currentTitle = item.originalTitle,
            currentTopic = item.topic
        )
    }

    private fun saveToSupabase(
        topic: NewsTopic, segments: List<String>, articles: List<NewsArticle>
    ) {
        viewModelScope.launch {
            try {
                val batchTime = NewsRefreshWorker.getCurrentBatchTime()
                if (supabaseRepo.checkExisting(topic, batchTime) != null) return@launch

                val reportText = segments.joinToString("\n---NEXT---\n")
                val articlesJson = gson.toJson(
                    articles.map { mapOf("title" to it.title, "source" to it.source.name) }
                )
                val id = supabaseRepo.saveReport(topic, reportText, articlesJson, batchTime)
                if (id != null) {
                    getTopicState(topic).reportId = id
                    Log.d(TAG, "💾 Saved to Supabase: ${topic.name} (id: $id)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Supabase save failed", e)
            }
        }
    }

    fun forceRefresh() {
        val topic = _currentTopic.value ?: NewsTopic.WORLD
        newsRepo.clearCache(topic)
        // Clear this topic's cached state
        topicTtsJobs[topic]?.cancel()
        topicLoadJobs[topic]?.cancel()
        topicStates.remove(topic)
        loadNews(topic)
    }

    /** Clear local audio cache and regenerate TTS with new voice settings */
    fun clearAudioCache() {
        viewModelScope.launch {
            val topic = _currentTopic.value ?: return@launch
            isPaused = false
            audioService?.stop()

            // Clear audio from current topic's items
            val state = getTopicState(topic)
            state.items = state.items.map { it.copy(audioBase64 = null) }
            _newsItems.postValue(state.items)

            try {
                getApplication<Application>().cacheDir.listFiles()?.forEach {
                    if (it.name.startsWith("news_audio_") || it.name.startsWith("news_report_"))
                        try { it.delete() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            // Re-generate TTS with new voice settings
            if (state.items.isNotEmpty() && state.reportGenerated) {
                autoGenerateAllTTS(topic)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        topicLoadJobs.values.forEach { it.cancel() }
        topicTtsJobs.values.forEach { it.cancel() }
        autoPlayHandler.removeCallbacksAndMessages(null)
        // Don't stop audio — let the foreground service continue playing in background
        // Audio can be stopped from notification controls or watch
        googleTTSHelper.shutdown()
        try { getApplication<Application>().unbindService(serviceConnection) } catch (_: Exception) {}
    }
}
