package com.jhaiian.lucky.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TrackChoice(val index: Int, val language: String?, val codec: String?) {
    val off: Boolean
        get() = index < 0
}

data class SavedSubtitle(val track: TrackChoice?, val externalUri: String?, val externalName: String?) {
    val external: Boolean
        get() = externalUri != null || externalName != null

    val off: Boolean
        get() = track == null && !external
}

data class RememberedTracks(val audio: TrackChoice?, val subtitle: SavedSubtitle?)

data class WatchEntry(
    val uri: String,
    val title: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val audio: TrackChoice? = null,
    val subtitle: SavedSubtitle? = null
) {
    val tracks: RememberedTracks?
        get() = if (audio == null && subtitle == null) null else RememberedTracks(audio, subtitle)

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val finished: Boolean
        get() = durationMs > 0 && (positionMs >= durationMs * 0.95 || durationMs - positionMs < 10_000L)

    val resumable: Boolean
        get() = positionMs >= 5_000L && !finished
}

object WatchHistory {
    private const val FILE_NAME = "watch_history.json"
    private const val MAX_ENTRIES = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var loaded = false
    private val state = MutableStateFlow<Map<String, WatchEntry>>(emptyMap())

    fun entries(context: Context): StateFlow<Map<String, WatchEntry>> {
        ensureLoaded(context)
        return state.asStateFlow()
    }

    fun get(context: Context, uri: String): WatchEntry? {
        ensureLoaded(context)
        return state.value[uri]
    }

    fun continueWatching(context: Context, limit: Int = 20): List<WatchEntry> {
        ensureLoaded(context)
        return state.value.values.filter { it.resumable }.sortedByDescending { it.updatedAt }.take(limit)
    }

    fun record(
        context: Context,
        uri: String,
        title: String?,
        positionMs: Long,
        durationMs: Long,
        audio: TrackChoice? = null,
        subtitle: SavedSubtitle? = null
    ) {
        ensureLoaded(context)
        if (durationMs <= 0 && positionMs <= 0) return
        synchronized(lock) {
            val previous = state.value[uri]
            val entry = WatchEntry(
                uri,
                title,
                positionMs.coerceAtLeast(0L),
                durationMs.coerceAtLeast(0L),
                System.currentTimeMillis(),
                audio ?: previous?.audio,
                subtitle ?: previous?.subtitle
            )
            val updated = state.value.toMutableMap()
            updated[uri] = entry
            if (updated.size > MAX_ENTRIES) {
                updated.values.sortedBy { it.updatedAt }.take(updated.size - MAX_ENTRIES).forEach { updated.remove(it.uri) }
            }
            state.value = updated
        }
        persist(context)
    }

    fun remove(context: Context, uri: String) {
        ensureLoaded(context)
        synchronized(lock) {
            state.value = state.value - uri
        }
        persist(context)
    }

    fun clear(context: Context) {
        synchronized(lock) {
            state.value = emptyMap()
            loaded = true
        }
        persist(context)
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun trackJson(track: TrackChoice) = JSONObject()
        .put("index", track.index)
        .put("language", track.language ?: "")
        .put("codec", track.codec ?: "")

    private fun trackFrom(json: JSONObject): TrackChoice? =
        if (json.has("index")) {
            TrackChoice(json.optInt("index", -1), json.optString("language").takeIf { it.isNotEmpty() }, json.optString("codec").takeIf { it.isNotEmpty() })
        } else {
            null
        }

    private fun subtitleJson(subtitle: SavedSubtitle): JSONObject {
        val json = JSONObject()
            .put("externalUri", subtitle.externalUri ?: "")
            .put("externalName", subtitle.externalName ?: "")
        subtitle.track?.let { json.put("track", trackJson(it)) }
        return json
    }

    private fun subtitleFrom(json: JSONObject) = SavedSubtitle(
        track = json.optJSONObject("track")?.let { trackFrom(it) },
        externalUri = json.optString("externalUri").takeIf { it.isNotEmpty() },
        externalName = json.optString("externalName").takeIf { it.isNotEmpty() }
    )

    private fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val source = file(context)
            if (!source.exists()) return
            val parsed = runCatching {
                val array = JSONArray(source.readText())
                buildMap {
                    for (i in 0 until array.length()) {
                        val json = array.optJSONObject(i) ?: continue
                        val uri = json.optString("uri").takeIf { it.isNotEmpty() } ?: continue
                        put(
                            uri,
                            WatchEntry(
                                uri = uri,
                                title = json.optString("title").takeIf { it.isNotEmpty() },
                                positionMs = json.optLong("position"),
                                durationMs = json.optLong("duration"),
                                updatedAt = json.optLong("updated"),
                                audio = json.optJSONObject("audio")?.let { trackFrom(it) },
                                subtitle = json.optJSONObject("subtitle")?.let { subtitleFrom(it) }
                            )
                        )
                    }
                }
            }.getOrDefault(emptyMap())
            state.value = parsed
        }
    }

    private fun persist(context: Context) {
        val snapshot = state.value.values.toList()
        val target = file(context)
        scope.launch {
            synchronized(this@WatchHistory) {
                val array = JSONArray()
                snapshot.forEach { entry ->
                    val json = JSONObject()
                        .put("uri", entry.uri)
                        .put("title", entry.title ?: "")
                        .put("position", entry.positionMs)
                        .put("duration", entry.durationMs)
                        .put("updated", entry.updatedAt)
                    entry.audio?.let { json.put("audio", trackJson(it)) }
                    entry.subtitle?.let { json.put("subtitle", subtitleJson(it)) }
                    array.put(json)
                }
                val temp = File(target.parentFile, "$FILE_NAME.tmp")
                runCatching {
                    temp.writeText(array.toString())
                    if (!temp.renameTo(target)) {
                        target.writeText(array.toString())
                        temp.delete()
                    }
                }
            }
        }
    }
}
