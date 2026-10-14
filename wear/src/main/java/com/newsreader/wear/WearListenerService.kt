package com.newsreader.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearListenerService : WearableListenerService() {

    private val TAG = "WearListenerSvc"

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val data = String(messageEvent.data)
        Log.d(TAG, "Message received on path: ${messageEvent.path}, data size: ${data.length}")

        when (messageEvent.path) {
            WearConstants.PATH_STATE -> {
                Log.d(TAG, "State update from phone (service)")
                stateCallback?.invoke(data)
                    ?: Log.w(TAG, "No state callback (activity not in foreground)")
            }
            WearConstants.PATH_TOPICS -> {
                Log.d(TAG, "Topics update from phone (service)")
                topicsCallback?.invoke(data)
                    ?: Log.w(TAG, "No topics callback (activity not in foreground)")
            }
            else -> {
                Log.d(TAG, "Other path: ${messageEvent.path}")
            }
        }
    }

    companion object {
        var stateCallback: ((String) -> Unit)? = null
        var topicsCallback: ((String) -> Unit)? = null
    }
}
