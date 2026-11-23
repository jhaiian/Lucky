package com.jhaiian.lucky.ui.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.QueueItem
import com.jhaiian.lucky.data.RememberedTracks
import com.jhaiian.lucky.data.SavedSubtitle
import com.jhaiian.lucky.data.TrackLanguage
import com.jhaiian.lucky.data.WatchHistory
import com.jhaiian.lucky.engine.LuckyPlayer
import com.jhaiian.lucky.engine.PlaybackState
import com.jhaiian.lucky.engine.PlayerStatus
import com.jhaiian.lucky.engine.RendererPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PRELOAD_WINDOW_MS = 10_000L
private const val PRELOAD_SHORT_FRACTION = 0.15
private const val PRELOAD_HYSTERESIS_MS = 2_000L

internal fun preloadWindowMs(durationMs: Long): Long = minOf(PRELOAD_WINDOW_MS, (durationMs * PRELOAD_SHORT_FRACTION).toLong())

internal fun preloadRearmed(armed: Boolean, remainingMs: Long, durationMs: Long): Boolean {
    val window = preloadWindowMs(durationMs)
    return if (armed) remainingMs <= window + PRELOAD_HYSTERESIS_MS else remainingMs <= window
}

internal fun isLowRamDevice(context: Context): Boolean =
    context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true

internal fun closeInBackground(player: LuckyPlayer) {
    if (!player.isReleased) Thread { player.close() }.start()
}

internal fun awaitTracks(player: LuckyPlayer): PlayerStatus? {
    repeat(40) {
        if (player.isReleased) return null
        val status = player.status()
        if (status.audioTracks.isNotEmpty() || status.durationMs != null || status.state == PlaybackState.ERROR) return status
        Thread.sleep(40)
    }
    return null
}

internal suspend fun chooseAudioTrack(player: LuckyPlayer, preferred: String) {
    val ready = withContext(Dispatchers.IO) { awaitTracks(player) }
    val chosen = ready?.let { TrackLanguage.pick(it.audioTracks, preferred) }
    if (chosen != null && chosen.index != ready.selectedAudio) player.selectAudio(chosen.index)
}

internal class RestoredTracks(val audio: Boolean, val subtitle: Boolean)

internal suspend fun restoreTracks(player: LuckyPlayer, remembered: RememberedTracks): RestoredTracks {
    val ready = withContext(Dispatchers.IO) { awaitTracks(player) } ?: return RestoredTracks(audio = false, subtitle = false)
    var audioRestored = false
    var subtitleRestored = false
    remembered.audio?.let { saved ->
        val target = when {
            saved.off -> if (ready.hasVideo) -1 else null
            else -> TrackLanguage.match(ready.audioTracks, saved)?.index
        }
        if (target != null) {
            if (target != (ready.selectedAudio ?: -2)) player.selectAudio(target)
            audioRestored = true
        }
    }
    remembered.subtitle?.let { saved ->
        val embedded = saved.track?.let { track -> TrackLanguage.match(ready.subtitleTracks.filter { !it.external }, track) }
        when {
            saved.off -> {
                player.selectSubtitle(null)
                subtitleRestored = true
            }
            embedded != null -> {
                if (embedded.index != ready.selectedSubtitle) player.selectSubtitle(embedded.index)
                subtitleRestored = true
            }
        }
    }
    return RestoredTracks(audio = audioRestored, subtitle = subtitleRestored)
}

private class AttachedSidecar(val id: Int, val uri: Uri, val name: String)

