package com.jhaiian.lucky.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class BlackBars(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun rotated(turns: Int): BlackBars {
        var bars = this
        repeat(((turns % 4) + 4) % 4) { bars = BlackBars(bars.bottom, bars.left, bars.top, bars.right) }
        return bars
    }

    fun mirrored(): BlackBars = BlackBars(right, top, left, bottom)
}

sealed interface BarDetection {
    class Detected(val bars: BlackBars) : BarDetection
    object Clean : BarDetection
    object Unreadable : BarDetection
    object Unclear : BarDetection
}

object BlackBarDetector {
    private const val SAMPLES = 12
    private const val SAMPLE_START = 0.06
    private const val SAMPLE_END = 0.92
    private const val MAX_SIDE = 720
    private const val MIN_SIDE = 32
    private const val LIT_LUMA = 24
    private const val LINE_FRACTION = 0.015f
    private const val MIN_RUN = 3
    private const val GAP_FRACTION = 0.02f
    private const val MIN_LIT = 0.03f
    private const val MIN_PICTURE = 0.2f
    private const val MIN_FRAMES = 3
    private const val POOL = 0.2f
    private const val MIN_BAR = 0.012f
    private const val LONE_BAR_MAX = 0.10f
    private const val EDGE_PAD = 0.5f
    private const val ASPECT_TOLERANCE = 0.3
    private val FALLBACK_POSITIONS = listOf(2_000L, 6_000L, 12_000L, 24_000L, 48_000L, 96_000L)

    private class LitFrame(val width: Int, val height: Int, val lit: BooleanArray)

    suspend fun detect(context: Context, uri: Uri, durationMs: Long, expectedAspect: Double): BarDetection =
        withContext(Dispatchers.IO) {
            val positions = positions(durationMs)
            val engine = engineFrames(context, uri, positions)
            var decoded = engine.size
            var frames = engine.mapNotNull { litFrame(it) }
            if (frames.size < MIN_FRAMES) {
                val system = systemFrames(context, uri, positions, expectedAspect)
                decoded += system.size
                val systemLit = system.mapNotNull { litFrame(it) }
                if (systemLit.size > frames.size) frames = systemLit
            }
            when {
                frames.size >= MIN_FRAMES -> measure(frames)
                decoded > 0 -> BarDetection.Unclear
                else -> BarDetection.Unreadable
            }
        }

    private fun positions(durationMs: Long): List<Long> {
        if (durationMs <= 0L) return FALLBACK_POSITIONS
        val start = durationMs * SAMPLE_START
        val span = durationMs * (SAMPLE_END - SAMPLE_START)
        return List(SAMPLES) { (start + span * it / (SAMPLES - 1)).toLong() }
    }

    private suspend fun engineFrames(context: Context, uri: Uri, positions: List<Long>): List<IntArray> {
        if (!LuckyEngine.isAvailable) return emptyList()
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return emptyList()
        val handle = LuckyEngine.createThumbnailer(descriptor.detachFd())
        if (handle == 0L) return emptyList()
        val frames = ArrayList<IntArray>()
        try {
            for (position in positions) {
                currentCoroutineContext().ensureActive()
                val data = runCatching { LuckyEngine.thumbnailerFrame(handle, position, MAX_SIDE, MAX_SIDE) }.getOrNull() ?: continue
                frames.add(data)
            }
        } finally {
            runCatching { LuckyEngine.releaseThumbnailer(handle) }
        }
        return frames
    }

    private suspend fun systemFrames(context: Context, uri: Uri, positions: List<Long>, expectedAspect: Double): List<IntArray> {
        val retriever = MediaMetadataRetriever()
        if (runCatching { retriever.setDataSource(context, uri) }.isFailure) {
            runCatching { retriever.release() }
            return emptyList()
        }
        val frames = ArrayList<IntArray>()
        try {
            val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val scale = if (sourceWidth > 0 && sourceHeight > 0) min(1f, MAX_SIDE / max(sourceWidth, sourceHeight).toFloat()) else 1f
            val targetWidth = (sourceWidth * scale).toInt()
            val targetHeight = (sourceHeight * scale).toInt()
            for (position in positions) {
                currentCoroutineContext().ensureActive()
                val bitmap = runCatching { grab(retriever, position * 1000L, targetWidth, targetHeight) }.getOrNull() ?: continue
                val data = runCatching { pixelsOf(bitmap) }.getOrNull()
                bitmap.recycle()
                if (data == null) continue
                if (expectedAspect > 0.0 && abs(ln((data[0].toDouble() / data[1]) / expectedAspect)) > ASPECT_TOLERANCE) continue
                frames.add(data)
            }
        } finally {
            runCatching { retriever.release() }
        }
        return frames
    }

