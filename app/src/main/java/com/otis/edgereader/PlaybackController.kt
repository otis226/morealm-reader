package com.otis.edgereader

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.util.LinkedHashMap
import java.util.Locale
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
    private val cache = object : LinkedHashMap<String, ByteArray>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 24
    }

    @Volatile private var chunks: List<String> = emptyList()
    @Volatile private var voice: String = "vi-VN-HoaiMyNeural"
    @Volatile private var speed: Float = 0.95f
    @Volatile private var pitchHz: Int = -30
    @Volatile private var paused = false
    private var player: MediaPlayer? = null

    fun start(text: String, voice: String, speed: Float, pitchHz: Int) {
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
        this.pitchHz = pitchHz
        paused = false
        status("Đang tạo đoạn đầu…")
        synthesizeAndPlay(0, gen)
        // Chuẩn bị đoạn thứ hai ngay lập tức trong worker còn lại để giảm khoảng ngắt.
        prefetch(1, gen)
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
            playBytes(it, index, gen, 0L)
            return
        }
        workers.submit {
            val started = System.currentTimeMillis()
            try {
                val bytes = synthesizeCached(chunks[index])
                if (gen != generation.get()) return@submit
                main.post { playBytes(bytes, index, gen, System.currentTimeMillis() - started) }
            } catch (t: Throwable) {
                if (gen != generation.get()) return@submit
                status("Lỗi ${shortVoiceName()}: ${t.message ?: "không tạo được âm thanh"}")
            }
        }
    }

    private fun playBytes(bytes: ByteArray, index: Int, gen: Int, synthMs: Long) {
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
            val prep = if (index == 0 && synthMs > 0) " · ${synthMs}ms" else ""
            status("Đang đọc ${index + 1}/${chunks.size}$prep")
            // Giữ trước hai đoạn kế tiếp nếu có.
            prefetch(index + 1, gen)
            prefetch(index + 2, gen)
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
                val bytes = synthesizeCached(chunks[index])
                if (gen == generation.get()) prefetched[index] = bytes
            } catch (_: Throwable) {
                // Đến lượt đoạn này sẽ thử lại và hiển thị lỗi chi tiết nếu vẫn thất bại.
            }
        }
    }

    private fun synthesizeCached(text: String): ByteArray {
        val key = cacheKey(text)
        synchronized(cache) { cache[key]?.let { return it } }
        val bytes = edge.synthesize(text, voice, speed, pitchHz)
        synchronized(cache) { cache[key] = bytes }
        return bytes
    }

    private fun cacheKey(text: String): String = buildString {
        append(voice)
        append('|')
        append(String.format(Locale.US, "%.2f", speed))
        append('|')
        append(pitchHz)
        append('|')
        append(text)
    }

    private fun shortVoiceName(): String = if (voice.contains("NamMinh")) "Nam Minh" else "Hoài My"

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

    /**
     * Tối ưu cảm giác bấm Đọc:
     * - đoạn đầu = đúng một câu (hoặc tối đa ~650 byte) để Edge trả audio thật sớm;
     * - các đoạn sau gom lớn hơn (~1700 byte) để giảm số lần bắt tay WebSocket;
     * - đoạn 2 được synth song song ngay khi đoạn 1 bắt đầu tạo.
     */
    private fun splitText(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val sentences = normalized.split(Regex("(?<=[.!?…;:。！？])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return listOf(text)

        val out = ArrayList<String>()
        val firstParts = splitByBytes(sentences.first(), FIRST_CHUNK_BYTES)
        out.add(firstParts.first())

        val remaining = ArrayList<String>()
        remaining.addAll(firstParts.drop(1))
        remaining.addAll(sentences.drop(1))

        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                out.add(current.toString().trim())
                current.clear()
            }
        }

        for (sentence in remaining) {
            for (part in splitByBytes(sentence, NORMAL_CHUNK_BYTES)) {
                val candidate = if (current.isEmpty()) part else "$current $part"
                if (candidate.toByteArray(Charsets.UTF_8).size > NORMAL_CHUNK_BYTES) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(part)
            }
        }
        flush()
        return out.filter { it.isNotBlank() }
    }

    private fun splitByBytes(text: String, maxBytes: Int): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text)
        val words = text.split(Regex("\\s+"))
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                out.add(current.toString())
                current.clear()
            }
        }
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.toByteArray(Charsets.UTF_8).size > maxBytes && current.isNotEmpty()) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        flush()
        return out.ifEmpty { listOf(text) }
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

    companion object {
        private const val FIRST_CHUNK_BYTES = 650
        private const val NORMAL_CHUNK_BYTES = 1700
    }
}
