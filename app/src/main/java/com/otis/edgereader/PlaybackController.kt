package com.otis.edgereader

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PlaybackController(
    private val onStatus: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val edge = EdgeTtsClient()
    private val generation = AtomicInteger(0)
    private val prefetched = ConcurrentHashMap<Int, ByteArray>()

    @Volatile private var chunks: List<String> = emptyList()
    @Volatile private var voice: String = "vi-VN-HoaiMyNeural"
    @Volatile private var speed: Float = 1f
    @Volatile private var paused = false
    private var player: MediaPlayer? = null

    fun start(text: String, voice: String, speed: Float) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            status("Hãy dán văn bản trước")
            return
        }
        val gen = generation.incrementAndGet()
        releasePlayer()
        prefetched.clear()
        chunks = splitText(clean)
        this.voice = voice
        this.speed = speed
        paused = false
        status("Đang chuẩn bị 1/${chunks.size}…")
        synthesizeAndPlay(0, gen)
    }

    fun pause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            paused = true
            status("Đã tạm dừng")
        }
    }

    fun resume(): Boolean {
        val p = player ?: return false
        if (!paused) return false
        p.start()
        paused = false
        status("Đang đọc…")
        return true
    }

    fun isPaused(): Boolean = paused

    fun stop() {
        generation.incrementAndGet()
        prefetched.clear()
        paused = false
        releasePlayer()
        status("Đã dừng")
    }

    fun release() {
        stop()
        workers.shutdownNow()
    }

    private fun synthesizeAndPlay(index: Int, gen: Int) {
        if (gen != generation.get()) return
        if (index >= chunks.size) {
            status("Đã đọc xong")
            return
        }
        prefetched.remove(index)?.let {
            playBytes(it, index, gen)
            return
        }
        workers.submit {
            try {
                val bytes = edge.synthesize(chunks[index], voice, speed)
                if (gen != generation.get()) return@submit
                main.post { playBytes(bytes, index, gen) }
            } catch (t: Throwable) {
                if (gen != generation.get()) return@submit
                status("Lỗi: ${t.message ?: "không tạo được âm thanh"}")
            }
        }
    }

    private fun playBytes(bytes: ByteArray, index: Int, gen: Int) {
        if (gen != generation.get()) return
        releasePlayer()
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setDataSource(ByteArraySource(bytes))
        mp.setOnPreparedListener {
            if (gen != generation.get()) {
                it.release()
                return@setOnPreparedListener
            }
            it.start()
            paused = false
            status("Đang đọc ${index + 1}/${chunks.size}")
            prefetch(index + 1, gen)
        }
        mp.setOnCompletionListener {
            if (gen == generation.get()) synthesizeAndPlay(index + 1, gen)
        }
        mp.setOnErrorListener { _, what, extra ->
            if (gen == generation.get()) status("Lỗi phát âm thanh ($what/$extra)")
            true
        }
        player = mp
        mp.prepareAsync()
    }

    private fun prefetch(index: Int, gen: Int) {
        if (index >= chunks.size || gen != generation.get() || prefetched.containsKey(index)) return
        workers.submit {
            try {
                val bytes = edge.synthesize(chunks[index], voice, speed)
                if (gen == generation.get()) prefetched[index] = bytes
            } catch (_: Throwable) {
                // Nếu prefetch lỗi, đến lượt đoạn đó sẽ thử lại bình thường.
            }
        }
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        player = null
    }

    private fun status(text: String) {
        main.post { onStatus(text) }
    }

    private fun splitText(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val sentences = normalized.split(Regex("(?<=[.!?…;:。！？])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                out.add(current.toString().trim())
                current.clear()
            }
        }

        for (sentence in sentences) {
            val candidate = if (current.isEmpty()) sentence else "$current $sentence"
            if (candidate.toByteArray(Charsets.UTF_8).size <= 2600) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            } else {
                flush()
                if (sentence.toByteArray(Charsets.UTF_8).size <= 2600) {
                    current.append(sentence)
                } else {
                    val words = sentence.split(Regex("\\s+"))
                    for (word in words) {
                        val c = if (current.isEmpty()) word else "$current $word"
                        if (c.toByteArray(Charsets.UTF_8).size > 2600) flush()
                        if (current.isNotEmpty()) current.append(' ')
                        current.append(word)
                    }
                }
            }
        }
        flush()
        return out.ifEmpty { listOf(text.take(1500)) }
    }

    private class ByteArraySource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val length = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, length)
            return length
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }
}
