package com.otis.edgereader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import com.otis.edgereader.core.text.WordBoundaryMapper
import com.otis.edgereader.core.tts.WordBoundary
import com.otis.edgereader.playback.StoryPlaybackService
import com.otis.edgereader.playback.StorySessionCommands
import com.otis.edgereader.storage.SharedPreferencesPositionStore
import java.io.File
import java.util.concurrent.Executor

/** Thin UI client. Playback, Edge connections and queues live in StoryPlaybackService. */
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
    private lateinit var playButton: TextView
    private lateinit var speedLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var bookTitleLabel: TextView

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
    private var voiceVolume = 1f
    private var ambientVolume = 0.18f
    private var ambientLabel = "Tắt"
    private var ambientUri: String? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var controlsVisible = true
    private var userSeeking = false
    private var windowStart = 0
    private var windowEnd = 0
    private var renderedChapter = -1
    private var displayedText: SpannableString? = null
    private var activeHighlight: BackgroundColorSpan? = null
    private var activeStyle: StyleSpan? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var segmentStart = 0
    private var mappedWordOffsets = IntArray(0)
    private var wordTexts: Array<String> = emptyArray()
    private var wordOffsetsMs = LongArray(0)
    private var lastWordIndex = -1

    private var pendingAmbientSlot: AmbientSlot? = null

    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val trackingRunnable = object : Runnable {
        override fun run() {
            updateTrackingFromPlayer()
            handler.postDelayed(this, TRACKING_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playButton.text = if (isPlaying) "Ⅱ" else "▶"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = CANVAS
        window.navigationBarColor = CANVAS
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        bookStore = FileBookStore(File(filesDir, "books_v1"))
        positionStore = SharedPreferencesPositionStore(this)
        buildUi()
        connectController()
        handler.post(trackingRunnable)
        scheduleHideControls()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(CANVAS)
            fitsSystemWindows = true
        }

        textView = TextView(this).apply {
            textSize = 21f
            setTextColor(INK)
            setLineSpacing(dp(8).toFloat(), 1.10f)
            setPadding(dp(28), dp(78), dp(28), dp(210))
            text = "Chưa có truyện\n\nChạm ••• để dán văn bản hoặc mở EPUB."
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_DOWN) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                false
            }
            setOnClickListener {
                if (!controlsVisible) showControlsTemporarily()
                else if (currentBook != null) jumpToTouchedSentence()
            }
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(textView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bookTitleLabel = TextView(this).apply {
            text = "Story Reader"
            textSize = 15f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        statusLabel = TextView(this).apply {
            text = "Dán hoặc mở một cuốn sách"
            textSize = 11f
            setTextColor(MUTED)
            maxLines = 1
        }
        titleStack.addView(bookTitleLabel)
        titleStack.addView(statusLabel)
        topBar.addView(titleStack, LinearLayout.LayoutParams(0, dp(52), 1f))

        val menuButton = iconPill("•••") { anchor -> showMainMenu(anchor) }
        topBar.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(42)))
        root.addView(
            topBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(60), Gravity.TOP).apply {
                leftMargin = dp(18); rightMargin = dp(18); topMargin = dp(8)
            },
        )

        playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            background = rounded(CARD, 28f)
            elevation = dp(12).toFloat()
        }
        chapterLabel = TextView(this).apply {
            text = "Chưa mở sách"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        playerPanel.addView(chapterLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))

        progress = SeekBar(this).apply {
            max = PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && chapterLength > 0) {
                        val pct = value * 100 / PROGRESS_MAX
                        val total = currentBook?.chapters?.size ?: 0
                        chapterLabel.text = "Chương ${currentChapter + 1}${if (total > 0) "/$total" else ""}  ·  $pct%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = true
                    handler.removeCallbacks(hideControlsRunnable)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    val value = seekBar?.progress ?: 0
                    val offset = if (chapterLength <= 0) 0 else ((value.toLong() * chapterLength) / PROGRESS_MAX).toInt()
                    seekText(currentChapter, offset)
                    scheduleHideControls()
                }
            })
        }
        playerPanel.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))

        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        transport.addView(transportButton("↶\nCâu", false) { sendCommand(StorySessionCommands.PREVIOUS_SENTENCE) })
        transport.addView(transportButton("−", false) { changeSpeed(-0.05f) })
        playButton = transportButton("▶", true) { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        transport.addView(playButton, LinearLayout.LayoutParams(dp(62), dp(62)).apply { leftMargin = dp(7); rightMargin = dp(7) })
        transport.addView(transportButton("+", false) { changeSpeed(0.05f) })
        transport.addView(transportButton("Câu\n↷", false) { sendCommand(StorySessionCommands.NEXT_SENTENCE) })
        playerPanel.addView(transport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))

        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        quickRow.addView(softAction("‹ Chương") { sendCommand(StorySessionCommands.PREVIOUS_CHAPTER) })
        quickRow.addView(softAction("Mục lục") { showToc() })
        quickRow.addView(softAction("Chương ›") { sendCommand(StorySessionCommands.NEXT_CHAPTER) })
        speedLabel = TextView(this).apply {
            text = "0.92×"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(ACCENT_DARK)
            background = rounded(ACCENT_SOFT, 18f)
            setOnClickListener { showSettingsSheet() }
        }
        quickRow.addView(speedLabel, LinearLayout.LayoutParams(dp(62), dp(38)).apply { leftMargin = dp(8) })
        playerPanel.addView(quickRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        root.addView(
            playerPanel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(14)
            },
        )
        setContentView(root)
    }

    private fun showMainMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Dán văn bản")
            menu.add("Mở EPUB")
            menu.add("Sách đã lưu")
            menu.add("Cài đặt đọc")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Dán văn bản" -> showPasteSheet()
                    "Mở EPUB" -> chooseEpub()
                    "Sách đã lưu" -> showLibrary()
                    "Cài đặt đọc" -> showSettingsSheet()
                }
                true
            }
            show()
        }
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, StoryPlaybackService::class.java))
        val listener = object : MediaController.Listener {
            override fun onCustomCommand(controller: MediaController, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
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
        val future = MediaController.Builder(this, token).setListener(listener).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }
                .onSuccess { connected ->
                    controller = connected
                    connected.addListener(playerListener)
                    playButton.text = if (connected.isPlaying) "Ⅱ" else "▶"
                    requestState()
                }
                .onFailure { statusLabel.text = "Không kết nối playback service" }
        }, mainExecutor)
    }

    private fun requestState() {
        val c = controller ?: return
        val future = c.sendCustomCommand(StorySessionCommands.command(StorySessionCommands.GET_STATE), Bundle.EMPTY)
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { result ->
                if (result.resultCode == SessionResult.RESULT_SUCCESS) handleState(result.extras)
            }
        }, mainExecutor)
    }

    private fun handleState(args: Bundle) {
        val bookId = args.getString(StorySessionCommands.KEY_BOOK_ID)
        if (!bookId.isNullOrBlank() && (bookId != currentBookId || currentBook == null)) {
            currentBookId = bookId
            currentBook = bookStore.load(bookId)
        }
        val oldChapter = currentChapter
        currentChapter = args.getInt(StorySessionCommands.KEY_CHAPTER, currentChapter)
        currentOffset = args.getInt(StorySessionCommands.KEY_OFFSET, currentOffset)
        chapterLength = args.getInt(StorySessionCommands.KEY_CHAPTER_LENGTH, chapterLength)
        currentVoice = args.getString(StorySessionCommands.KEY_VOICE) ?: currentVoice
        currentSpeed = args.getFloat(StorySessionCommands.KEY_SPEED, currentSpeed)
        currentPitch = args.getInt(StorySessionCommands.KEY_PITCH, currentPitch)
        voiceVolume = args.getFloat(StorySessionCommands.KEY_VOICE_VOLUME, voiceVolume)
        ambientUri = args.getString(StorySessionCommands.KEY_AMBIENT_URI)
        ambientLabel = args.getString(StorySessionCommands.KEY_AMBIENT_LABEL) ?: ambientLabel
        ambientVolume = args.getFloat(StorySessionCommands.KEY_AMBIENT_VOLUME, ambientVolume)
        speedLabel.text = String.format("%.2f×", currentSpeed)

        val chapterCount = args.getInt(StorySessionCommands.KEY_CHAPTER_COUNT, currentBook?.chapters?.size ?: 0)
        val title = args.getString(StorySessionCommands.KEY_CHAPTER_TITLE).orEmpty()
        val pct = if (chapterLength > 0) (currentOffset * 100 / chapterLength).coerceIn(0, 100) else 0
        bookTitleLabel.text = currentBook?.title ?: "Story Reader"
        if (chapterCount <= 0 || currentBook == null) {
            chapterLabel.text = "Chưa mở sách"
            statusLabel.text = "Dán hoặc mở một cuốn sách"
        } else {
            chapterLabel.text = "Chương ${currentChapter + 1}/$chapterCount  ·  $pct%"
            statusLabel.text = title.ifBlank { "Chương ${currentChapter + 1}" }
        }
        val error = args.getString(StorySessionCommands.KEY_ERROR)?.takeIf { it.isNotBlank() }
        if (error != null) statusLabel.text = error
        if (!userSeeking && chapterLength > 0) {
            progress.progress = ((currentOffset.toLong() * PROGRESS_MAX) / chapterLength).toInt().coerceIn(0, PROGRESS_MAX)
        }
        ensureReaderWindow(currentOffset, force = oldChapter != currentChapter || displayedText == null)
    }

    private fun handleSegment(args: Bundle) {
        segmentStart = args.getInt(StorySessionCommands.KEY_SEGMENT_START, 0)
        val segmentText = args.getString(StorySessionCommands.KEY_SEGMENT_TEXT).orEmpty()
        wordTexts = args.getStringArray(StorySessionCommands.KEY_WORD_TEXTS) ?: emptyArray()
        wordOffsetsMs = args.getLongArray(StorySessionCommands.KEY_WORD_OFFSETS_MS) ?: LongArray(0)
        val durations = args.getLongArray(StorySessionCommands.KEY_WORD_DURATIONS_MS) ?: LongArray(0)
        val boundaries = wordTexts.indices.map { i ->
            WordBoundary(wordTexts[i], wordOffsetsMs.getOrElse(i) { 0L }, durations.getOrElse(i) { 0L })
        }
        mappedWordOffsets = WordBoundaryMapper.map(segmentText, boundaries).map { it.charOffset }.toIntArray()
        lastWordIndex = -1
        ensureReaderWindow(segmentStart, force = false)
    }

    private fun updateTrackingFromPlayer() {
        val c = controller ?: return
        if (wordOffsetsMs.isEmpty() || mappedWordOffsets.isEmpty()) return
        val ms = c.currentPosition.coerceAtLeast(0L)
        var index = -1
        for (i in wordOffsetsMs.indices) {
            if (wordOffsetsMs[i] <= ms) index = i else break
        }
        if (index < 0 || index == lastWordIndex || index >= mappedWordOffsets.size) return
        lastWordIndex = index
        val global = segmentStart + mappedWordOffsets[index]
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
        val chapterChanged = renderedChapter != currentChapter
        if (!force && !chapterChanged && globalOffset in (windowStart + WINDOW_GUARD)..(windowEnd - WINDOW_GUARD)) return
        val provisionalStart = (globalOffset - WINDOW_BEFORE).coerceAtLeast(0)
        val provisionalEnd = (provisionalStart + WINDOW_SIZE).coerceAtMost(chapter.text.length)
        windowStart = if (provisionalEnd == chapter.text.length) (provisionalEnd - WINDOW_SIZE).coerceAtLeast(0) else provisionalStart
        windowEnd = provisionalEnd
        renderedChapter = currentChapter
        activeHighlight = null
        activeStyle = null
        displayedText = SpannableString(chapter.text.substring(windowStart, windowEnd))
        textView.text = displayedText
        scroll.post { scrollToGlobalOffset(globalOffset, true) }
    }

    private fun highlightGlobalRange(globalStart: Int, length: Int) {
        val span = displayedText ?: return
        val localStart = (globalStart - windowStart).coerceIn(0, span.length)
        val localEnd = (localStart + length).coerceIn(localStart, span.length)
        if (localStart >= localEnd) return
        activeHighlight?.let(span::removeSpan)
        activeStyle?.let(span::removeSpan)
        val highlight = BackgroundColorSpan(HIGHLIGHT)
        val style = StyleSpan(Typeface.BOLD)
        activeHighlight = highlight
        activeStyle = style
        span.setSpan(highlight, localStart, localEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(style, localStart, localEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        textView.invalidate()
        scrollToGlobalOffset(globalStart, false)
    }

    private fun scrollToGlobalOffset(globalOffset: Int, immediate: Boolean) {
        val local = (globalOffset - windowStart).coerceIn(0, textView.text.length)
        val layout = textView.layout ?: return
        val line = layout.getLineForOffset(local)
        val y = layout.getLineTop(line)
        val top = scroll.scrollY
        val bottom = top + scroll.height
        if (y < top + scroll.height / 4 || y > bottom - scroll.height / 4) {
            val target = (y - scroll.height / 3).coerceAtLeast(0)
            if (immediate) scroll.scrollTo(0, target) else scroll.smoothScrollTo(0, target)
        }
    }

    private fun jumpToTouchedSentence() {
        val layout = textView.layout ?: return
        val line = layout.getLineForVertical(lastTouchY.toInt().coerceAtLeast(0))
        val localOffset = layout.getOffsetForHorizontal(line, lastTouchX.coerceAtLeast(0f))
        seekText(currentChapter, (windowStart + localOffset).coerceIn(0, chapterLength))
        showControlsTemporarily()
    }

    private fun seekText(chapter: Int, offset: Int) {
        sendCommand(StorySessionCommands.SEEK_TEXT, Bundle().apply {
            putInt(StorySessionCommands.KEY_CHAPTER, chapter)
            putInt(StorySessionCommands.KEY_OFFSET, offset)
        })
    }

    private fun changeSpeed(delta: Float) {
        currentSpeed = (currentSpeed + delta).coerceIn(0.60f, 1.50f)
        speedLabel.text = String.format("%.2f×", currentSpeed)
        sendTtsSettings()
    }

    private fun sendTtsSettings() {
        sendCommand(StorySessionCommands.SET_TTS, Bundle().apply {
            putString(StorySessionCommands.KEY_VOICE, currentVoice)
            putFloat(StorySessionCommands.KEY_SPEED, currentSpeed)
            putInt(StorySessionCommands.KEY_PITCH, currentPitch)
            putFloat(StorySessionCommands.KEY_VOICE_VOLUME, voiceVolume)
        })
    }

    private fun showPasteSheet() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        if (pasted.isBlank()) {
            Toast.makeText(this, "Clipboard không có văn bản", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Dán văn bản")
            .setItems(arrayOf("Thay thế & đọc", "Nối tiếp ở cuối & đọc", "Chỉ thay thế")) { _, which ->
                when (which) {
                    0 -> replaceWithPastedText(pasted, true)
                    1 -> appendPastedText(pasted)
                    else -> replaceWithPastedText(pasted, false)
                }
            }.show()
    }

    private fun replaceWithPastedText(text: String, autoplay: Boolean) {
        val book = Book(PASTED_BOOK_ID, "Truyện dán", listOf(Chapter("pasted-1", "Văn bản", text)))
        positionStore.clear(book.id)
        saveAndOpen(book, autoplay)
    }

    private fun appendPastedText(text: String) {
        val base = currentBook
        val book = if (base == null) {
            Book(PASTED_BOOK_ID, "Truyện dán", listOf(Chapter("pasted-1", "Văn bản", text)))
        } else {
            val chapters = base.chapters.toMutableList()
            val last = chapters.last()
            chapters[chapters.lastIndex] = last.copy(text = last.text.trimEnd() + "\n\n" + text)
            base.copy(chapters = chapters)
        }
        saveAndOpen(book, true)
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

    private fun chooseAmbientAudio(slot: AmbientSlot) {
        pendingAmbientSlot = slot
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            flags = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_AMBIENT_AUDIO)
    }

    @Deprecated("Legacy result API keeps the minimal reader dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_EPUB -> data?.data?.let(::importEpub)
            REQUEST_AMBIENT_AUDIO -> data?.data?.let(::acceptAmbientAudio)
        }
    }

    private fun importEpub(uri: Uri) {
        statusLabel.text = "Đang import EPUB…"
        Thread {
            val temp = File.createTempFile("import_", ".epub", cacheDir)
            runCatching {
                contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().buffered().use { output -> input.copyTo(output) } }
                    ?: error("Không mở được EPUB")
                val book = ZipFileEpubArchive(temp).use(EpubBookParser::parse)
                bookStore.save(book)
                book
            }.onSuccess { book ->
                runOnUiThread {
                    saveAndOpen(book, false, alreadySaved = true)
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

    private fun acceptAmbientAudio(uri: Uri) {
        val slot = pendingAmbientSlot ?: AmbientSlot.CUSTOM
        pendingAmbientSlot = null
        runCatching { contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION) }
        ambientSlotPrefs().edit().putString(slot.key, uri.toString()).apply()
        selectAmbient(slot, uri.toString())
    }

    private fun selectAmbient(slot: AmbientSlot, explicitUri: String? = null) {
        if (slot == AmbientSlot.OFF) {
            ambientLabel = "Tắt"
            ambientUri = null
            sendAmbient()
            return
        }
        val uri = explicitUri ?: ambientSlotPrefs().getString(slot.key, null)
        if (uri.isNullOrBlank()) {
            Toast.makeText(this, "Chọn file audio cho ${slot.label}", Toast.LENGTH_SHORT).show()
            chooseAmbientAudio(slot)
            return
        }
        ambientLabel = slot.label
        ambientUri = uri
        sendAmbient()
    }

    private fun sendAmbient() {
        sendCommand(StorySessionCommands.SET_AMBIENT, Bundle().apply {
            putString(StorySessionCommands.KEY_AMBIENT_URI, ambientUri)
            putString(StorySessionCommands.KEY_AMBIENT_LABEL, ambientLabel)
            putFloat(StorySessionCommands.KEY_AMBIENT_VOLUME, ambientVolume)
        })
    }

    private fun saveAndOpen(book: Book, autoplay: Boolean, alreadySaved: Boolean = false) {
        if (!alreadySaved) bookStore.save(book)
        currentBook = book
        currentBookId = book.id
        renderedChapter = -1
        sendCommand(StorySessionCommands.OPEN_BOOK, Bundle().apply {
            putString(StorySessionCommands.KEY_BOOK_ID, book.id)
            putBoolean(StorySessionCommands.KEY_AUTOPLAY, autoplay)
        })
        currentChapter = 0
        currentOffset = 0
        chapterLength = book.chapters.first().text.length
        displayedText = null
        ensureReaderWindow(0, true)
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
                bookStore.load(books[index].id)?.let { saveAndOpen(it, false, alreadySaved = true) }
            }.show()
    }

    private fun showToc() {
        val book = currentBook ?: return
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(book.chapters.mapIndexed { i, c -> "${i + 1}. ${c.title}" }.toTypedArray()) { _, index -> seekText(index, 0) }
            .show()
    }

    private fun showSettingsSheet() {
        handler.removeCallbacks(hideControlsRunnable)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(28))
            background = rounded(CARD, 30f)
        }
        panel.addView(View(this).apply { background = rounded(0xFFD7D4CE.toInt(), 4f) }, LinearLayout.LayoutParams(dp(42), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        panel.addView(TextView(this).apply {
            text = "Cài đặt đọc"
            textSize = 22f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(18))
        })

        panel.addView(sectionLabel("GIỌNG ĐỌC"))
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(SURFACE, 15f)
        }
        lateinit var male: TextView
        lateinit var female: TextView
        fun refreshVoice() {
            styleSegment(male, currentVoice == "vi-VN-NamMinhNeural")
            styleSegment(female, currentVoice == "vi-VN-HoaiMyNeural")
        }
        male = segment("Nam Minh") { currentVoice = "vi-VN-NamMinhNeural"; refreshVoice(); sendTtsSettings() }
        female = segment("Hoài My") { currentVoice = "vi-VN-HoaiMyNeural"; refreshVoice(); sendTtsSettings() }
        voiceRow.addView(male, LinearLayout.LayoutParams(0, dp(44), 1f))
        voiceRow.addView(female, LinearLayout.LayoutParams(0, dp(44), 1f))
        refreshVoice()
        panel.addView(voiceRow)

        panel.addView(sliderBlock("Độ trầm", "$currentPitch Hz", 35, ((currentPitch + 250) / 10).coerceIn(0, 35)) { label, value, stopped ->
            currentPitch = -250 + value * 10
            label.text = "$currentPitch Hz"
            if (stopped) sendTtsSettings()
        })
        panel.addView(sliderBlock("Âm lượng giọng", "${(voiceVolume * 100).toInt()}%", 100, (voiceVolume * 100).toInt()) { label, value, stopped ->
            voiceVolume = value / 100f
            label.text = "$value%"
            if (stopped) sendTtsSettings()
        })

        panel.addView(sectionLabel("ÂM NỀN"))
        val ambientSlots = listOf(AmbientSlot.OFF, AmbientSlot.RAIN, AmbientSlot.FIRE, AmbientSlot.OCEAN, AmbientSlot.NIGHT, AmbientSlot.CAFE, AmbientSlot.WARM_MUSIC, AmbientSlot.CUSTOM)
        ambientSlots.forEach { slot ->
            panel.addView(ambientRow(slot) {
                dialog.dismiss()
                selectAmbient(slot)
            })
        }
        panel.addView(sliderBlock("Âm lượng nền", "${(ambientVolume * 100).toInt()}%", 60, (ambientVolume * 100).toInt().coerceAtMost(60)) { label, value, stopped ->
            ambientVolume = value / 100f
            label.text = "$value%"
            if (stopped) sendAmbient()
        })

        panel.addView(sectionLabel("HẸN GIỜ NGỦ"))
        val sleepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(0 to "Tắt", 15 to "15m", 30 to "30m", 60 to "60m").forEach { (minutes, label) ->
            sleepRow.addView(chip(label) { setSleep(minutes); dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
        panel.addView(sleepRow)

        val scroller = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(panel) }
        dialog.setContentView(scroller)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.22f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.78f).toInt())
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.78f).toInt())
            setGravity(Gravity.BOTTOM)
        }
        dialog.setOnDismissListener { scheduleHideControls() }
    }

    private fun sliderBlock(title: String, value: String, max: Int, initial: Int, onChange: (TextView, Int, Boolean) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(4)) }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        labels.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(INK) }, LinearLayout.LayoutParams(0, dp(28), 1f))
        val valueView = TextView(this).apply { text = value; textSize = 14f; setTextColor(MUTED); gravity = Gravity.END }
        labels.addView(valueView, LinearLayout.LayoutParams(dp(84), dp(28)))
        box.addView(labels)
        box.addView(SeekBar(this).apply {
            this.max = max
            progress = initial.coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(valueView, progress, false) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { onChange(valueView, seekBar?.progress ?: initial, true) }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        return box
    }

    private fun ambientRow(slot: AmbientSlot, onClick: () -> Unit): View {
        val assigned = slot == AmbientSlot.OFF || !ambientSlotPrefs().getString(slot.key, null).isNullOrBlank()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(if (ambientLabel == slot.label) ACCENT_SOFT else SURFACE, 15f)
            setOnClickListener { onClick() }
            val icon = when (slot) {
                AmbientSlot.OFF -> "○"; AmbientSlot.RAIN -> "☂"; AmbientSlot.FIRE -> "♨"; AmbientSlot.OCEAN -> "≈"
                AmbientSlot.NIGHT -> "☾"; AmbientSlot.CAFE -> "☕"; AmbientSlot.WARM_MUSIC -> "♫"; AmbientSlot.CUSTOM -> "+"
            }
            addView(TextView(this@V1ReaderActivity).apply { text = icon; textSize = 20f; setTextColor(ACCENT_DARK); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(38), dp(48)))
            addView(TextView(this@V1ReaderActivity).apply { text = slot.label; textSize = 15f; setTextColor(INK); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(TextView(this@V1ReaderActivity).apply {
                text = when { ambientLabel == slot.label -> "✓"; assigned && slot != AmbientSlot.OFF -> "Đã chọn"; else -> "›" }
                textSize = 12f
                setTextColor(if (ambientLabel == slot.label) ACCENT_DARK else MUTED)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(dp(70), dp(48)))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(6) }
        }
    }

    private fun setSleep(minutes: Int) {
        sendCommand(StorySessionCommands.SET_SLEEP_TIMER, Bundle().apply { putInt(StorySessionCommands.KEY_SLEEP_MINUTES, minutes) })
    }

    private fun sendCommand(action: String, args: Bundle = Bundle.EMPTY) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Playback service chưa sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }
        val future = c.sendCustomCommand(StorySessionCommands.command(action), args)
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { result ->
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    if (result.extras.keySet().isNotEmpty()) handleState(result.extras)
                } else {
                    val error = result.extras.getString(StorySessionCommands.KEY_ERROR) ?: "Lệnh playback lỗi"
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            }
        }, mainExecutor)
        showControlsTemporarily()
    }

    private fun showControlsTemporarily() {
        setControlsVisible(true)
        scheduleHideControls()
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        if (visible) {
            topBar.visibility = View.VISIBLE
            playerPanel.visibility = View.VISIBLE
            topBar.alpha = 0f
            playerPanel.alpha = 0f
            topBar.animate().alpha(1f).setDuration(150).start()
            playerPanel.animate().alpha(1f).setDuration(180).start()
        } else {
            topBar.animate().alpha(0f).setDuration(140).withEndAction { if (!controlsVisible) topBar.visibility = View.GONE }.start()
            playerPanel.animate().alpha(0f).setDuration(140).withEndAction { if (!controlsVisible) playerPanel.visibility = View.GONE }.start()
        }
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
    }

    private fun ambientSlotPrefs() = getSharedPreferences("ambient_slots_v1", Context.MODE_PRIVATE)

    private fun sectionLabel(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(MUTED)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(8))
    }

    private fun segment(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(INK)
        setOnClickListener { onClick() }
    }

    private fun styleSegment(view: TextView, active: Boolean) {
        view.background = rounded(if (active) CARD else Color.TRANSPARENT, 12f)
        view.setTextColor(if (active) INK else MUTED)
        view.elevation = if (active) dp(2).toFloat() else 0f
        view.setTypeface(view.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(INK)
        background = rounded(SURFACE, 18f)
        setOnClickListener { onClick() }
    }

    private fun iconPill(label: String, onClick: (View) -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(INK)
        background = rounded(SURFACE, 21f)
        setOnClickListener { onClick(this) }
    }

    private fun transportButton(label: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = if (primary) 22f else 14f
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else INK)
        background = rounded(if (primary) ACCENT else Color.TRANSPARENT, if (primary) 31f else 18f)
        setOnClickListener { onClick() }
        if (!primary) layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f)
    }

    private fun softAction(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(INK)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }

    private enum class AmbientSlot(val key: String, val label: String) {
        OFF("off", "Tắt"), RAIN("rain", "Mưa"), FIRE("fire", "Lò sưởi"), OCEAN("ocean", "Sóng biển"),
        NIGHT("night", "Rừng đêm"), CAFE("cafe", "Café"), WARM_MUSIC("warm_music", "Nhạc nền ấm"), CUSTOM("custom", "Tùy chọn")
    }

    companion object {
        private const val REQUEST_EPUB = 501
        private const val REQUEST_AMBIENT_AUDIO = 502
        private const val PASTED_BOOK_ID = "story-reader:pasted"
        private const val PROGRESS_MAX = 10_000
        private const val TRACKING_INTERVAL_MS = 70L
        private const val CONTROLS_TIMEOUT_MS = 6_000L
        private const val WINDOW_SIZE = 10_000
        private const val WINDOW_BEFORE = 2_800
        private const val WINDOW_GUARD = 1_200

        private val CANVAS = Color.rgb(247, 244, 238)
        private val CARD = Color.rgb(255, 254, 251)
        private val SURFACE = Color.rgb(239, 237, 232)
        private val TRACK = Color.rgb(220, 217, 210)
        private val INK = Color.rgb(42, 40, 37)
        private val MUTED = Color.rgb(122, 118, 111)
        private val ACCENT = Color.rgb(91, 132, 119)
        private val ACCENT_DARK = Color.rgb(62, 100, 88)
        private val ACCENT_SOFT = Color.rgb(226, 237, 232)
        private val HIGHLIGHT = Color.rgb(232, 239, 226)
    }
}
