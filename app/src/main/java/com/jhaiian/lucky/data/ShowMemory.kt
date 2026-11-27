package com.jhaiian.lucky.data

import android.content.Context
import android.content.SharedPreferences
import com.jhaiian.lucky.engine.FitMode
import com.jhaiian.lucky.engine.VideoTransform
import org.json.JSONObject
import java.util.Locale

data class ShowSettings(
    val audio: TrackChoice?,
    val subtitle: SavedSubtitle?,
    val subtitleDelayMs: Long,
    val audioDelayMs: Int,
    val view: VideoTransform?
) {
    val tracks: RememberedTracks?
        get() = if (audio == null && subtitle == null) null else RememberedTracks(audio, subtitle)
}

object ShowMemory {
    private const val FILE_NAME = "show_memory"
    private const val MAX_SHOWS = 200

    private val episodePattern = Regex(
        "(?i)[\\s._\\-\\[(]*(?:s\\d{1,2}[\\s._\\-]*e\\d{1,4}|\\d{1,2}x\\d{2,4}|season[\\s._\\-]*\\d{1,2}|episode[\\s._\\-]*\\d{1,4}|ep[\\s._\\-]?\\d{1,4}|e\\d{2,4}\\b)"
    )
    private val bracketPattern = Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}")
    private val separatorPattern = Regex("[\\s._\\-]+")
    private val copyPrefix = Regex("(?i)^copy of\\s+")

    fun keyFor(name: String?): String? {
        val raw = name?.substringBeforeLast('.', name)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = episodePattern.find(raw) ?: return null
        val head = raw.substring(0, match.range.first)
            .replace(copyPrefix, "")
            .replace(bracketPattern, " ")
            .replace(separatorPattern, " ")
            .trim()
            .lowercase(Locale.ROOT)
        return head.takeIf { it.length >= 2 }
    }

    private val seasonEpisode = Regex("(?i)s(\\d{1,2})[\\s._\\-]*e(\\d{1,4})")
    private val crossEpisode = Regex("(?i)(?<!\\d)(\\d{1,2})x(\\d{2,4})(?!\\d)")
    private val plainEpisode = Regex("(?i)(?:episode|ep|e)[\\s._\\-]?(\\d{1,4})")

    fun episodeNumber(name: String?): Long? {
        val text = name ?: return null
        seasonEpisode.find(text)?.let { return it.groupValues[1].toLong() * 10_000L + it.groupValues[2].toLong() }
        crossEpisode.find(text)?.let { return it.groupValues[1].toLong() * 10_000L + it.groupValues[2].toLong() }
        plainEpisode.find(text)?.let { return it.groupValues[1].toLong() }
        return null
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(context: Context, key: String?): ShowSettings? {
        if (key == null) return null
        val stored = prefs(context).getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(stored)
            ShowSettings(
                audio = json.optJSONObject("audio")?.let { WatchHistory.trackFrom(it) },
                subtitle = when {
                    json.optBoolean("subtitleOff", false) -> SavedSubtitle(null, null, null)
                    else -> json.optJSONObject("subtitle")?.let { WatchHistory.trackFrom(it) }?.let { SavedSubtitle(it, null, null) }
                },
                subtitleDelayMs = json.optLong("subtitleDelayMs", 0L),
                audioDelayMs = json.optInt("audioDelayMs", 0),
                view = json.optJSONObject("view")?.let { viewFrom(it) }
            )
        }.getOrNull()
    }

    fun saveTracks(context: Context, key: String?, audio: TrackChoice?, subtitle: SavedSubtitle?) {
        update(context, key) { json ->
            if (audio != null) json.put("audio", WatchHistory.trackJson(audio))
            if (subtitle != null) {
                val track = subtitle.track
                when {
                    subtitle.off -> {
                        json.put("subtitleOff", true)
                        json.remove("subtitle")
                    }
                    track != null -> {
                        json.put("subtitleOff", false)
                        json.put("subtitle", WatchHistory.trackJson(track))
                    }
                    else -> {
                        json.put("subtitleOff", false)
                        json.remove("subtitle")
                    }
                }
            }
        }
    }

    fun saveDelays(context: Context, key: String?, subtitleDelayMs: Long, audioDelayMs: Int) {
        update(context, key) { json ->
            json.put("subtitleDelayMs", subtitleDelayMs)
            json.put("audioDelayMs", audioDelayMs)
        }
    }

    fun saveView(context: Context, key: String?, view: VideoTransform) {
        if (key == null) return
        if (get(context, key)?.view == view) return
        update(context, key) { json ->
            json.put(
                "view",
                JSONObject()
                    .put("fit", view.fit.name)
                    .put("aspect", view.aspect.toDouble())
                    .put("zoom", view.zoom.toDouble())
                    .put("zoomX", view.zoomX.toDouble())
                    .put("zoomY", view.zoomY.toDouble())
                    .put("panX", view.panX.toDouble())
                    .put("panY", view.panY.toDouble())
                    .put("turns", view.turns)
                    .put("mirror", view.mirror)
            )
        }
    }

    private fun clean(value: Double, fallback: Float, min: Float, max: Float): Float =
        if (value.isNaN() || value.isInfinite()) fallback else value.toFloat().coerceIn(min, max)

    private fun viewFrom(json: JSONObject) = VideoTransform(
        fit = FitMode.parse(json.optString("fit")),
        aspect = clean(json.optDouble("aspect", 0.0), 0f, 0f, 10f),
        zoom = clean(json.optDouble("zoom", 1.0), 1f, 0.1f, 12f),
        zoomX = clean(json.optDouble("zoomX", 1.0), 1f, 0.1f, 12f),
        zoomY = clean(json.optDouble("zoomY", 1.0), 1f, 0.1f, 12f),
        panX = clean(json.optDouble("panX", 0.0), 0f, -12f, 12f),
        panY = clean(json.optDouble("panY", 0.0), 0f, -12f, 12f),
        turns = ((json.optInt("turns", 0) % 4) + 4) % 4,
        mirror = json.optBoolean("mirror", false)
    )

    private fun update(context: Context, key: String?, change: (JSONObject) -> Unit) {
        if (key == null) return
        val store = prefs(context)
        val json = store.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        change(json)
        json.put("updatedAt", System.currentTimeMillis())
        val editor = store.edit().putString(key, json.toString())
        val all = store.all
        if (all.size > MAX_SHOWS) {
            all.entries
                .filter { it.key != key }
                .sortedBy { entry -> (entry.value as? String)?.let { runCatching { JSONObject(it).optLong("updatedAt") }.getOrNull() } ?: 0L }
                .take(all.size - MAX_SHOWS)
                .forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
