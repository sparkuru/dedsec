package im.majo.ctos;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

/** Synthetic G0 only; no Activity, shell, network, vault read or global writes.
 * A completed diagnostic with zero eligible anonymous file candidates fails
 * the gate. This test cannot establish any production OpenSSH behavior. */
public final class SshMemoryPlatformTest {
    private static final String TARGET = "im.majo.ctos.secretsfixture";
    private static final String METHOD = "im.majo.ctos.SshMemoryPlatformTest#syntheticAnonymousMemoryAndManagedExec";
    private static final String EXECUTABLE = "libctos_ssh_memory_probe.so";
    private static final String TAG = "CtosSshG0Fixture";
    private static final int OUTPUT_LIMIT = 16384;
    private static final Set<String> BACKENDS = new HashSet<>(Arrays.asList("meta", "ashared", "raw_memfd"));
    private static final Set<String> PHASES = new HashSet<>(Arrays.asList(
            "arguments", "parent_guard", "limits", "page_size", "memlock_limit", "raw_syscall_candidate", "reap_clock_failure",
            "invalid_size", "create", "map_lock", "prot_existing_mapping", "fstat", "write", "reopen_offsets",
            "stdio", "append_truncate_replace", "posix_shape", "exec_whitelist", "stale_missing",
            "synchronized_wipe", "held_fd_eof", "cancel_reap", "timeout_reap", "cleanup", "candidate", "gate"));
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
            "invalid_size", "create", "map_lock", "posix_shape", "cancel_reap", "timeout_reap",
            "exec_whitelist", "stale_missing", "synchronized_wipe", "held_fd_eof", "cleanup", "candidate"));
    private static final Pattern RECORD = Pattern.compile(
            "SSH_G0 backend=([a-z_]+) phase=([a-z_]+) state=(pass|unsupported|error) errno=([0-9]{1,4}) value=([0-9]{1,20})");
    private static final BigInteger MAX_U64 = new BigInteger("18446744073709551615");

    static Context guard() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        assertEquals("Only the independent fixture may run", TARGET, target.getPackageName());
        assertEquals("Unexpected test package", TARGET + ".test", instrumentation.getContext().getPackageName());
        assertEquals("Unexpected target UID", target.getApplicationInfo().uid, Process.myUid());
        assertTrue("System UID cannot be a fixture", Process.myUid() >= Process.FIRST_APPLICATION_UID);
        assertNull("Fixture cannot have a shared UID", target.getPackageManager().getPackageInfo(TARGET, 0).sharedUserId);
        assertNull("Test cannot have a shared UID", target.getPackageManager().getPackageInfo(TARGET + ".test", 0).sharedUserId);
        assertNotEquals("Production must be a different UID", target.getPackageManager().getApplicationInfo("im.majo.ctos", 0).uid, Process.myUid());
        assertTrue("Fixture Application required", target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertFalse("Own Root auto-start must remain disabled", target.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
        assertTrue("The G0 fixture must be fresh", target.getContentResolver().getPersistedUriPermissions().isEmpty());
        for (String name : new String[]{"app-secrets.bin", "vault-export-journal.json"}) {
            for (String suffix : new String[]{"", ".bak", ".new"}) {
                assertFalse("Existing fixture state requires reconciliation", new File(target.getNoBackupFilesDir(), name + suffix).exists());
            }
        }
        for (String marker : new String[]{"secrets-ui-flow-owned.marker", "totp-ui-flow-owned.marker"}) {
            assertFalse("Reused fixture state requires reconciliation", new File(target.getNoBackupFilesDir(), marker).exists());
        }
        return target;
    }

    static String drain(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[512];
        try {
            int count;
            while ((count = input.read(block)) >= 0) {
                if (bytes.size() + count > OUTPUT_LIMIT) throw new IOException("G0 output bound exceeded");
                bytes.write(block, 0, count);
            }
            byte[] result = bytes.toByteArray();
            try {
                for (byte value : result) if (value != '\n' && (value < 32 || value > 126)) throw new IOException("G0 output encoding rejected");
                return new String(result, StandardCharsets.US_ASCII);
            } finally { Arrays.fill(result, (byte) 0); }
        } finally { Arrays.fill(block, (byte) 0); bytes.reset(); }
    }

    private static void validate(String output, int exit) {
        assertFalse("Probe output required", output.isEmpty());
        assertTrue("Probe output must end with a complete record", output.endsWith("\n"));
        String[] lines = output.split("\n");
        assertTrue("Probe record count bounded", lines.length <= 80);
        Map<String, Set<String>> passed = new HashMap<>();
        Map<String, String> candidates = new HashMap<>();
        Set<String> unsupported = new HashSet<>();
        int eligible = 0;
        boolean errors = false, gateSeen = false;
        String gateState = null;
        BigInteger gateValue = null;
        for (int index = 0; index < lines.length; ++index) {
            assertTrue("Probe record length bounded", lines[index].length() <= 200);
            Matcher record = RECORD.matcher(lines[index]);
            assertTrue("Unknown probe output rejected without echo", record.matches());
            String backend = record.group(1), phase = record.group(2), state = record.group(3);
            int error = Integer.parseInt(record.group(4));
            BigInteger value = new BigInteger(record.group(5));
            assertTrue("Unknown backend", BACKENDS.contains(backend));
            assertTrue("Unknown phase", PHASES.contains(phase));
            assertTrue("errno out of range", error <= 4095);
            assertTrue("Value out of range", value.compareTo(MAX_U64) <= 0);
            assertEquals("Success cannot hide an errno", "pass".equals(state), error == 0);
            if ("error".equals(state)) errors = true;
            if ("unsupported".equals(state)) unsupported.add(backend);
            if ("pass".equals(state)) passed.computeIfAbsent(backend, ignored -> new HashSet<>()).add(phase);
            if ("candidate".equals(phase)) {
                assertFalse("Meta cannot be a candidate", "meta".equals(backend));
                assertNull("Duplicate candidate outcome", candidates.put(backend, state));
                assertEquals("Candidate eligibility count", "pass".equals(state) ? BigInteger.ONE : BigInteger.ZERO, value);
                if ("pass".equals(state)) ++eligible;
            }
            if ("gate".equals(phase)) {
                assertEquals("Gate must be meta", "meta", backend);
                assertFalse("Only one gate outcome", gateSeen);
                assertEquals("Gate is the final record", lines.length - 1, index);
                gateSeen = true; gateState = state; gateValue = value;
            }
            // Only parsed, fixed allowlisted records reach diagnostics.
            Log.i(TAG, "backend=" + backend + " phase=" + phase + " state=" + state + " errno=" + error + " value=" + value);
        }
        assertTrue("Diagnostic gate outcome required", gateSeen);
        assertEquals("Both candidates must be evaluated explicitly", 2, candidates.size());
        assertTrue("Metadata incomplete", passed.getOrDefault("meta", new HashSet<>()).containsAll(Arrays.asList("page_size", "memlock_limit", "raw_syscall_candidate")));
        for (String backend : new String[]{"ashared", "raw_memfd"}) {
            assertTrue("Every candidate must clean up", passed.getOrDefault(backend, new HashSet<>()).contains("cleanup"));
            if ("pass".equals(candidates.get(backend))) {
                assertFalse("Unsupported cannot become eligible", unsupported.contains(backend));
                assertTrue("Eligible requires the complete lifecycle", passed.getOrDefault(backend, new HashSet<>()).containsAll(REQUIRED));
                if ("ashared".equals(backend)) assertTrue("Shared memory protection boundary missing", passed.get(backend).contains("prot_existing_mapping"));
            }
        }
        assertEquals("Gate eligible count must agree", BigInteger.valueOf(eligible), gateValue);
        Log.i(TAG, "phase=diagnostic_complete api=" + Build.VERSION.SDK_INT + " eligible=" + eligible);
        assertFalse("Probe reported a mandatory error", errors);
        assertTrue("G0 fails when no complete anonymous file candidate is eligible", eligible >= 1);
        assertEquals("Native exit must agree with gate", 0, exit);
        assertEquals("Diagnostic completion is not automatically gate success", "pass", gateState);
        Log.i(TAG, "phase=g0_capability_gate_pass production_openssh=false");
    }

    private static boolean awaitOwnedProcess(java.lang.Process child, long deadline, boolean[] interrupted) {
        for (int attempt = 0; attempt < 256; ++attempt) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) return !child.isAlive();
            try { return child.waitFor(remaining, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interruption) { interrupted[0] = true; }
        }
        return !child.isAlive();
    }

    private static boolean closeOwnedStream(java.io.Closeable stream) {
        try { stream.close(); return true; }
        catch (IOException | RuntimeException failure) { return false; }
    }

    static void cleanup(java.lang.Process child, Future<String> output, ExecutorService reader) {
        boolean[] interrupted = {Thread.interrupted()};
        boolean complete = true;
        long deadline = SystemClock.elapsedRealtime() + 8000;
        try {
            if (child != null) {
                boolean reaped = false;
                try {
                    if (child.isAlive()) child.destroy();
                    reaped = awaitOwnedProcess(child, Math.min(deadline, SystemClock.elapsedRealtime() + 2000), interrupted);
                } catch (RuntimeException failure) { complete = false; }
                if (!reaped) {
                    try {
                        child.destroyForcibly();
                        if (!awaitOwnedProcess(child, Math.min(deadline, SystemClock.elapsedRealtime() + 3000), interrupted)) complete = false;
                    } catch (RuntimeException failure) { complete = false; }
                }
                // Independent attempts: one IOException cannot skip the others.
                if (!closeOwnedStream(child.getOutputStream())) complete = false;
                if (!closeOwnedStream(child.getInputStream())) complete = false;
                if (!closeOwnedStream(child.getErrorStream())) complete = false;
            }
            if (output != null) output.cancel(true);
            reader.shutdownNow();
            boolean terminated = reader.isTerminated();
            for (int attempt = 0; !terminated && attempt < 256; ++attempt) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try { terminated = reader.awaitTermination(remaining, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interruption) { interrupted[0] = true; }
            }
            if (!terminated) complete = false;
            assertTrue("Owned G0 process/streams/executor cleanup incomplete", complete);
        } finally {
            if (interrupted[0] || Thread.interrupted()) Thread.currentThread().interrupt();
        }
    }

    @Test public void syntheticAnonymousMemoryAndManagedExec() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshMemoryPlatformProbe");
        assertTrue("G0 opt-in accepts only true or omission", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("G0 requires explicit scoped authorization", "true".equals(optIn));
        assertEquals("Select only the named G0 method", METHOD, arguments.getString("class"));
        Context target = guard();
        File directory = new File(target.getApplicationInfo().nativeLibraryDir);
        File executable = new File(directory, EXECUTABLE);
        assertTrue("APK native directory must be absolute", directory.isAbsolute());
        assertEquals("Fixed executable must remain directly inside the APK native directory", directory.getCanonicalFile(), executable.getCanonicalFile().getParentFile());
        assertTrue("Opt-in fixture native probe must exist and be executable", executable.isFile() && executable.canExecute());
        ExecutorService reader = Executors.newSingleThreadExecutor();
        java.lang.Process child = null;
        Future<String> output = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "--probe");
            builder.environment().clear(); builder.environment().put("LANG", "C");
            builder.redirectErrorStream(true);
            try { child = builder.start(); } catch (IOException failure) { throw new AssertionError("Owned G0 executable launch failed"); }
            final InputStream stream = child.getInputStream();
            output = reader.submit(() -> drain(stream));
            child.getOutputStream().close();
            assertTrue("Owned native probe exceeded its deadline", child.waitFor(30, TimeUnit.SECONDS));
            String records;
            try { records = output.get(3, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException failure) { throw new AssertionError("Bounded G0 output collection failed"); }
            validate(records, child.exitValue());
            guard();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Owned G0 execution interrupted");
        } finally { cleanup(child, output, reader); }
    }
}
