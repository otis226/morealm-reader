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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.otis.edgereader.core.library.FileBookStore
import com.otis.edgereader.core.model.Book
import com.otis.edgereader.core.model.ReadingPosition
import com.otis.edgereader.core.playback.PlaybackStateMachine
import com.otis.edgereader.core.playback.PlaybackStatus
import com.otis.edgereader.core.text.MappedWordBoundary
import com.otis.edgereader.core.text.SentenceChunker
import com.otis.edgereader.core.text.SentenceNavigator
import com.otis.edgereader.core.text.TextChunk
import com.otis.edgereader.core.text.WordBoundaryMapper
import com.otis.edgereader.core.tts.SynthesisCoordinator
import com.otis.edgereader.core.tts.SynthesisSpec
import com.otis.edgereader.core.tts.TtsAudio
import com.otis.edgereader.core.tts.edge.EdgeTtsEngine
import com.otis.edgereader.storage.SharedPreferencesPositionStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Service-owned narration pipeline.
 *
 * Activity/UI never owns narration playback, Edge sockets or synthesis jobs.
 * Every text seek/navigation invalidates the previous generation, so stale audio
 * cannot be enqueued after the user moves elsewhere in the book.
 */
class StoryPlaybackService : MediaSessionService(), Player.Listener {
    private lateinit var player: ExoPlayer
    private lateinit var ambient: AmbientPlayer
    private var mediaSession: MediaSession? = null
    private lateinit var bookStore: FileBookStore
    private lateinit var positionStore: SharedPreferencesPositionStore
    private lateinit var synthesis: SynthesisCoordinator
    private val state = PlaybackStateMachine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentBook: Book? = null
    private var generation = 0L
    private var nextSynthesisOffset = 0
    private var nextSegmentIndex = 0
    private var synthInFlight = false
    private var firstChunk = true
    private var autoPlayWhenReady = false
    private var sleepRunnable: Runnable? = null

