package com.newsreader.wear

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

class WearMainActivity : FragmentActivity(), MessageClient.OnMessageReceivedListener {

    private val TAG = "WearMainActivity"
    private lateinit var viewModel: WearViewModel

    // Views
    private lateinit var viewFlipper: ViewFlipper
    private lateinit var bgImage: ImageView
    private lateinit var bgOverlay: View
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnTopics: ImageButton
    private lateinit var btnVolume: ImageButton
    private lateinit var progressArc: ProgressBar
    private lateinit var topicsContainer: LinearLayout
    private lateinit var volumeSlider: CircularVolumeSlider
    private lateinit var tvVolumeLevel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_main)
        viewModel = ViewModelProvider(this)[WearViewModel::class.java]

        // Find all views
        viewFlipper = findViewById(R.id.view_flipper)
        bgImage = findViewById(R.id.bg_image)
        bgOverlay = findViewById(R.id.bg_overlay)
        tvTitle = findViewById(R.id.tv_title)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnTopics = findViewById(R.id.btn_topics)
        btnVolume = findViewById(R.id.btn_volume)
        progressArc = findViewById(R.id.progress_arc)
        topicsContainer = findViewById(R.id.topics_container)
        volumeSlider = findViewById(R.id.volume_slider)
        tvVolumeLevel = findViewById(R.id.tv_volume_level)

        setupButtons()
        setupRotaryInput()
        observeViewModel()

        // Enable marquee
        tvTitle.isSelected = true

        // Start foreground service safely
        try {
            val serviceIntent = Intent(this, WearForegroundService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.d(TAG, "Started WearForegroundService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
        WearListenerService.stateCallback = { data -> runOnUiThread { viewModel.updateState(data) } }
        WearListenerService.topicsCallback = { data -> runOnUiThread { viewModel.updateTopics(data) } }
        Log.d(TAG, "Listeners registered")
        viewModel.recheckConnection()
        viewFlipper.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(this)
        Log.d(TAG, "Message listener removed (service callbacks remain)")
    }

    override fun onDestroy() {
        super.onDestroy()
        WearListenerService.stateCallback = null
        WearListenerService.topicsCallback = null
        Log.d(TAG, "Activity destroyed, callbacks cleared")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val data = String(messageEvent.data)
        when (messageEvent.path) {
            WearConstants.PATH_STATE -> viewModel.updateState(data)
            WearConstants.PATH_TOPICS -> viewModel.updateTopics(data)
        }
    }

    private fun setupButtons() {
        btnPlayPause.setOnClickListener { viewModel.sendCommand(WearConstants.CMD_PLAY_PAUSE) }
        btnNext.setOnClickListener { viewModel.sendCommand(WearConstants.CMD_NEXT) }
        btnPrev.setOnClickListener { viewModel.sendCommand(WearConstants.CMD_PREV) }
        btnTopics.setOnClickListener { switchPage(1) }
        btnVolume.setOnClickListener { switchPage(2) }
        volumeSlider.onVolumeChanged = { level -> viewModel.setVolume(level) }
    }

    private fun switchPage(page: Int) {
        viewFlipper.displayedChild = page
        val showBg = if (page == 0) View.VISIBLE else View.GONE
        bgImage.visibility = showBg
        bgOverlay.visibility = showBg
    }

    private fun setupRotaryInput() {
        viewFlipper.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                if (delta > 0) viewModel.volumeUp()
                else if (delta < 0) viewModel.volumeDown()
                true
            } else false
        }
        viewFlipper.requestFocus()
    }

    private fun observeViewModel() {
        viewModel.playbackState.observe(this) { state ->
            tvTitle.text = state.title.take(60)
            tvSubtitle.text = "${state.topic} · ${state.currentIndex + 1}/${state.totalItems}"
            btnPlayPause.setImageResource(
                if (state.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            progressArc.progress = state.progress
            volumeSlider.max = state.maxVolume
            volumeSlider.progress = state.volume
            tvVolumeLevel.text = "${state.volume}"
        }

        viewModel.availableTopics.observe(this) { topics ->
            buildTopicsList(topics)
        }
    }

    private fun buildTopicsList(topicNames: List<String>) {
        topicsContainer.removeAllViews()
        val activeTopic = viewModel.playbackState.value?.topic ?: ""

        for (display in topicNames) {
            val topicKey = viewModel.resolveTopicKey(display)
            val isActive = display.equals(activeTopic, ignoreCase = true)

            val card = TextView(this).apply {
                text = if (isActive) "♪  $display" else display
                textSize = 15f
                setTextColor(if (isActive) 0xFFE07A3A.toInt() else 0xFFFFFFFF.toInt())
                setPadding(dp(20), dp(12), dp(20), dp(12))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(50)
                ).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(24).toFloat()
                    if (isActive) {
                        setColor(0xFF2A1E14.toInt())
                        setStroke(dp(2), 0xFFE07A3A.toInt())
                    } else {
                        setColor(0xFF2A2A2A.toInt())
                        setStroke(dp(1), 0xFF3D3D3D.toInt())
                    }
                }
                setOnClickListener {
                    viewModel.selectTopic(topicKey)
                    switchPage(0)
                }
            }
            topicsContainer.addView(card)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && viewFlipper.displayedChild != 0) {
            switchPage(0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
