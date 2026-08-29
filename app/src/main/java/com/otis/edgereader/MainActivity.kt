package com.otis.edgereader

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var controller: PlaybackController
    private lateinit var textInput: EditText
    private lateinit var status: TextView
    private lateinit var speedLabel: TextView
    private lateinit var voiceSpinner: Spinner
    private var speed = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = PlaybackController { status.text = it }
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(250, 248, 243))
        }

        root.addView(TextView(this).apply {
            text = "Đọc văn bản bằng Edge"
            textSize = 24f
            setTextColor(Color.rgb(30, 30, 30))
            setPadding(0, 0, 0, dp(10))
        })

        root.addView(TextView(this).apply {
            text = "Dán truyện vào ô dưới, chọn giọng rồi bấm Đọc."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(12))
        })

        textInput = EditText(this).apply {
            hint = "Dán văn bản vào đây…"
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
            textSize = 17f
        }
        root.addView(textInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        voiceRow.addView(TextView(this).apply {
            text = "Giọng:"
            textSize = 16f
        })
        voiceSpinner = Spinner(this)
        voiceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Hoài My (Nữ)", "Nam Minh (Nam)"),
        )
        voiceRow.addView(voiceSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(voiceRow)

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        speedLabel = TextView(this).apply {
            text = "Tốc độ 1.00x"
            textSize = 15f
        }
        speedRow.addView(speedLabel, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        speedRow.addView(SeekBar(this).apply {
            max = 100
            progress = 25
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

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        fun button(title: String, action: () -> Unit): Button = Button(this).apply {
            text = title
            setOnClickListener { action() }
        }
        buttons.addView(button("Đọc") {
            if (!controller.resume()) {
                val voice = if (voiceSpinner.selectedItemPosition == 0) "vi-VN-HoaiMyNeural" else "vi-VN-NamMinhNeural"
                controller.start(textInput.text.toString(), voice, speed)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(button("Tạm dừng") { controller.pause() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(button("Dừng") { controller.stop() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(button("Xóa") { textInput.setText(""); controller.stop() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        status = TextView(this).apply {
            text = "Sẵn sàng"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(status)

        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }
}
