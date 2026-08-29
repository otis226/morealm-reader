package com.otis.edgereader.playback

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.otis.edgereader.core.library.FileBookStore
import com.otis.edgereader.core.model.Book
import com.otis.edgereader.core.model.ReadingPosition
import com.otis.edgereader.core.playback.PlaybackStateMachine
import com.otis.edgereader.core.playback.PlaybackStatus
import com.otis.edgereader.core.text.SentenceChunker
import com.otis.edgereader.core.text.SentenceNavigator
import com.otis.edgereader.core.text.TextChunk
import com.otis.edgereader.core.tts.SynthesisCoordinator
import com.otis.edgereader.core.tts.SynthesisSpec
import com.otis.edgereader.core.tts.TtsAudio
import com.otis.edgereader.core.tts.WordBoundary
import com.otis.edgereader.core.tts.edge.EdgeTtsEngine
import com.otis.edgereader.storage.SharedPreferencesPositionStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Service-owned narration pipeline.
 *
 * Invariants:
 * - Activity never owns ExoPlayer or Edge sockets.
 * - Every seek/navigation starts a new generation; stale synthesis cannot enqueue audio.
 * - Only a small rolling set of MP3 segments is materialized at once.
 * - Reading position is expressed only as (chapterIndex, charOffset).
 */
class StoryPlaybackService : MediaSessionService(), Player.Listener {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private lateinit var bookStore: FileBookStore
    private lateinit var positionStore: SharedPreferencesPositionStore
    private lateinit var synthesis: SynthesisCoordinator
    private val state = PlaybackStateMachine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentBook: Book? = null
    private var generation: Long = 0L
    private var nextSynthesisOffset: Int = 0
    private var nextSegmentIndex: Int = 0
    private var synthInFlight = false
    private var firstChunk = true
    private var autoPlayWhenReady = false
    private var sleepRunnable: Runnable? = null

    private var voice = DEFAULT_VOICE
    private var speed = DEFAULT_SPEED
    private var pitchHz = DEFAULT_PITCH

    private val segments = ConcurrentHashMap<String, SegmentMeta>()
    private val segmentDir: File by lazy { File(cacheDir, "tts_segments_v1") }