    private var voice = DEFAULT_VOICE
    private var speed = DEFAULT_SPEED
    private var pitchHz = DEFAULT_PITCH
    private var voiceVolume = DEFAULT_VOICE_VOLUME

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
        ambient = AmbientPlayer(this)
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
            volume = voiceVolume
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
            ): ListenableFuture<SessionResult> =
                Futures.immediateFuture(handleCustomCommand(customCommand.customAction, args))
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()

        restoreLastBookState()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun handleCustomCommand(action: String, args: Bundle): SessionResult {
        return try {
            when (action) {
                StorySessionCommands.OPEN_BOOK -> {
                    val bookId = args.getString(StorySessionCommands.KEY_BOOK_ID).orEmpty()
                    openBook(bookId, args.getBoolean(StorySessionCommands.KEY_AUTOPLAY, false))
                }

                StorySessionCommands.SEEK_TEXT -> {
                    val book = currentBook ?: return failure("Chưa mở sách")
                    val chapter = args.getInt(
                        StorySessionCommands.KEY_CHAPTER,
                        state.snapshot().position.chapterIndex,
                    )
                    val offset = args.getInt(StorySessionCommands.KEY_OFFSET, 0)
                    restartAt(
                        ReadingPosition(chapter, offset).normalized(book),
                        shouldContinuePlaying(),
                    )
                }

                StorySessionCommands.NEXT_SENTENCE -> navigateSentence(true)
                StorySessionCommands.PREVIOUS_SENTENCE -> navigateSentence(false)
                StorySessionCommands.NEXT_CHAPTER -> navigateChapter(true)
                StorySessionCommands.PREVIOUS_CHAPTER -> navigateChapter(false)

                StorySessionCommands.SET_TTS -> updateTtsSettings(args)

                StorySessionCommands.SET_AMBIENT -> {
                    val uri = args.getString(StorySessionCommands.KEY_AMBIENT_URI)
                    val label = args.getString(StorySessionCommands.KEY_AMBIENT_LABEL).orEmpty()
                    val volume = args.getFloat(
                        StorySessionCommands.KEY_AMBIENT_VOLUME,
                        ambient.volume,
                    )
                    ambient.configure(uri, label, volume)
                    ambient.syncWithNarration(player.isPlaying)
                }

                StorySessionCommands.SET_SLEEP_TIMER ->
                    setSleepTimer(args.getInt(StorySessionCommands.KEY_SLEEP_MINUTES, 0))

                StorySessionCommands.GET_STATE -> Unit
                else -> return SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            }
            broadcastState()
            SessionResult(SessionResult.RESULT_SUCCESS, stateBundle())
        } catch (t: Throwable) {
            state.fail(t.message ?: "Lỗi phát truyện")
            broadcastState()
            failure(t.message ?: "Lỗi phát truyện")
        }
    }

    private fun updateTtsSettings(args: Bundle) {
        val oldVoice = voice
        val oldSpeed = speed
        val oldPitch = pitchHz

        voice = args.getString(StorySessionCommands.KEY_VOICE)
            ?.takeIf { it.isNotBlank() }
            ?: voice
        speed = args.getFloat(StorySessionCommands.KEY_SPEED, speed).coerceIn(0.55f, 1.8f)
        pitchHz = args.getInt(StorySessionCommands.KEY_PITCH, pitchHz).coerceIn(-250, 100)
        voiceVolume = args.getFloat(
            StorySessionCommands.KEY_VOICE_VOLUME,
            voiceVolume,
        ).coerceIn(0f, 1f)
        player.volume = voiceVolume
        saveTtsSettings()

        val synthesisChanged = oldVoice != voice || oldSpeed != speed || oldPitch != pitchHz
        if (synthesisChanged && currentBook != null) {
            restartAt(exactCurrentPosition(), shouldContinuePlaying())
        }
    }

    private fun openBook(bookId: String, autoplay: Boolean) {
        require(bookId.isNotBlank()) { "Thiếu book id" }
        val book = bookStore.load(bookId) ?: error("Không tìm thấy sách")
        currentBook = book
        playbackPrefs().edit().putString(KEY_LAST_BOOK_ID, book.id).apply()
        val saved = positionStore.load(book.id)?.normalized(book) ?: ReadingPosition.START
        state.load(book, saved)
        restartAt(saved, autoplay)
    }

    private fun restoreLastBookState() {
        val id = playbackPrefs().getString(KEY_LAST_BOOK_ID, null) ?: return
        val book = bookStore.load(id) ?: return
        currentBook = book
        val saved = positionStore.load(book.id)?.normalized(book) ?: ReadingPosition.START
        state.load(book, saved)
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
                    acceptSynthesizedSegment(
                        localGeneration,
                        chapterIndex,
                        localSegmentIndex,
                        chunk,
                        audio,
                    )
                }
            },
            onError = { error ->
                mainHandler.post {
                    if (localGeneration == generation) {
                        synthInFlight = false
                        state.fail(error.message ?: "Edge TTS lỗi")
                        player.pause()
                        ambient.syncWithNarration(false)
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
            words = WordBoundaryMapper.map(chunk.text, audio.boundaries),
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
                    .build(),
            )
            .build()

        val oldCount = player.mediaItemCount
        val wasEmpty = oldCount == 0
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.addMediaItem(mediaItem)
        nextSynthesisOffset = chunk.endExclusive
        firstChunk = false
        synthInFlight = false

        if (wasEmpty) {
            player.prepare()
            if (autoPlayWhenReady) player.play()
        } else if (wasEnded) {
            player.seekTo(oldCount, 0L)
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
            ambient.syncWithNarration(false)
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

    override fun onPlayerError(error: PlaybackException) {
        state.fail("Audio playback: ${error.message}")
        persistExactPosition()
        ambient.syncWithNarration(false)
        broadcastState()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        mainHandler.removeCallbacks(progressSaver)
        ambient.syncWithNarration(isPlaying)
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
            if (player.mediaItemCount == 0 && currentBook != null) {
                val snapshot = state.snapshot()
                restartAt(snapshot.position, autoplay = true)
            }
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
        val charInChunk = WordBoundaryMapper.charOffsetAtPlayback(
            meta.words,
            player.currentPosition.coerceAtLeast(0L),
            fallback = 0,
        )
        return ReadingPosition(meta.chapterIndex, meta.start + charInChunk).normalized(book)
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
        val args = Bundle().apply {
            putInt(StorySessionCommands.KEY_CHAPTER, meta.chapterIndex)
            putInt(StorySessionCommands.KEY_SEGMENT_START, meta.start)
            putInt(StorySessionCommands.KEY_SEGMENT_END, meta.endExclusive)
            putString(StorySessionCommands.KEY_SEGMENT_TEXT, meta.text)
            putStringArray(
                StorySessionCommands.KEY_WORD_TEXTS,
                meta.words.map { it.text }.toTypedArray(),
            )
            putLongArray(
                StorySessionCommands.KEY_WORD_OFFSETS_MS,
                meta.words.map { it.offsetMs }.toLongArray(),
            )
            putLongArray(
                StorySessionCommands.KEY_WORD_DURATIONS_MS,
                meta.words.map { it.durationMs }.toLongArray(),
            )
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
            putFloat(StorySessionCommands.KEY_VOICE_VOLUME, voiceVolume)
            putString(StorySessionCommands.KEY_AMBIENT_URI, ambient.uri)
            putString(StorySessionCommands.KEY_AMBIENT_LABEL, ambient.label)
            putFloat(StorySessionCommands.KEY_AMBIENT_VOLUME, ambient.volume)
        }
    }

    private fun shouldContinuePlaying(): Boolean =
        player.playWhenReady ||
            state.snapshot().status == PlaybackStatus.PLAYING ||
            state.snapshot().status == PlaybackStatus.PREPARING

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
        val prefs = getSharedPreferences(PREF_TTS, MODE_PRIVATE)
        voice = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        speed = prefs.getFloat(KEY_SPEED, DEFAULT_SPEED).coerceIn(0.55f, 1.8f)
        pitchHz = prefs.getInt(KEY_PITCH, DEFAULT_PITCH).coerceIn(-250, 100)
        voiceVolume = prefs.getFloat(KEY_VOICE_VOLUME_PREF, DEFAULT_VOICE_VOLUME).coerceIn(0f, 1f)
    }

    private fun saveTtsSettings() {
        getSharedPreferences(PREF_TTS, MODE_PRIVATE).edit()
            .putString(KEY_VOICE, voice)
            .putFloat(KEY_SPEED, speed)
            .putInt(KEY_PITCH, pitchHz)
            .putFloat(KEY_VOICE_VOLUME_PREF, voiceVolume)
            .apply()
    }

    private fun playbackPrefs() = getSharedPreferences(PREF_PLAYBACK, MODE_PRIVATE)

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
        segmentDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
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
        ambient.close()
        player.removeListener(this)
        player.release()
        mediaSession?.release()
        mediaSession = null
        clearMaterializedSegments()
        super.onDestroy()
    }

    private data class SegmentMeta(
        val mediaId: String,
        val generation: Long,
        val chapterIndex: Int,
        val segmentIndex: Int,
        val start: Int,
        val endExclusive: Int,
        val text: String,
        val words: List<MappedWordBoundary>,
        val file: File,
    )

    companion object {
        private const val PREFETCH_AHEAD = 2
        private const val POSITION_SAVE_INTERVAL_MS = 4_000L
        private const val DEFAULT_VOICE = "vi-VN-NamMinhNeural"
        private const val DEFAULT_SPEED = 0.92f
        private const val DEFAULT_PITCH = -80
        private const val DEFAULT_VOICE_VOLUME = 1f

        private const val PREF_TTS = "tts_settings_v1"
        private const val KEY_VOICE = "voice"
        private const val KEY_SPEED = "speed"
        private const val KEY_PITCH = "pitch"
        private const val KEY_VOICE_VOLUME_PREF = "voice_volume"
        private const val PREF_PLAYBACK = "playback_v1"
        private const val KEY_LAST_BOOK_ID = "last_book_id"
    }
}
