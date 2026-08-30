package com.otis.edgereader

import android.app.Activity
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
import android.text.TextUtils
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

/**
 * iOS-soft reader shell. Playback, Edge synthesis, caching and persistence remain owned by
 * StoryPlaybackService; this activity is deliberately just a presentation/controller client.
 */
class SoftReaderActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable -> handler.post(runnable) }

    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var textView: TextView
    private lateinit var emptyState: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var playerPanel: LinearLayout
    private lateinit var bookTitleView: TextView
    private lateinit var chapterTopView: TextView
    private lateinit var statusView: TextView
    private lateinit var chapterLabel: TextView
    private lateinit var progressPct: TextView
    private lateinit var progress: SeekBar
    private lateinit var playButton: TextView
    private lateinit var speedChip: TextView
    private lateinit var ambientChip: TextView
    private lateinit var sleepChip: TextView

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
    private var sleepMinutes = 0

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

    private val hideControlsRunnable = Runnable {
        if (currentBook != null) setControlsVisible(false)
    }
    private val trackingRunnable = object : Runnable {
        override fun run() {
            updateTrackingFromPlayer()
            handler.postDelayed(this, TRACKING_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayGlyph(isPlaying)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        bookStore = FileBookStore(File(filesDir, "books_v1"))
        positionStore = SharedPreferencesPositionStore(this)
        buildUi()
        connectController()
        handler.post(trackingRunnable)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(BG) }

        textView = TextView(this).apply {
            textSize = 20.5f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            setLineSpacing(dp(9).toFloat(), 1.08f)
            setPadding(dp(28), dp(118), dp(28), dp(190))
            text = ""
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                false
            }
            setOnClickListener {
                if (!controlsVisible) {
                    showControlsTemporarily()
                } else if (currentBook != null) {
                    jumpToTouchedSentence()
                }
            }
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(textView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        emptyState = buildEmptyState()
        root.addView(
            emptyState,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        topBar = buildTopBar()
        root.addView(
            topBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(92), Gravity.TOP).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                topMargin = dp(4)
            },
        )

        playerPanel = buildPlayerPanel()
        root.addView(
            playerPanel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(14)
            },
        )
        updateEmptyState()
        setContentView(root)
    }

    private fun buildEmptyState(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(24), dp(32), dp(24))

        addView(TextView(this@SoftReaderActivity).apply {
            text = "Story Reader"
            textSize = 31f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        })
        addView(TextView(this@SoftReaderActivity).apply {
            text = "Một nơi yên tĩnh để đọc và nghe truyện."
            textSize = 15f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(28))
        })
        addView(primaryPill("Dán & đọc") { showPasteSheet() }, LinearLayout.LayoutParams(dp(210), dp(52)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(softPill("Mở EPUB") { chooseEpub() }, LinearLayout.LayoutParams(dp(210), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        })
        addView(TextView(this@SoftReaderActivity).apply {
            text = "Bạn có thể copy truyện từ ChatGPT rồi dán nối tiếp bất cứ lúc nào."
            textSize = 12.5f
            setTextColor(MUTED_2)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(24), dp(18), 0)
        })
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = rounded(0xE8FAF7F1.toInt(), 24)
            elevation = dp(1).toFloat()
        }

        bar.addView(circleControl("＋", 40) { showSourceSheet() }, LinearLayout.LayoutParams(dp(44), dp(44)))

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
        }
        bookTitleView = TextView(this).apply {
            text = "Story Reader"
            textSize = 15f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        chapterTopView = TextView(this).apply {
            text = ""
            textSize = 11.5f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        }
        statusView = TextView(this).apply {
            visibility = View.GONE
            textSize = 10.5f
            setTextColor(ERROR)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleBox.addView(bookTitleView)
        titleBox.addView(chapterTopView)
        titleBox.addView(statusView)
        bar.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        bar.addView(circleControl("•••", 40) { showMoreSheet() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        return bar
    }

    private fun buildPlayerPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(14))
            background = rounded(CARD, 28)
            elevation = dp(10).toFloat()
        }

        val chapterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chapterRow.addView(iconControl("‹") { sendCommand(StorySessionCommands.PREVIOUS_CHAPTER) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        chapterLabel = TextView(this).apply {
            text = "Chưa mở sách"
            textSize = 13f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener { showToc() }
        }
        chapterRow.addView(chapterLabel, LinearLayout.LayoutParams(0, dp(34), 1f))
        progressPct = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        chapterRow.addView(progressPct, LinearLayout.LayoutParams(dp(48), dp(34)))
        chapterRow.addView(iconControl("›") { sendCommand(StorySessionCommands.NEXT_CHAPTER) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        panel.addView(chapterRow)

        progress = SeekBar(this).apply {
            max = PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && chapterLength > 0) progressPct.text = "${value * 100 / PROGRESS_MAX}%"
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
        panel.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(5))
        }
        transport.addView(transportControl("↶", "câu") { sendCommand(StorySessionCommands.PREVIOUS_SENTENCE) })
        transport.addView(circleControl("−", 44) { changeSpeed(-0.05f) }, LinearLayout.LayoutParams(dp(50), dp(50)))
        playButton = circleControl("▶", 62) {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }.apply {
            textSize = 21f
            background = rounded(ACCENT, 31)
            setTextColor(Color.WHITE)
        }
        transport.addView(playButton, LinearLayout.LayoutParams(dp(68), dp(68)).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
        transport.addView(circleControl("＋", 44) { changeSpeed(0.05f) }, LinearLayout.LayoutParams(dp(50), dp(50)))
        transport.addView(transportControl("↷", "câu") { sendCommand(StorySessionCommands.NEXT_SENTENCE) })
        panel.addView(transport)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        speedChip = compactChip("0.92×") { showSettingsSheet(focus = SettingsFocus.SPEED) }
        sleepChip = compactChip("☾  Tắt") { showSleepSheet() }
        ambientChip = compactChip("♪  Tắt") { showSettingsSheet(focus = SettingsFocus.AMBIENT) }
        chips.addView(speedChip, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(5) })
        chips.addView(sleepChip, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(5); rightMargin = dp(5) })
        chips.addView(ambientChip, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(5) })
        panel.addView(chips)
        return panel
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
                this@SoftReaderActivity.controller = null
                showStatus("Mất kết nối player", true)
            }
        }
        val future = MediaController.Builder(this, token).setListener(listener).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }
                .onSuccess { connected ->
                    controller = connected
                    connected.addListener(playerListener)
                    updatePlayGlyph(connected.isPlaying)
                    requestState()
                }
                .onFailure { showStatus("Không kết nối playback service", true) }
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

        val chapterCount = args.getInt(StorySessionCommands.KEY_CHAPTER_COUNT, currentBook?.chapters?.size ?: 0)
        val chapterTitle = args.getString(StorySessionCommands.KEY_CHAPTER_TITLE).orEmpty()
        val pct = if (chapterLength > 0) (currentOffset * 100 / chapterLength).coerceIn(0, 100) else 0
        val bookTitle = args.getString(StorySessionCommands.KEY_TITLE).orEmpty().ifBlank { currentBook?.title.orEmpty() }

        bookTitleView.text = bookTitle.ifBlank { "Story Reader" }
        chapterTopView.text = when {
            chapterCount <= 0 -> ""
            chapterTitle.isNotBlank() -> "Chương ${currentChapter + 1} · $chapterTitle"
            else -> "Chương ${currentChapter + 1}"
        }
        chapterLabel.text = chapterTitle.ifBlank { if (chapterCount > 0) "Chương ${currentChapter + 1}" else "Chưa mở sách" }
        progressPct.text = if (chapterCount > 0) "$pct%" else ""
        speedChip.text = String.format("%.2f×", currentSpeed)
        ambientChip.text = "♪  ${ambientLabel.ifBlank { "Tắt" }}"
        sleepChip.text = if (sleepMinutes <= 0) "☾  Tắt" else "☾  ${sleepMinutes}p"

        if (!userSeeking && chapterLength > 0) {
            progress.progress = ((currentOffset.toLong() * PROGRESS_MAX) / chapterLength).toInt().coerceIn(0, PROGRESS_MAX)
        }

        val error = args.getString(StorySessionCommands.KEY_ERROR)?.takeIf { it.isNotBlank() }
        val status = args.getString(StorySessionCommands.KEY_STATUS).orEmpty()
        showStatus(error ?: status, error != null)
        ensureReaderWindow(currentOffset, force = oldChapter != currentChapter || displayedText == null)
        updateEmptyState()
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
            progressPct.text = "${(global * 100 / chapterLength).coerceIn(0, 100)}%"
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
        speedChip.text = String.format("%.2f×", currentSpeed)
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

    private fun showSourceSheet() {
        showActionSheet("Thêm nội dung", listOf(
            ActionItem("Dán văn bản", "Từ clipboard", "＋") { showPasteSheet() },
            ActionItem("Mở EPUB", "Import sách theo chương", "⌑") { chooseEpub() },
            ActionItem("Thư viện", "Các sách đã lưu", "▤") { showLibrary() },
        ))
    }

    private fun showMoreSheet() {
        showActionSheet(currentBook?.title ?: "Story Reader", listOf(
            ActionItem("Cài đặt đọc", "Giọng, độ trầm, âm lượng", "⚙") { showSettingsSheet() },
            ActionItem("Mục lục", "Chọn chương", "≡") { showToc() },
            ActionItem("Dán nối tiếp", "Thêm phần mới vào cuối", "＋") { showPasteSheet() },
        ))
    }

    private fun showPasteSheet() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        if (pasted.isBlank()) {
            Toast.makeText(this, "Clipboard chưa có văn bản", Toast.LENGTH_SHORT).show()
            return
        }
        showActionSheet("Dán văn bản", listOf(
            ActionItem("Thay thế & đọc", "Tạo nội dung mới và phát ngay", "▶") { replaceWithPastedText(pasted, true) },
            ActionItem("Nối tiếp ở cuối", "Giữ vị trí hiện tại, thêm phần mới", "＋") { appendPastedText(pasted) },
            ActionItem("Chỉ thay thế", "Không tự phát", "□") { replaceWithPastedText(pasted, false) },
        ))
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
        showStatus("Đang import EPUB…", false)
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
                    saveAndOpen(book, false, alreadySaved = true)
                    Toast.makeText(this, "Đã import ${book.chapters.size} chương", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    showStatus("Không import được EPUB", true)
                    Toast.makeText(this, error.message ?: "EPUB lỗi", Toast.LENGTH_LONG).show()
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
            ambientChip.text = "♪  Tắt"
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
        ambientChip.text = "♪  ${slot.label}"
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
        currentChapter = 0
        currentOffset = 0
        chapterLength = book.chapters.firstOrNull()?.text?.length ?: 0
        displayedText = null
        updateEmptyState()
        bookTitleView.text = book.title
        sendCommand(StorySessionCommands.OPEN_BOOK, Bundle().apply {
            putString(StorySessionCommands.KEY_BOOK_ID, book.id)
            putBoolean(StorySessionCommands.KEY_AUTOPLAY, autoplay)
        })
        ensureReaderWindow(0, true)
    }

    private fun showLibrary() {
        val books = bookStore.list()
        if (books.isEmpty()) {
            Toast.makeText(this, "Chưa có sách đã lưu", Toast.LENGTH_SHORT).show()
            return
        }
        showActionSheet("Thư viện", books.map { entry ->
            ActionItem(entry.title, "${entry.chapterCount} chương", "▤") {
                bookStore.load(entry.id)?.let { saveAndOpen(it, false, alreadySaved = true) }
            }
        })
    }

    private fun showToc() {
        val book = currentBook ?: return
        showActionSheet(book.title, book.chapters.mapIndexed { index, chapter ->
            ActionItem(chapter.title.ifBlank { "Chương ${index + 1}" }, "Chương ${index + 1}", if (index == currentChapter) "✓" else "") {
                seekText(index, 0)
            }
        }, maxHeightFraction = 0.78f)
    }

    private fun showSettingsSheet(focus: SettingsFocus = SettingsFocus.TOP) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(28))
            background = rounded(CARD, 30)
        }
        content.addView(sheetHandle())
        content.addView(sheetTitle("Cài đặt đọc"))

        content.addView(sectionLabel("GIỌNG ĐỌC"))
        val voiceSegment = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(SOFT_FILL, 15)
        }
        val nam = segmented("Nam Minh", currentVoice == "vi-VN-NamMinhNeural") {
            currentVoice = "vi-VN-NamMinhNeural"
            dialog.dismiss()
            sendTtsSettings()
            showSettingsSheet(SettingsFocus.TOP)
        }
        val my = segmented("Hoài My", currentVoice == "vi-VN-HoaiMyNeural") {
            currentVoice = "vi-VN-HoaiMyNeural"
            dialog.dismiss()
            sendTtsSettings()
            showSettingsSheet(SettingsFocus.TOP)
        }
        voiceSegment.addView(nam, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(2) })
        voiceSegment.addView(my, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(2) })
        content.addView(voiceSegment, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        content.addView(settingSlider(
            label = "Tốc độ",
            valueText = String.format("%.2f×", currentSpeed),
            max = 90,
            initial = ((currentSpeed - 0.60f) * 100).toInt().coerceIn(0, 90),
            onChange = { value, valueView ->
                currentSpeed = 0.60f + value / 100f
                valueView.text = String.format("%.2f×", currentSpeed)
                speedChip.text = String.format("%.2f×", currentSpeed)
            },
            onStop = { sendTtsSettings() },
        ))
        content.addView(settingSlider(
            label = "Độ trầm",
            valueText = "$currentPitch Hz",
            max = 25,
            initial = ((currentPitch + 250) / 10).coerceIn(0, 25),
            onChange = { value, valueView ->
                currentPitch = -250 + value * 10
                valueView.text = "$currentPitch Hz"
            },
            onStop = { sendTtsSettings() },
        ))
        content.addView(settingSlider(
            label = "Âm lượng giọng",
            valueText = "${(voiceVolume * 100).toInt()}%",
            max = 100,
            initial = (voiceVolume * 100).toInt(),
            onChange = { value, valueView ->
                voiceVolume = value / 100f
                valueView.text = "$value%"
            },
            onStop = { sendTtsSettings() },
        ))

        content.addView(sectionLabel("ÂM NỀN"))
        content.addView(TextView(this).apply {
            text = "Mỗi lựa chọn dùng file audio thật trên máy. Chọn lần đầu để gán file."
            textSize = 12.5f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(8))
        })
        AmbientSlot.entries.forEach { slot ->
            content.addView(ambientRow(slot, dialog))
        }
        content.addView(settingSlider(
            label = "Âm lượng nền",
            valueText = "${(ambientVolume * 100).toInt()}%",
            max = 60,
            initial = (ambientVolume * 100).toInt().coerceAtMost(60),
            onChange = { value, valueView ->
                ambientVolume = value / 100f
                valueView.text = "$value%"
            },
            onStop = { sendAmbient() },
        ))

        content.addView(sectionLabel("HẸN GIỜ NGỦ"))
        val sleepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0 to "Tắt", 15 to "15p", 30 to "30p", 60 to "60p").forEachIndexed { index, item ->
            sleepRow.addView(segmented(item.second, sleepMinutes == item.first) {
                setSleep(item.first)
                dialog.dismiss()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (index > 0) leftMargin = dp(5)
            })
        }
        content.addView(sleepRow)

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        dialog.setContentView(scrollView)
        dialog.setOnDismissListener { scheduleHideControls() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.24f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.82f).toInt())
            setGravity(Gravity.BOTTOM)
        }
        if (focus != SettingsFocus.TOP) {
            handler.postDelayed({
                val target = if (focus == SettingsFocus.AMBIENT) content.height / 2 else dp(120)
                scrollView.smoothScrollTo(0, target)
            }, 180)
        }
    }

    private fun showSleepSheet() {
        showActionSheet("Hẹn giờ ngủ", listOf(
            ActionItem("Tắt", "Không tự dừng", "○") { setSleep(0) },
            ActionItem("15 phút", "Dừng sau 15 phút", "☾") { setSleep(15) },
            ActionItem("30 phút", "Dừng sau 30 phút", "☾") { setSleep(30) },
            ActionItem("60 phút", "Dừng sau 60 phút", "☾") { setSleep(60) },
        ))
    }

    private fun setSleep(minutes: Int) {
        sleepMinutes = minutes
        sleepChip.text = if (minutes <= 0) "☾  Tắt" else "☾  ${minutes}p"
        sendCommand(StorySessionCommands.SET_SLEEP_TIMER, Bundle().apply {
            putInt(StorySessionCommands.KEY_SLEEP_MINUTES, minutes)
        })
    }

    private fun showActionSheet(title: String, actions: List<ActionItem>, maxHeightFraction: Float = 0.62f) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(24))
            background = rounded(CARD, 30)
        }
        panel.addView(sheetHandle())
        panel.addView(sheetTitle(title))
        actions.forEach { action ->
            panel.addView(actionRow(action) {
                dialog.dismiss()
                action.onClick()
            })
        }
        val scrollView = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(panel)
        }
        dialog.setContentView(scrollView)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.22f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply {
                height = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
                gravity = Gravity.BOTTOM
            }
        }
        dialog.setOnDismissListener { scheduleHideControls() }
    }

    private fun actionRow(item: ActionItem, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = rounded(SOFT_FILL, 18)
            setOnClickListener { onClick() }
        }
        val icon = TextView(this).apply {
            text = item.icon
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(ACCENT_DARK)
            background = rounded(ACCENT_SOFT, 16)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        labels.addView(TextView(this).apply {
            text = item.title
            textSize = 15.5f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        })
        if (item.subtitle.isNotBlank()) labels.addView(TextView(this).apply {
            text = item.subtitle
            textSize = 12f
            setTextColor(MUTED)
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 23f
            setTextColor(MUTED_2)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), dp(42)))
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun ambientRow(slot: AmbientSlot, dialog: Dialog): View {
        val assigned = if (slot == AmbientSlot.OFF) true else !ambientSlotPrefs().getString(slot.key, null).isNullOrBlank()
        val selected = ambientLabel == slot.label
        return actionRow(
            ActionItem(
                title = slot.label,
                subtitle = when {
                    slot == AmbientSlot.OFF -> "Không phát âm nền"
                    assigned -> "Đã gán file audio"
                    else -> "Chạm để chọn file audio"
                },
                icon = if (selected) "✓" else slot.symbol,
            ) {
                dialog.dismiss()
                selectAmbient(slot)
            },
        )
    }

    private fun settingSlider(
        label: String,
        valueText: String,
        max: Int,
        initial: Int,
        onChange: (Int, TextView) -> Unit,
        onStop: () -> Unit,
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = label
            textSize = 14.5f
            setTextColor(TEXT)
        }, LinearLayout.LayoutParams(0, dp(28), 1f))
        val value = TextView(this).apply {
            text = valueText
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        head.addView(value, LinearLayout.LayoutParams(dp(86), dp(28)))
        box.addView(head)
        box.addView(SeekBar(this).apply {
            this.max = max
            progress = initial.coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress, value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = onStop()
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        return box
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(MUTED_2)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(2), dp(20), 0, dp(8))
        letterSpacing = 0.08f
    }

    private fun sheetTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 23f
        setTextColor(TEXT)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        includeFontPadding = false
        setPadding(dp(2), dp(8), 0, dp(8))
    }

    private fun sheetHandle(): TextView = TextView(this).apply {
        text = ""
        gravity = Gravity.CENTER
        background = rounded(0xFFD8D4CC.toInt(), 2)
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(7)
        }
    }

    private fun segmented(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13.5f
        gravity = Gravity.CENTER
        setTextColor(if (selected) TEXT else MUTED)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(if (selected) Color.WHITE else Color.TRANSPARENT, 12)
        if (selected) elevation = dp(1).toFloat()
        setOnClickListener { onClick() }
    }

    private fun primaryPill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        background = rounded(ACCENT, 26)
        setOnClickListener { onClick() }
    }

    private fun softPill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14.5f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(TEXT)
        background = rounded(SOFT_FILL, 24)
        setOnClickListener { onClick() }
    }

    private fun compactChip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(MUTED)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(SOFT_FILL, 18)
        setOnClickListener { onClick() }
    }

    private fun circleControl(label: String, size: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = if (size >= 60) 20f else 18f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(SOFT_FILL, size / 2)
        setOnClickListener { onClick() }
    }

    private fun iconControl(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 25f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(MUTED)
        setOnClickListener { onClick() }
    }

    private fun transportControl(icon: String, caption: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        addView(TextView(this@SoftReaderActivity).apply {
            text = icon
            textSize = 22f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        addView(TextView(this@SoftReaderActivity).apply {
            text = caption
            textSize = 10.5f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun sendCommand(action: String, args: Bundle = Bundle.EMPTY) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player đang khởi động…", Toast.LENGTH_SHORT).show()
            return
        }
        val future = c.sendCustomCommand(StorySessionCommands.command(action), args)
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { result ->
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    if (result.extras.keySet().isNotEmpty()) handleState(result.extras)
                } else {
                    val error = result.extras.getString(StorySessionCommands.KEY_ERROR) ?: "Không thực hiện được"
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            }
        }, mainExecutor)
        showControlsTemporarily()
    }

    private fun updatePlayGlyph(isPlaying: Boolean) {
        playButton.text = if (isPlaying) "Ⅱ" else "▶"
    }

    private fun updateEmptyState() {
        val hasBook = currentBook != null
        emptyState.visibility = if (hasBook) View.GONE else View.VISIBLE
        textView.visibility = if (hasBook) View.VISIBLE else View.INVISIBLE
        playerPanel.visibility = if (hasBook && controlsVisible) View.VISIBLE else View.GONE
        if (!hasBook) {
            topBar.visibility = View.GONE
            handler.removeCallbacks(hideControlsRunnable)
        } else if (controlsVisible) {
            topBar.visibility = View.VISIBLE
        }
    }

    private fun showStatus(value: String, isError: Boolean) {
        val cleaned = value.trim()
        val show = isError || cleaned.startsWith("Lỗi", true)
        statusView.visibility = if (show && cleaned.isNotBlank()) View.VISIBLE else View.GONE
        if (show) {
            statusView.text = cleaned
            statusView.setTextColor(ERROR)
        }
    }

    private fun showControlsTemporarily() {
        setControlsVisible(true)
        scheduleHideControls()
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val hasBook = currentBook != null
        topBar.visibility = if (visible && hasBook) View.VISIBLE else View.GONE
        playerPanel.visibility = if (visible && hasBook) View.VISIBLE else View.GONE
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (currentBook != null) handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
    }

    private fun ambientSlotPrefs() = getSharedPreferences("ambient_slots_v1", Context.MODE_PRIVATE)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }

    private data class ActionItem(
        val title: String,
        val subtitle: String,
        val icon: String,
        val onClick: () -> Unit,
    )

    private enum class SettingsFocus { TOP, SPEED, AMBIENT }

    private enum class AmbientSlot(val key: String, val label: String, val symbol: String) {
        OFF("off", "Tắt", "○"),
        RAIN("rain", "Mưa", "∿"),
        FIRE("fire", "Lò sưởi", "△"),
        OCEAN("ocean", "Sóng biển", "≈"),
        NIGHT("night", "Rừng đêm", "☾"),
        CAFE("cafe", "Café", "◌"),
        WARM_MUSIC("warm_music", "Nhạc nền ấm", "♪"),
        CUSTOM("custom", "Âm thanh khác", "＋"),
    }

    companion object {
        private const val REQUEST_EPUB = 601
        private const val REQUEST_AMBIENT_AUDIO = 602
        private const val PASTED_BOOK_ID = "story-reader:pasted"
        private const val PROGRESS_MAX = 10_000
        private const val TRACKING_INTERVAL_MS = 70L
        private const val CONTROLS_TIMEOUT_MS = 5_500L
        private const val WINDOW_SIZE = 10_000
        private const val WINDOW_BEFORE = 2_800
        private const val WINDOW_GUARD = 1_200

        private val BG = Color.rgb(248, 246, 240)
        private val CARD = Color.rgb(255, 254, 251)
        private val TEXT = Color.rgb(39, 39, 37)
        private val MUTED = Color.rgb(111, 111, 106)
        private val MUTED_2 = Color.rgb(151, 149, 141)
        private val SOFT_FILL = Color.rgb(239, 238, 233)
        private val ACCENT = Color.rgb(74, 134, 123)
        private val ACCENT_DARK = Color.rgb(54, 104, 95)
        private val ACCENT_SOFT = Color.rgb(226, 239, 234)
        private val HIGHLIGHT = Color.rgb(235, 226, 205)
        private val ERROR = Color.rgb(161, 73, 64)
    }
}
