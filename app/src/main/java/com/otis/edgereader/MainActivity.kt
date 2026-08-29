package com.otis.edgereader

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var controller: PlaybackController
    private lateinit var readerScroll: ScrollView
    private lateinit var readerText: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var playerPanel: LinearLayout
    private lateinit var status: TextView
    private lateinit var progressSeek: SeekBar
    private lateinit var progressLabel: TextView
    private lateinit var speedLabel: TextView
    private lateinit var playPauseButton: Button
    private lateinit var documentTitle: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideControls() }

    private var speed = 0.95f
    private var pitchHz = -70
    private var voiceName = "vi-VN-NamMinhNeural"
    private var voiceVolume = 1.0f
    private var ambienceVolume = 0.20f
    private var musicVolume = 0.10f
    private var backgroundMode = BackgroundSoundEngine.Mode.OFF
    private var customSoundUri: Uri? = null

    private var currentText = ""
    private var windowStart = 0
    private var windowEnd = 0
    private var spannable = SpannableString("")
    private var highlightSpan: BackgroundColorSpan? = null
    private var boldSpan: StyleSpan? = null

    private var controlsVisible = false
    private var userSeeking = false
    private var lastReadOffset = 0
    private var lastTapGlobalOffset = -1
    private var playingUi = false
    private var pausedUi = false

    private val ambienceOptions = listOf(
        "Tắt" to BackgroundSoundEngine.Mode.OFF,
        "Mưa nhẹ" to BackgroundSoundEngine.Mode.RAIN,
        "Lò sưởi" to BackgroundSoundEngine.Mode.FIREPLACE,
        "Sóng biển" to BackgroundSoundEngine.Mode.OCEAN,
        "Brown noise" to BackgroundSoundEngine.Mode.BROWN_NOISE,
        "Đêm yên tĩnh" to BackgroundSoundEngine.Mode.NIGHT,
        "Pad ấm" to BackgroundSoundEngine.Mode.PAD,
        "Mưa + Pad ấm" to BackgroundSoundEngine.Mode.RAIN_PAD,
        "Âm thanh riêng…" to BackgroundSoundEngine.Mode.CUSTOM,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(250, 248, 243)
        window.navigationBarColor = Color.rgb(250, 248, 243)
        buildUi()
        controller = PlaybackController(
            context = this,
            onStatus = { updateStatus(it) },
            onWord = { start, end -> highlightRange(start, end) },
            onProgress = { current, total -> updateProgress(current, total) },
        )
        applyAudioSettings()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(Color.rgb(250, 248, 243))
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "Story Reader"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(32, 32, 32))
        })
        documentTitle = TextView(this).apply {
            text = "Dán văn bản hoặc mở EPUB"
            textSize = 11.5f
            setTextColor(Color.DKGRAY)
            maxLines = 1
        }
        titleBox.addView(documentTitle)
        topBar.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(smallButton("DÁN") { showPasteSheet() })
        topBar.addView(smallButton("EPUB") { openEpubPicker() })
        topBar.addView(smallButton("⚙") { showSettingsSheet() })
        root.addView(topBar)

        readerText = TextView(this).apply {
            text = "Copy truyện từ ChatGPT rồi chạm giữa màn hình để mở điều khiển.\n\nKhi player đang mở, chạm trực tiếp vào một câu để đọc từ câu đó."
            textSize = 20f
            setTextColor(Color.rgb(35, 35, 35))
            setLineSpacing(dp(6).toFloat(), 1.10f)
            setPadding(dp(16), dp(18), dp(16), dp(32))
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP && controlsVisible && currentText.isNotBlank()) {
                    val layout = layout
                    if (layout != null) {
                        val x = (event.x - totalPaddingLeft).coerceAtLeast(0f)
                        val y = (event.y - totalPaddingTop).coerceAtLeast(0f)
                        val line = layout.getLineForVertical(y.toInt().coerceAtLeast(0))
                        val local = layout.getOffsetForHorizontal(line, x)
                        lastTapGlobalOffset = (windowStart + local).coerceIn(0, currentText.length)
                    }
                }
                false
            }
            setOnClickListener {
                if (!controlsVisible) {
                    showControls()
                } else if (lastTapGlobalOffset >= 0 && currentText.isNotBlank()) {
                    val target = sentenceStartAtOrBefore(currentText, lastTapGlobalOffset)
                    lastTapGlobalOffset = -1
                    renderWindow(target)
                    startCurrentText(target)
                    bumpControls()
                }
            }
        }

        readerScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                readerText,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        root.addView(
            readerScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(4)
            }
        )

        playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }

        val progressHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progressLabel = TextView(this).apply {
            text = "0%"
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        speedLabel = TextView(this).apply {
            text = "0.95x"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.END
        }
        progressHeader.addView(progressLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progressHeader.addView(speedLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        playerPanel.addView(progressHeader)

        progressSeek = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        progressLabel.text = "${progress / 10}%"
                        bumpControls()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = true
                    bumpControls()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    if (currentText.isBlank()) return
                    val p = seekBar?.progress ?: 0
                    val rough = ((p / 1000.0) * currentText.length).toInt().coerceIn(0, currentText.length)
                    val target = sentenceStartAtOrBefore(currentText, rough)
                    renderWindow(target)
                    startCurrentText(target)
                    bumpControls()
                }
            })
        }
        playerPanel.addView(progressSeek)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(playerButton("⏮") { jumpSentence(-1) })
        controls.addView(playerButton("−") { adjustSpeed(-0.05f) })
        playPauseButton = playerButton("▶") { togglePlayPause() }
        controls.addView(playPauseButton)
        controls.addView(playerButton("+") { adjustSpeed(0.05f) })
        controls.addView(playerButton("⏭") { jumpSentence(1) })
        playerPanel.addView(controls)

        status = TextView(this).apply {
            text = "Sẵn sàng"
            textSize = 11.5f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        playerPanel.addView(status)
        root.addView(playerPanel)

        setContentView(root)
    }

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        playerPanel.visibility = View.VISIBLE
        bumpControls()
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        playerPanel.visibility = View.GONE
        lastTapGlobalOffset = -1
    }

    private fun bumpControls() {
        if (!controlsVisible) return
        uiHandler.removeCallbacks(autoHideRunnable)
        uiHandler.postDelayed(autoHideRunnable, AUTO_HIDE_MS)
    }

    private fun togglePlayPause() {
        bumpControls()
        if (playingUi && !pausedUi) {
            controller.pause()
            return
        }
        if (controller.resume()) return
        if (currentText.isBlank()) {
            showPasteSheet()
            return
        }
        val target = sentenceStartAtOrBefore(currentText, lastReadOffset.coerceIn(0, currentText.length))
        startCurrentText(target)
    }

    private fun adjustSpeed(delta: Float) {
        speed = (speed + delta).coerceIn(0.65f, 1.50f)
        speedLabel.text = "%.2fx".format(speed)
        if (controller.isActiveOrPaused() && currentText.isNotBlank()) {
            val target = sentenceStartAtOrBefore(currentText, lastReadOffset.coerceIn(0, currentText.length))
            startCurrentText(target)
        }
        bumpControls()
    }

    private fun jumpSentence(direction: Int) {
        if (currentText.isBlank()) return
        val base = lastReadOffset.coerceIn(0, currentText.length)
        val target = if (direction > 0) nextSentenceStart(currentText, base) else previousSentenceStart(currentText, base)
        renderWindow(target)
        startCurrentText(target)
        bumpControls()
    }

    private fun showPasteSheet() {
        bumpControls()
        val text = clipboardText()
        val dialog = bottomSheet("Dán văn bản") { sheet, close ->
            sheet.addView(sheetButton("Dán thay thế & đọc từ đầu") {
                if (text.isBlank()) {
                    status.text = "Clipboard đang trống"
                } else {
                    controller.stop()
                    documentTitle.text = "Văn bản từ clipboard"
                    setDocumentText(text)
                    startCurrentText(0)
                }
                close()
            })
            sheet.addView(sheetButton("Dán nối tiếp ở cuối") {
                if (text.isBlank()) status.text = "Clipboard đang trống" else appendClipboardText(text)
                close()
            })
            sheet.addView(sheetButton("Chỉ dán thay thế, chưa đọc") {
                if (text.isNotBlank()) {
                    controller.stop()
                    documentTitle.text = "Văn bản từ clipboard"
                    setDocumentText(text)
                    status.text = "Đã dán"
                }
                close()
            })
        }
        dialog.show()
        styleBottomSheet(dialog)
    }

    private fun showSettingsSheet() {
        bumpControls()
        val dialog = bottomSheet("Cài đặt đọc & âm thanh") { sheet, close ->
            addSpinnerRow(
                sheet,
                "Giọng",
                listOf("Hoài My (Nữ)", "Nam Minh (Nam)"),
                if (voiceName.contains("NamMinh")) 1 else 0,
            ) { position ->
                voiceName = if (position == 1) "vi-VN-NamMinhNeural" else "vi-VN-HoaiMyNeural"
            }

            addSeekRow(sheet, "Độ trầm", 20, (-pitchHz / 10).coerceIn(0, 20)) { value, label ->
                pitchHz = -value * 10
                label.text = "Độ trầm ${pitchHz}Hz"
            }

            addSeekRow(sheet, "Tốc độ", 85, ((speed - 0.65f) * 100).toInt().coerceIn(0, 85)) { value, label ->
                speed = 0.65f + value / 100f
                speedLabel.text = "%.2fx".format(speed)
                label.text = "Tốc độ %.2fx".format(speed)
            }

            addSpinnerRow(
                sheet,
                "Âm nền",
                ambienceOptions.map { it.first },
                ambienceOptions.indexOfFirst { it.second == backgroundMode }.coerceAtLeast(0),
            ) { position ->
                backgroundMode = ambienceOptions[position].second
                controller.setBackgroundMode(backgroundMode)
                if (backgroundMode == BackgroundSoundEngine.Mode.CUSTOM && customSoundUri == null) {
                    close()
                    openCustomSoundPicker()
                }
            }

            addVolumeRow(sheet, "Giọng", (voiceVolume * 100).toInt()) { value ->
                voiceVolume = value / 100f
                controller.setVoiceVolume(voiceVolume)
            }
            addVolumeRow(sheet, "Ambience", (ambienceVolume * 100).toInt()) { value ->
                ambienceVolume = value / 100f
                controller.setAmbienceVolume(ambienceVolume)
            }
            addVolumeRow(sheet, "Nhạc", (musicVolume * 100).toInt()) { value ->
                musicVolume = value / 100f
                controller.setMusicVolume(musicVolume)
            }

            sheet.addView(sheetButton("Chọn file âm thanh nền riêng…") {
                close()
                openCustomSoundPicker()
            })
        }
        dialog.show()
        styleBottomSheet(dialog)
    }

    private fun appendClipboardText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        if (currentText.isBlank()) {
            setDocumentText(clean)
            startCurrentText(0)
            return
        }
        val appendStart = currentText.length + 2
        currentText = currentText + "\n\n" + clean
        if (controller.isActiveOrPaused()) {
            controller.appendText(currentText, appendStart)
        } else {
            lastReadOffset = appendStart
        }
        status.text = "Đã nối thêm ${clean.length} ký tự"
        bumpControls()
    }

    private fun startCurrentText(startOffset: Int = 0) {
        if (currentText.isBlank()) {
            status.text = "Hãy dán văn bản hoặc import EPUB"
            return
        }
        val safe = startOffset.coerceIn(0, currentText.length)
        lastReadOffset = safe
        renderWindow(safe)
        applyAudioSettings()
        controller.start(currentText, voiceName, speed, pitchHz, safe)
        playingUi = true
        pausedUi = false
        playPauseButton.text = "⏸"
    }

    private fun applyAudioSettings() {
        if (!::controller.isInitialized) return
        controller.setVoiceVolume(voiceVolume)
        controller.setAmbienceVolume(ambienceVolume)
        controller.setMusicVolume(musicVolume)
        controller.setBackgroundMode(backgroundMode)
        controller.setCustomBackgroundUri(customSoundUri)
    }

    private fun updateStatus(text: String) {
        status.text = text
        when {
            text.startsWith("Đã tạm dừng") -> {
                playingUi = true
                pausedUi = true
                playPauseButton.text = "▶"
            }
            text.startsWith("Đang đọc") || text.startsWith("Đang tạo") -> {
                playingUi = true
                pausedUi = false
                playPauseButton.text = "⏸"
            }
            text.startsWith("Đã dừng") || text.startsWith("Đã đọc xong") || text.startsWith("Lỗi") -> {
                playingUi = false
                pausedUi = false
                playPauseButton.text = "▶"
            }
        }
    }

    private fun updateProgress(current: Int, total: Int) {
        lastReadOffset = current.coerceIn(0, max(total, 0))
        if (total <= 0 || userSeeking) return
        val value = ((current.toDouble() / total) * 1000.0).toInt().coerceIn(0, 1000)
        progressSeek.progress = value
        progressLabel.text = "${value / 10}%"
    }

    private fun openEpubPicker() {
        bumpControls()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/epub+zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/zip", "application/octet-stream"))
        }
        startActivityForResult(intent, REQ_EPUB)
    }

    private fun openCustomSoundPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQ_AUDIO)
    }

    @Deprecated("Deprecated in Android SDK but kept for minSdk 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        when (requestCode) {
            REQ_EPUB -> importEpub(uri)
            REQ_AUDIO -> {
                customSoundUri = uri
                backgroundMode = BackgroundSoundEngine.Mode.CUSTOM
                controller.setCustomBackgroundUri(uri)
                controller.setBackgroundMode(backgroundMode)
                status.text = "Đã chọn âm thanh nền riêng"
            }
        }
    }

    private fun importEpub(uri: Uri) {
        controller.stop()
        showControls()
        status.text = "Đang đọc EPUB…"
        Thread {
            val result = runCatching { EpubParser.parse(this, uri) }
            runOnUiThread {
                result.onSuccess { book ->
                    documentTitle.text = book.title ?: "EPUB"
                    setDocumentText(book.text)
                    status.text = "Đã import EPUB · ${book.text.length} ký tự"
                }.onFailure { error ->
                    status.text = "Không import được EPUB: ${error.message ?: "lỗi không rõ"}"
                }
            }
        }.start()
    }

    private fun clipboardText(): String {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
    }

    private fun setDocumentText(text: String) {
        currentText = text.trim()
        lastReadOffset = 0
        progressSeek.progress = 0
        progressLabel.text = "0%"
        renderWindow(0)
    }

    /**
     * Performance: chỉ đưa một cửa sổ nhỏ vào TextView thay vì SpannableString của cả EPUB.
     * Full text vẫn giữ dạng String để TTS/seek dùng offset toàn cục.
     */
    private fun renderWindow(centerOffset: Int) {
        if (currentText.isBlank()) return
        val center = centerOffset.coerceIn(0, currentText.length)
        var start = (center - WINDOW_BEFORE).coerceAtLeast(0)
        var end = (start + WINDOW_CHARS).coerceAtMost(currentText.length)
        if (end - start < WINDOW_CHARS && end == currentText.length) {
            start = (end - WINDOW_CHARS).coerceAtLeast(0)
        }
        start = paragraphBoundaryBefore(currentText, start)
        end = paragraphBoundaryAfter(currentText, end)

        windowStart = start
        windowEnd = end
        spannable = SpannableString(currentText.substring(start, end))
        highlightSpan = null
        boldSpan = null
        readerText.text = spannable
        readerScroll.post {
            val local = (center - windowStart).coerceIn(0, max(spannable.length - 1, 0))
            val layout = readerText.layout
            if (layout != null && spannable.isNotEmpty()) {
                val line = layout.getLineForOffset(local)
                val y = max(0, layout.getLineTop(line) - readerScroll.height / 3)
                readerScroll.scrollTo(0, y)
            } else {
                readerScroll.scrollTo(0, 0)
            }
        }
    }

    private fun highlightRange(start: Int, end: Int) {
        if (currentText.isBlank() || start < 0 || end <= start) return
        if (start < windowStart || end > windowEnd || start - windowStart < WINDOW_EDGE || windowEnd - end < WINDOW_EDGE) {
            renderWindow(start)
        }

        highlightSpan?.let { spannable.removeSpan(it) }
        boldSpan?.let { spannable.removeSpan(it) }
        highlightSpan = null
        boldSpan = null

        val localStart = start - windowStart
        val localEnd = (end - windowStart).coerceAtMost(spannable.length)
        if (localStart !in 0 until spannable.length || localEnd <= localStart) return

        val bg = BackgroundColorSpan(Color.rgb(255, 222, 132))
        val bold = StyleSpan(Typeface.BOLD)
        spannable.setSpan(bg, localStart, localEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(bold, localStart, localEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        highlightSpan = bg
        boldSpan = bold
        readerText.text = spannable

        readerText.post {
            val layout = readerText.layout ?: return@post
            val line = layout.getLineForOffset(localStart)
            val targetY = max(0, layout.getLineTop(line) - readerScroll.height / 3)
            readerScroll.smoothScrollTo(0, targetY)
        }
    }

    private fun sentenceStartAtOrBefore(text: String, offset: Int): Int {
        if (text.isBlank()) return 0
        var i = offset.coerceIn(0, text.length)
        if (i == text.length && i > 0) i--
        while (i > 0) {
            val c = text[i - 1]
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') break
            i--
        }
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun nextSentenceStart(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i < text.length) {
            val c = text[i]
            i++
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') {
                while (i < text.length && text[i].isWhitespace()) i++
                return i.coerceAtMost(text.length)
            }
        }
        return text.length
    }

    private fun previousSentenceStart(text: String, offset: Int): Int {
        val currentStart = sentenceStartAtOrBefore(text, offset)
        if (currentStart <= 0) return 0
        var probe = currentStart - 1
        while (probe > 0 && text[probe].isWhitespace()) probe--
        return sentenceStartAtOrBefore(text, probe)
    }

    private fun paragraphBoundaryBefore(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        val found = text.lastIndexOf('\n', offset.coerceAtMost(text.length - 1))
        return if (found >= 0) found + 1 else offset
    }

    private fun paragraphBoundaryAfter(text: String, offset: Int): Int {
        if (offset >= text.length) return text.length
        val found = text.indexOf('\n', offset)
        return if (found >= 0) found + 1 else text.length
    }

    private fun bottomSheet(title: String, build: (LinearLayout, () -> Unit) -> Unit): Dialog {
        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 249, 246))
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        sheet.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(4), dp(6), dp(10))
        })
        val close = { dialog.dismiss(); Unit }
        build(sheet, close)
        val scroll = ScrollView(this).apply { addView(sheet) }
        dialog.setContentView(scroll)
        return dialog
    }

    private fun styleBottomSheet(dialog: Dialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            val params = attributes
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            attributes = params
        }
    }

    private fun addSpinnerRow(parent: LinearLayout, title: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 14f
        }, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, items.lastIndex))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    private fun addSeekRow(parent: LinearLayout, title: String, maxValue: Int, initial: Int, onChange: (Int, TextView) -> Unit) {
        val label = TextView(this).apply {
            text = title
            textSize = 14f
            setPadding(dp(6), dp(5), dp(6), 0)
        }
        parent.addView(label)
        parent.addView(SeekBar(this).apply {
            max = maxValue
            progress = initial.coerceIn(0, maxValue)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress, label)
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        onChange(initial.coerceIn(0, maxValue), label)
    }

    private fun addVolumeRow(parent: LinearLayout, title: String, initial: Int, onChange: (Int) -> Unit) {
        val label = TextView(this).apply {
            text = "$title $initial%"
            textSize = 13f
            setPadding(dp(6), dp(4), dp(6), 0)
        }
        parent.addView(label)
        parent.addView(SeekBar(this).apply {
            max = 100
            progress = initial.coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$title $progress%"
                    onChange(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
    }

    private fun smallButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 11f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(9), 0, dp(9), 0)
        setOnClickListener { action(); bumpControls() }
    }

    private fun playerButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = if (title == "▶") 18f else 16f
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f)
    }

    private fun sheetButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 14f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }

    companion object {
        private const val REQ_EPUB = 3001
        private const val REQ_AUDIO = 3002
        private const val AUTO_HIDE_MS = 5500L
        private const val WINDOW_CHARS = 12_000
        private const val WINDOW_BEFORE = 3_500
        private const val WINDOW_EDGE = 700
    }
}
