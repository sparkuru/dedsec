package im.majo.ctos;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Arrays;
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

/** Real upstream parser with public synthetic strings; no network, Activity,
 * user vault, Root, clipboard or production trust provider. */
public final class SshPolicyPlatformTest {
    private static final String METHOD = "im.majo.ctos.SshPolicyPlatformTest#syntheticNativeConfigurationPolicy";
    private static final String EXECUTABLE = "libctos_ssh_policy_probe.so";
    private static final Pattern RECORD = Pattern.compile(
            "SSH_POLICY phase=([a-z_]+) state=(pass|error) errno=([0-9]{1,4}) value=([0-9]{1,4})");

    private static void validate(String output, int exit) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("match_exec", 21);
        expected.put("commands", 12);
        expected.put("trust_options", 19);
        expected.put("external", 42);
        expected.put("jump_syntax", 5);
        expected.put("include_closure", 14);
        expected.put("hop_options", 2);
        expected.put("managed_files", 20);
        expected.put("proxy_format", 10);
        expected.put("entry", 1);
        expected.put("trust_entry", 1);
        expected.put("gate", 147);
        assertTrue("Policy output must end in a complete record", output.endsWith("\n"));
        String[] lines = output.split("\n");
        assertTrue("Diagnostic policy record count bounded", lines.length <= 20);
        Set<String> diagnostics = new HashSet<>(Arrays.asList(
                "failure_reason", "failure_object", "failure_line", "failure_native", "failure_memory", "failure_effects"));
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            assertTrue("Policy record bounded", line.length() <= 200);
            Matcher record = RECORD.matcher(line);
            assertTrue("Unknown policy output rejected without echo", record.matches());
            String phase = record.group(1);
            assertTrue("Policy phase must be allowlisted", expected.containsKey(phase) || diagnostics.contains(phase));
            assertTrue("Policy phases cannot repeat", seen.add(phase));
            int error = Integer.parseInt(record.group(3));
            assertTrue("Policy errno bounded", error <= 4095);
            assertEquals("Policy state and errno must agree", "pass".equals(record.group(2)), error == 0);
            Log.i("CtosSshPolicyFixture", "phase=" + phase + " state=" + record.group(2)
                    + " errno=" + error + " value=" + Integer.parseInt(record.group(4)));
        }
        assertEquals("Every policy group and final gate required", expected.size(), lines.length);
        int index = 0;
        for (Map.Entry<String, Integer> phase : expected.entrySet()) {
            assertTrue("Policy record bounded", lines[index].length() <= 200);
            Matcher record = RECORD.matcher(lines[index++]);
            assertTrue("Unknown policy output rejected without echo", record.matches());
            assertEquals("Policy phases must be complete and ordered", phase.getKey(), record.group(1));
            assertEquals("Policy group cannot hide failure", "pass", record.group(2));
            assertEquals("Policy success requires errno zero", "0", record.group(3));
            assertEquals("Exact policy case count required", phase.getValue().intValue(), Integer.parseInt(record.group(4)));
        }
        assertEquals("Native exit must agree with policy gate", 0, exit);
    }

    @Test public void syntheticNativeConfigurationPolicy() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshPolicyPlatformProbe");
        assertTrue("Policy opt-in accepts only true or omission", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Policy fixture requires explicit scoped execution", "true".equals(optIn));
        assertEquals("Select only the named policy method", METHOD, arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        File directory = new File(target.getApplicationInfo().nativeLibraryDir);
        File executable = new File(directory, EXECUTABLE);
        assertTrue("APK native directory must be absolute", directory.isAbsolute());
        assertEquals("Executable must remain directly inside APK native directory",
                directory.getCanonicalFile(), executable.getCanonicalFile().getParentFile());
        assertEquals("Executable basename must remain fixed", EXECUTABLE, executable.getCanonicalFile().getName());
        assertTrue("Policy executable must exist and be executable", executable.isFile() && executable.canExecute());
        ExecutorService reader = Executors.newSingleThreadExecutor();
        java.lang.Process child = null;
        Future<String> output = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "--synthetic-policy");
            builder.environment().clear();
            builder.environment().put("LANG", "C");
            builder.redirectErrorStream(true);
            try { child = builder.start(); }
            catch (IOException failure) { throw new AssertionError("Owned policy executable launch failed"); }
            final InputStream stream = child.getInputStream();
            output = reader.submit(() -> SshMemoryPlatformTest.drain(stream));
            child.getOutputStream().close();
            assertTrue("Owned policy probe exceeded deadline", child.waitFor(60, TimeUnit.SECONDS));
            String records;
            try { records = output.get(3, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException failure) { throw new AssertionError("Bounded policy output collection failed"); }
            validate(records, child.exitValue());
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Owned policy execution interrupted");
        } finally {
            try { SshMemoryPlatformTest.cleanup(child, output, reader); }
            finally { SshMemoryPlatformTest.guard(); }
        }
    }
}
