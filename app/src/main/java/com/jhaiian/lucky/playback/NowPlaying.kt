package com.jhaiian.lucky.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PlaybackControls {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun stop()
}

data class NowPlayingState(
    val uri: String,
    val title: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val seekable: Boolean
)

object NowPlaying {
    private val current = MutableStateFlow<NowPlayingState?>(null)
    private val running = MutableStateFlow(false)

    val state: StateFlow<NowPlayingState?> = current.asStateFlow()
    val serviceActive: StateFlow<Boolean> = running.asStateFlow()

    @Volatile
    var controls: PlaybackControls? = null

    fun publish(value: NowPlayingState) {
        current.value = value
    }

    fun release() {
        current.value = null
        controls = null
    }

    internal fun markRunning(active: Boolean) {
        running.value = active
    }

    fun startService(context: Context) {
        if (running.value || current.value == null) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }
    }
}