internal suspend fun attachSidecars(
    context: Context,
    player: LuckyPlayer,
    video: Uri,
    autoSubtitles: Boolean,
    remembered: SavedSubtitle? = null,
    onAttached: (Int, String, String) -> Unit = { _, _, _ -> }
) {
    val sidecars = withContext(Dispatchers.IO) {
        findSidecarSubtitles(context, video).mapNotNull { sidecar ->
            player.addSubtitle(context, sidecar.uri, sidecar.name)?.let { id -> AttachedSidecar(id, sidecar.uri, sidecar.name) }
        }
    }
    sidecars.forEach { onAttached(it.id, it.uri.toString(), it.name) }
    if (remembered?.external == true) {
        val name = remembered.externalName.orEmpty()
        val matched = sidecars.firstOrNull { it.name == name }?.id
        val reopened = matched ?: remembered.externalUri?.let { stored ->
            withContext(Dispatchers.IO) { player.addSubtitle(context, Uri.parse(stored), name) }?.also { id -> onAttached(id, stored, name) }
        }
        if (reopened != null) {
            player.selectSubtitle(reopened)
            return
        }
    }
    if (sidecars.isNotEmpty() && autoSubtitles) {
        val current = player.status()
        if (current.selectedSubtitle == null) {
            val language = TrackLanguage.appLocale(context).language
            val preferred = sidecars.firstOrNull { sidecar ->
                val code = TrackLanguage.iso3(sidecarLanguage(sidecar.name))
                code != null && code == TrackLanguage.iso3(language)
            } ?: sidecars.first()
            player.selectSubtitle(preferred.id)
        }
    }
}

internal class PreloadSetup(
    val preferHardware: Boolean,
    val renderer: RendererPreference,
    val preferredAudio: String?,
    val autoSubtitles: Boolean,
    val remembered: RememberedTracks?,
    val apply: (LuckyPlayer) -> Unit
)

internal class PreloadedItem(
    val uri: String,
    val player: LuckyPlayer,
    val preferHardware: Boolean,
    val renderer: RendererPreference
)

internal class NextItemPreloader(private val context: Context, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var target: String? = null
    private var building: LuckyPlayer? = null
    private var ready: PreloadedItem? = null

    fun wants(uri: String): Boolean = target != uri

    fun isReady(uri: String): Boolean = ready?.let { it.uri == uri && !it.player.isReleased } == true

    fun preload(item: QueueItem, setup: PreloadSetup) {
        if (target == item.uri) return
        val previous = discard()
        target = item.uri
        job = scope.launch {
            previous?.cancelAndJoin()
            load(item, setup)
        }
    }

    fun take(uri: String, preferHardware: Boolean, renderer: RendererPreference): LuckyPlayer? {
        val item = ready
        if (item != null &&
            item.uri == uri &&
            item.preferHardware == preferHardware &&
            item.renderer == renderer &&
            !item.player.isReleased &&
            item.player.status().state != PlaybackState.ERROR
        ) {
            ready = null
            target = null
            job = null
            return item.player
        }
        cancel()
        return null
    }

    fun cancel() {
        discard()
        target = null
    }

    private fun discard(): Job? {
        val previous = job
        job = null
        previous?.cancel()
        building?.let { closeInBackground(it) }
        building = null
        ready?.let { closeInBackground(it.player) }
        ready = null
        return previous
    }

    private fun resumePosition(uri: String): Long? =
        if (LuckyPrefs.resumePlayback(context)) WatchHistory.get(context, uri)?.takeIf { it.resumable }?.positionMs else null

    private suspend fun load(item: QueueItem, setup: PreloadSetup) {
        var owned: LuckyPlayer? = null
        try {
            val uri = Uri.parse(item.uri)
            val player = withContext(Dispatchers.IO + NonCancellable) {
                LuckyPlayer.open(
                    context,
                    uri,
                    preferHardware = setup.preferHardware,
                    renderer = setup.renderer,
                    autoplay = false,
                    preload = true
                )
            } ?: return
            owned = player
            currentCoroutineContext().ensureActive()
            building = player
            setup.apply(player)
            player.beginAudio()
            val restored = setup.remembered?.let { restoreTracks(player, it) }
            if (restored?.audio != true) setup.preferredAudio?.let { chooseAudioTrack(player, it) }
            resumePosition(item.uri)?.let { player.seekTo(it) }
            val subtitleRestored = restored?.subtitle == true
            if (!setup.autoSubtitles && !subtitleRestored) player.selectSubtitle(null)
            attachSidecars(context, player, uri, setup.autoSubtitles && !subtitleRestored, setup.remembered?.subtitle?.takeIf { !subtitleRestored })
            currentCoroutineContext().ensureActive()
            if (player.isReleased) return
            ready = PreloadedItem(item.uri, player, setup.preferHardware, setup.renderer)
            building = null
            owned = null
        } finally {
            owned?.let {
                if (building === it) building = null
                closeInBackground(it)
            }
        }
    }
}
