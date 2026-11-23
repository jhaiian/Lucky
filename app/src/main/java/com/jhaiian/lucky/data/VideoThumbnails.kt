package com.jhaiian.lucky.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object VideoThumbnails {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val failed = HashSet<String>()
    private val permits = Semaphore(3)

    fun cached(uri: Uri): Bitmap? = cache.get(uri.toString())

    suspend fun load(context: Context, uri: Uri, width: Int = 384, height: Int = 216): Bitmap? {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        synchronized(failed) { if (key in failed) return null }
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(key) ?: decode(context.applicationContext, uri, width, height)?.also { cache.put(key, it) }
                    ?: run {
                        synchronized(failed) { failed.add(key) }
                        null
                    }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun decode(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { resolver.loadThumbnail(uri, Size(width, height), null) }.getOrNull()?.let { return it }
        } else if (uri.authority == MediaStore.AUTHORITY) {
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
            if (id != null && id >= 0) {
                runCatching {
                    MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                }.getOrNull()?.let { return it }
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = (duration * 1000L / 10L).coerceIn(0L, 10_000_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
            } else {
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                    val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height, 1f)
                    if (scale < 1f) {
                        Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
                    } else {
                        frame
                    }
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun clear() {
        cache.evictAll()
        synchronized(failed) { failed.clear() }
    }
}