    private val progressSaver = object : Runnable {
        override fun run() {
            persistExactPosition()
            if (::player.isInitialized && player.isPlaying) {
                mainHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        segmentDir.mkdirs()
        cleanupSegmentDir()

        bookStore = FileBookStore(File(filesDir, "books_v1"))
        positionStore = SharedPreferencesPositionStore(this)
        synthesis = SynthesisCoordinator(EdgeTtsEngine())
        loadTtsSettings()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(this@StoryPlaybackService)
        }

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val base = super.onConnect(session, controller)
                if (!base.isAccepted) return base
                val commands = base.availableSessionCommands.buildUpon().apply {
                    StorySessionCommands.customCommands.forEach(::add)
                }.build()
                return MediaSession.ConnectionResult.accept(commands, base.availablePlayerCommands)
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                return Futures.immediateFuture(handleCustomCommand(customCommand.customAction, args))
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun handleCustomCommand(action: String, args: Bundle): SessionResult {
        return try {
            when (action) {
                StorySessionCommands.OPEN_BOOK -> {
                    val bookId = args.getString(StorySessionCommands.KEY_BOOK_ID).orEmpty()
                    val autoplay = args.getBoolean(StorySessionCommands.KEY_AUTOPLAY, false)
                    openBook(bookId, autoplay)
                }

                StorySessionCommands.SEEK_TEXT -> {
                    val book = currentBook ?: return failure("Chưa mở sách")
                    val chapter = args.getInt(StorySessionCommands.KEY_CHAPTER, state.snapshot().position.chapterIndex)
                    val offset = args.getInt(StorySessionCommands.KEY_OFFSET, 0)
                    restartAt(ReadingPosition(chapter, offset).normalized(book), shouldContinuePlaying())
                }

                StorySessionCommands.NEXT_SENTENCE -> navigateSentence(forward = true)
                StorySessionCommands.PREVIOUS_SENTENCE -> navigateSentence(forward = false)
                StorySessionCommands.NEXT_CHAPTER -> navigateChapter(forward = true)
                StorySessionCommands.PREVIOUS_CHAPTER -> navigateChapter(forward = false)

                StorySessionCommands.SET_TTS -> {
                    voice = args.getString(StorySessionCommands.KEY_VOICE)?.takeIf { it.isNotBlank() } ?: voice
                    speed = args.getFloat(StorySessionCommands.KEY_SPEED, speed).coerceIn(0.55f, 1.8f)
                    pitchHz = args.getInt(StorySessionCommands.KEY_PITCH, pitchHz).coerceIn(-250, 100)
                    saveTtsSettings()
                    currentBook?.let {
                        restartAt(exactCurrentPosition(), shouldContinuePlaying())
                    }
                }

                StorySessionCommands.SET_SLEEP_TIMER -> {
                    setSleepTimer(args.getInt(StorySessionCommands.KEY_SLEEP_MINUTES, 0))
                }

                StorySessionCommands.GET_STATE -> Unit
                else -> return SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            }
            SessionResult(SessionResult.RESULT_SUCCESS, stateBundle())
        } catch (t: Throwable) {
            state.fail(t.message ?: "Lỗi phát truyện")
            broadcastState()
            failure(t.message ?: "Lỗi phát truyện")
        }
    }

    private fun openBook(bookId: String, autoplay: Boolean) {
        require(bookId.isNotBlank()) { "Thiếu book id" }
        val book = bookStore.load(bookId) ?: error("Không tìm thấy sách")
        currentBook = book
        val saved = positionStore.load(book.id)?.normalized(book) ?: ReadingPosition.START
        state.load(book, saved)
        restartAt(saved, autoplay)
    }

    private fun restartAt(position: ReadingPosition, autoplay: Boolean) {
        val book = currentBook ?: return
        val normalized = position.normalized(book)
        val chapterText = book.chapter(normalized.chapterIndex).text
        val sentenceStart = SentenceNavigator.startAtOrBefore(chapterText, normalized.charOffset)
        val start = normalized.copy(charOffset = sentenceStart)

        generation = synthesis.beginGeneration()
        synthInFlight = false
        firstChunk = true
        nextSegmentIndex = 0
        nextSynthesisOffset = sentenceStart
        autoPlayWhenReady = autoplay

        player.stop()
        player.clearMediaItems()
        clearMaterializedSegments()
        state.seek(start)
        if (autoplay) state.requestPlay() else state.pause()
        positionStore.save(book.id, start)
        broadcastState()
        synthNextIfNeeded()
    }

    private fun synthNextIfNeeded() {
        val book = currentBook ?: return
        if (synthInFlight) return
        val snapshot = state.snapshot()
        val chapterIndex = snapshot.position.chapterIndex
        val chapter = book.chapter(chapterIndex)
        if (nextSynthesisOffset >= chapter.text.length) return

        val currentIndex = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val queuedAhead = (player.mediaItemCount - currentIndex - 1).coerceAtLeast(0)
        if (player.mediaItemCount > 0 && queuedAhead >= PREFETCH_AHEAD) return

        val chunk = if (firstChunk) {
            SentenceChunker.firstChunkAfterSeek(chapter.text, nextSynthesisOffset)
        } else {
            SentenceChunker.nextChunk(chapter.text, nextSynthesisOffset)
        } ?: return

        val localGeneration = generation
        val localSegmentIndex = nextSegmentIndex++
        synthInFlight = true
        synthesis.submit(
            generation = localGeneration,
            spec = SynthesisSpec(chunk.text, voice, speed, pitchHz),
            onAccepted = { audio ->
                mainHandler.post {
                    acceptSynthesizedSegment(localGeneration, chapterIndex, localSegmentIndex, chunk, audio)
                }
            },
            onError = { error ->
                mainHandler.post {
                    if (localGeneration == generation) {
                        synthInFlight = false
                        state.fail(error.message ?: "Edge TTS lỗi")
                        player.pause()
                        broadcastState()
                    }
                }
            },
        )
    }

    private fun acceptSynthesizedSegment(
        localGeneration: Long,
        chapterIndex: Int,
        segmentIndex: Int,
        chunk: TextChunk,
        audio: TtsAudio,
    ) {
        if (localGeneration != generation || audio.generation != generation) return
        val book = currentBook ?: return
        if (chapterIndex != state.snapshot().position.chapterIndex) return

        val file = File(segmentDir, "g${generation}_c${chapterIndex}_s${segmentIndex}.mp3")
        file.writeBytes(audio.encodedAudio)
        val mediaId = "${generation}:${chapterIndex}:${segmentIndex}"
        val meta = SegmentMeta(
            mediaId = mediaId,
            generation = generation,
            chapterIndex = chapterIndex,
            segmentIndex = segmentIndex,
            start = chunk.start,
            endExclusive = chunk.endExclusive,
            text = chunk.text,
            words = mapWordOffsets(chunk.text, audio.boundaries),
            file = file,
        )
        segments[mediaId] = meta

        val mediaItem = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.chapter(chapterIndex).title)
                    .setAlbumTitle(book.title)
                    .build()
            )
            .build()

        val wasEmpty = player.mediaItemCount == 0
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.addMediaItem(mediaItem)
        nextSynthesisOffset = chunk.endExclusive
        firstChunk = false
        synthInFlight = false

        if (wasEmpty || wasEnded) {
            player.prepare()
            if (autoPlayWhenReady) player.play()
        }
        synthNextIfNeeded()
    }

