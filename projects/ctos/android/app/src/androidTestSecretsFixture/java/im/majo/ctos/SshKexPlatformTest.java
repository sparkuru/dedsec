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

/** Real packet/KEX/signature/Newkeys functions; synthetic keys, no network,
 * product host authority, vault, clipboard, Root or Activity. */
public final class SshKexPlatformTest {
    @Test public void syntheticRealKeyExchange() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshKexPlatformProbe");
        assertTrue("Strict KEX opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped KEX execution required", "true".equals(optIn));
        assertEquals("Only the named KEX method", "im.majo.ctos.SshKexPlatformTest#syntheticRealKeyExchange", arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        File directory = new File(target.getApplicationInfo().nativeLibraryDir);
        File executable = new File(directory, "libctos_ssh_kex_probe.so");
        assertTrue("Absolute APK native path required", directory.isAbsolute());
        assertEquals("Fixed executable stays in APK native directory", directory.getCanonicalFile(), executable.getCanonicalFile().getParentFile());
        assertEquals("Fixed executable basename", "libctos_ssh_kex_probe.so", executable.getCanonicalFile().getName());
        assertTrue("KEX executable present", executable.isFile() && executable.canExecute());
        java.lang.Process child = null; Future<String> output = null;
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "--synthetic-kex");
            builder.environment().clear(); builder.environment().put("LANG", "C"); builder.redirectErrorStream(true);
            try { child = builder.start(); }
            catch (IOException failure) { throw new AssertionError("Owned KEX launch failed"); }
            final InputStream stream = child.getInputStream();
            output = reader.submit(() -> SshMemoryPlatformTest.drain(stream));
            child.getOutputStream().close();
            assertTrue("KEX process deadline", child.waitFor(120, TimeUnit.SECONDS));
            String records = output.get(3, TimeUnit.SECONDS);
            java.util.regex.Matcher rejected = java.util.regex.Pattern.compile(
                    "SSH_KEX phase=gate state=error errno=5 value=([0-9]{1,5})\\n").matcher(records);
            if (rejected.matches()) throw new AssertionError("Native KEX rejected source line " + rejected.group(1));
            String expected = "SSH_KEX phase=handshake state=pass errno=0 value=27\n"
                    + "SSH_KEX phase=cleanup state=pass errno=0 value=1\n"
                    + "SSH_KEX phase=gate state=pass errno=0 value=27\n";
            assertTrue("Exact complete KEX protocol required", expected.equals(records));
            assertEquals("KEX gate and native exit agree", 0, child.exitValue());
            Log.i("CtosSshKexFixture", "phase=kex_gate state=pass cases=27 product_host_authority=false");
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt(); throw new AssertionError("Owned KEX execution interrupted");
        } finally {
            try { SshMemoryPlatformTest.cleanup(child, output, reader); }
            finally { SshMemoryPlatformTest.guard(); }
        }
    }
}
