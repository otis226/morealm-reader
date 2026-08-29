package com.otis.edgereader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PlaybackController(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onWord: (Int, Int) -> Unit,
    private val onProgress: (Int, Int) -> Unit,
) {
    private data class Chunk(
        val start: Int,
        val end: Int,
        val text: String,
    )

    private data class TimedRange(
        val start: Int,
        val end: Int,
        val offsetMs: Long,
        val durationMs: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(3)
    private val edge = EdgeTtsClient()
    private val background = BackgroundSoundEngine(context.applicationContext)
    private val generation = AtomicInteger(0)
    private val prefetched = ConcurrentHashMap<Int, EdgeTtsClient.SynthesisResult>()
    private val cache = object : LinkedHashMap<String, EdgeTtsClient.SynthesisResult>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, EdgeTtsClient.SynthesisResult>?
        ): Boolean = size > 32
    }

    @Volatile private var chunks: List<Chunk> = emptyList()
    @Volatile private var sourceText: String = ""
    @Volatile private var voice: String = "vi-VN-HoaiMyNeural"
    @Volatile private var speed: Float = 0.95f
    @Volatile private var pitchHz: Int = -60
    @Volatile private var voiceVolume: Float = 1f
    @Volatile private var paused = false
    @Volatile private var active = false
    @Volatile private var currentOffset = 0
    private var player: MediaPlayer? = null
    private var progressRunnable: Runnable? = null
    private var lastHighlightedStart = -1

    fun setVoiceVolume(value: Float) {
        voiceVolume = value.coerceIn(0f, 1f)
        player?.let { runCatching { it.setVolume(voiceVolume, voiceVolume) } }
    }

    fun setBackgroundMode(mode: BackgroundSoundEngine.Mode) {
        background.setMode(mode)
    }

    fun setAmbienceVolume(value: Float) {
        background.setAmbienceVolume(value)
    }

    fun setMusicVolume(value: Float) {
        background.setMusicVolume(value)
    }

    fun setCustomBackgroundUri(uri: Uri?) {
        background.setCustomUri(uri)
    }

    fun isActiveOrPaused(): Boolean = active || paused

    fun start(text: String, voice: String, speed: Float, pitchHz: Int, startOffset: Int = 0) {
        val source = text.trim()
        if (source.isEmpty()) {
            status("Chưa có văn bản để đọc")
            return
        }
        val safeStart = snapReadingStart(source, startOffset.coerceIn(0, source.length))
        val gen = generation.incrementAndGet()
        releasePlayer()
        prefetched.clear()
        sourceText = source
        chunks = splitText(source, safeStart)
        this.voice = voice
        this.speed = speed
        this.pitchHz = pitchHz
        paused = false
        active = true
        currentOffset = safeStart
        lastHighlightedStart = -1
        highlight(-1, -1)
        progress(safeStart, source.length)
        background.start()
        status("Đang tạo giọng…")
        synthesizeAndPlay(0, gen)
        prefetch(1, gen)
        prefetch(2, gen)
    }

    /**
     * Nối phần văn bản mới vào queue hiện tại. fullText phải là toàn bộ text đang hiển thị,
     * appendStart là vị trí ký tự bắt đầu của phần mới trong fullText.
     */
    fun appendText(fullText: String, appendStart: Int) {
        val source = fullText.trim()
        if (source.isBlank()) return
        val start = appendStart.coerceIn(0, source.length)
        sourceText = source
        val extra = splitText(source, start)
        if (extra.isNotEmpty()) chunks = chunks + extra
        progress(currentOffset.coerceAtMost(source.length), source.length)
        status("Đã nối thêm văn bản · sẽ đọc tiếp")
    }

    fun pause() {
        val p = player
        if (p != null && p.isPlaying) p.pause()
        background.pause()
        if (active) {
            paused = true
            status("Đã tạm dừng")
        }
    }

    fun resume(): Boolean {
        if (!paused) return false
        val p = player ?: return false
        p.start()
        background.resume()
        paused = false
        active = true
        status("Đang đọc…")
        return true
    }

    fun stop() {
        generation.incrementAndGet()
        prefetched.clear()
        paused = false
        active = false
        releasePlayer()
        background.stop()
        highlight(-1, -1)
        status("Đã dừng")
    }

    fun release() {
        stop()
        background.release()
        workers.shutdownNow()
    }

    private fun synthesizeAndPlay(index: Int, gen: Int) {
        if (gen != generation.get()) return
        if (index >= chunks.size) {
            active = false
            paused = false
            background.stop()
            highlight(-1, -1)
            progress(sourceText.length, sourceText.length)
            status("Đã đọc xong")
            return
        }
        prefetched.remove(index)?.let {
            playResult(it, index, gen, 0L)
            return
        }
        workers.submit {
            val started = System.currentTimeMillis()
            try {
                val result = synthesizeCached(chunks[index].text)
                if (gen != generation.get()) return@submit
                main.post {
                    playResult(result, index, gen, System.currentTimeMillis() - started)
                }
            } catch (t: Throwable) {
                if (gen != generation.get()) return@submit
                active = false
                background.stop()
                status("Lỗi ${shortVoiceName()}: ${t.message ?: "không tạo được âm thanh"}")
            }
        }
    }

    private fun playResult(
        result: EdgeTtsClient.SynthesisResult,
        index: Int,
        gen: Int,
        synthMs: Long,
    ) {
        if (gen != generation.get()) return
        releasePlayer()
        val chunk = chunks.getOrNull(index) ?: return
        val timedRanges = mapWordRanges(chunk, result.words)

        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setVolume(voiceVolume, voiceVolume)
        mp.setDataSource(ByteArraySource(result.audio))
        mp.setOnPreparedListener {
            if (gen != generation.get()) {
                it.release()
                return@setOnPreparedListener
            }
            it.setVolume(voiceVolume, voiceVolume)
            it.start()
            active = true
            paused = false
            val prep = if (index == 0 && synthMs > 0) " · ${synthMs}ms" else ""
            val tracking = if (timedRanges.isEmpty()) " · theo đoạn" else " · theo từ"
            status("Đang đọc ${index + 1}/${chunks.size}$prep$tracking")
            if (timedRanges.isEmpty()) {
                currentOffset = chunk.start
                highlight(chunk.start, chunk.end)
                progress(currentOffset, sourceText.length)
            } else {
                startWordTracking(it, timedRanges, gen)
            }
            prefetch(index + 1, gen)
            prefetch(index + 2, gen)
            prefetch(index + 3, gen)
        }
        mp.setOnCompletionListener {
            stopProgressLoop()
            currentOffset = chunk.end
            progress(currentOffset, sourceText.length)
            if (gen == generation.get()) synthesizeAndPlay(index + 1, gen)
        }
        mp.setOnErrorListener { _, what, extra ->
            stopProgressLoop()
            active = false
            background.stop()
            if (gen == generation.get()) status("Lỗi phát âm thanh ($what/$extra)")
            true
        }
        player = mp
        mp.prepareAsync()
    }

    private fun startWordTracking(mp: MediaPlayer, ranges: List<TimedRange>, gen: Int) {
        stopProgressLoop()
        var lastIndex = -1
        val runner = object : Runnable {
            override fun run() {
                if (gen != generation.get() || player !== mp) return
                val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L) + TRACKING_LEAD_MS
                var found = lastIndex.coerceAtLeast(0)
                while (found + 1 < ranges.size && ranges[found + 1].offsetMs <= pos) found++
                if (found < ranges.size && ranges[found].offsetMs <= pos && found != lastIndex) {
                    lastIndex = found
                    val r = ranges[found]
                    currentOffset = r.start
                    highlight(r.start, r.end)
                    progress(currentOffset, sourceText.length)
                }
                if (gen == generation.get() && player === mp) {
                    main.postDelayed(this, if (paused) 140L else 45L)
                }
            }
        }
        progressRunnable = runner
        main.post(runner)
    }

    private fun stopProgressLoop() {
        progressRunnable?.let { main.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun mapWordRanges(
        chunk: Chunk,
        boundaries: List<EdgeTtsClient.WordBoundary>,
    ): List<TimedRange> {
        if (boundaries.isEmpty()) return emptyList()
        val out = ArrayList<TimedRange>(boundaries.size)
        var searchFrom = 0
        for (b in boundaries) {
            val word = b.text.trim()
            if (word.isEmpty()) continue
            var local = chunk.text.indexOf(word, startIndex = searchFrom, ignoreCase = false)
            if (local < 0) local = chunk.text.indexOf(word, startIndex = searchFrom, ignoreCase = true)
            if (local < 0) continue
            val start = chunk.start + local
            val end = (start + word.length).coerceAtMost(chunk.end)
            out.add(TimedRange(start, end, b.offsetMs, b.durationMs))
            searchFrom = local + word.length
        }
        return out
    }

    private fun prefetch(index: Int, gen: Int) {
        if (index >= chunks.size || gen != generation.get() || prefetched.containsKey(index)) return
        workers.submit {
            try {
                val chunk = chunks.getOrNull(index) ?: return@submit
                val result = synthesizeCached(chunk.text)
                if (gen == generation.get()) prefetched[index] = result
            } catch (_: Throwable) {
                // Đến lượt đoạn này sẽ thử lại và hiển thị lỗi nếu vẫn thất bại.
            }
        }
    }

    private fun synthesizeCached(text: String): EdgeTtsClient.SynthesisResult {
        val key = cacheKey(text)
        synchronized(cache) { cache[key]?.let { return it } }
        val result = edge.synthesize(text, voice, speed, pitchHz)
        synchronized(cache) { cache[key] = result }
        return result
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
        stopProgressLoop()
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

    private fun highlight(start: Int, end: Int) {
        if (start == lastHighlightedStart && start >= 0) return
        lastHighlightedStart = start
        main.post { onWord(start, end) }
    }

    private fun progress(current: Int, total: Int) {
        main.post { onProgress(current.coerceAtLeast(0), total.coerceAtLeast(0)) }
    }

    private fun snapReadingStart(text: String, requested: Int): Int {
        if (requested <= 0 || requested >= text.length) return requested.coerceIn(0, text.length)
        var pos = requested
        var moved = 0
        while (pos > 0 && moved < 80) {
            val c = text[pos - 1]
            if (c.isWhitespace() || c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') break
            pos--
            moved++
        }
        return pos
    }

    private fun splitText(text: String, startOffset: Int): List<Chunk> {
        val out = ArrayList<Chunk>()
        var pos = startOffset.coerceIn(0, text.length)
        var chunkIndex = 0
        while (pos < text.length) {
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos >= text.length) break

            val maxChars = if (chunkIndex == 0) FIRST_CHUNK_CHARS else NORMAL_CHUNK_CHARS
            val hardEnd = (pos + maxChars).coerceAtMost(text.length)
            var end = if (chunkIndex == 0) {
                firstSentenceEnd(text, pos, hardEnd) ?: bestBreak(text, pos, hardEnd)
            } else {
                bestBreak(text, pos, hardEnd)
            }
            if (end <= pos) end = hardEnd
            out.add(Chunk(pos, end, text.substring(pos, end)))
            pos = end
            chunkIndex++
        }
        return out
    }

    private fun firstSentenceEnd(text: String, start: Int, hardEnd: Int): Int? {
        for (i in start until hardEnd) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') return i + 1
        }
        return null
    }

    private fun bestBreak(text: String, start: Int, hardEnd: Int): Int {
        if (hardEnd >= text.length) return text.length
        for (i in hardEnd - 1 downTo start + 40) {
            val c = text[i]
            if (c == '\n' || c == '.' || c == '!' || c == '?' || c == '…' || c == ';' || c == ':') return i + 1
        }
        for (i in hardEnd - 1 downTo start + 20) {
            if (text[i].isWhitespace()) return i + 1
        }
        return hardEnd
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
        private const val FIRST_CHUNK_CHARS = 180
        private const val NORMAL_CHUNK_CHARS = 620
        private const val TRACKING_LEAD_MS = 70L
    }
}