    private fun navigateSentence(forward: Boolean) {
        val book = currentBook ?: return
        val exact = exactCurrentPosition()
        val text = book.chapter(exact.chapterIndex).text
        val target = if (forward) {
            SentenceNavigator.nextStart(text, exact.charOffset)
        } else {
            SentenceNavigator.previousStart(text, exact.charOffset)
        }
        restartAt(ReadingPosition(exact.chapterIndex, target), shouldContinuePlaying())
    }

    private fun navigateChapter(forward: Boolean) {
        val book = currentBook ?: return
        val current = state.snapshot().position.chapterIndex
        val target = if (forward) current + 1 else current - 1
        if (target !in 0..book.lastChapterIndex) return
        restartAt(ReadingPosition(target, 0), shouldContinuePlaying())
    }

    private fun advanceChapterAfterPlayback() {
        val book = currentBook ?: return
        val current = state.snapshot().position.chapterIndex
        if (current >= book.lastChapterIndex) {
            val last = book.chapter(book.lastChapterIndex)
            state.seek(ReadingPosition(book.lastChapterIndex, last.text.length))
            state.chapterCompleted()
            positionStore.save(book.id, state.snapshot().position)
            broadcastState()
            return
        }
        restartAt(ReadingPosition(current + 1, 0), autoplay = true)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val meta = mediaItem?.mediaId?.let(segments::get) ?: return
        if (meta.generation != generation) return
        val book = currentBook ?: return
        state.seek(ReadingPosition(meta.chapterIndex, meta.start))
        if (player.isPlaying || player.playWhenReady) state.markPlaying()
        positionStore.save(book.id, state.snapshot().position)
        broadcastSegment(meta)
        broadcastState()
        cleanupOldSegments(meta.segmentIndex)
        synthNextIfNeeded()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            val book = currentBook ?: return
            val chapter = book.chapter(state.snapshot().position.chapterIndex)
            if (nextSynthesisOffset >= chapter.text.length && !synthInFlight) {
                advanceChapterAfterPlayback()
            } else {
                synthNextIfNeeded()
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        mainHandler.removeCallbacks(progressSaver)
        if (isPlaying) {
            state.markPlaying()
            mainHandler.postDelayed(progressSaver, POSITION_SAVE_INTERVAL_MS)
        } else if (!player.playWhenReady) {
            state.pause()
            persistExactPosition()
        }
        broadcastState()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        autoPlayWhenReady = playWhenReady
        if (playWhenReady) {
            if (player.mediaItemCount == 0) synthNextIfNeeded()
        } else {
            persistExactPosition()
        }
    }

    private fun exactCurrentPosition(): ReadingPosition {
        val book = currentBook ?: return ReadingPosition.START
        val fallback = state.snapshot().position.normalized(book)
        val mediaId = player.currentMediaItem?.mediaId ?: return fallback
        val meta = segments[mediaId] ?: return fallback
        if (meta.generation != generation) return fallback
        val ms = player.currentPosition.coerceAtLeast(0L)
        val word = meta.words.lastOrNull { it.offsetMs <= ms }
        val offset = if (word != null) meta.start + word.charOffsetInChunk else meta.start
        return ReadingPosition(meta.chapterIndex, offset).normalized(book)
    }

    private fun persistExactPosition() {
        val book = currentBook ?: return
        val exact = exactCurrentPosition()
        state.seek(exact)
        if (player.isPlaying) state.markPlaying()
        positionStore.save(book.id, exact)
    }

    private fun broadcastState() {
        mediaSession?.broadcastCustomCommand(
            StorySessionCommands.command(StorySessionCommands.STATE_CHANGED),
            stateBundle(),
        )
    }

    private fun broadcastSegment(meta: SegmentMeta) {
        val words = meta.words
        val args = Bundle().apply {
            putInt(StorySessionCommands.KEY_CHAPTER, meta.chapterIndex)
            putInt(StorySessionCommands.KEY_SEGMENT_START, meta.start)
            putInt(StorySessionCommands.KEY_SEGMENT_END, meta.endExclusive)
            putString(StorySessionCommands.KEY_SEGMENT_TEXT, meta.text)
            putStringArray(StorySessionCommands.KEY_WORD_TEXTS, words.map { it.text }.toTypedArray())
            putLongArray(StorySessionCommands.KEY_WORD_OFFSETS_MS, words.map { it.offsetMs }.toLongArray())
            putLongArray(StorySessionCommands.KEY_WORD_DURATIONS_MS, words.map { it.durationMs }.toLongArray())
        }
        mediaSession?.broadcastCustomCommand(
            StorySessionCommands.command(StorySessionCommands.SEGMENT_CHANGED),
            args,
        )
    }

    private fun stateBundle(): Bundle {
        val book = currentBook
        val exact = if (book != null) exactCurrentPosition() else ReadingPosition.START
        val chapter = book?.chapters?.getOrNull(exact.chapterIndex)
        return Bundle().apply {
            putString(StorySessionCommands.KEY_BOOK_ID, book?.id)
            putString(StorySessionCommands.KEY_TITLE, book?.title)
            putInt(StorySessionCommands.KEY_CHAPTER, exact.chapterIndex)
            putInt(StorySessionCommands.KEY_OFFSET, exact.charOffset)
            putInt(StorySessionCommands.KEY_CHAPTER_COUNT, book?.chapters?.size ?: 0)
            putInt(StorySessionCommands.KEY_CHAPTER_LENGTH, chapter?.text?.length ?: 0)
            putString(StorySessionCommands.KEY_CHAPTER_TITLE, chapter?.title)
            putString(StorySessionCommands.KEY_STATUS, state.snapshot().status.name)
            putString(StorySessionCommands.KEY_ERROR, state.snapshot().errorMessage)
            putString(StorySessionCommands.KEY_VOICE, voice)
            putFloat(StorySessionCommands.KEY_SPEED, speed)
            putInt(StorySessionCommands.KEY_PITCH, pitchHz)
        }
    }

    private fun shouldContinuePlaying(): Boolean =
        player.playWhenReady || state.snapshot().status == PlaybackStatus.PLAYING || state.snapshot().status == PlaybackStatus.PREPARING

    private fun mapWordOffsets(text: String, boundaries: List<WordBoundary>): List<WordPoint> {
        var cursor = 0
        return boundaries.map { boundary ->
            var index = text.indexOf(boundary.text, startIndex = cursor, ignoreCase = false)
            if (index < 0) index = text.indexOf(boundary.text, startIndex = cursor, ignoreCase = true)
            if (index < 0) index = cursor.coerceAtMost(text.length)
            cursor = (index + boundary.text.length).coerceAtMost(text.length)
            WordPoint(boundary.text, boundary.offsetMs, boundary.durationMs, index)
        }
    }

    private fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let(mainHandler::removeCallbacks)
        sleepRunnable = null
        if (minutes <= 0) return
        val runnable = Runnable {
            player.pause()
            sleepRunnable = null
        }
        sleepRunnable = runnable
        mainHandler.postDelayed(runnable, minutes * 60_000L)
    }

