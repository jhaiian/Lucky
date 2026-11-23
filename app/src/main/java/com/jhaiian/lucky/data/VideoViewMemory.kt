package com.jhaiian.lucky.data

import android.content.Context
import com.jhaiian.lucky.engine.FitMode
import com.jhaiian.lucky.engine.VideoTransform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

object VideoViewMemory {
    private const val FILE_NAME = "video_views.json"
    private const val MAX_ENTRIES = 500
    private const val TOLERANCE = 0.0005f

    private class Entry(val view: VideoTransform, val updatedAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val writeLock = Any()
    private var loaded = false
    private val entries = HashMap<String, Entry>()

    fun get(context: Context, uri: String): VideoTransform? {
        ensureLoaded(context)
        return synchronized(lock) { entries[uri]?.view }
    }

    fun save(context: Context, uri: String, view: VideoTransform, baseline: VideoTransform) {
        ensureLoaded(context)
        val changed = synchronized(lock) {
            if (same(view, baseline)) {
                entries.remove(uri) != null
            } else {
                val previous = entries[uri]
                if (previous != null && same(previous.view, view)) {
                    false
                } else {
                    entries[uri] = Entry(view, System.currentTimeMillis())
                    trim()
                    true
                }
            }
        }
        if (changed) persist(context)
    }

    fun remove(context: Context, uri: String) {
        ensureLoaded(context)
        val removed = synchronized(lock) { entries.remove(uri) != null }
        if (removed) persist(context)
    }

    fun clear(context: Context) {
        synchronized(lock) {
            entries.clear()
            loaded = true
        }
        persist(context)
    }

    private fun near(a: Float, b: Float) = abs(a - b) <= TOLERANCE

    private fun same(a: VideoTransform, b: VideoTransform) =
        a.fit == b.fit &&
            a.turns == b.turns &&
            a.mirror == b.mirror &&
            near(a.aspect, b.aspect) &&
            near(a.zoom, b.zoom) &&
            near(a.zoomX, b.zoomX) &&
            near(a.zoomY, b.zoomY) &&
            near(a.panX, b.panX) &&
            near(a.panY, b.panY)

    private fun trim() {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries.sortedBy { it.value.updatedAt }.take(entries.size - MAX_ENTRIES).forEach { entries.remove(it.key) }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

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

    private fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val source = file(context)
            if (!source.exists()) return
            runCatching {
                val array = JSONArray(source.readText())
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val uri = json.optString("uri").takeIf { it.isNotEmpty() } ?: continue
                    entries[uri] = Entry(viewFrom(json), json.optLong("updated"))
                }
            }
        }
    }

    private fun persist(context: Context) {
        val target = file(context)
        scope.launch {
            synchronized(writeLock) {
                runCatching {
                    val snapshot = synchronized(lock) { entries.map { it.key to it.value } }
                    val array = JSONArray()
                    snapshot.forEach { (uri, entry) ->
                        array.put(
                            JSONObject()
                                .put("uri", uri)
                                .put("fit", entry.view.fit.name)
                                .put("aspect", entry.view.aspect.toDouble())
                                .put("zoom", entry.view.zoom.toDouble())
                                .put("zoomX", entry.view.zoomX.toDouble())
                                .put("zoomY", entry.view.zoomY.toDouble())
                                .put("panX", entry.view.panX.toDouble())
                                .put("panY", entry.view.panY.toDouble())
                                .put("turns", entry.view.turns)
                                .put("mirror", entry.view.mirror)
                                .put("updated", entry.updatedAt)
                        )
                    }
                    val temp = File(target.parentFile, "$FILE_NAME.tmp")
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
