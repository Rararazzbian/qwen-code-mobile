package com.qwen.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Proof-of-concept control screen; the native projects and chats UI replaces it in phase 2. */
class MainActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var startStop: Button
    private lateinit var openShell: Button
    private val listener: () -> Unit = { runOnUiThread(::render) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val baseUrl = field(column, "API base URL (OpenAI-compatible)", settings.baseUrl, InputType.TYPE_TEXT_VARIATION_URI)
        val apiKey = field(column, "API key", settings.apiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model = field(column, "Model", settings.model, InputType.TYPE_TEXT_VARIATION_NORMAL)

        status = TextView(this).apply { textSize = 16f; setPadding(0, pad / 2, 0, pad / 2) }
        column.addView(status)

        startStop = button(column, "Start") {
            if (RuntimeState.phase in ACTIVE) {
                AgentService.stop(this)
            } else {
                settings.baseUrl = baseUrl.text.toString()
                settings.apiKey = apiKey.text.toString()
                settings.model = model.text.toString()
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                AgentService.start(this)
            }
        }
        openShell = button(column, "Open Web Shell") { startActivity(Intent(this, WebShellActivity::class.java)) }
        button(column, "Allow running in background") { requestBatteryExemption() }
        button(column, "Copy log") {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Qwen Code log", RuntimeState.logText()))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }

        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(log) }
        column.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
    }

    override fun onStart() {
        super.onStart()
        RuntimeState.addListener(listener)
        render()
    }

    override fun onStop() {
        RuntimeState.removeListener(listener)
        super.onStop()
    }

    private fun render() {
        val phase = RuntimeState.phase
        status.text = "Status: ${phase.name.lowercase()} ${RuntimeState.detail}".trimEnd()
        startStop.text = if (phase in ACTIVE) "Stop" else "Start"
        openShell.isEnabled = phase == RuntimeState.Phase.RUNNING
        log.text = RuntimeState.logText()
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already allowed to run in the background", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
        )
    }

    private fun field(parent: LinearLayout, hint: String, value: String, variation: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or variation
        isSingleLine = true
        parent.addView(this)
    }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        parent.addView(this)
    }

    private companion object {
        val ACTIVE = setOf(
            RuntimeState.Phase.INSTALLING, RuntimeState.Phase.STARTING,
            RuntimeState.Phase.RUNNING, RuntimeState.Phase.STOPPING,
        )
    }
}