    private fun loadTtsSettings() {
        val prefs = getSharedPreferences("tts_settings_v1", MODE_PRIVATE)
        voice = prefs.getString("voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
        speed = prefs.getFloat("speed", DEFAULT_SPEED)
        pitchHz = prefs.getInt("pitch", DEFAULT_PITCH)
    }

    private fun saveTtsSettings() {
        getSharedPreferences("tts_settings_v1", MODE_PRIVATE).edit()
            .putString("voice", voice)
            .putFloat("speed", speed)
            .putInt("pitch", pitchHz)
            .apply()
    }

    private fun failure(message: String): SessionResult = SessionResult(
        SessionResult.RESULT_ERROR_UNKNOWN,
        Bundle().apply { putString(StorySessionCommands.KEY_ERROR, message) },
    )

    private fun cleanupOldSegments(currentSegmentIndex: Int) {
        val threshold = currentSegmentIndex - 2
        segments.values
            .filter { it.generation == generation && it.segmentIndex < threshold }
            .forEach { meta ->
                segments.remove(meta.mediaId)
                meta.file.delete()
            }
    }

    private fun clearMaterializedSegments() {
        segments.values.forEach { it.file.delete() }
        segments.clear()
        cleanupSegmentDir()
    }

    private fun cleanupSegmentDir() {
        segmentDir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistExactPosition()
        if (!player.playWhenReady && !player.isPlaying) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        persistExactPosition()
        synthesis.close()
        player.removeListener(this)
        player.release()
        mediaSession?.release()
        mediaSession = null
        clearMaterializedSegments()
        super.onDestroy()
    }

    private data class WordPoint(
        val text: String,
        val offsetMs: Long,
        val durationMs: Long,
        val charOffsetInChunk: Int,
    )

    private data class SegmentMeta(
        val mediaId: String,
        val generation: Long,
        val chapterIndex: Int,
        val segmentIndex: Int,
        val start: Int,
        val endExclusive: Int,
        val text: String,
        val words: List<WordPoint>,
        val file: File,
    )

    companion object {
        private const val PREFETCH_AHEAD = 2
        private const val POSITION_SAVE_INTERVAL_MS = 4_000L
        private const val DEFAULT_VOICE = "vi-VN-NamMinhNeural"
        private const val DEFAULT_SPEED = 0.92f
        private const val DEFAULT_PITCH = -80
    }
}
