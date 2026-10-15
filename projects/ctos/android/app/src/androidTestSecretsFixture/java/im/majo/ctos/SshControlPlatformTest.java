package im.majo.ctos;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;

/** Owned Unix socketpair and public synthetic FD transport only. This cannot
 * prove real KEX, host approval, secret release or a production role registry. */
public final class SshControlPlatformTest {
    private static final String METHOD = "im.majo.ctos.SshControlPlatformTest#syntheticBoundNativeControl";

    @Test public void syntheticBoundNativeControl() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshControlPlatformProbe");
        assertTrue("Strict control opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped control execution required", "true".equals(optIn));
        assertEquals("Only the named control method", METHOD, arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        File directory = new File(target.getApplicationInfo().nativeLibraryDir);
        File executable = new File(directory, "libctos_ssh_control_probe.so");
        assertTrue("Absolute APK native path required", directory.isAbsolute());
        assertEquals("Fixed executable stays in APK native directory", directory.getCanonicalFile(), executable.getCanonicalFile().getParentFile());
        assertEquals("Fixed executable basename", "libctos_ssh_control_probe.so", executable.getCanonicalFile().getName());
        assertTrue("Control executable present", executable.isFile() && executable.canExecute());
        java.lang.Process child = null; Future<String> output = null;
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "--synthetic-control");
            builder.environment().clear(); builder.environment().put("LANG", "C"); builder.redirectErrorStream(true);
            try { child = builder.start(); }
            catch (IOException failure) { throw new AssertionError("Owned control launch failed"); }
            final InputStream stream = child.getInputStream();
            output = reader.submit(() -> SshMemoryPlatformTest.drain(stream));
            child.getOutputStream().close();
            assertTrue("Control process deadline", child.waitFor(60, TimeUnit.SECONDS));
            String records = output.get(3, TimeUnit.SECONDS);
            String expected = "SSH_CONTROL phase=transport state=pass errno=0 value=24\n"
                    + "SSH_CONTROL phase=cleanup state=pass errno=0 value=1\n"
                    + "SSH_CONTROL phase=gate state=pass errno=0 value=24\n";
            assertTrue("Exact complete control protocol required", expected.equals(records));
            assertEquals("Control gate and native exit agree", 0, child.exitValue());
            Log.i("CtosSshControlFixture", "phase=transport_gate state=pass cases=24 host_approval=false");
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt(); throw new AssertionError("Owned control execution interrupted");
        } finally {
            try { SshMemoryPlatformTest.cleanup(child, output, reader); }
            finally { SshMemoryPlatformTest.guard(); }
        }
    }
}
