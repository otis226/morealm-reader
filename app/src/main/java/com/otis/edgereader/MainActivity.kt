package com.otis.edgereader

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
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
    private lateinit var speedLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var soundSpinner: Spinner

    private var speed = 0.95f
    private var pitchHz = -60
    private var voiceVolume = 1.0f
    private var rainVolume = 0.24f
    private var padVolume = 0.14f
    private var currentText = ""
    private var spannable = SpannableString("")
    private var highlightSpan: BackgroundColorSpan? = null
    private var boldSpan: StyleSpan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        controller = PlaybackController(
            onStatus = { status.text = it },
            onWord = { start, end -> highlightRange(start, end) },
        )
        applyAudioSettings()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(250, 248, 243))
        }

        root.addView(TextView(this).apply {
            text = "Edge Story Reader"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(32, 32, 32))
            setPadding(0, 0, 0, dp(3))
        })

        root.addView(TextView(this).apply {
            text = "Copy truyện từ ChatGPT → Dán & Đọc → app theo chữ + âm nền."
            textSize = 12.5f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(7))
        })

        readerText = TextView(this).apply {
            text = "Copy một đoạn truyện, rồi bấm Dán & Đọc.\n\nTừ đang đọc sẽ được tô sáng và màn hình tự cuộn theo."
            textSize = 19f
            setTextColor(Color.rgb(35, 35, 35))
            setLineSpacing(dp(5).toFloat(), 1.08f)
            setPadding(dp(14), dp(14), dp(14), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        readerScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                readerText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }
        root.addView(
            readerScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        )

        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        voiceRow.addView(TextView(this).apply {
            text = "Giọng:"
            textSize = 14f
        })
        voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Hoài My (Nữ)", "Nam Minh (Nam)"),
            )
        }
        voiceRow.addView(
            voiceSpinner,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(voiceRow)

        val pitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pitchLabel = TextView(this).apply {
            text = "Độ trầm -60Hz"
            textSize = 14f
        }
        pitchRow.addView(
            pitchLabel,
            LinearLayout.LayoutParams(dp(125), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        pitchRow.addView(SeekBar(this).apply {
            max = 20
            progress = 6
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    pitchHz = -progress * 10
                    pitchLabel.text = "Độ trầm ${pitchHz}Hz"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(pitchRow)

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        speedLabel = TextView(this).apply {
            text = "Tốc độ 0.95x"
            textSize = 14f
        }
        speedRow.addView(
            speedLabel,
            LinearLayout.LayoutParams(dp(125), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        speedRow.addView(SeekBar(this).apply {
            max = 100
            progress = 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speed = 0.75f + progress / 100f
                    speedLabel.text = "Tốc độ %.2fx".format(speed)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(speedRow)

        val soundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        soundRow.addView(TextView(this).apply {
            text = "Âm nền:"
            textSize = 14f
        })
        soundSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Tắt", "Mưa", "Pad ấm", "Mưa + Pad ấm"),
            )
            setSelection(3)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (::controller.isInitialized) controller.setBackgroundMode(selectedBackgroundMode())
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        soundRow.addView(
            soundSpinner,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(soundRow)

        addVolumeRow(root, "Giọng", 100) { progress ->
            voiceVolume = progress / 100f
            if (::controller.isInitialized) controller.setVoiceVolume(voiceVolume)
        }
        addVolumeRow(root, "Mưa", 24) { progress ->
            rainVolume = progress / 100f
            if (::controller.isInitialized) controller.setRainVolume(rainVolume)
        }
        addVolumeRow(root, "Nhạc", 14) { progress ->
            padVolume = progress / 100f
            if (::controller.isInitialized) controller.setPadVolume(padVolume)
        }

        val pasteButton = Button(this).apply {
            text = "DÁN & ĐỌC"
            textSize = 16f
            setOnClickListener { pasteAndRead() }
        }
        root.addView(
            pasteButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        fun button(title: String, action: () -> Unit): Button = Button(this).apply {
            text = title
            setOnClickListener { action() }
        }
        buttons.addView(
            button("Tiếp tục") { controller.resume() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttons.addView(
            button("Tạm dừng") { controller.pause() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttons.addView(
            button("Dừng") { controller.stop() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(buttons)

        status = TextView(this).apply {
            text = "Sẵn sàng · -60Hz · Mưa + Pad"
            textSize = 12.5f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(status)

        setContentView(root)
    }

    private fun addVolumeRow(
        parent: LinearLayout,
        title: String,
        initial: Int,
        onChange: (Int) -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = "$title $initial%"
            textSize = 13f
        }
        row.addView(label, LinearLayout.LayoutParams(dp(95), ViewGroup.LayoutParams.WRAP_CONTENT))
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

    private fun pasteAndRead() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        if (text.isBlank()) {
            status.text = "Clipboard đang trống"
            return
        }
        setReaderText(text)
        startCurrentText()
    }

    private fun startCurrentText() {
        if (currentText.isBlank()) {
            status.text = "Hãy copy văn bản rồi bấm Dán & Đọc"
            return
        }
        val voice = if (voiceSpinner.selectedItemPosition == 0) {
            "vi-VN-HoaiMyNeural"
        } else {
            "vi-VN-NamMinhNeural"
        }
        applyAudioSettings()
        controller.start(currentText, voice, speed, pitchHz)
    }

    private fun applyAudioSettings() {
        if (!::controller.isInitialized) return
        controller.setVoiceVolume(voiceVolume)
        controller.setRainVolume(rainVolume)
        controller.setPadVolume(padVolume)
        controller.setBackgroundMode(selectedBackgroundMode())
    }

    private fun selectedBackgroundMode(): BackgroundSoundEngine.Mode = when (soundSpinner.selectedItemPosition) {
        1 -> BackgroundSoundEngine.Mode.RAIN
        2 -> BackgroundSoundEngine.Mode.PAD
        3 -> BackgroundSoundEngine.Mode.RAIN_PAD
        else -> BackgroundSoundEngine.Mode.OFF
    }

    private fun setReaderText(text: String) {
        currentText = text.trim()
        spannable = SpannableString(currentText)
        highlightSpan = null
        boldSpan = null
        readerText.text = spannable
        readerScroll.post { readerScroll.scrollTo(0, 0) }
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
            val line = layout.getLineForOffset(start.coerceAtMost(spannable.length - 1))
            val targetY = max(0, layout.getLineTop(line) - readerScroll.height / 3)
            readerScroll.smoothScrollTo(0, targetY)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }
}