    private fun grab(retriever: MediaMetadataRetriever, positionUs: Long, width: Int, height: Int): Bitmap? {
        val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && width > 1 && height > 1) {
            return retriever.getScaledFrameAtTime(positionUs, option, width, height)
        }
        val frame = retriever.getFrameAtTime(positionUs, option) ?: return null
        val shrink = min(1f, MAX_SIDE / max(frame.width, frame.height).toFloat())
        if (shrink >= 1f) return frame
        val scaled = Bitmap.createScaledBitmap(
            frame,
            (frame.width * shrink).toInt().coerceAtLeast(1),
            (frame.height * shrink).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== frame) frame.recycle()
        return scaled
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val data = IntArray(2 + width * height)
        data[0] = width
        data[1] = height
        bitmap.getPixels(data, 2, width, 0, 0, width, height)
        return data
    }

    private fun litFrame(data: IntArray): LitFrame? {
        if (data.size < 2) return null
        val width = data[0]
        val height = data[1]
        if (width < MIN_SIDE || height < MIN_SIDE || data.size < 2 + width * height) return null
        val lit = BooleanArray(width * height)
        var total = 0
        for (index in lit.indices) {
            val pixel = data[2 + index]
            val luma = (((pixel shr 16) and 0xFF) * 54 + ((pixel shr 8) and 0xFF) * 183 + (pixel and 0xFF) * 19) shr 8
            if (luma > LIT_LUMA) {
                lit[index] = true
                total++
            }
        }
        if (total < (width * height * MIN_LIT).toInt()) return null
        return LitFrame(width, height, lit)
    }

    private fun measure(all: List<LitFrame>): BarDetection {
        val width = all[0].width
        val height = all[0].height
        val frames = all.filter { it.width == width && it.height == height }
        if (frames.size < MIN_FRAMES) return BarDetection.Unclear

        val rowCounts = frames.map { frame ->
            IntArray(height) { y ->
                var count = 0
                val start = y * width
                for (x in 0 until width) if (frame.lit[start + x]) count++
                count
            }
        }
        val rows = pool(rowCounts, height)
        val rowRun = mainRun(rows, max(MIN_RUN, (width * LINE_FRACTION).toInt()), max(3, (height * GAP_FRACTION).toInt()))
            ?: return BarDetection.Unclear
        val top = rowRun.first
        val bottom = height - 1 - rowRun.last
        val pictureRows = height - top - bottom
        if (pictureRows < height * MIN_PICTURE) return BarDetection.Unclear

        val colCounts = frames.map { frame ->
            val counts = IntArray(width)
            for (y in top until height - bottom) {
                val start = y * width
                for (x in 0 until width) if (frame.lit[start + x]) counts[x]++
            }
            counts
        }
        val cols = pool(colCounts, width)
        val colRun = mainRun(cols, max(MIN_RUN, (pictureRows * LINE_FRACTION).toInt()), max(3, (width * GAP_FRACTION).toInt()))
            ?: return BarDetection.Unclear
        val left = colRun.first
        val right = width - 1 - colRun.last
        if (width - left - right < width * MIN_PICTURE) return BarDetection.Unclear

        var leftBar = edge(left, width)
        var rightBar = edge(right, width)
        var topBar = edge(top, height)
        var bottomBar = edge(bottom, height)
        leftBar = floorNoise(leftBar)
        rightBar = floorNoise(rightBar)
        topBar = floorNoise(topBar)
        bottomBar = floorNoise(bottomBar)
        if (leftBar == 0f && rightBar < LONE_BAR_MAX) rightBar = 0f
        if (rightBar == 0f && leftBar < LONE_BAR_MAX) leftBar = 0f
        if (topBar == 0f && bottomBar < LONE_BAR_MAX) bottomBar = 0f
        if (bottomBar == 0f && topBar < LONE_BAR_MAX) topBar = 0f
        val clean = leftBar == 0f && topBar == 0f && rightBar == 0f && bottomBar == 0f
        return if (clean) BarDetection.Clean else BarDetection.Detected(BlackBars(leftBar, topBar, rightBar, bottomBar))
    }

    private fun pool(counts: List<IntArray>, size: Int): IntArray {
        val frames = counts.size
        val rank = frames - max(1, ceil(frames * POOL).toInt())
        val column = IntArray(frames)
        return IntArray(size) { index ->
            for (frame in 0 until frames) column[frame] = counts[frame][index]
            column.sort()
            column[rank]
        }
    }

    private fun mainRun(counts: IntArray, need: Int, gap: Int): IntRange? {
        var bestStart = -1
        var bestEnd = -1
        var bestMass = -1L
        var start = -1
        var last = -1
        var mass = 0L
        for (index in counts.indices) {
            if (counts[index] < need) continue
            if (start < 0) {
                start = index
                mass = 0L
            } else if (index - last - 1 >= gap) {
                if (mass > bestMass) {
                    bestStart = start
                    bestEnd = last
                    bestMass = mass
                }
                start = index
                mass = 0L
            }
            last = index
            mass += counts[index]
        }
        if (start >= 0 && mass > bestMass) {
            bestStart = start
            bestEnd = last
        }
        return if (bestStart < 0) null else bestStart..bestEnd
    }

    private fun edge(count: Int, size: Int): Float = if (count > 0) (count + EDGE_PAD) / size else 0f

    private fun floorNoise(bar: Float): Float = if (bar >= MIN_BAR) bar else 0f
}
