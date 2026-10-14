package com.newsreader.ui

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.tasks.Tasks
import com.google.android.material.chip.Chip
import com.newsreader.R
import com.newsreader.data.ApiConfig
import com.newsreader.data.NewsTopic
import com.newsreader.data.TTSEngine
import com.newsreader.databinding.ActivityMainBinding
import com.newsreader.service.NewsRefreshWorker
import com.newsreader.viewmodel.MainViewModel
import kotlinx.coroutines.*
import com.google.gson.Gson
import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var newsAdapter: NewsAdapter

    // Current voice settings indices
    private var selectedLanguageIndex = 0
    private var selectedSpeakerIndex  = 0

    // Track chips for proper selection
    private val topicChips = mutableMapOf<NewsTopic, Chip>()
    private var isSelectingChip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved TTS engine and API key preferences
        ApiConfig.loadPreferences(this)

        setupRecyclerView()
        setupTopicChips()
        setupPlayerControls()
        setupSettingsButton()
        setupRefreshButton()
        setupTTSToggle()
        observeViewModel()
        updateVoiceInfoBar()

        // Schedule background news refresh every 4 hours
        scheduleNewsRefresh()

        // Handle wear command if launched from background
        handleWearIntent(intent)

        // Request notification permission for foreground service (Android 13+)
        requestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWearIntent(intent)
    }

    private fun handleWearIntent(intent: Intent?) {
        intent?.getStringExtra("wear_command")?.let { command ->
            Log.d(TAG, "Wear command from intent: $command")
            handleWearCommand(command)
            intent.removeExtra("wear_command")
        }
    }

    override fun onResume() {
        super.onResume()
        // Send initial state + topics to watch
        sendStateToWatch()
        sendTopicsToWatch()
        Log.d(TAG, "Activity resumed, state sent to watch")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    // ── Schedule background news refresh ──────────────────────────────
    private fun scheduleNewsRefresh() {
        NewsRefreshWorker.schedulePeriodicWork(this)
    }

    // ── Manual refresh button ─────────────────────────────────────────
    private fun setupRefreshButton() {
        binding.btnRefresh.setOnClickListener {
            triggerManualRefresh()
        }
    }

    private fun triggerManualRefresh() {
        binding.btnRefresh.isEnabled = false
        binding.btnRefresh.alpha = 0.5f
        Toast.makeText(this, "Refreshing news...", Toast.LENGTH_SHORT).show()

        // Force refresh current topic directly (bypass cache + Supabase)
        viewModel.forceRefresh()

        // Also trigger background worker for all topics
        NewsRefreshWorker.triggerImmediateRefresh(this)

        // Re-enable button after cooldown
        binding.btnRefresh.postDelayed({
            binding.btnRefresh.isEnabled = true
            binding.btnRefresh.alpha = 1.0f
        }, 3000)
    }

    private fun setupRecyclerView() {
        newsAdapter = NewsAdapter { index -> viewModel.prepareItem(index) }
        binding.recyclerNews.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = newsAdapter
            setHasFixedSize(false)
            itemAnimator?.changeDuration = 150
            // Prevent flicker on partial updates
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }

    private fun setupTopicChips() {
        NewsTopic.entries.forEach { topic ->
            val chip = Chip(this).apply {
                text = topic.displayName
                isCheckable = true
                isChecked = (topic == NewsTopic.WORLD)
                setChipBackgroundColorResource(android.R.color.transparent)
                setTextColor(resources.getColorStateList(android.R.color.white, null))
                chipStrokeWidth = 1.5f
                chipCornerRadius = 20f

                // Set initial styling
                if (topic == NewsTopic.WORLD) {
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#E07A3A"))
                    setTextColor(Color.parseColor("#E07A3A"))
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#2A1A10"))
                } else {
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#3A3A3A"))
                    setTextColor(Color.WHITE)
                }

                setOnClickListener {
                    if (!isSelectingChip) {
                        selectTopic(topic)
                    }
                }
            }
            topicChips[topic] = chip
            binding.chipGroupTopics.addView(chip)
        }
    }

    private fun selectTopic(topic: NewsTopic) {
        isSelectingChip = true

        // Update all chip visual states
        topicChips.forEach { (t, chip) ->
            val isSelected = t == topic
            chip.isChecked = isSelected
            if (isSelected) {
                chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#E07A3A"))
                chip.setTextColor(Color.parseColor("#E07A3A"))
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#2A1A10"))
            } else {
                chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#3A3A3A"))
                chip.setTextColor(Color.WHITE)
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            }
        }

        isSelectingChip = false

        // Load data for the selected topic
        viewModel.loadNews(topic)
        sendTopicsToWatch()
    }

    private fun setupPlayerControls() {
        binding.btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.btnNext.setOnClickListener      { viewModel.playNext() }
        binding.btnPrevious.setOnClickListener  { viewModel.playPrevious() }
        binding.btnAutoPlay.setOnClickListener   { viewModel.toggleAutoPlay() }
    }

    // ── TTS Engine Toggle ──────────────────────────────────────────────
    private fun setupTTSToggle() {
        binding.btnTtsEngine.setOnClickListener {
            viewModel.toggleTTSEngine()
        }
        // Set initial state
        updateTTSToggleButton(ApiConfig.ttsEngine)
    }

    private fun updateTTSToggleButton(engine: TTSEngine) {
        if (engine == TTSEngine.GOOGLE) {
            binding.btnTtsEngine.text = "Google"
            binding.btnTtsEngine.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            binding.btnTtsEngine.text = "Sarvam"
            binding.btnTtsEngine.setTextColor(Color.parseColor("#E07A3A"))
        }
    }

    // ── Voice Settings Dialog ─────────────────────────────────────────
    private fun setupSettingsButton() {
        binding.btnSettings.setOnClickListener { showVoiceSettingsDialog() }
    }

    private fun showVoiceSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_voice_settings)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Engine info label
        val tvEngineInfo = dialog.findViewById<TextView>(R.id.tv_engine_info)
        tvEngineInfo.text = "Engine: ${ApiConfig.ttsEngine.displayName}"

        // API Key field
        val etApiKey = dialog.findViewById<EditText>(R.id.et_api_key)
        etApiKey.setText(ApiConfig.sarvamApiKey)

        val languageNames = resources.getStringArray(R.array.language_names)
        val languageCodes = resources.getStringArray(R.array.language_codes)
        val speakerNames  = resources.getStringArray(R.array.speaker_names)
        val speakerIds    = resources.getStringArray(R.array.speaker_ids)

        val spinnerLanguage = dialog.findViewById<Spinner>(R.id.spinner_language)
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = langAdapter
        spinnerLanguage.setSelection(selectedLanguageIndex)

        val spinnerSpeaker = dialog.findViewById<Spinner>(R.id.spinner_speaker)
        val spkAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speakerNames)
        spkAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSpeaker.adapter = spkAdapter
        spinnerSpeaker.setSelection(selectedSpeakerIndex)

        dialog.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.findViewById<Button>(R.id.btn_apply).setOnClickListener {
            selectedLanguageIndex = spinnerLanguage.selectedItemPosition
            selectedSpeakerIndex  = spinnerSpeaker.selectedItemPosition

            ApiConfig.sarvamLanguage = languageCodes[selectedLanguageIndex]
            ApiConfig.sarvamSpeaker  = speakerIds[selectedSpeakerIndex]

            // Save custom API key
            val newApiKey = etApiKey.text.toString().trim()
            if (newApiKey.isNotEmpty()) {
                ApiConfig.sarvamApiKey = newApiKey
            }

            // Persist all preferences
            ApiConfig.savePreferences(this)

            updateVoiceInfoBar()
            viewModel.clearAudioCache()

            Toast.makeText(
                this,
                "Voice: ${speakerNames[selectedSpeakerIndex]} · ${languageNames[selectedLanguageIndex]}",
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateVoiceInfoBar() {
        val languageNames = resources.getStringArray(R.array.language_names)
        val speakerNames  = resources.getStringArray(R.array.speaker_names)
        val engineLabel = if (ApiConfig.ttsEngine == TTSEngine.GOOGLE) "Google" else "Sarvam"
        binding.tvVoiceInfo.text = "$engineLabel · ${languageNames[selectedLanguageIndex]} · ${speakerNames[selectedSpeakerIndex]}"
    }

    /** Format milliseconds to M:SS */
    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${String.format("%02d", seconds)}"
    }

    private fun observeViewModel() {
        viewModel.newsItems.observe(this) { items ->
            newsAdapter.submitList(items.toList()) // toList() ensures DiffUtil sees a new list
            sendStateToWatch()

            // Show/hide empty state
            if (items.isEmpty() && viewModel.isLoading.value != true) {
                binding.recyclerNews.visibility = View.GONE
            } else {
                binding.recyclerNews.visibility = View.VISIBLE
            }

            // Scroll to currently playing item
            val playingIndex = items.indexOfFirst { it.isCurrentlyPlaying }
            if (playingIndex >= 0) {
                binding.recyclerNews.post {
                    (binding.recyclerNews.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(playingIndex, 0)
                }
            }
        }

        viewModel.playbackState.observe(this) { state ->
            binding.btnPlayPause.setImageResource(
                if (state.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            binding.tvCurrentTitle.text  = state.currentTitle.ifEmpty { "Select a story to play" }
            binding.tvTopicLabel.text    = state.currentTopic.displayName
            binding.progressBar.progress = state.progress
            binding.tvTrackCounter.text  = if (state.totalItems > 0)
                "${state.currentIndex + 1} / ${state.totalItems}" else "— / —"
            binding.tvElapsed.text  = formatTime(state.elapsedMs)
            binding.tvDuration.text = formatTime(state.durationMs)
            sendStateToWatch()
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            error?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }

        // TTS generation progress (shown in player bar)
        viewModel.ttsProgress.observe(this) { progress ->
            if (progress != null) {
                binding.tvTtsProgress.visibility = View.VISIBLE
                binding.tvTtsProgress.text = progress
            } else {
                binding.tvTtsProgress.visibility = View.GONE
            }
        }

        // Sync chip selection when topic changes (e.g. from Wear command)
        viewModel.currentTopic.observe(this) { topic ->
            isSelectingChip = true
            topicChips.forEach { (t, chip) ->
                val isSelected = t == topic
                chip.isChecked = isSelected
                if (isSelected) {
                    chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#E07A3A"))
                    chip.setTextColor(Color.parseColor("#E07A3A"))
                    chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#2A1A10"))
                } else {
                    chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#3A3A3A"))
                    chip.setTextColor(Color.WHITE)
                    chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                }
            }
            isSelectingChip = false
        }

        // TTS engine toggle state
        viewModel.ttsEngine.observe(this) { engine ->
            updateTTSToggleButton(engine)
            updateVoiceInfoBar()
        }

        // Auto-play toggle state
        viewModel.autoPlayEnabled.observe(this) { enabled ->
            if (enabled) {
                binding.btnAutoPlay.setTextColor(Color.parseColor("#E07A3A"))
                binding.btnAutoPlay.setBackgroundColor(Color.parseColor("#2A1A10"))
            } else {
                binding.btnAutoPlay.setTextColor(Color.parseColor("#666666"))
                binding.btnAutoPlay.setBackgroundResource(com.newsreader.R.drawable.auto_play_bg)
            }
        }
    }

    // ── Wear OS communication ─────────────────────────────────────────

    private fun handleWearCommand(command: String) {
        // Re-send topics to watch in case it missed them at startup
        sendTopicsToWatch()
        when {
            command == ApiConfig.CMD_PLAY_PAUSE -> viewModel.togglePlayPause()
            command == ApiConfig.CMD_NEXT       -> viewModel.playNext()
            command == ApiConfig.CMD_PREV       -> viewModel.playPrevious()
            command == ApiConfig.CMD_VOLUME_UP  -> adjustVolume(AudioManager.ADJUST_RAISE)
            command == ApiConfig.CMD_VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            command.startsWith(ApiConfig.CMD_VOLUME_SET) -> {
                val level = command.removePrefix(ApiConfig.CMD_VOLUME_SET).toIntOrNull()
                if (level != null) setVolumeLevel(level)
            }
            command.startsWith(ApiConfig.CMD_TOPIC_CHANGE) -> {
                val topicName = command.removePrefix(ApiConfig.CMD_TOPIC_CHANGE)
                NewsTopic.entries.find { it.name == topicName }?.let {
                    selectTopic(it)
                }
            }
        }
    }

    private fun adjustVolume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        // Send updated state to watch so volume bar updates
        sendStateToWatch()
    }

    private fun setVolumeLevel(level: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, maxVol), 0)
        sendStateToWatch()
    }

    // Throttle state updates to watch (max once per 500ms)
    private var lastStateSentTime = 0L
    private var pendingStateSend = false

    private fun sendStateToWatch() {
        val now = System.currentTimeMillis()
        if (now - lastStateSentTime < 500) {
            // Schedule a delayed send if not already pending
            if (!pendingStateSend) {
                pendingStateSend = true
                binding.root.postDelayed({
                    pendingStateSend = false
                    doSendStateToWatch()
                }, 500)
            }
            return
        }
        doSendStateToWatch()
    }

    private fun doSendStateToWatch() {
        lastStateSentTime = System.currentTimeMillis()
        val state = viewModel.playbackState.value ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val stateJson = Gson().toJson(mapOf(
            "isPlaying"    to state.isPlaying,
            "currentIndex" to state.currentIndex,
            "totalItems"   to state.totalItems,
            "title"        to state.currentTitle,
            "topic"        to state.currentTopic.displayName,
            "progress"     to state.progress,
            "volume"       to volume,
            "maxVolume"    to maxVolume
        ))
        // Cache for WearableListenerService (always used for background commands)
        com.newsreader.service.WearableListenerService.lastKnownStateJson = stateJson
        sendMessageToWatch(ApiConfig.WEAR_PATH_STATE, stateJson.toByteArray())
    }

    private fun sendTopicsToWatch() {
        val topics = NewsTopic.entries.map { mapOf("name" to it.name, "display" to it.displayName) }
        val topicsJson = Gson().toJson(topics)
        sendMessageToWatch(ApiConfig.WEAR_PATH_TOPICS, topicsJson.toByteArray())
    }

    private fun sendMessageToWatch(path: String, data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this@MainActivity).connectedNodes)
                nodes.forEach { node ->
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, path, data)
                }
            } catch (e: Exception) {
                // Watch not connected — silent fail
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed")
    }
}
