package dev.tacchi.probe

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Development fixture: these inert callbacks only gain behavior when the module actually hooks them. */
class ProbeActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val inset = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
        }
        content.addView(TextView(this).apply {
            setText(R.string.app_name)
            textSize = 24f
        })
        content.addView(TextView(this).apply {
            setText(R.string.probe_instructions)
            textSize = 16f
        })
        status = TextView(this).apply {
            setText(R.string.module_unconfirmed)
            textSize = 18f
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        content.addView(Button(this).apply {
            setText(R.string.probe_run)
            setOnClickListener { onProbeRequested() }
        })
        content.addView(Button(this).apply {
            setText(R.string.probe_cancel)
            setOnClickListener { onProbeCanceled() }
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        onHostReady()
    }

    override fun onPause() {
        onHostPaused()
        super.onPause()
    }

    fun onHostReady() = Unit
    fun onProbeRequested() { status.setText(R.string.probe_waiting) }
    fun onProbeCanceled() = Unit
    fun onHostPaused() = Unit
    fun showModuleStatus(message: String) { status.text = message }
}
