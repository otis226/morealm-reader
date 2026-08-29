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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
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
    private lateinit var status: TextView
    private lateinit var progressSeek: SeekBar
    private lateinit var progressLabel: TextView
    private lateinit var documentTitle: TextView

    private var speed = 0.95f
    private var pitchHz = -70
    private var voiceName = "vi-VN-NamMinhNeural"
    private var voiceVolume = 1.0f
    private var ambienceVolume = 0.20f
    private var musicVolume = 0.10f
    private var backgroundMode = BackgroundSoundEngine.Mode.OFF
    private var customSoundUri: Uri? = null

    private var currentText = ""
    private var spannable = SpannableString("")
    private var highlightSpan: BackgroundColorSpan? = null
    private var boldSpan: StyleSpan? = null
    private var userSeeking = false

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
        buildUi()
        controller = PlaybackController(
            context = this,
            onStatus = { status.text = it },
            onWord = { start, end -> highlightRange(start, end) },
            onProgress = { current, total -> updateProgress(current, total) },
        )
        applyAudioSettings()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.rgb(250, 248, 243))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "Edge Story Reader"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(32, 32, 32))
        })
        documentTitle = TextView(this).apply {
            text = "Dán văn bản hoặc mở EPUB"
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        titleBox.addView(documentTitle)
        top.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(smallButton("EPUB") { openEpubPicker() })
        top.addView(smallButton("⚙") { showSettingsSheet() })
        root.addView(top)

        readerText = TextView(this).apply {
            text = "Copy truyện từ ChatGPT rồi bấm Dán ở thanh dưới.\n\nBạn cũng có thể import EPUB. Từ đang đọc sẽ được tô sáng và màn hình tự cuộn theo."
            textSize = 19f
            setTextColor(Color.rgb(35, 35, 35))
            setLineSpacing(dp(5).toFloat(), 1.08f)
            setPadding(dp(14), dp(14), dp(14), dp(24))
            setBackgroundColor(Color.WHITE)
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
                topMargin = dp(8)
            }
        )

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        progressLabel = TextView(this).apply {
            text = "0%"
            textSize = 12f
            gravity = Gravity.CENTER
        }
        progressRow.addView(progressLabel, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        progressSeek = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) progressLabel.text = "${progress / 10}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    if (currentText.isBlank()) return
                    val p = seekBar?.progress ?: 0
                    val offset = ((p / 1000.0) * currentText.length).toInt().coerceIn(0, currentText.length)
                    startCurrentText(offset)
                }
            })
        }
        progressRow.addView(progressSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(progressRow)

        status = TextView(this).apply {
            text = "Sẵn sàng"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(3))
        }
        root.addView(status)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bottom.addView(actionButton("DÁN") { showPasteSheet() })
        bottom.addView(actionButton("▶ ĐỌC") {
            if (!controller.resume()) startCurrentText(currentOffsetFromSeek())
        })
        bottom.addView(actionButton("⏸") { controller.pause() })
        bottom.addView(actionButton("■") { controller.stop() })
        bottom.addView(actionButton("⚙") { showSettingsSheet() })
        root.addView(bottom)

        setContentView(root)
    }

    private fun showPasteSheet() {
        val text = clipboardText()
        val dialog = bottomSheet("Dán văn bản") { sheet, close ->
            sheet.addView(sheetButton("Dán thay thế & đọc từ đầu") {
                if (text.isBlank()) {
                    status.text = "Clipboard đang trống"
                } else {
                    controller.stop()
                    documentTitle.text = "Văn bản từ clipboard"
                    setReaderText(text, preserveScroll = false)
                    startCurrentText(0)
                }
                close()
            })
            sheet.addView(sheetButton("Dán nối tiếp ở cuối") {
                if (text.isBlank()) {
                    status.text = "Clipboard đang trống"
                } else {
                    appendClipboardText(text)
                }
                close()
            })
            sheet.addView(sheetButton("Chỉ dán thay thế, chưa đọc") {
                if (text.isNotBlank()) {
                    controller.stop()
                    documentTitle.text = "Văn bản từ clipboard"
                    setReaderText(text, preserveScroll = false)
                    status.text = "Đã dán · bấm Đọc khi sẵn sàng"
                }
                close()
            })
        }
        dialog.show()
        styleBottomSheet(dialog)
    }

    private fun showSettingsSheet() {
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

            addSeekRow(sheet, "Tốc độ", 100, ((speed - 0.75f) * 100).toInt().coerceIn(0, 100)) { value, label ->
                speed = 0.75f + value / 100f
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
            sheet.addView(TextView(this).apply {
                text = "Đổi giọng / tốc độ / độ trầm sẽ áp dụng khi bắt đầu hoặc tua sang vị trí mới. Âm nền và volume đổi ngay."
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(dp(6), dp(8), dp(6), dp(4))
            })
        }
        dialog.show()
        styleBottomSheet(dialog)
    }

    private fun appendClipboardText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        if (currentText.isBlank()) {
            setReaderText(clean, preserveScroll = false)
            startCurrentText(0)
            return
        }
        val appendStart = currentText.length + 2
        val combined = currentText + "\n\n" + clean
        setReaderText(combined, preserveScroll = true)
        if (controller.isActiveOrPaused()) {
            controller.appendText(currentText, appendStart)
        } else {
            startCurrentText(appendStart)
        }
        status.text = "Đã nối thêm ${clean.length} ký tự"
    }

    private fun startCurrentText(startOffset: Int = 0) {
        if (currentText.isBlank()) {
            status.text = "Hãy dán văn bản hoặc import EPUB"
            return
        }
        applyAudioSettings()
        controller.start(currentText, voiceName, speed, pitchHz, startOffset)
    }

    private fun currentOffsetFromSeek(): Int {
        if (currentText.isBlank()) return 0
        return ((progressSeek.progress / 1000.0) * currentText.length).toInt().coerceIn(0, currentText.length)
    }

    private fun applyAudioSettings() {
        if (!::controller.isInitialized) return
        controller.setVoiceVolume(voiceVolume)
        controller.setAmbienceVolume(ambienceVolume)
        controller.setMusicVolume(musicVolume)
        controller.setBackgroundMode(backgroundMode)
        controller.setCustomBackgroundUri(customSoundUri)
    }

    private fun updateProgress(current: Int, total: Int) {
        if (total <= 0 || userSeeking) return
        val value = ((current.toDouble() / total) * 1000.0).toInt().coerceIn(0, 1000)
        progressSeek.progress = value
        progressLabel.text = "${value / 10}%"
    }

    private fun openEpubPicker() {
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
        status.text = "Đang đọc EPUB…"
        Thread {
            val result = runCatching { EpubParser.parse(this, uri) }
            runOnUiThread {
                result.onSuccess { book ->
                    documentTitle.text = book.title ?: "EPUB"
                    setReaderText(book.text, preserveScroll = false)
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

    private fun setReaderText(text: String, preserveScroll: Boolean) {
        val oldY = readerScroll.scrollY
        currentText = text.trim()
        spannable = SpannableString(currentText)
        highlightSpan = null
        boldSpan = null
        readerText.text = spannable
        progressSeek.progress = 0
        progressLabel.text = "0%"
        readerScroll.post {
            if (preserveScroll) readerScroll.scrollTo(0, oldY) else readerScroll.scrollTo(0, 0)
        }
    }

    private fun highlightRange(start: Int, end: Int) {
        if (currentText.isBlank()) return
        highlightSpan?.let { spannable.removeSpan(it) }
        boldSpan?.let { spannable.removeSpan(it) }
        highlightSpan = null
        boldSpan = null

        if (start < 0 || end <= start || start >= spannable.length) {
            readerText.invalidate()
            return
        }

        val safeEnd = end.coerceAtMost(spannable.length)
        val bg = BackgroundColorSpan(Color.rgb(255, 222, 132))
        val bold = StyleSpan(Typeface.BOLD)
        spannable.setSpan(bg, start, safeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(bold, start, safeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        highlightSpan = bg
        boldSpan = bold
        readerText.text = spannable

        readerText.post {
            val layout = readerText.layout ?: return@post
            val safeStart = start.coerceAtMost(max(0, spannable.length - 1))
            val line = layout.getLineForOffset(safeStart)
            val targetY = max(0, layout.getLineTop(line) - readerScroll.height / 3)
            readerScroll.smoothScrollTo(0, targetY)
        }
    }

    private fun bottomSheet(
        title: String,
        content: (LinearLayout, () -> Unit) -> Unit,
    ): Dialog {
        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(252, 251, 248))
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        sheet.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        content(sheet) { dialog.dismiss() }
        dialog.setContentView(sheet)
        return dialog
    }

    private fun styleBottomSheet(dialog: Dialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun addSpinnerRow(
        parent: LinearLayout,
        title: String,
        items: List<String>,
        selected: Int,
        onSelected: (Int) -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = "$title:"
            textSize = 14f
        }, LinearLayout.LayoutParams(dp(95), ViewGroup.LayoutParams.WRAP_CONTENT))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(items.indices))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    onSelected(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    private fun addSeekRow(
        parent: LinearLayout,
        title: String,
        maxValue: Int,
        initial: Int,
        onChange: (Int, TextView) -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = title
            textSize = 13f
        }
        row.addView(label, LinearLayout.LayoutParams(dp(125), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(SeekBar(this).apply {
            max = maxValue
            progress = initial
            onChange(initial, label)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    onChange(progress, label)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    private fun addVolumeRow(parent: LinearLayout, title: String, initial: Int, onChange: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = "$title $initial%"
            textSize = 13f
        }
        row.addView(label, LinearLayout.LayoutParams(dp(105), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(SeekBar(this).apply {
            max = 100
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$title $progress%"
                    onChange(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    private fun smallButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun sheetButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }

    companion object {
        private const val REQ_EPUB = 2201
        private const val REQ_AUDIO = 2202
    }
}
