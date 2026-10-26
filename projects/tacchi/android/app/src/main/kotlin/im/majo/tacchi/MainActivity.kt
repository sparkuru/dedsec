package im.majo.tacchi

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import im.majo.tacchi.diagnostics.Diagnostics
import im.majo.tacchi.diagnostics.DiagnosticsSession
import im.majo.tacchi.diagnostics.FrameworkState
import im.majo.tacchi.diagnostics.HostState
import im.majo.tacchi.diagnostics.ProbeState
import im.majo.tacchi.ipc.NativeDiagnostics
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val session = DiagnosticsSession()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private var read: Future<*>? = null
    private var timeout: Runnable? = null
    private lateinit var readAt: TextView
    private lateinit var progress: TextView
    private lateinit var failure: TextView
    private lateinit var frameworkStatus: TextView
    private lateinit var frameworkDetail: TextView
    private lateinit var hostStatus: TextView
    private lateinit var probeStatus: TextView
    private lateinit var probeDetail: TextView
    private lateinit var refreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildPage()
        render()
    }

    override fun onResume() {
        super.onResume()
        session.resume()?.let(::readSnapshot)
    }

    override fun onPause() {
        session.pause()
        cancelRead()
        render()
        super.onPause()
    }

    override fun onDestroy() {
        session.destroy()
        cancelRead()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun readSnapshot(token: Long) {
        cancelRead()
        render()
        val deadline = Runnable {
            if (session.fail(token)) {
                cancelRead()
                render()
            }
        }
        timeout = deadline
        handler.postDelayed(deadline, READ_TIMEOUT_MILLIS)
        val activity = WeakReference(this)
        val app = application as TacchiApplication
        val mainHandler = handler
        try {
            read = executor.submit {
                val result = try {
                    Diagnostics.decode(app.frameworkStatus.snapshot() + NativeDiagnostics.snapshot(app))
                } catch (_: RuntimeException) { null }
                val sampledAt = System.currentTimeMillis()
                mainHandler.post { activity.get()?.acceptSnapshot(token, result, sampledAt) }
            }
        } catch (_: RejectedExecutionException) {
            acceptSnapshot(token, null, System.currentTimeMillis())
        }
    }

    private fun acceptSnapshot(token: Long, diagnostics: Diagnostics?, sampledAt: Long) {
        val accepted = if (diagnostics == null) session.fail(token)
        else session.complete(token, diagnostics, sampledAt)
        if (!accepted) return
        timeout?.let(handler::removeCallbacks)
        timeout = null
        read = null
        render()
    }

    private fun cancelRead() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        read?.cancel(true)
        read = null
        // Binder may ignore interruption. Bound queued work and discard its late result.
        executor.purge()
    }

    private fun buildPage() {
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.page_background)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        val scroll = BoundedScrollView(this).apply {
            id = R.id.diagnostics_scroll
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        content.addView(text(R.string.app_name, 20f, heading = true))
        content.addView(text(R.string.diagnostics_title, 28f, heading = true), block(top = 24))
        content.addView(text(R.string.diagnostics_subtitle, 16f, secondary = true), block(top = 8))
        readAt = text(R.string.snapshot_waiting, 14f, secondary = true).apply { id = R.id.snapshot_time }
        content.addView(readAt, block(top = 8))
        progress = text(R.string.diagnostics_loading, 16f).apply {
            id = R.id.loading_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(progress, block(top = 16))

        val framework = card(R.string.framework_title)
        frameworkStatus = text(R.string.state_unknown, 22f).apply { id = R.id.framework_status }
        frameworkDetail = text(R.string.framework_requirement, 16f, secondary = true)
        framework.addView(frameworkStatus, block(top = 12))
        framework.addView(frameworkDetail, block(top = 8))
        content.addView(framework, block(top = 24))

        val host = card(R.string.host_title)
        hostStatus = text(R.string.host_unknown, 22f).apply { id = R.id.host_status }
        host.addView(hostStatus, block(top = 12))
        host.addView(text(R.string.host_explanation, 16f, secondary = true), block(top = 8))
        content.addView(host, block(top = 12))

        val probe = card(R.string.probe_title)
        probeStatus = text(R.string.probe_unknown, 22f).apply { id = R.id.probe_status }
        probeDetail = text(R.string.probe_explanation, 16f, secondary = true)
        probe.addView(probeStatus, block(top = 12))
        probe.addView(probeDetail, block(top = 8))
        content.addView(probe, block(top = 12))

        failure = text(R.string.diagnostics_failure, 16f).apply {
            id = R.id.read_error
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(failure, block(top = 16))
        refreshButton = Button(this).apply {
            id = R.id.refresh_status
            setText(R.string.refresh_status)
            isAllCaps = false
            textSize = 16f
            minHeight = dp(48)
            minimumHeight = dp(48)
            backgroundTintList = getColorStateList(R.color.action_background)
            setTextColor(getColorStateList(R.color.action_foreground))
            setOnClickListener { session.refresh()?.let(::readSnapshot) }
        }
        content.addView(refreshButton, block(top = 16))
        content.addView(text(R.string.privacy_explanation, 16f, secondary = true), block(top = 24))
        installInsets(root)
        setContentView(root)
        root.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun installInsets(root: View) {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                val cutout = insets.displayCutout
                view.setPadding(maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                    maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                    maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                    maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0))
            }
            insets
        }
    }

    private fun render() {
        val state = session.state
        val diagnostic = state.diagnostics
        refreshButton.isEnabled = !state.loading
        progress.visibility = if (state.loading) View.VISIBLE else View.GONE
        failure.visibility = if (state.readFailed) View.VISIBLE else View.GONE
        readAt.text = state.readAtMillis?.let {
            getString(R.string.snapshot_read_at, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it)))
        } ?: getString(R.string.snapshot_waiting)
        frameworkStatus.setText(when (diagnostic.framework) {
            FrameworkState.UNKNOWN -> R.string.state_unknown
            FrameworkState.UNAVAILABLE -> R.string.framework_unavailable
            FrameworkState.CONNECTED -> R.string.framework_connected
            FrameworkState.DISCONNECTED -> R.string.framework_disconnected
            FrameworkState.UNSUPPORTED -> R.string.framework_unsupported
            FrameworkState.AMBIGUOUS -> R.string.framework_ambiguous
            FrameworkState.FAILED -> R.string.framework_failed
        })
        frameworkDetail.text = diagnostic.api?.let { getString(R.string.framework_api, it) }
            ?: getString(R.string.framework_requirement)
        hostStatus.setText(when (diagnostic.host) {
            HostState.UNKNOWN -> R.string.host_unknown
            HostState.CONFIRMED -> R.string.host_confirmed
            HostState.STALE -> R.string.host_stale
        })
        probeStatus.setText(when (diagnostic.probe) {
            ProbeState.UNKNOWN -> R.string.probe_unknown
            ProbeState.RUNNING -> R.string.probe_running
            ProbeState.PASSED -> R.string.probe_passed
            ProbeState.FAILED -> R.string.probe_failed
            ProbeState.CANCELED -> R.string.probe_canceled
            ProbeState.EXPIRED -> R.string.probe_expired
        })
        probeDetail.setText(when (diagnostic.errorCode) {
            "hardware_unavailable" -> R.string.probe_hardware_unavailable
            "auth_failed" -> R.string.probe_auth_failed
            "crypto_failed" -> R.string.probe_crypto_failed
            "canceled" -> R.string.probe_cancel_detail
            "expired" -> R.string.probe_expiry_detail
            else -> R.string.probe_explanation
        })
    }

    private fun card(title: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        setBackgroundResource(R.drawable.status_card)
        addView(text(title, 16f, heading = true))
    }

    private fun text(label: Int, size: Float, secondary: Boolean = false, heading: Boolean = false) = TextView(this).apply {
        setText(label)
        textSize = size
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        isAccessibilityHeading = heading
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun block(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private class BoundedScrollView(context: Context) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maximum = (640 * resources.displayMetrics.density).toInt()
            val width = minOf(MeasureSpec.getSize(widthMeasureSpec), maximum)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }

    companion object { private const val READ_TIMEOUT_MILLIS = 5_000L }
}
