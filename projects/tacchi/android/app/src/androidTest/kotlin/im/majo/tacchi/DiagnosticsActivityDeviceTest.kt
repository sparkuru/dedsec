package im.majo.tacchi

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Compile locally; execute on Android for actual layout, lifecycle and accessibility evidence. */
class DiagnosticsActivityDeviceTest {
    @Test fun nativePageScrollsToAccessibleRefreshAndClearsOnPause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val scroll = activity.findViewById<ScrollView>(R.id.diagnostics_scroll)
                assertNotNull(scroll)
                assertNotNull(activity.findViewById<TextView>(R.id.framework_status))
                assertNotNull(activity.findViewById<TextView>(R.id.host_status))
                assertNotNull(activity.findViewById<TextView>(R.id.probe_status))
                scroll.fullScroll(View.FOCUS_DOWN)
                val refresh = activity.findViewById<Button>(R.id.refresh_status)
                refresh.requestRectangleOnScreen(Rect(0, 0, refresh.width, refresh.height), true)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val refresh = activity.findViewById<Button>(R.id.refresh_status)
                val density = activity.resources.displayMetrics.density
                assertTrue(refresh.height >= 48 * density)
                assertTrue(refresh.width >= 48 * density)
                val bounds = Rect()
                assertTrue(refresh.getGlobalVisibleRect(bounds))
                assertEquals(activity.getString(R.string.refresh_status), refresh.text.toString())
                instrumentation.callActivityOnPause(activity)
                assertEquals(activity.getString(R.string.state_unknown),
                    activity.findViewById<TextView>(R.id.framework_status).text.toString())
                assertEquals(activity.getString(R.string.host_unknown),
                    activity.findViewById<TextView>(R.id.host_status).text.toString())
                assertEquals(activity.getString(R.string.probe_unknown),
                    activity.findViewById<TextView>(R.id.probe_status).text.toString())
                instrumentation.callActivityOnResume(activity)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loading_status).visibility)
                assertFalse(refresh.isEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
