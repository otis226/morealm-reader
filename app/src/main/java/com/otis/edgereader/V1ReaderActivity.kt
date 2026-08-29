package com.otis.edgereader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.otis.edgereader.core.epub.EpubBookParser
import com.otis.edgereader.core.epub.ZipFileEpubArchive
import com.otis.edgereader.core.library.FileBookStore
import com.otis.edgereader.core.model.Book
import com.otis.edgereader.core.model.Chapter
import com.otis.edgereader.playback.StoryPlaybackService
import com.otis.edgereader.playback.StorySessionCommands
import com.otis.edgereader.storage.SharedPreferencesPositionStore
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Thin v1 reader UI. It owns no playback, sockets, or synthesis queue.
 * All playback lives in StoryPlaybackService and is controlled through MediaController.
 */
class V1ReaderActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable -> handler.post(runnable) }

    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var textView: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var playerPanel: LinearLayout
    private lateinit var chapterLabel: TextView
    private lateinit var progress: SeekBar
    private lateinit var playButton: Button
    private lateinit var speedLabel: TextView
    private lateinit var statusLabel: TextView

    private lateinit var bookStore: FileBookStore
    private lateinit var positionStore: SharedPreferencesPositionStore
    private var currentBook: Book? = null
    private var currentBookId: String? = null
    private var currentChapter = 0
    private var currentOffset = 0
    private var chapterLength = 0
    private var currentSpeed = 0.92f
    private var currentPitch = -80
    private var currentVoice = "vi-VN-NamMinhNeural"

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var controlsVisible = true
    private var userSeeking = false
    private var windowStart = 0
    private var windowEnd = 0
    private var displayedText: SpannableString? = null
    private var activeHighlight: BackgroundColorSpan? = null
    private var activeStyle: StyleSpan? = null

    private var segmentStart = 0
    private var segmentText = ""
    private var wordTexts: Array<String> = emptyArray()
    private var wordOffsetsMs: LongArray = longArrayOf()
    private var wordDurationsMs: LongArray = longArrayOf()
    private var wordCharOffsets: IntArray = intArrayOf()
    private var lastWordIndex = -1

    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val trackingRunnable = object : Runnable {
        override fun run() {
            updateTrackingFromPlayer()
            handler.postDelayed(this, TRACKING_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playButton.text = if (isPlaying) "⏸" else "▶"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookStore = FileBookStore(File(filesDir, "books_v1"))
        positionStore = SharedPreferencesPositionStore(this)
        buildUi()
        connectController()
        handler.post(trackingRunnable)
        scheduleHideControls()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(250, 247, 241))
        }

        textView = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.rgb(42, 39, 35))
            setLineSpacing(dp(7).toFloat(), 1.08f)
            setPadding(dp(22), dp(32), dp(22), dp(120))
            text = "Dán văn bản hoặc import EPUB để bắt đầu."
            setOnClickListener { toggleControls() }
            setOnLongClickListener {
                if (currentBook != null) {
                    Toast.makeText(this@V1ReaderActivity, "Kéo thanh tiến trình hoặc dùng câu trước/sau để nhảy", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(textView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xEEFFFFFF.toInt())
        }
        topBar.addView(button("DÁN") { showPasteSheet() })
        topBar.addView(button("EPUB") { chooseEpub() })
        topBar.addView(button("SÁCH") { showLibrary() })
        topBar.addView(button("⚙") { showSettingsSheet() })
        statusLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.END
            text = "V1"
        }
        topBar.addView(statusLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(0xF5FFFFFF.toInt())
        }

        chapterLabel = TextView(this).apply {
            text = "Chưa mở sách"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        playerPanel.addView(chapterLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = SeekBar(this).apply {
            max = PROGRESS_MAX
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && chapterLength > 0) {
                        val pct = value * 100 / PROGRESS_MAX
                        chapterLabel.text = "Ch ${currentChapter + 1}/${currentBook?.chapters?.size ?: 0} · $pct%"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = true
                    handler.removeCallbacks(hideControlsRunnable)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    val offset = if (chapterLength <= 0) 0 else ((progress.progress.toLong() * chapterLength) / PROGRESS_MAX).toInt()
                    sendCommand(
                        StorySessionCommands.SEEK_TEXT,
                        Bundle().apply {
                            putInt(StorySessionCommands.KEY_CHAPTER, currentChapter)
                            putInt(StorySessionCommands.KEY_OFFSET, offset)
                        },
                    )
                    scheduleHideControls()
                }
            })
        }
        playerPanel.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        transport.addView(button("⏮ câu") { sendCommand(StorySessionCommands.PREVIOUS_SENTENCE) })
        transport.addView(button("−") { changeSpeed(-0.05f) })
        playButton = button("▶") {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        transport.addView(playButton, LinearLayout.LayoutParams(0, dp(48), 1.2f))
        transport.addView(button("+") { changeSpeed(0.05f) })
        transport.addView(button("câu ⏭") { sendCommand(StorySessionCommands.NEXT_SENTENCE) })
        playerPanel.addView(transport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val chapterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        chapterRow.addView(button("‹ CHƯƠNG") { sendCommand(StorySessionCommands.PREVIOUS_CHAPTER) })
        chapterRow.addView(button("MỤC LỤC") { showToc() })
        chapterRow.addView(button("CHƯƠNG ›") { sendCommand(StorySessionCommands.NEXT_CHAPTER) })
        speedLabel = TextView(this).apply {
            text = "0.92×"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        chapterRow.addView(speedLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f))
        playerPanel.addView(chapterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(
            playerPanel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        setContentView(root)
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, StoryPlaybackService::class.java))
        val listener = object : MediaController.Listener {
            override fun onCustomCommand(
                controller: MediaController,
                command: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                when (command.customAction) {
                    StorySessionCommands.STATE_CHANGED -> handleState(args)
                    StorySessionCommands.SEGMENT_CHANGED -> handleSegment(args)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onDisconnected(controller: MediaController) {
                this@V1ReaderActivity.controller = null
                statusLabel.text = "Mất kết nối player"
            }
        }
        val future = MediaController.Builder(this, token)
            .setListener(listener)
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { connected ->
                        controller = connected
                        connected.addListener(playerListener)
                        playButton.text = if (connected.isPlaying) "⏸" else "▶"
                        requestState()
                    }
                    .onFailure {
                        statusLabel.text = "Không kết nối được playback service"
                    }
            },
            mainExecutor,
        )
    }

    private fun requestState() {
        val c = controller ?: return
        val future = c.sendCustomCommand(StorySessionCommands.command(StorySessionCommands.GET_STATE), Bundle.EMPTY)
        future.addListener(
            {
                runCatching { future.get() }.getOrNull()?.let { result ->
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) handleState(result.extras)
                }
            },
            mainExecutor,
        )
    }

    private fun handleState(args: Bundle) {
        val bookId = args.getString(StorySessionCommands.KEY_BOOK_ID)
        if (!bookId.isNullOrBlank() && bookId != currentBookId) {
            currentBookId = bookId
            currentBook = bookStore.load(bookId)
        }
        currentChapter = args.getInt(StorySessionCommands.KEY_CHAPTER, currentChapter)
        currentOffset = args.getInt(StorySessionCommands.KEY_OFFSET, currentOffset)
        chapterLength = args.getInt(StorySessionCommands.KEY_CHAPTER_LENGTH, chapterLength)
        currentVoice = args.getString(StorySessionCommands.KEY_VOICE) ?: currentVoice
        currentSpeed = args.getFloat(StorySessionCommands.KEY_SPEED, currentSpeed)
        currentPitch = args.getInt(StorySessionCommands.KEY_PITCH, currentPitch)
        speedLabel.text = String.format("%.2f×", currentSpeed)

        val chapterCount = args.getInt(StorySessionCommands.KEY_CHAPTER_COUNT, currentBook?.chapters?.size ?: 0)
        val chapterTitle = args.getString(StorySessionCommands.KEY_CHAPTER_TITLE).orEmpty()
        val pct = if (chapterLength > 0) (currentOffset * 100 / chapterLength).coerceIn(0, 100) else 0
        chapterLabel.text = "Ch ${currentChapter + 1}/$chapterCount · $pct%${if (chapterTitle.isNotBlank()) " · $chapterTitle" else ""}"
        if (!userSeeking && chapterLength > 0) {
            progress.progress = ((currentOffset.toLong() * PROGRESS_MAX) / chapterLength).toInt().coerceIn(0, PROGRESS_MAX)
        }
        statusLabel.text = args.getString(StorySessionCommands.KEY_ERROR)
            ?.takeIf { it.isNotBlank() }
            ?: args.getString(StorySessionCommands.KEY_STATUS).orEmpty()

        ensureReaderWindow(currentOffset, force = displayedText == null)
    }

    private fun handleSegment(args: Bundle) {
        segmentStart = args.getInt(StorySessionCommands.KEY_SEGMENT_START, 0)
        segmentText = args.getString(StorySessionCommands.KEY_SEGMENT_TEXT).orEmpty()
        wordTexts = args.getStringArray(StorySessionCommands.KEY_WORD_TEXTS) ?: emptyArray()
        wordOffsetsMs = args.getLongArray(StorySessionCommands.KEY_WORD_OFFSETS_MS) ?: longArrayOf()
        wordDurationsMs = args.getLongArray(StorySessionCommands.KEY_WORD_DURATIONS_MS) ?: longArrayOf()
        wordCharOffsets = mapWordCharOffsets(segmentText, wordTexts)
        lastWordIndex = -1
        ensureReaderWindow(segmentStart, force = false)
    }

    private fun updateTrackingFromPlayer() {
        val c = controller ?: return
        if (wordOffsetsMs.isEmpty() || wordTexts.isEmpty()) return
        val ms = c.currentPosition.coerceAtLeast(0L)
        var index = -1
        for (i in wordOffsetsMs.indices) {
            if (wordOffsetsMs[i] <= ms) index = i else break
        }
        if (index < 0 || index == lastWordIndex || index >= wordCharOffsets.size) return
        lastWordIndex = index
        val global = segmentStart + wordCharOffsets[index]
        val length = wordTexts.getOrNull(index)?.length ?: 1
        currentOffset = global
        ensureReaderWindow(global, force = false)
        highlightGlobalRange(global, length)
        if (!userSeeking && chapterLength > 0) {
            progress.progress = ((global.toLong() * PROGRESS_MAX) / chapterLength).toInt().coerceIn(0, PROGRESS_MAX)
        }
    }

    private fun ensureReaderWindow(globalOffset: Int, force: Boolean) {
        val book = currentBook ?: return
        val chapter = book.chapters.getOrNull(currentChapter) ?: return
        if (!force && globalOffset in (windowStart + WINDOW_GUARD)..(windowEnd - WINDOW_GUARD)) return

        val start = (globalOffset - WINDOW_BEFORE).coerceAtLeast(0)
        val end = (start + WINDOW_SIZE).coerceAtMost(chapter.text.length)
        windowStart = if (end == chapter.text.length) (end - WINDOW_SIZE).coerceAtLeast(0) else start
        windowEnd = end
        displayedText = SpannableString(chapter.text.substring(windowStart, windowEnd))
        textView.text = displayedText
        scroll.post { scrollToGlobalOffset(globalOffset, immediate = true) }
    }

    private fun highlightGlobalRange(globalStart: Int, length: Int) {
        val span = displayedText ?: return
        val localStart = (globalStart - windowStart).coerceIn(0, span.length)
        val localEnd = (localStart + length).coerceIn(localStart, span.length)
        if (localStart >= localEnd) return

        activeHighlight?.let(span::removeSpan)
        activeStyle?.let(span::removeSpan)
        val highlight = BackgroundColorSpan(0xFFFFE082.toInt())
        val style = StyleSpan(Typeface.BOLD)
        activeHighlight = highlight
        activeStyle = style
        span.setSpan(highlight, localStart, localEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(style, localStart, localEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        textView.invalidate()
        scrollToGlobalOffset(globalStart, immediate = false)
    }

    private fun scrollToGlobalOffset(globalOffset: Int, immediate: Boolean) {
        val local = (globalOffset - windowStart).coerceIn(0, textView.text.length)
        val layout = textView.layout ?: return
        val line = layout.getLineForOffset(local)
        val y = layout.getLineTop(line)
        val viewportTop = scroll.scrollY
        val viewportBottom = viewportTop + scroll.height
        if (y < viewportTop + scroll.height / 4 || y > viewportBottom - scroll.height / 4) {
            val target = (y - scroll.height / 3).coerceAtLeast(0)
            if (immediate) scroll.scrollTo(0, target) else scroll.smoothScrollTo(0, target)
        }
    }

    private fun changeSpeed(delta: Float) {
        currentSpeed = (currentSpeed + delta).coerceIn(0.60f, 1.50f)
        speedLabel.text = String.format("%.2f×", currentSpeed)
        sendTtsSettings()
    }

    private fun sendTtsSettings() {
        sendCommand(
            StorySessionCommands.SET_TTS,
            Bundle().apply {
                putString(StorySessionCommands.KEY_VOICE, currentVoice)
                putFloat(StorySessionCommands.KEY_SPEED, currentSpeed)
                putInt(StorySessionCommands.KEY_PITCH, currentPitch)
            },
        )
    }

    private fun showPasteSheet() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        if (text.isBlank()) {
            Toast.makeText(this, "Clipboard không có văn bản", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Dán văn bản")
            .setItems(arrayOf("Thay thế & đọc", "Nối tiếp ở cuối & đọc", "Chỉ thay thế")) { _, which ->
                when (which) {
                    0 -> replaceWithPastedText(text, autoplay = true)
                    1 -> appendPastedText(text)
                    else -> replaceWithPastedText(text, autoplay = false)
                }
            }
            .show()
    }

    private fun replaceWithPastedText(text: String, autoplay: Boolean) {
        val book = Book(
            id = PASTED_BOOK_ID,
            title = "Truyện dán",
            chapters = listOf(Chapter("pasted-1", "Văn bản", text)),
        )
        positionStore.clear(book.id)
        saveAndOpen(book, autoplay)
    }

    private fun appendPastedText(text: String) {
        val base = currentBook
        val book = if (base == null) {
            Book(PASTED_BOOK_ID, "Truyện dán", listOf(Chapter("pasted-1", "Văn bản", text)))
        } else {
            val chapters = base.chapters.toMutableList()
            if (chapters.isEmpty()) {
                chapters += Chapter("pasted-1", "Văn bản", text)
            } else {
                val last = chapters.last()
                chapters[chapters.lastIndex] = last.copy(text = last.text.trimEnd() + "\n\n" + text)
            }
            base.copy(chapters = chapters)
        }
        saveAndOpen(book, autoplay = true)
    }

    private fun chooseEpub() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/epub+zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/octet-stream", "application/zip"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_EPUB)
    }

    @Deprecated("Deprecated in Android API; kept intentionally to avoid adding an Activity dependency to the minimal v1 client")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EPUB && resultCode == RESULT_OK) {
            data?.data?.let(::importEpub)
        }
    }

    private fun importEpub(uri: Uri) {
        statusLabel.text = "Đang import EPUB…"
        Thread {
            val temp = File.createTempFile("import_", ".epub", cacheDir)
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: error("Không mở được EPUB")
                val book = ZipFileEpubArchive(temp).use(EpubBookParser::parse)
                bookStore.save(book)
                book
            }.onSuccess { book ->
                runOnUiThread {
                    saveAndOpen(book, autoplay = false, alreadySaved = true)
                    Toast.makeText(this, "Đã import ${book.chapters.size} chương", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    statusLabel.text = "EPUB lỗi"
                    Toast.makeText(this, error.message ?: "Không import được EPUB", Toast.LENGTH_LONG).show()
                }
            }
            temp.delete()
        }.start()
    }

    private fun saveAndOpen(book: Book, autoplay: Boolean, alreadySaved: Boolean = false) {
        if (!alreadySaved) bookStore.save(book)
        currentBook = book
        currentBookId = book.id
        sendCommand(
            StorySessionCommands.OPEN_BOOK,
            Bundle().apply {
                putString(StorySessionCommands.KEY_BOOK_ID, book.id)
                putBoolean(StorySessionCommands.KEY_AUTOPLAY, autoplay)
            },
        )
        renderBookStart(book)
    }

    private fun renderBookStart(book: Book) {
        currentChapter = 0
        currentOffset = 0
        chapterLength = book.chapters.first().text.length
        displayedText = null
        ensureReaderWindow(0, force = true)
    }

    private fun showLibrary() {
        val books = bookStore.list()
        if (books.isEmpty()) {
            Toast.makeText(this, "Chưa có sách đã lưu", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Sách đã lưu")
            .setItems(books.map { "${it.title} · ${it.chapterCount} chương" }.toTypedArray()) { _, index ->
                val summary = books[index]
                bookStore.load(summary.id)?.let { saveAndOpen(it, autoplay = false, alreadySaved = true) }
            }
            .show()
    }

    private fun showToc() {
        val book = currentBook ?: return
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(book.chapters.mapIndexed { i, chapter -> "${i + 1}. ${chapter.title}" }.toTypedArray()) { _, index ->
                sendCommand(
                    StorySessionCommands.SEEK_TEXT,
                    Bundle().apply {
                        putInt(StorySessionCommands.KEY_CHAPTER, index)
                        putInt(StorySessionCommands.KEY_OFFSET, 0)
                    },
                )
            }
            .show()
    }

    private fun showSettingsSheet() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        panel.addView(TextView(this).apply {
            text = "Giọng đọc"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        val voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        voiceRow.addView(button("Nam Minh") {
            currentVoice = "vi-VN-NamMinhNeural"
            sendTtsSettings()
            dialog.dismiss()
        })
        voiceRow.addView(button("Hoài My") {
            currentVoice = "vi-VN-HoaiMyNeural"
            sendTtsSettings()
            dialog.dismiss()
        })
        panel.addView(voiceRow)

        val pitchLabel = TextView(this).apply { text = "Độ trầm: $currentPitch Hz" }
        panel.addView(pitchLabel)
        panel.addView(SeekBar(this).apply {
            max = 35
            progress = ((currentPitch + 250) / 10).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    currentPitch = -250 + value * 10
                    pitchLabel.text = "Độ trầm: $currentPitch Hz"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { sendTtsSettings() }
            })
        })

        panel.addView(TextView(this).apply { text = "Hẹn giờ ngủ"; setPadding(0, dp(14), 0, dp(4)) })
        val sleepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0 to "Tắt", 15 to "15p", 30 to "30p", 60 to "60p").forEach { (minutes, label) ->
            sleepRow.addView(button(label) {
                sendCommand(
                    StorySessionCommands.SET_SLEEP_TIMER,
                    Bundle().apply { putInt(StorySessionCommands.KEY_SLEEP_MINUTES, minutes) },
                )
                dialog.dismiss()
            })
        }
        panel.addView(sleepRow)

        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.setOnDismissListener { scheduleHideControls() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun sendCommand(action: String, args: Bundle = Bundle.EMPTY) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Playback service chưa sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }
        val future = c.sendCustomCommand(StorySessionCommands.command(action), args)
        future.addListener(
            {
                runCatching { future.get() }.getOrNull()?.let { result ->
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                        if (result.extras.keySet().isNotEmpty()) handleState(result.extras)
                    } else {
                        val error = result.extras.getString(StorySessionCommands.KEY_ERROR) ?: "Lệnh playback lỗi"
                        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
                    }
                }
            },
            mainExecutor,
        )
        showControlsTemporarily()
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
        if (controlsVisible) scheduleHideControls()
    }

    private fun showControlsTemporarily() {
        setControlsVisible(true)
        scheduleHideControls()
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        playerPanel.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
    }

    private fun mapWordCharOffsets(text: String, words: Array<String>): IntArray {
        var cursor = 0
        return IntArray(words.size) { i ->
            val word = words[i]
            var index = text.indexOf(word, cursor, ignoreCase = false)
            if (index < 0) index = text.indexOf(word, cursor, ignoreCase = true)
            if (index < 0) index = cursor.coerceAtMost(text.length)
            cursor = (index + word.length).coerceAtMost(text.length)
            index
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_EPUB = 501
        private const val PASTED_BOOK_ID = "story-reader:pasted"
        private const val PROGRESS_MAX = 10_000
        private const val TRACKING_INTERVAL_MS = 70L
        private const val CONTROLS_TIMEOUT_MS = 6_000L
        private const val WINDOW_SIZE = 10_000
        private const val WINDOW_BEFORE = 2_800
        private const val WINDOW_GUARD = 1_200
    }
}
