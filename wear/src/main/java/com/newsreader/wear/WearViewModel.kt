package com.newsreader.wear

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.*

data class WearPlaybackState(
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0,
    val totalItems: Int = 0,
    val title: String = "Loading news...",
    val topic: String = "World",
    val progress: Int = 0,
    val volume: Int = 7,
    val maxVolume: Int = 15
)

object WearConstants {
    const val CMD_PLAY_PAUSE   = "play_pause"
    const val CMD_NEXT         = "next"
    const val CMD_PREV         = "previous"
    const val CMD_VOLUME_UP    = "volume_up"
    const val CMD_VOLUME_DOWN  = "volume_down"
    const val CMD_VOLUME_SET   = "volume_set:"
    const val PATH_CONTROL     = "/news/control"
    const val PATH_STATE       = "/news/state"
    const val PATH_TOPICS      = "/news/topics"
}

class WearViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "WearViewModel"

    private val _playbackState  = MutableLiveData(WearPlaybackState())
    val playbackState: LiveData<WearPlaybackState> = _playbackState

    private val _availableTopics = MutableLiveData<List<String>>(emptyList())
    val availableTopics: LiveData<List<String>> = _availableTopics

    private val _isConnected    = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private var currentTopicIndex = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init { checkConnection() }

    fun updateState(stateJson: String) {
        try {
            val isPlaying     = stateJson.contains("\"isPlaying\":true")
            val title         = extractJsonString(stateJson, "title")
            val topic         = extractJsonString(stateJson, "topic")
            val currentIndex  = extractJsonInt(stateJson, "currentIndex")
            val totalItems    = extractJsonInt(stateJson, "totalItems")
            val progress      = extractJsonInt(stateJson, "progress")
            val volume        = extractJsonInt(stateJson, "volume")
            val maxVolume     = extractJsonInt(stateJson, "maxVolume").let { if (it > 0) it else 15 }

            _playbackState.postValue(WearPlaybackState(
                isPlaying    = isPlaying,
                title        = title,
                topic        = topic,
                currentIndex = currentIndex,
                totalItems   = totalItems,
                progress     = progress,
                volume       = volume,
                maxVolume    = maxVolume
            ))
            _isConnected.postValue(true)

            // Sync current topic index with received topic
            val topics = _availableTopics.value ?: emptyList()
            val idx = topics.indexOfFirst { it.equals(topic, ignoreCase = true) }
            if (idx >= 0) currentTopicIndex = idx

            Log.d(TAG, "State updated: playing=$isPlaying, $topic, ${currentIndex+1}/$totalItems, vol=$volume/$maxVolume")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse state", e)
        }
    }

    // Map of display name → enum name (e.g. "Tech" → "TECHNOLOGY")
    private val topicKeyMap = mutableMapOf<String, String>()

    fun updateTopics(topicsJson: String) {
        try {
            val displayNames = Regex("\"display\":\"([^\"]+)\"")
                .findAll(topicsJson)
                .map { it.groupValues[1] }
                .toList()
            val enumNames = Regex("\"name\":\"([^\"]+)\"")
                .findAll(topicsJson)
                .map { it.groupValues[1] }
                .toList()
            // Build mapping: display → enum name
            topicKeyMap.clear()
            displayNames.zip(enumNames).forEach { (display, enumName) ->
                topicKeyMap[display] = enumName
            }
            _availableTopics.postValue(displayNames)
            Log.d(TAG, "Topics updated: $displayNames, keys: $topicKeyMap")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse topics", e)
        }
    }

    /** Resolve display name to enum name for sending commands */
    fun resolveTopicKey(displayName: String): String {
        return topicKeyMap[displayName] ?: displayName.uppercase().replace(" ", "_")
    }

    fun sendCommand(command: String) {
        scope.launch {
            try {
                val nodes = Tasks.await(
                    Wearable.getNodeClient(getApplication()).connectedNodes
                )
                if (nodes.isEmpty()) {
                    _isConnected.postValue(false)
                    Log.w(TAG, "No connected nodes")
                    return@launch
                }
                nodes.forEach { node ->
                    Log.d(TAG, "Sending command '$command' to node ${node.displayName}")
                    Wearable.getMessageClient(getApplication())
                        .sendMessage(node.id, WearConstants.PATH_CONTROL, command.toByteArray())
                }
            } catch (e: Exception) {
                _isConnected.postValue(false)
                Log.e(TAG, "Failed to send command: $command", e)
            }
        }
    }

    fun volumeUp() {
        sendCommand(WearConstants.CMD_VOLUME_UP)
        // Optimistic UI update
        val current = _playbackState.value ?: return
        val newVol = (current.volume + 1).coerceAtMost(current.maxVolume)
        _playbackState.postValue(current.copy(volume = newVol))
    }

    fun volumeDown() {
        sendCommand(WearConstants.CMD_VOLUME_DOWN)
        // Optimistic UI update
        val current = _playbackState.value ?: return
        val newVol = (current.volume - 1).coerceAtLeast(0)
        _playbackState.postValue(current.copy(volume = newVol))
    }

    fun setVolume(level: Int) {
        val current = _playbackState.value ?: return
        val clamped = level.coerceIn(0, current.maxVolume)
        sendCommand("${WearConstants.CMD_VOLUME_SET}$clamped")
        // Optimistic UI update
        _playbackState.postValue(current.copy(volume = clamped))
    }

    fun cycleNextTopic() {
        val topics = _availableTopics.value ?: return
        if (topics.isEmpty()) return
        currentTopicIndex = (currentTopicIndex + 1) % topics.size
        val topicDisplay = topics[currentTopicIndex]
        val topicName = resolveTopicKey(topicDisplay)
        Log.d(TAG, "Cycling to topic: $topicDisplay ($topicName)")
        sendCommand("topic:$topicName")
    }

    /** Select a specific topic by enum name */
    fun selectTopic(topicName: String) {
        val topics = _availableTopics.value ?: return
        // Match by resolved key or display name
        val idx = topics.indexOfFirst { resolveTopicKey(it) == topicName }
        if (idx >= 0) currentTopicIndex = idx
        Log.d(TAG, "Selecting topic: $topicName")
        sendCommand("topic:$topicName")
    }

    /** Get the next topic display name for preview on button */
    fun getNextTopicName(): String {
        val topics = _availableTopics.value ?: return "Change Topic"
        if (topics.isEmpty()) return "Change Topic"
        val nextIdx = (currentTopicIndex + 1) % topics.size
        return "⟳ ${topics[nextIdx]}"
    }

    private fun checkConnection() {
        scope.launch {
            try {
                val nodes = Tasks.await(
                    Wearable.getNodeClient(getApplication()).connectedNodes
                )
                val connected = nodes.isNotEmpty()
                _isConnected.postValue(connected)
                if (connected) {
                    Log.d(TAG, "Connected to: ${nodes.map { it.displayName }}")
                } else {
                    Log.w(TAG, "No connected nodes found")
                }
            } catch (e: Exception) {
                _isConnected.postValue(false)
                Log.e(TAG, "Connection check failed", e)
            }
        }
    }

    fun recheckConnection() = checkConnection()

    private fun extractJsonString(json: String, key: String): String {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractJsonInt(json: String, key: String): Int {
        val regex = Regex("\"$key\"\\s*:\\s*(\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}
