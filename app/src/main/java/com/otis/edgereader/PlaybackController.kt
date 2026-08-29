package com.otis.edgereader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class PlaybackController(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onWord: (Int, Int) -> Unit,
    private val onProgress: (Int, Int) -> Unit,
) {
    /** Chỉ giữ offset, không giữ substring của cả cuốn sách trong hàng nghìn object. */
    private data class Chunk(
        val start: Int,
        val end: Int,
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
    private val diskCache = EdgeAudioDiskCache(context.applicationContext)
    private val generation = AtomicInteger(0)

    /** Key là index trong playChunks của phiên hiện tại. */
    private val prefetched = ConcurrentHashMap<Int, EdgeTtsClient.SynthesisResult>()

    /** Cache RAM nhỏ; cache bền nằm ở diskCache. */
    private val memoryCache = object : LinkedHashMap<String, EdgeTtsClient.SynthesisResult>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, EdgeTtsClient.SynthesisResult>?
        ): Boolean = size > 64
    }

    @Volatile private var sourceText: String = ""
    @Volatile private var documentChunks: List<Chunk> = emptyList()
    @Volatile private var playChunks: List<Chunk> = emptyList()
    @Volatile private var voice: String = "vi-VN-HoaiMyNeural"

    /** Speed/pitch bây giờ là playback local, KHÔNG nằm trong Edge cache key. */
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

    /** Đổi tốc độ tức thì, không gọi Edge. */
    fun setPlaybackSpeed(value: Float) {
        speed = value.coerceIn(MIN_SPEED, MAX_SPEED)
        applyPlaybackParams()
    }

    /** Đổi độ trầm tức thì bằng Android PlaybackParams, không gọi Edge. */
    fun setPlaybackPitchHz(value: Int) {
        pitchHz = value.coerceIn(MIN_PITCH_HZ, MAX_PITCH_HZ)
        applyPlaybackParams()
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
        val sameDocument = source == sourceText
        val sameVoice = voice == this.voice
        val speedChanged = speed != this.speed
        val pitchChanged = pitchHz != this.pitchHz

        /*
         * MainActivity cũ gọi start() sau khi bấm +/- tốc độ. Nếu đang ở cùng tài liệu,
         * cùng voice và chỉ speed/pitch thay đổi quanh vị trí hiện tại thì không restart,
         * không synth lại. Điều này cũng giúp tương thích UI cũ mà không cần regenerate.
         */
        if (
            sameDocument &&
            sameVoice &&
            (active || paused) &&
            (speedChanged || pitchChanged) &&
            abs(safeStart - currentOffset) <= LOCAL_ADJUST_WINDOW_CHARS
        ) {
            this.speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
            this.pitchHz = pitchHz.coerceIn(MIN_PITCH_HZ, MAX_PITCH_HZ)
            applyPlaybackParams()
            status("Đang đọc · %.2fx · đổi tức thì".format(this.speed))
            return
        }

        val gen = generation.incrementAndGet()
        releasePlayer()
        prefetched.clear()

        if (!sameDocument) {
            sourceText = source
            documentChunks = buildDocumentChunks(source)
        }

        this.voice = voice
        this.speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        this.pitchHz = pitchHz.coerceIn(MIN_PITCH_HZ, MAX_PITCH_HZ)
        playChunks = buildPlayQueue(safeStart)

        paused = false
        active = true
        currentOffset = safeStart
        lastHighlightedStart = -1
        highlight(-1, -1)
        progress(safeStart, source.length)
        background.start()
        status("Đang tạo câu đầu…")

        synthesizeAndPlay(0, gen)
        prefetch(1, gen)
        prefetch(2, gen)
        prefetch(3, gen)
    }

    /** Nối text mới mà không tạo substring cho toàn bộ phần cũ. */
    fun appendText(fullText: String, appendStart: Int) {
        val source = fullText.trim()
        if (source.isBlank()) return
        val start = appendStart.coerceIn(0, source.length)

        sourceText = source
        documentChunks = buildDocumentChunks(source)
        val extra = buildPlayQueue(start)
        if (extra.isNotEmpty()) playChunks = playChunks + extra

        progress(currentOffset.coerceAtMost(source.length), source.length)
        status("Đã nối thêm văn bản · sẽ đọc tiếp")
    }

    fun pause() {
        val p = player
        if (p != null && runCatching { p.isPlaying }.getOrDefault(false)) {
            runCatching { p.pause() }
        }
        background.pause()
        if (active) {
            paused = true
            status("Đã tạm dừng")
        }
    }

    fun resume(): Boolean {
        if (!paused) return false
        val p = player ?: return false
        applyPlaybackParams(p)
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
        if (index >= playChunks.size) {
            active = false
            paused = false
            background.stop()
            highlight(-1, -1)
            progress(sourceText.length, sourceText.length)
            status("Đã đọc xong")
            return
        }

        prefetched.remove(index)?.let {
            playResult(it, index, gen, 0L, true)
            return
        }

        workers.submit {
            val started = System.currentTimeMillis()
            try {
                val chunk = playChunks.getOrNull(index) ?: return@submit
                val result = synthesizeCached(chunkText(chunk))
                if (gen != generation.get()) return@submit
                main.post {
                    playResult(
                        result = result.result,
                        index = index,
                        gen = gen,
                        synthMs = System.currentTimeMillis() - started,
                        cacheHit = result.cacheHit,
                    )
                }
            } catch (t: Throwable) {
                if (gen != generation.get()) return@submit
                active = false
                background.stop()
                status("Lỗi ${shortVoiceName()}: ${t.message ?: "không tạo được âm thanh"}")
            }
        }
    }

    private data class CachedResult(
        val result: EdgeTtsClient.SynthesisResult,
        val cacheHit: Boolean,
    )

    private fun playResult(
        result: EdgeTtsClient.SynthesisResult,
        index: Int,
        gen: Int,
        synthMs: Long,
        cacheHit: Boolean,
    ) {
        if (gen != generation.get()) return
        releasePlayer()
        val chunk = playChunks.getOrNull(index) ?: return
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
            applyPlaybackParams(it)
            it.start()
            active = true
            paused = false

            val prep = when {
                index != 0 -> ""
                cacheHit -> " · cache"
                synthMs > 0 -> " · ${synthMs}ms"
                else -> ""
            }
            val tracking = if (timedRanges.isEmpty()) " · theo đoạn" else " · theo từ"
            status("Đang đọc ${index + 1}/${playChunks.size}$prep$tracking")

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
            prefetch(index + 4, gen)
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

                /*
                 * currentPosition là timeline của media gốc, nên vẫn khớp WordBoundary
                 * kể cả khi PlaybackParams.speed thay đổi tức thì.
                 */
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
        val text = chunkText(chunk)
        val out = ArrayList<TimedRange>(boundaries.size)
        var searchFrom = 0

        for (b in boundaries) {
            val word = b.text.trim()
            if (word.isEmpty()) continue
            var local = text.indexOf(word, startIndex = searchFrom, ignoreCase = false)
            if (local < 0) local = text.indexOf(word, startIndex = searchFrom, ignoreCase = true)
            if (local < 0) continue

            val start = chunk.start + local
            val end = (start + word.length).coerceAtMost(chunk.end)
            out.add(TimedRange(start, end, b.offsetMs, b.durationMs))
            searchFrom = local + word.length
        }
        return out
    }

    private fun prefetch(index: Int, gen: Int) {
        if (index >= playChunks.size || gen != generation.get() || prefetched.containsKey(index)) return
        workers.submit {
            try {
                val chunk = playChunks.getOrNull(index) ?: return@submit
                val result = synthesizeCached(chunkText(chunk)).result
                if (gen == generation.get()) prefetched[index] = result
            } catch (_: Throwable) {
                // Tới lượt đoạn này sẽ thử lại và hiển thị lỗi nếu vẫn thất bại.
            }
        }
    }

    private fun synthesizeCached(text: String): CachedResult {
        val key = cacheKey(text)
        synchronized(memoryCache) {
            memoryCache[key]?.let { return CachedResult(it, true) }
        }

        diskCache.get(key)?.let {
            synchronized(memoryCache) { memoryCache[key] = it }
            return CachedResult(it, true)
        }

        /*
         * Edge luôn synth ở tốc độ/pitch gốc. Speed + độ trầm được xử lý local,
         * nên cùng một audio có thể dùng lại cho mọi tốc độ/pitch.
         */
        val result = edge.synthesize(
            text = text,
            voice = voice,
            speed = EDGE_SYNTH_SPEED,
            pitchHz = EDGE_SYNTH_PITCH_HZ,
        )
        synchronized(memoryCache) { memoryCache[key] = result }
        diskCache.put(key, result)
        return CachedResult(result, false)
    }

    private fun cacheKey(text: String): String = "$voice\u0000$text"

    private fun chunkText(chunk: Chunk): String {
        if (sourceText.isEmpty()) return ""
        val start = chunk.start.coerceIn(0, sourceText.length)
        val end = chunk.end.coerceIn(start, sourceText.length)
        return sourceText.substring(start, end)
    }

    /**
     * Tạo index một lần cho tài liệu. Chunk chỉ chứa 2 Int nên EPUB dài không tạo
     * hàng nghìn substring/Spannable và giảm GC rất nhiều.
     */
    private fun buildDocumentChunks(text: String): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Chunk>(text.length / NORMAL_CHUNK_CHARS + 4)
        var pos = 0
        while (pos < text.length) {
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos >= text.length) break
            val hardEnd = (pos + NORMAL_CHUNK_CHARS).coerceAtMost(text.length)
            var end = bestBreak(text, pos, hardEnd)
            if (end <= pos) end = hardEnd
            out.add(Chunk(pos, end))
            pos = end
        }
        return out
    }

    /**
     * Câu đầu cực ngắn để có tiếng sớm; sau đó nối vào index đã có của document.
     */
    private fun buildPlayQueue(startOffset: Int): List<Chunk> {
        if (sourceText.isBlank() || startOffset >= sourceText.length) return emptyList()
        val start = startOffset.coerceIn(0, sourceText.length)
        val firstHardEnd = (start + FIRST_CHUNK_CHARS).coerceAtMost(sourceText.length)
        var firstEnd = firstSentenceEnd(sourceText, start, firstHardEnd)
            ?: bestBreak(sourceText, start, firstHardEnd)
        if (firstEnd <= start) firstEnd = firstHardEnd

        val out = ArrayList<Chunk>()
        out.add(Chunk(start, firstEnd))

        var cursor = firstEnd
        for (chunk in documentChunks) {
            if (chunk.end <= cursor) continue
            if (chunk.start < cursor) {
                out.add(Chunk(cursor, chunk.end))
            } else {
                out.add(chunk)
            }
            cursor = chunk.end
        }
        return out
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

    private fun applyPlaybackParams() {
        player?.let { applyPlaybackParams(it) }
    }

    private fun applyPlaybackParams(mp: MediaPlayer) {
        val wasPlaying = runCatching { mp.isPlaying }.getOrDefault(false)
        runCatching {
            mp.playbackParams = PlaybackParams()
                .setSpeed(speed.coerceIn(MIN_SPEED, MAX_SPEED))
                .setPitch(pitchFactor(pitchHz))
        }
        if (!wasPlaying && paused) {
            runCatching { mp.pause() }
        }
    }

    /** -200 -> khoảng 0.82x pitch; 0 -> 1.0x. */
    private fun pitchFactor(hz: Int): Float {
        val depth = ((-hz).coerceIn(0, 200)) / 200f
        return (1f - depth * 0.18f).coerceIn(0.82f, 1f)
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
        while (pos > 0 && moved < 100) {
            val c = text[pos - 1]
            if (c.isWhitespace() || c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') break
            pos--
            moved++
        }
        return pos
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

    /** Cache MP3 + WordBoundary qua nhiều lần mở app. */
    private class EdgeAudioDiskCache(context: Context) {
        private val dir = File(context.cacheDir, "edge_tts_audio_v2").apply { mkdirs() }

        init {
            cleanup()
        }

        fun get(rawKey: String): EdgeTtsClient.SynthesisResult? {
            val key = hash(rawKey)
            val audioFile = File(dir, "$key.mp3")
            val metaFile = File(dir, "$key.json")
            if (!audioFile.isFile || !metaFile.isFile) return null

            return runCatching {
                val audio = audioFile.readBytes()
                if (audio.isEmpty()) return@runCatching null
                val root = JSONObject(metaFile.readText(Charsets.UTF_8))
                val arr = root.optJSONArray("words") ?: JSONArray()
                val words = ArrayList<EdgeTtsClient.WordBoundary>(arr.length())
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val text = item.optString("text")
                    if (text.isBlank()) continue
                    words.add(
                        EdgeTtsClient.WordBoundary(
                            text = text,
                            offsetMs = item.optLong("offsetMs"),
                            durationMs = item.optLong("durationMs"),
                        )
                    )
                }
                val now = System.currentTimeMillis()
                audioFile.setLastModified(now)
                metaFile.setLastModified(now)
                EdgeTtsClient.SynthesisResult(audio, words)
            }.getOrNull()
        }

        fun put(rawKey: String, result: EdgeTtsClient.SynthesisResult) {
            runCatching {
                val key = hash(rawKey)
                val audioFile = File(dir, "$key.mp3")
                val metaFile = File(dir, "$key.json")
                val audioTmp = File(dir, "$key.mp3.tmp")
                val metaTmp = File(dir, "$key.json.tmp")

                audioTmp.writeBytes(result.audio)
                val words = JSONArray()
                result.words.forEach { word ->
                    words.put(
                        JSONObject()
                            .put("text", word.text)
                            .put("offsetMs", word.offsetMs)
                            .put("durationMs", word.durationMs)
                    )
                }
                metaTmp.writeText(JSONObject().put("words", words).toString(), Charsets.UTF_8)

                if (audioFile.exists()) audioFile.delete()
                if (metaFile.exists()) metaFile.delete()
                audioTmp.renameTo(audioFile)
                metaTmp.renameTo(metaFile)

                if (dirSize() > DISK_CACHE_MAX_BYTES) cleanup()
            }
        }

        private fun cleanup() {
            runCatching {
                var total = dirSize()
                if (total <= DISK_CACHE_MAX_BYTES) return
                val groups = dir.listFiles()
                    .orEmpty()
                    .filter { it.isFile && !it.name.endsWith(".tmp") }
                    .groupBy { it.name.substringBeforeLast('.') }
                    .map { (_, files) ->
                        val size = files.sumOf { it.length() }
                        val modified = files.minOfOrNull { it.lastModified() } ?: 0L
                        Triple(files, size, modified)
                    }
                    .sortedBy { it.third }

                for ((files, size, _) in groups) {
                    if (total <= DISK_CACHE_TARGET_BYTES) break
                    files.forEach { it.delete() }
                    total -= size
                }
                dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
            }
        }

        private fun dirSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

        private fun hash(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        companion object {
            private const val DISK_CACHE_MAX_BYTES = 140L * 1024L * 1024L
            private const val DISK_CACHE_TARGET_BYTES = 100L * 1024L * 1024L
        }
    }

    companion object {
        private const val FIRST_CHUNK_CHARS = 100
        private const val NORMAL_CHUNK_CHARS = 520
        private const val TRACKING_LEAD_MS = 70L
        private const val LOCAL_ADJUST_WINDOW_CHARS = 900

        private const val EDGE_SYNTH_SPEED = 1.0f
        private const val EDGE_SYNTH_PITCH_HZ = 0

        private const val MIN_SPEED = 0.65f
        private const val MAX_SPEED = 1.50f
        private const val MIN_PITCH_HZ = -200
        private const val MAX_PITCH_HZ = 0
    }
}
