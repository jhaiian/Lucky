package com.jhaiian.lucky.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

internal class AudioRoute(context: Context) {

    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var callback: AudioDeviceCallback? = null
    private var pending: Runnable? = null

    private fun activeDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val routed = runCatching { manager.getAudioDevicesForAttributes(attributes) }.getOrNull().orEmpty()
            if (routed.isNotEmpty()) return routed
        }
        val sinks = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return emptyList()
        val best = sinks
            .filter { it.isSink && PRIORITY.indexOf(it.type) >= 0 }
            .minByOrNull { PRIORITY.indexOf(it.type) }
        return listOfNotNull(best)
    }

    private fun directSupport(format: AudioFormat, bitstream: Boolean): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val support = AudioManager.getDirectPlaybackSupport(format, attributes)
            if (bitstream) {
                support and AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED != 0
            } else {
                support != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            legacyDirectSupport(format)
        } else {
            false
        }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun legacyDirectSupport(format: AudioFormat): Boolean =
        AudioTrack.isDirectPlaybackSupported(format, attributes)

    private fun directPcm(mask: Int): Boolean = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT).any { encoding ->
        directSupport(
            AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(PROBE_RATE)
                .setChannelMask(mask)
                .build(),
            bitstream = false
        )
    }

    private fun deviceChannels(device: AudioDeviceInfo): Int {
        if (device.type !in MULTICHANNEL_TYPES) return 2
        var best = runCatching { device.channelCounts.maxOrNull() }.getOrNull() ?: 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (best < 6 && directPcm(AudioFormat.CHANNEL_OUT_5POINT1)) best = 6
            if (best < 8 && directPcm(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)) best = 8
        }
        return maxOf(best, 2)
    }

    fun maxChannels(): Int {
        val devices = activeDevices()
        if (devices.isEmpty()) return 2
        return devices.minOf { deviceChannels(it) }
    }

    fun supportsEncoded(sampleRate: Int, channels: Int, encoding: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return encodedMasks(channels).any { mask ->
                directSupport(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build(),
                    bitstream = true
                )
            }
        }
        return activeDevices().any { device ->
            device.type in ENCODED_TYPES && runCatching { device.encodings.contains(encoding) }.getOrDefault(false)
        }
    }

    fun listen(onChange: () -> Unit) {
        stopListening()
        val notify = Runnable { onChange() }
        val created = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = schedule(notify)

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = schedule(notify)
        }
        callback = created
        runCatching { manager.registerAudioDeviceCallback(created, handler) }
    }

    fun stopListening() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        callback?.let { runCatching { manager.unregisterAudioDeviceCallback(it) } }
        callback = null
    }

    private fun schedule(task: Runnable) {
        pending?.let { handler.removeCallbacks(it) }
        pending = task
        handler.postDelayed(task, SETTLE_MILLIS)
    }

    companion object {
        private const val PROBE_RATE = 48_000
        private const val SETTLE_MILLIS = 400L

        private val PRIORITY = listOf(
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        )

        private val MULTICHANNEL_TYPES = setOf(
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_DOCK
        )

        private val ENCODED_TYPES = setOf(
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )

        fun encodedMasks(channels: Int): List<Int> = when {
            channels <= 2 -> listOf(AudioFormat.CHANNEL_OUT_STEREO)
            channels <= 6 -> listOf(AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO)
            else -> listOf(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO)
        }
    }
}
