package com.otis.edgereader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class BackgroundSoundEngine(private val context: Context) {
    enum class Mode {
        OFF,
        RAIN,
        FIREPLACE,
        OCEAN,
        BROWN_NOISE,
        NIGHT,
        PAD,
        RAIN_PAD,
        CUSTOM,
    }

    private var mode: Mode = Mode.OFF
    private var ambienceVolume: Float = 0.22f
    private var musicVolume: Float = 0.12f
    private var ambienceTrack: AudioTrack? = null
    private var musicTrack: AudioTrack? = null
    private var customPlayer: MediaPlayer? = null
    private var customUri: Uri? = null
    private var active = false
    private var paused = false

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        if (active) restartTracks()
    }

    fun setAmbienceVolume(value: Float) {
        ambienceVolume = value.coerceIn(0f, 1f)
        ambienceTrack?.setVolume(ambienceVolume)
        customPlayer?.setVolume(ambienceVolume, ambienceVolume)
    }

    fun setMusicVolume(value: Float) {
        musicVolume = value.coerceIn(0f, 1f)
        musicTrack?.setVolume(musicVolume)
    }

    fun setCustomUri(uri: Uri?) {
        customUri = uri
        if (active && mode == Mode.CUSTOM) restartTracks()
    }

    fun start() {
        active = true
        paused = false
        restartTracks()
    }

    fun pause() {
        if (!active) return
        ambienceTrack?.let { runCatching { it.pause() } }
        musicTrack?.let { runCatching { it.pause() } }
        customPlayer?.let { runCatching { it.pause() } }
        paused = true
    }

    fun resume() {
        if (!active || !paused) return
        ambienceTrack?.let { runCatching { it.play() } }
        musicTrack?.let { runCatching { it.play() } }
        customPlayer?.let { runCatching { it.start() } }
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

        when (mode) {
            Mode.RAIN -> startAmbience(generateRain())
            Mode.FIREPLACE -> startAmbience(generateFireplace())
            Mode.OCEAN -> startAmbience(generateOcean())
            Mode.BROWN_NOISE -> startAmbience(generateBrownNoise())
            Mode.NIGHT -> startAmbience(generateNight())
            Mode.PAD -> startMusic(generateWarmPad())
            Mode.RAIN_PAD -> {
                startAmbience(generateRain())
                startMusic(generateWarmPad())
            }
            Mode.CUSTOM -> startCustom()
            Mode.OFF -> Unit
        }
    }

    private fun startAmbience(samples: ShortArray) {
        ambienceTrack = createLoopTrack(samples, ambienceVolume).also { it.play() }
    }

    private fun startMusic(samples: ShortArray) {
        musicTrack = createLoopTrack(samples, musicVolume).also { it.play() }
    }

    private fun startCustom() {
        val uri = customUri ?: return
        customPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(context, uri)
            isLooping = true
            setVolume(ambienceVolume, ambienceVolume)
            setOnPreparedListener { if (active && mode == Mode.CUSTOM) it.start() }
            prepareAsync()
        }
    }

    private fun releaseTracks() {
        ambienceTrack?.let {
            runCatching { it.stop() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        musicTrack?.let {
            runCatching { it.stop() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        customPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        ambienceTrack = null
        musicTrack = null
        customPlayer = null
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
            smooth = smooth * 0.88 + white * 0.12
            slow = slow * 0.996 + white * 0.004
            if (random.nextDouble() < 0.00025) {
                drop = 1.0
                dropPhase = 0.0
            }
            val dropTone = if (drop > 0.002) {
                val value = sin(dropPhase) * drop
                dropPhase += 2.0 * PI * 360.0 / SAMPLE_RATE
                drop *= 0.993
                value
            } else 0.0
            val sample = ((white - smooth) * 0.22 + (smooth - slow) * 0.92 + dropTone * 0.12) * 4300.0
            data[i] = sample.coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return data
    }

    private fun generateFireplace(): ShortArray {
        val count = SAMPLE_RATE * LOOP_SECONDS
        val data = ShortArray(count)
        val random = Random(9981L)
        var brown = 0.0
        var crackle = 0.0
        for (i in 0 until count) {
            brown = (brown + (random.nextDouble() * 2.0 - 1.0) * 0.035).coerceIn(-1.0, 1.0) * 0.994
            if (random.nextDouble() < 0.0009) crackle = random.nextDouble() * 1.3
            crackle *= 0.965
            val pop = (random.nextDouble() * 2.0 - 1.0) * crackle
            val sample = (brown * 0.58 + pop * 0.42) * 5200.0
            data[i] = sample.coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return data
    }

    private fun generateOcean(): ShortArray {
        val count = SAMPLE_RATE * LOOP_SECONDS
        val data = ShortArray(count)
        val random = Random(417L)
        var smooth = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val white = random.nextDouble() * 2.0 - 1.0
            smooth = smooth * 0.985 + white * 0.015
            val wave = 0.58 + 0.38 * sin(2.0 * PI * 0.095 * t) + 0.12 * sin(2.0 * PI * 0.19 * t + 1.3)
            val foam = (white * 0.15 + smooth * 0.85) * wave
            data[i] = (foam * 4700.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return data
    }

    private fun generateBrownNoise(): ShortArray {
        val count = SAMPLE_RATE * LOOP_SECONDS
        val data = ShortArray(count)
        val random = Random(77L)
        var value = 0.0
        for (i in 0 until count) {
            value += (random.nextDouble() * 2.0 - 1.0) * 0.025
            value = value.coerceIn(-1.0, 1.0) * 0.995
            data[i] = (value * 6200.0).toInt().toShort()
        }
        return data
    }

    private fun generateNight(): ShortArray {
        val count = SAMPLE_RATE * LOOP_SECONDS
        val data = ShortArray(count)
        val random = Random(1709L)
        var bed = 0.0
        var chirpRemaining = 0
        var chirpPhase = 0.0
        for (i in 0 until count) {
            val white = random.nextDouble() * 2.0 - 1.0
            bed = bed * 0.992 + white * 0.008
            if (chirpRemaining <= 0 && random.nextDouble() < 0.00008) {
                chirpRemaining = SAMPLE_RATE / 7
                chirpPhase = 0.0
            }
            val chirp = if (chirpRemaining > 0) {
                chirpRemaining--
                val envelope = chirpRemaining.toDouble() / (SAMPLE_RATE / 7.0)
                val v = sin(chirpPhase) * envelope
                chirpPhase += 2.0 * PI * 3200.0 / SAMPLE_RATE
                v
            } else 0.0
            val sample = (bed * 0.5 + chirp * 0.12) * 4200.0
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
            val breathe = 0.70 + 0.13 * sin(loopPhase) + 0.06 * sin(loopPhase * 2.0)
            var value = 0.0
            for ((index, frequency) in frequencies.withIndex()) {
                val gain = when (index) {
                    0 -> 0.31
                    1 -> 0.22
                    2 -> 0.19
                    else -> 0.10
                }
                value += sin(2.0 * PI * frequency * t) * gain
                value += sin(2.0 * PI * frequency * 2.0 * t) * gain * 0.05
            }
            data[i] = (value * breathe * 3600.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return data
    }

    companion object {
        private const val SAMPLE_RATE = 22_050
        private const val LOOP_SECONDS = 12
    }
}
