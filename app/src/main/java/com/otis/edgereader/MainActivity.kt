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
    private lateinit var voiceSpinner: Spinner
    private lateinit var toneSpinner: Spinner

    private var speed = 0.95f
    private var currentText = ""
    private var spannable = SpannableString("")
    private var highlightSpan: BackgroundColorSpan? = null
    private var boldSpan: StyleSpan? = null

    private val pitchValues = intArrayOf(0, -20, -40, -60, -80, -100, -120)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        controller = PlaybackController(
            onStatus = { status.text = it },
            onWord = { start, end -> highlightRange(start, end) },
        )
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(250, 248, 243))
        }

        root.addView(TextView(this).apply {
            text = "Edge Story Reader"
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(32, 32, 32))
            setPadding(0, 0, 0, dp(4))
        })

        root.addView(TextView(this).apply {
            text = "Copy truyện từ ChatGPT → bấm Dán & Đọc → app tự theo chữ."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(8))
        })

        readerText = TextView(this).apply {
            text = "Copy một đoạn truyện, rồi bấm Dán & Đọc.\n\nTừ đang được đọc sẽ được tô sáng và màn hình tự cuộn theo."
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
                ScrollView.LayoutParams(
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
            setPadding(0, dp(8), 0, 0)
        }
        voiceRow.addView(TextView(this).apply {
            text = "Giọng:"
            textSize = 15f
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

        val toneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toneRow.addView(TextView(this).apply {
            text = "Độ trầm:"
            textSize = 15f
        })
        toneSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Tự nhiên (0Hz)",
                    "Ấm nhẹ (-20Hz)",
                    "Ấm (-40Hz)",
                    "Trầm (-60Hz)",
                    "Rất trầm (-80Hz)",
                    "Sâu (-100Hz)",
                    "Rất sâu (-120Hz)",
                ),
            )
            setSelection(3)
        }
        toneRow.addView(
            toneSpinner,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(toneRow)

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
            LinearLayout.LayoutParams(dp(108), ViewGroup.LayoutParams.WRAP_CONTENT)
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
            setPadding(0, dp(4), 0, dp(4))
        }
        fun button(title: String, action: () -> Unit): Button = Button(this).apply {
            text = title
            setOnClickListener { action() }
        }
        buttons.addView(
            button("Đọc lại") { startCurrentText() },
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
            text = "Sẵn sàng · mặc định -60Hz"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(status)

        setContentView(root)
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
        val pitch = pitchValues[toneSpinner.selectedItemPosition.coerceIn(pitchValues.indices)]
        controller.start(currentText, voice, speed, pitch)
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
