package com.jhaiian.lucky.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import androidx.annotation.Keep
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Keep
class AudioSink internal constructor(private val route: AudioRoute) {

    private var track: AudioTrack? = null
    private var buffer: ByteBuffer? = null
    private var sampleRate = 0
    private var encoding = AudioFormat.ENCODING_PCM_FLOAT
    private var channelMask = 0
    private var bufferBytes = 0
    private val timestamp = AudioTimestamp()
    private var timestampFrames = -1L
    private var timestampNanos = 0L
    private var lastPoll = 0L
    private var resumedAt = 0L
    private var headBase = 0L
    private var lastHead = 0L
    @Volatile
    private var volume = 1f
    @Volatile
    private var speed = 1f

    private fun createTrack(): AudioTrack? {
        val created = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            null
        } ?: return null
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            return null
        }
        created.setVolume(volume)
        if (!isEncoded()) applySpeed(created)
        resetPosition()
        return created
    }

    private fun isEncoded() = encoding != AudioFormat.ENCODING_PCM_FLOAT

    private fun applySpeed(target: AudioTrack) {
        runCatching {
            target.playbackParams = PlaybackParams()
                .allowDefaults()
                .setSpeed(speed)
                .setPitch(1f)
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
        }
    }

    @Synchronized
    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.1f, 8f)
        if (isEncoded()) return
        track?.let {
            applySpeed(it)
            timestampFrames = -1L
            lastPoll = 0L
            resumedAt = System.nanoTime()
        }
    }

    @Keep
    @Synchronized
    fun open(sampleRate: Int, channels: Int, capacityBytes: Int): ByteBuffer? {
        release()
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> return null
        }
        encoding = AudioFormat.ENCODING_PCM_FLOAT
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
        if (minimum <= 0) return null
        this.sampleRate = sampleRate
        channelMask = mask
        bufferBytes = maxOf(minimum * 2, sampleRate / 4 * channels * 4)
        track = createTrack() ?: return null
        return ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.nativeOrder()).also { buffer = it }
    }

    @Keep
    @Synchronized
    fun openEncoded(sampleRate: Int, channels: Int, encoding: Int, capacityBytes: Int): ByteBuffer? {
        release()
        if (encoding !in ENCODED_FORMATS) return null
        this.sampleRate = sampleRate
        this.encoding = encoding
        for (mask in AudioRoute.encodedMasks(channels)) {
            val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
            if (minimum <= 0) continue
            channelMask = mask
            bufferBytes = maxOf(minimum * 2, maxBytesPerSecond(encoding) / 4)
            track = createTrack()
            if (track != null) {
                return ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.nativeOrder()).also { buffer = it }
            }
        }
        this.encoding = AudioFormat.ENCODING_PCM_FLOAT
        return null
    }

    @Keep
    fun maxChannels(): Int = route.maxChannels()

    @Keep
    fun supportsEncoded(sampleRate: Int, channels: Int, encoding: Int): Boolean =
        encoding in ENCODED_FORMATS && route.supportsEncoded(sampleRate, channels, encoding)

    private fun maxBytesPerSecond(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_AC3 -> 80_000
        AudioFormat.ENCODING_E_AC3 -> 768_000
        AudioFormat.ENCODING_DTS -> 192_000
        else -> 3_000_000
    }

    @Keep
    @Synchronized
    fun write(size: Int): Int {
        val active = track ?: return -1
        val data = buffer ?: return -1
        data.clear()
        data.limit(size.coerceAtMost(data.capacity()))
        return active.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING)
    }

    @Keep
    @Synchronized
    fun play() {
        val active = track ?: return
        if (active.playState != AudioTrack.PLAYSTATE_PLAYING) {
            timestampFrames = -1L
            lastPoll = 0L
            resumedAt = System.nanoTime()
            runCatching { active.play() }
        }
    }

    @Keep
    @Synchronized
    fun pause() {
        val active = track ?: return
        if (active.playState == AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { active.pause() }
        }
        timestampFrames = -1L
    }

    @Keep
    @Synchronized
    fun flush(): Boolean {
        val previous = track ?: return false
        runCatching { previous.pause() }
        runCatching { previous.flush() }
        previous.release()
        track = createTrack()
        return track != null
    }

    @Keep
    @Synchronized
    fun playedFrames(): Long {
        val active = track ?: return -1L
        val now = System.nanoTime()
        val playing = active.playState == AudioTrack.PLAYSTATE_PLAYING
        if (playing && now - lastPoll > 250_000_000L) {
            lastPoll = now
            if (active.getTimestamp(timestamp) && timestamp.framePosition > 0 && timestamp.nanoTime >= resumedAt) {
                timestampFrames = timestamp.framePosition
                timestampNanos = timestamp.nanoTime
            }
        }
        val head = headPosition(active)
        if (timestampFrames >= 0) {
            val elapsed = if (playing) now - timestampNanos else 0L
            val factor = if (isEncoded()) 1.0 else speed.toDouble()
            val extrapolated = timestampFrames + (elapsed * sampleRate * factor / 1_000_000_000.0).toLong()
            val ceiling = if (isEncoded() && head == 0L) Long.MAX_VALUE else head + sampleRate / 10
            return minOf(extrapolated, ceiling).coerceAtLeast(0L)
        }
        return head
    }

    private fun headPosition(active: AudioTrack): Long {
        val raw = active.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (raw < lastHead) headBase += 0x100000000L
        lastHead = raw
        return headBase + raw
    }

    private fun resetPosition() {
        timestampFrames = -1L
        timestampNanos = 0L
        lastPoll = 0L
        resumedAt = 0L
        headBase = 0L
        lastHead = 0L
    }

    @Keep
    @Synchronized
    fun setVolume(level: Float) {
        volume = level.coerceIn(0f, 1f)
        track?.setVolume(volume)
    }

    @Keep
    @Synchronized
    fun release() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            it.release()
        }
        track = null
        buffer = null
    }

    private companion object {
        val ENCODED_FORMATS = setOf(
            AudioFormat.ENCODING_AC3,
            AudioFormat.ENCODING_E_AC3,
            AudioFormat.ENCODING_DTS,
            AudioFormat.ENCODING_DTS_HD,
            AudioFormat.ENCODING_DOLBY_TRUEHD
        )
    }
}
