package com.otis.edgereader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import java.util.Random

class BackgroundSoundEngine {
    enum class Mode {
        OFF,
        RAIN,
        PAD,
        RAIN_PAD,
    }

    private var mode: Mode = Mode.RAIN_PAD
    private var rainVolume: Float = 0.24f
    private var padVolume: Float = 0.14f
    private var rainTrack: AudioTrack? = null
    private var padTrack: AudioTrack? = null
    private var active = false
    private var paused = false

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        if (active) restartTracks()
    }

    fun setRainVolume(value: Float) {
        rainVolume = value.coerceIn(0f, 1f)
        rainTrack?.setVolume(rainVolume)
    }

    fun setPadVolume(value: Float) {
        padVolume = value.coerceIn(0f, 1f)
        padTrack?.setVolume(padVolume)
    }

    fun start() {
        active = true
        paused = false
        restartTracks()
    }

    fun pause() {
        if (!active) return
        rainTrack?.let { runCatching { it.pause() } }
        padTrack?.let { runCatching { it.pause() } }
        paused = true
    }

    fun resume() {
        if (!active || !paused) return
        rainTrack?.let { runCatching { it.play() } }
        padTrack?.let { runCatching { it.play() } }
        paused = false
    }

    fun stop() {
        active = false
        paused = false
        releaseTracks()
    }

    fun release() = stop()

    private fun restartTracks() {
        releaseTracks()
        if (!active || mode == Mode.OFF) return

        if (mode == Mode.RAIN || mode == Mode.RAIN_PAD) {
            rainTrack = createLoopTrack(generateRain(), rainVolume)
            rainTrack?.play()
        }
        if (mode == Mode.PAD || mode == Mode.RAIN_PAD) {
            padTrack = createLoopTrack(generateWarmPad(), padVolume)
            padTrack?.play()
        }
    }

    private fun releaseTracks() {
        rainTrack?.let {
            runCatching { it.stop() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        padTrack?.let {
            runCatching { it.stop() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        rainTrack = null
        padTrack = null
    }

    private fun createLoopTrack(samples: ShortArray, volume: Float): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .also { track ->
                track.write(samples, 0, samples.size)
                track.setLoopPoints(0, samples.size, -1)
                track.setVolume(volume)
            }
    }

    private fun generateRain(): ShortArray {
        val count = SAMPLE_RATE * LOOP_SECONDS
        val data = ShortArray(count)
        val random = Random(226L)
        var smooth = 0.0
        var slow = 0.0
        var drop = 0.0
        var dropPhase = 0.0

        for (i in 0 until count) {
            val white = random.nextDouble() * 2.0 - 1.0
            smooth = smooth * 0.86 + white * 0.14
            slow = slow * 0.995 + white * 0.005

            if (random.nextDouble() < 0.00032) {
                drop = 1.0
                dropPhase = 0.0
            }
            val dropTone = if (drop > 0.002) {
                val value = sin(dropPhase) * drop
                dropPhase += 2.0 * PI * 420.0 / SAMPLE_RATE
                drop *= 0.991
                value
            } else {
                0.0
            }

            val hiss = (white - smooth) * 0.34
            val body = (smooth - slow) * 0.78
            val sample = (hiss + body + dropTone * 0.22) * 5200.0
            data[i] = sample.coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return data
    }

    private fun generateWarmPad(): ShortArray {
        val count = SAMPLE_RATE * LOOP_SECONDS
        val data = ShortArray(count)
        val frequencies = doubleArrayOf(110.0, 130.8, 164.8, 220.0)

        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val loopPhase = 2.0 * PI * i.toDouble() / count
            val breathe = 0.74 + 0.16 * sin(loopPhase) + 0.08 * sin(loopPhase * 2.0)
            var value = 0.0
            for ((index, frequency) in frequencies.withIndex()) {
                val gain = when (index) {
                    0 -> 0.34
                    1 -> 0.24
                    2 -> 0.22
                    else -> 0.12
                }
                value += sin(2.0 * PI * frequency * t) * gain
                value += sin(2.0 * PI * frequency * 2.0 * t) * gain * 0.08
            }
            val sample = value * breathe * 4300.0
            data[i] = sample.coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return data
    }

    companion object {
        private const val SAMPLE_RATE = 22_050
        private const val LOOP_SECONDS = 10
    }
}
