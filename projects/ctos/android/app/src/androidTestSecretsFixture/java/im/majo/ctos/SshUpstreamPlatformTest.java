package im.majo.ctos;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assume;
import org.junit.Test;

/** Trusted synthetic G2 only: real upstream file calls and an owned loopback
 * identification endpoint. No Activity, user vault, Root or clipboard access;
 * this is not a production policy, broker, login or trust validation test. */
public final class SshUpstreamPlatformTest {
    private static final String METHOD = "im.majo.ctos.SshUpstreamPlatformTest#syntheticUpstreamMemoryFilesAndProxyJump";
    private static final String TAG = "CtosSshG2Fixture";
    private static final String DRIVER = "libctos_ssh_upstream_probe.so";
    private static final String CLIENT = "libctos_ssh_upstream_client.so";
    private static final String BASELINE = "libctos_ssh_upstream_baseline.so";
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
            "config", "fd_negative", "identity_hostfile", "proxy_inherit",
            "cancel_reap", "revoke_local", "cleanup", "client_parent", "client_jump",
            "capacity", "object_limit", "proposal_stream", "local_scope"));
    private static final Set<String> PHASES = new HashSet<>(Arrays.asList(
            "config", "fd_negative", "identity_hostfile", "proxy_inherit",
            "cancel_reap", "revoke_local", "cleanup", "crypto_version", "gate",
            "capacity", "object_limit", "proposal_stream", "local_scope", "client_parent", "client_jump"));
    private static final Pattern RECORD = Pattern.compile(
            "SSH_G2 phase=([a-z_]+) state=(pass|error) errno=([0-9]{1,4}) value=([0-9]{1,20}) pid=([0-9]{1,10}) generation=([0-9]{1,20})");
    private static final BigInteger MAX_U64 = new BigInteger("18446744073709551615");
    private static final BigInteger GENERATION = BigInteger.valueOf(73);

    private static File executable(File directory, String basename) throws IOException {
        File file = new File(directory, basename);
        assertTrue("APK native directory must be absolute", directory.isAbsolute());
        assertEquals("Fixed executable must remain directly inside the APK native directory",
                directory.getCanonicalFile(), file.getCanonicalFile().getParentFile());
        assertEquals("Fixed executable cannot resolve to another basename", basename, file.getCanonicalFile().getName());
        assertTrue("Opt-in upstream executable must exist and be executable", file.isFile() && file.canExecute());
        return file;
    }

    private static void validate(String output, int exit) {
        assertFalse("Upstream probe output required", output.isEmpty());
        assertTrue("Upstream output must end with a complete record", output.endsWith("\n"));
        String[] lines = output.split("\n");
        assertTrue("Upstream record count bounded", lines.length <= 80);
        Set<String> seen = new HashSet<>();
        Set<String> passed = new HashSet<>();
        boolean errors = false;
        String gateState = null;
        BigInteger gateValue = null;
        BigInteger parentPid = null, jumpPid = null;
        for (int index = 0; index < lines.length; ++index) {
            assertTrue("Upstream record length bounded", lines[index].length() <= 200);
            Matcher record = RECORD.matcher(lines[index]);
            assertTrue("Unknown upstream output rejected without echo", record.matches());
            String phase = record.group(1), state = record.group(2);
            int error = Integer.parseInt(record.group(3));
            BigInteger value = new BigInteger(record.group(4));
            BigInteger pid = new BigInteger(record.group(5));
            BigInteger generation = new BigInteger(record.group(6));
            assertTrue("Unknown upstream phase", PHASES.contains(phase));
            assertTrue("Duplicate upstream phase", seen.add(phase));
            assertTrue("Upstream errno out of range", error <= 4095);
            assertTrue("Upstream value out of range", value.compareTo(MAX_U64) <= 0);
            assertTrue("Upstream PID out of range", pid.signum() > 0 && pid.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0);
            assertEquals("Synthetic upstream generation must match", GENERATION, generation);
            assertEquals("Upstream success cannot hide an errno", "pass".equals(state), error == 0);
            if ("error".equals(state)) errors = true;
            else passed.add(phase);
            if ("pass".equals(state)) {
                if ("capacity".equals(phase)) assertEquals("Synthetic object capacity must match", BigInteger.valueOf(16384), value);
                if ("object_limit".equals(phase)) assertEquals("Synthetic object limit must match", BigInteger.valueOf(16), value);
                if ("proposal_stream".equals(phase) || "local_scope".equals(phase)
                        || "client_parent".equals(phase) || "client_jump".equals(phase)) {
                    assertEquals("Synthetic boundary confirmation must match", BigInteger.ONE, value);
                }
                if ("crypto_version".equals(phase)) assertTrue("Runtime crypto version required", value.signum() > 0);
                if ("client_parent".equals(phase)) parentPid = pid;
                if ("client_jump".equals(phase)) jumpPid = pid;
            }
            if ("proxy_inherit".equals(phase) && "pass".equals(state)) {
                assertTrue("ProxyJump requires at least two verified process reads", value.compareTo(BigInteger.valueOf(2)) >= 0);
                assertNotNull("Verified parent read required before ProxyJump outcome", parentPid);
                assertNotNull("Verified jump read required before ProxyJump outcome", jumpPid);
                assertNotEquals("ProxyJump reads must come from distinct processes", parentPid, jumpPid);
            }
            if ("gate".equals(phase)) {
                assertEquals("Upstream gate must be the final record", lines.length - 1, index);
                gateState = state;
                gateValue = value;
            }
            // Only fixed allowlisted and bounded fields reach Android diagnostics.
            Log.i(TAG, "phase=" + phase + " state=" + state + " errno=" + error
                    + " value=" + value + " pid=" + pid + " generation=" + generation);
        }
        assertFalse("Upstream probe reported an error", errors);
        assertTrue("All six upstream groups, distinct process reads and cleanup must pass", passed.containsAll(REQUIRED));
        assertEquals("Upstream gate outcome required", "pass", gateState);
        assertEquals("Only the six upstream groups count toward the gate", BigInteger.valueOf(6), gateValue);
        assertEquals("Native exit must agree with upstream gate", 0, exit);
        Log.i(TAG, "phase=g2_synthetic_upstream_gate_pass api=" + Build.VERSION.SDK_INT
                + " groups=6 production_openssh=false");
    }

    @Test public void syntheticUpstreamMemoryFilesAndProxyJump() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshUpstreamPlatformProbe");
        assertTrue("G2 opt-in accepts only true or omission", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("G2 requires explicit scoped authorization", "true".equals(optIn));
        assertEquals("Select only the named G2 method", METHOD, arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        File directory = new File(target.getApplicationInfo().nativeLibraryDir);
        File driver = executable(directory, DRIVER);
        File client = executable(directory, CLIENT);
        File baseline = executable(directory, BASELINE);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        java.lang.Process child = null;
        Future<String> output = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(driver.getAbsolutePath(), client.getAbsolutePath(), baseline.getAbsolutePath());
            builder.environment().clear();
            builder.environment().put("LANG", "C");
            builder.redirectErrorStream(true);
            try { child = builder.start(); }
            catch (IOException failure) { throw new AssertionError("Owned G2 executable launch failed"); }
            final InputStream stream = child.getInputStream();
            output = reader.submit(() -> SshMemoryPlatformTest.drain(stream));
            child.getOutputStream().close();
            assertTrue("Owned upstream probe exceeded its deadline", child.waitFor(60, TimeUnit.SECONDS));
            String records;
            try { records = output.get(3, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException failure) { throw new AssertionError("Bounded G2 output collection failed"); }
            validate(records, child.exitValue());
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Owned G2 execution interrupted");
        } finally {
            // Both checks run even when native validation or cleanup fails.
            try { SshMemoryPlatformTest.cleanup(child, output, reader); }
            finally { SshMemoryPlatformTest.guard(); }
        }
    }
}
