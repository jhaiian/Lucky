package com.jhaiian.lucky.playback

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private class SystemMotionScale(private val context: Context) : MotionDurationScale {
    private var factor by mutableFloatStateOf(read())

    override val scaleFactor: Float
        get() = factor

    fun refresh() {
        factor = read()
    }

    private fun read(): Float = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
}

class CompositionKeeper(context: Context, owner: LifecycleOwner) : DefaultLifecycleObserver {
    private val base = AndroidUiDispatcher.CurrentThread
    private val clock = PausableMonotonicFrameClock(requireNotNull(base[MonotonicFrameClock])).apply { pause() }
    private val motion = SystemMotionScale(context.applicationContext)
    private val scope = CoroutineScope(base + clock + motion)
    private var started = false
    private var keepAwake = false

    val recomposer = Recomposer(base + clock + motion)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        scope.launch {
            NowPlaying.serviceActive.collect { active ->
                keepAwake = active
                sync()
            }
        }
        owner.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        motion.refresh()
        sync()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        sync()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
        recomposer.cancel()
        scope.cancel()
    }

    private fun sync() {
        if (started || keepAwake) clock.resume() else clock.pause()
    }
}
