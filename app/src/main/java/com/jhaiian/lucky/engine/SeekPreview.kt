package com.jhaiian.lucky.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SeekPreview private constructor(
    private val handle: Long,
    private val maxWidth: Int,
    private val maxHeight: Int,
    private val onFrame: (Long, Bitmap) -> Unit
) : AutoCloseable {

    private class Job(val positionMs: Long, val user: Boolean)

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val strip = ArrayDeque<Long>()
    private val cache = LinkedHashMap<Long, Bitmap>(64, 0.75f, true)
    private val failedBuckets = HashSet<Long>()
    private var requested = NONE
    private var closed = false
    private var cacheBytes = 0
    private var failures = 0
    private var background = false

    @Volatile
    var unavailable = false
        private set

    private val worker = Thread({ loop() }, "lucky-preview").apply {
        isDaemon = true
        start()
    }

    fun request(positionMs: Long) {
        if (positionMs < 0L || unavailable) return
        lock.withLock {
            if (closed) return
            requested = positionMs
            wake.signal()
        }
    }

    fun prefetch(positionsMs: List<Long>) {
        lock.withLock {
            if (closed) return
            strip.clear()
            if (!unavailable) {
                positionsMs.filter { it >= 0L && peek(it) == null }.forEach { strip.addLast(it) }
            }
            wake.signal()
        }
    }

    fun peek(positionMs: Long): Bitmap? = synchronized(cache) { cache[positionMs / BUCKET_MS] }

    override fun close() {
        lock.withLock {
            closed = true
            requested = NONE
            strip.clear()
            wake.signalAll()
        }
    }

    private fun next(rest: Boolean): Job? {
        lock.lock()
        try {
            if (rest && requested == NONE && !closed) wake.await(STRIP_PAUSE_MS, TimeUnit.MILLISECONDS)
            while (true) {
                if (closed) return null
                val position = requested
                if (position != NONE) {
                    requested = NONE
                    return Job(position, true)
                }
                val queued = strip.removeFirstOrNull()
                if (queued != null) return Job(queued, false)
                wake.await()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun loop() {
        try {
            var rest = false
            while (true) {
                val job = next(rest) ?: break
                process(job)
                rest = !job.user
            }
        } finally {
            synchronized(cache) {
                cache.clear()
                cacheBytes = 0
            }
            runCatching { LuckyEngine.releaseThumbnailer(handle) }
        }
    }

    private fun prioritize(user: Boolean) {
        if (background == !user) return
        background = !user
        runCatching { Process.setThreadPriority(if (user) Process.THREAD_PRIORITY_DEFAULT else Process.THREAD_PRIORITY_BACKGROUND) }
    }

    private fun process(job: Job) {
        val bucket = job.positionMs / BUCKET_MS
        val hit = synchronized(cache) { cache[bucket] }
        if (hit != null) {
            if (job.user) onFrame(bucket, hit)
            return
        }
        if (bucket in failedBuckets) return
        prioritize(job.user)
        val data = runCatching {
            LuckyEngine.thumbnailerFrame(handle, bucket * BUCKET_MS + BUCKET_MS / 2, maxWidth, maxHeight)
        }.getOrNull()
        val bitmap = data?.let { bitmapFrom(it) }
        if (bitmap == null) {
            if (failedBuckets.size < MAX_FAILED_BUCKETS) failedBuckets.add(bucket)
            if (++failures >= MAX_FAILURES) unavailable = true
            return
        }
        failures = 0
        store(bucket, bitmap)
        if (job.user) onFrame(bucket, bitmap)
    }

    private fun bitmapFrom(data: IntArray): Bitmap? {
        if (data.size < 2) return null
        val width = data[0]
        val height = data[1]
        if (width <= 0 || height <= 0 || data.size < 2 + width * height) return null
        return runCatching { Bitmap.createBitmap(data, 2, width, width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
    }

    private fun store(bucket: Long, bitmap: Bitmap) {
        synchronized(cache) {
            cache.put(bucket, bitmap)?.let { cacheBytes -= it.byteCount }
            cacheBytes += bitmap.byteCount
            val iterator = cache.entries.iterator()
            while (cacheBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == bucket) continue
                cacheBytes -= entry.value.byteCount
                iterator.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_WIDTH = 320
        const val DEFAULT_HEIGHT = 160
        const val BUCKET_MS = 2_000L
        private const val NONE = -1L
        private const val STRIP_PAUSE_MS = 250L
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
        private const val MAX_FAILURES = 6
        private const val MAX_FAILED_BUCKETS = 256

        fun open(
            context: Context,
            uri: Uri,
            maxWidth: Int = DEFAULT_WIDTH,
            maxHeight: Int = DEFAULT_HEIGHT,
            onFrame: (Long, Bitmap) -> Unit
        ): SeekPreview? {
            if (!LuckyEngine.isAvailable) return null
            val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
            val handle = LuckyEngine.createThumbnailer(descriptor.detachFd())
            return if (handle != 0L) SeekPreview(handle, maxWidth, maxHeight, onFrame) else null
        }
    }
}
