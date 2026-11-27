package com.jhaiian.lucky.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.jhaiian.lucky.MainActivity
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.VideoThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var art: Bitmap? = null
    private var artUri: String? = null
    private var artJob: Job? = null
    private var metadataKey: Triple<String, String, Long>? = null
    private var metadataArt: Bitmap? = null
    private var stopping = false

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() {
            NowPlaying.controls?.play()
        }

        override fun onPause() {
            NowPlaying.controls?.pause()
        }

        override fun onSeekTo(pos: Long) {
            NowPlaying.controls?.seekTo(pos.coerceAtLeast(0L))
        }

        override fun onSkipToNext() {
            NowPlaying.controls?.next()
        }

        override fun onSkipToPrevious() {
            NowPlaying.controls?.previous()
        }

        override fun onFastForward() {
            seekBy(SKIP_MS)
        }

        override fun onRewind() {
            seekBy(-SKIP_MS)
        }

        override fun onStop() {
            close()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.playback_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.playback_channel_description)
                setShowBadge(false)
            }
        )
        session = MediaSession(this, SESSION_TAG).apply {
            setCallback(callback)
            setSessionActivity(contentIntent())
            isActive = true
        }
        NowPlaying.markRunning(true)
        goForeground(NowPlaying.state.value)
        scope.launch {
            NowPlaying.state.collect { state ->
                if (state == null) shutdown() else publish(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = NowPlaying.state.value
        goForeground(state)
        when (intent?.action) {
            ACTION_TOGGLE -> if (state?.playing == true) NowPlaying.controls?.pause() else NowPlaying.controls?.play()
            ACTION_NEXT -> NowPlaying.controls?.next()
            ACTION_PREVIOUS -> NowPlaying.controls?.previous()
            ACTION_CLOSE -> close()
        }
        if (state == null) shutdown()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        close()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        artJob?.cancel()
        session?.let { active ->
            runCatching {
                active.isActive = false
                active.release()
            }
        }
        session = null
        NowPlaying.markRunning(false)
        super.onDestroy()
    }

    private fun seekBy(delta: Long) {
        val state = NowPlaying.state.value ?: return
        var target = (state.positionMs + delta).coerceAtLeast(0L)
        if (state.durationMs > 0L) target = target.coerceAtMost(state.durationMs)
        NowPlaying.controls?.seekTo(target)
    }

    private fun close() {
        NowPlaying.controls?.stop()
        shutdown()
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        NowPlaying.markRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(state: NowPlayingState?) {
        if (stopping) return
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(state),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }
    }

    private fun publish(state: NowPlayingState) {
        if (stopping) return
        val active = session ?: return
        loadArt(state.uri)
        val key = Triple(state.uri, state.title, if (state.seekable) state.durationMs else -1L)
        if (key != metadataKey || art !== metadataArt) {
            metadataKey = key
            metadataArt = art
            active.setMetadata(metadata(state))
        }
        active.setPlaybackState(playbackState(state))
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun loadArt(uri: String) {
        if (uri == artUri) return
        artUri = uri
        artJob?.cancel()
        val parsed = Uri.parse(uri)
        art = VideoThumbnails.cached(parsed)
        if (art != null) return
        artJob = scope.launch {
            val loaded = VideoThumbnails.load(this@PlaybackService, parsed) ?: return@launch
            if (artUri != uri) return@launch
            art = loaded
            NowPlaying.state.value?.takeIf { it.uri == uri }?.let { publish(it) }
        }
    }

    private fun metadata(state: NowPlayingState): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, state.title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (state.seekable && state.durationMs > 0L) state.durationMs else -1L)
        art?.let {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
        }
        return builder.build()
    }

    private fun playbackState(state: NowPlayingState): PlaybackState {
        var actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP
        if (state.seekable) {
            actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        }
        if (state.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (state.hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.positionMs,
                if (state.playing) state.speed else 0f,
                SystemClock.elapsedRealtime()
            )
            .build()
    }

    private fun buildNotification(state: NowPlayingState?): Notification {
        val playing = state?.playing == true
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_lucky)
            .setContentTitle(state?.title ?: getString(R.string.app_name))
            .setContentIntent(contentIntent())
            .setDeleteIntent(serviceIntent(ACTION_CLOSE, REQUEST_CLOSE))
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        art?.let { builder.setLargeIcon(it) }
        val compact = mutableListOf<Int>()
        var index = 0
        if (state?.hasPrevious == true) {
            builder.addAction(action(R.drawable.ic_media_previous, R.string.player_previous, ACTION_PREVIOUS, REQUEST_PREVIOUS))
            compact += index++
        }
        builder.addAction(
            action(
                if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
                if (playing) R.string.player_pause else R.string.player_play,
                ACTION_TOGGLE,
                REQUEST_TOGGLE
            )
        )
        compact += index++
        if (state?.hasNext == true) {
            builder.addAction(action(R.drawable.ic_media_next, R.string.player_next, ACTION_NEXT, REQUEST_NEXT))
            compact += index++
        }
        builder.addAction(action(R.drawable.ic_media_close, R.string.playback_close, ACTION_CLOSE, REQUEST_CLOSE))
        val style = Notification.MediaStyle().setShowActionsInCompactView(*compact.toIntArray())
        session?.let { style.setMediaSession(it.sessionToken) }
        builder.setStyle(style)
        return builder.build()
    }

    private fun action(icon: Int, label: Int, action: String, request: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), serviceIntent(action, request)).build()

    private fun serviceIntent(action: String, request: Int): PendingIntent =
        PendingIntent.getService(
            this,
            request,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val SESSION_TAG = "Lucky"
        private const val NOTIFICATION_ID = 4210
        private const val SKIP_MS = 10_000L
        private const val ACTION_TOGGLE = "com.jhaiian.lucky.playback.TOGGLE"
        private const val ACTION_NEXT = "com.jhaiian.lucky.playback.NEXT"
        private const val ACTION_PREVIOUS = "com.jhaiian.lucky.playback.PREVIOUS"
        private const val ACTION_CLOSE = "com.jhaiian.lucky.playback.CLOSE"
        private const val REQUEST_OPEN = 1
        private const val REQUEST_TOGGLE = 2
        private const val REQUEST_NEXT = 3
        private const val REQUEST_PREVIOUS = 4
        private const val REQUEST_CLOSE = 5
    }
}
