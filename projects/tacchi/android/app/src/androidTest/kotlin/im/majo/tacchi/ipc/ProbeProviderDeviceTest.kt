package im.majo.tacchi.ipc

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Requires Android execution; JVM identity tests do not exercise Binder or package visibility. */
class ProbeProviderDeviceTest {
    @Test fun managerUidCannotImpersonateFixtureEvenWithClaimedPackage() {
        val reply = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            Uri.parse("content://im.majo.tacchi.probe"), "begin", null,
            Bundle().apply { putString("package", "dev.tacchi.probe") },
        )
        assertNotNull(reply)
        assertFalse(reply!!.getBoolean("ok"))
        assertFalse(reply.containsKey("id"))
    }
}
