package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Fake-native ownership and wire regression; these tests do not execute JNI. */
public final class SshConfigEvaluatorTest {
    private interface Action { void run() throws Exception; }

    private static final class View implements SshConfigEvaluator.ViewBinding {
        volatile boolean active = true;
        boolean failToken;
        @Override public long token() throws VaultException {
            if (failToken) throw new VaultException("TEST", "private test path");
            return 91;
        }
        @Override public boolean active() { return active; }
    }

    private static class Binding implements SshConfigEvaluator.NativeBinding {
        int opens, runs, summaries, cancels, closes, destroys;
        boolean failOpen, failRun, failClose, failDestroy;
        long openedToken = 92;
        byte[] requestBorrow, requestCopy, summaryBorrow;
        int[] diagnostic = {0, 0, 0, 0, 0};
        int[] diagnosticBorrow;
        byte[] response;
        Action onOpen, onRun, onSummary;

        Binding() throws IOException { response = frame("alias", "host.example", "remote"); }

        private static void action(Action action) throws IOException {
            if (action == null) return;
            try { action.run(); }
            catch (IOException failure) { throw failure; }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new IOException("test callback"); }
        }

        @Override public long open(long view, byte[] request) throws IOException {
            assertEquals(91, view);
            opens++;
            requestBorrow = request;
            requestCopy = request.clone();
            action(onOpen);
            if (failOpen) throw new IOException("private test config path");
            return openedToken;
        }
        @Override public int[] run(long handle) throws IOException {
            assertEquals(92, handle);
            runs++;
            action(onRun);
            if (failRun) throw new IOException("private test config body");
            diagnosticBorrow = diagnostic == null ? null : diagnostic.clone();
            return diagnosticBorrow;
        }
        @Override public byte[] summary(long handle) throws IOException {
            assertEquals(92, handle);
            summaries++;
            action(onSummary);
            summaryBorrow = response == null ? null : response.clone();
            return summaryBorrow;
        }
        @Override public void cancel(long handle) {
            assertEquals(92, handle);
            cancels++;
        }
        @Override public void close(long handle) throws IOException {
            assertEquals(92, handle);
            closes++;
            if (failClose) throw new IOException("private test close path");
        }
        @Override public void destroy(long handle) throws IOException {
            assertEquals(92, handle);
            destroys++;
            if (failDestroy) throw new IOException("private test destroy path");
        }
    }

    private static SshConfigEvaluator.Request request() throws IOException {
        return request("alias", "remote");
    }

    private static SshConfigEvaluator.Request request(String alias, String user) throws IOException {
        return new SshConfigEvaluator.Request(alias, user, "fallback", "local", "local.example",
                "tag", "command", 2222, 0xffffffffL);
    }

    private static byte[] frame(String alias, String host, String user) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x43534553);
        out.writeInt(1);
        int[] fields = {user.isEmpty() ? 1 : 0, 2222, 2, 1, 1, 2, 1, 1, 3, 2, 1};
        for (int field : fields) out.writeInt(field);
        for (String text : new String[]{alias, host, user}) {
            byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
            out.writeInt(encoded.length);
            out.write(encoded);
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static String text(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.getInt()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static IOException rejected(Action action) throws Exception {
        try { action.run(); fail("expected bounded failure"); }
        catch (IOException failure) {
            assertFalse(failure.getMessage().contains("private test"));
            assertEquals(null, failure.getCause());
            return failure;
        }
        throw new AssertionError();
    }

    private static void wiped(byte[] bytes) {
        assertNotNull(bytes);
        assertArrayEquals(new byte[bytes.length], bytes);
    }

    @Test public void standardUtf8RequestPreservesAllFieldsAndUnsignedUid() throws Exception {
        View view = new View();
        Binding binding = new Binding();
        SshConfigEvaluator.Request request = new SshConfigEvaluator.Request(
                "别名\ud83d\ude80", "用户", "备用", "local", "localhost", "标签", "echo \ud83d\ude80", 0, 0xffffffffL);
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(view, request, binding)) {
            ByteBuffer wire = ByteBuffer.wrap(binding.requestCopy);
            assertEquals(0x43534551, wire.getInt());
            assertEquals(1, wire.getInt());
            assertEquals(0, wire.getInt());
            assertEquals(0xffffffffL, Integer.toUnsignedLong(wire.getInt()));
            assertEquals("别名\ud83d\ude80", text(wire));
            assertEquals("用户", text(wire));
            assertEquals("备用", text(wire));
            assertEquals("local", text(wire));
            assertEquals("localhost", text(wire));
            assertEquals("标签", text(wire));
            assertEquals("echo \ud83d\ude80", text(wire));
            assertFalse(wire.hasRemaining());
            wiped(binding.requestBorrow);
        }
        assertTrue(view.active);
    }

    @Test public void requestRejectsMalformedUnicodeControlsAndByteOverflow() throws Exception {
        for (String bad : new String[]{"", "a\0b", "a\nb", "a\u007fb", "a\u0085b", "\ud800", "\udc00",
                "\ud800x", repeat("a", 256), repeat("é", 128)}) {
            rejected(() -> request(bad, "user"));
        }
        rejected(() -> new SshConfigEvaluator.Request("a", "", "", "l", "h", "", "", -1, 0));
        rejected(() -> new SshConfigEvaluator.Request("a", "", "", "l", "h", "", "", 65536, 0));
        rejected(() -> new SshConfigEvaluator.Request("a", "", "", "l", "h", "", "", 0, -1));
        rejected(() -> new SshConfigEvaluator.Request("a", "", "", "l", "h", "", "", 0, 0x100000000L));
        rejected(() -> new SshConfigEvaluator.Request("a", "", "", "l", "h", "", repeat("a", 8193), 0, 0));
    }

    @Test public void requestAcceptsExactByteLimitsAndMaximumFrame() throws Exception {
        String name = repeat("a", 255), command = repeat("b", 8192);
        Binding binding = new Binding();
        SshConfigEvaluator.Request maximum = new SshConfigEvaluator.Request(name, name, name,
                name, name, name, command, 65535, 0);
        try (SshConfigEvaluator ignored = SshConfigEvaluator.open(new View(), maximum, binding)) {
            assertEquals(SshConfigEvaluator.MAX_REQUEST_BYTES, binding.requestCopy.length);
        }
        request(repeat("é", 127) + "x", "\ud83d\ude80");
    }

    @Test public void summaryIsDecodedFreshAndNativeBuffersAreWiped() throws Exception {
        View view = new View();
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(view, request(), binding)) {
            rejected(evaluator::summary);
            evaluator.run();
            assertArrayEquals(new int[5], binding.diagnosticBorrow);
            binding.response = frame("别名\ud83d\ude80", "主机", "用户");
            SshConfigEvaluator.Summary first = evaluator.summary();
            assertEquals("别名\ud83d\ude80", first.alias);
            assertEquals("主机", first.hostname);
            assertEquals("用户", first.user);
            assertEquals(2222, first.port);
            assertEquals(2, first.identities);
            assertEquals(1, first.certificates);
            assertEquals(1, first.localForwards);
            assertEquals(2, first.remoteForwards);
            assertEquals(3, first.requestTty);
            assertEquals(2, first.sessionType);
            assertTrue(first.hasRawFields && first.hasJump && first.hasRemoteCommand);
            assertFalse(first.userRequired);
            wiped(binding.summaryBorrow);
            binding.response = frame("alias", "second", "");
            SshConfigEvaluator.Summary second = evaluator.summary();
            assertTrue(second.userRequired);
            assertEquals("second", second.hostname);
            assertEquals("主机", first.hostname);
            assertEquals(2, binding.summaries);
            view.active = false;
            rejected(evaluator::summary);
            assertEquals(2, binding.summaries);
        }
    }

    @Test public void summaryRejectsTruncationTrailingAndInvalidUtf8() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding)) {
            evaluator.run();
            byte[] valid = binding.response;
            for (int size = 0; size < valid.length; size++) {
                binding.response = Arrays.copyOf(valid, size);
                rejected(evaluator::summary);
                wiped(binding.summaryBorrow);
            }
            binding.response = Arrays.copyOf(valid, valid.length + 1);
            rejected(evaluator::summary);
            byte[] invalidUtf8 = valid.clone();
            invalidUtf8[56] = (byte)0xff;
            binding.response = invalidUtf8;
            rejected(evaluator::summary);
            invalidUtf8[56] = 0;
            rejected(evaluator::summary);
            invalidUtf8[56] = '\n';
            rejected(evaluator::summary);
            binding.response = null;
            rejected(evaluator::summary);
        }
    }

    @Test public void summaryRejectsInvalidFlagsCountsEnumsAndLengths() throws Exception {
        int[][] mutations = {{0, 0}, {4, 2}, {8, 2}, {12, 0}, {12, 65536}, {16, 257},
                {20, -1}, {24, 33}, {28, 32}, {32, 2}, {36, 2}, {40, -1}, {40, 4},
                {44, -1}, {44, 3}, {48, 0}, {52, -1}, {52, 256}, {52, Integer.MAX_VALUE}};
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding)) {
            evaluator.run();
            byte[] valid = binding.response;
            for (int[] mutation : mutations) {
                binding.response = valid.clone();
                ByteBuffer.wrap(binding.response).putInt(mutation[0], mutation[1]);
                rejected(evaluator::summary);
                wiped(binding.summaryBorrow);
            }
            binding.response = frame("alias", "host", "");
            ByteBuffer.wrap(binding.response).putInt(8, 0);
            rejected(evaluator::summary);
            binding.response = new byte[SshConfigEvaluator.MAX_SUMMARY_BYTES + 1];
            rejected(evaluator::summary);
        }
    }

    @Test public void summaryAcceptsExactMaximumFrame() throws Exception {
        Binding binding = new Binding();
        binding.response = frame(repeat("a", 255), repeat("b", 255), repeat("c", 255));
        assertEquals(SshConfigEvaluator.MAX_SUMMARY_BYTES, binding.response.length);
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding)) {
            evaluator.run();
            assertEquals(255, evaluator.summary().user.length());
        }
    }

    @Test public void diagnosticsRetainExactlyFiveFieldsAndNeverSensitiveMessages() throws Exception {
        for (int[] diagnostic : new int[][]{{1, 4, 2, 3, 13}, {2, 0, 0, 0, 16}, {1, 3, 128, 16385, 7}}) {
            Binding binding = new Binding();
            binding.diagnostic = diagnostic;
            try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding)) {
                IOException failure = rejected(evaluator::run);
                assertTrue(failure instanceof SshConfigEvaluator.EvaluationException);
                SshConfigEvaluator.Diagnostic actual = ((SshConfigEvaluator.EvaluationException)failure).diagnostic;
                assertEquals(diagnostic[0], actual.status);
                assertEquals(diagnostic[1], actual.reason);
                assertEquals(diagnostic[2], actual.object);
                assertEquals(diagnostic[3], actual.line);
                assertEquals(diagnostic[4], actual.errorNumber);
                assertArrayEquals(new int[5], binding.diagnosticBorrow);
                rejected(evaluator::run);
                rejected(evaluator::summary);
                assertEquals(1, binding.runs);
            }
        }
    }

    @Test public void malformedDiagnosticsFailClosedButRetainCleanup() throws Exception {
        int[][] invalid = {null, {}, {0, 0, 0, 0}, {0, 0, 0, 0, 0, 0}, {0, 1, 0, 0, 0},
                {3, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 14, 0, 0, 1}, {1, 1, 129, 0, 1},
                {1, 1, -1, 0, 1}, {1, 1, 0, 1, 1}, {1, 1, 1, 16386, 1}, {1, 1, 1, -1, 1},
                {1, 1, 0, 0, 0}, {2, 1, 0, 0, 1}, {2, 0, 1, 0, 1}, {2, 0, 0, 1, 1}, {2, 0, 0, 0, 0}};
        for (int[] diagnostic : invalid) {
            Binding binding = new Binding();
            binding.diagnostic = diagnostic;
            try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding)) {
                IOException failure = rejected(evaluator::run);
                assertFalse(failure instanceof SshConfigEvaluator.EvaluationException);
                rejected(evaluator::summary);
            }
            assertEquals(1, binding.closes);
            assertEquals(1, binding.destroys);
        }
    }

    @Test public void closeAndDestroyRetriesKeepTheirSeparateStages() throws Exception {
        View view = new View();
        Binding binding = new Binding();
        SshConfigEvaluator evaluator = SshConfigEvaluator.open(view, request(), binding);
        binding.failClose = true;
        rejected(evaluator::close);
        assertEquals(1, binding.closes);
        assertEquals(0, binding.destroys);
        binding.failClose = false;
        binding.failDestroy = true;
        rejected(evaluator::close);
        assertEquals(2, binding.closes);
        assertEquals(1, binding.destroys);
        rejected(evaluator::run);
        binding.failDestroy = false;
        evaluator.close();
        evaluator.close();
        assertEquals(2, binding.closes);
        assertEquals(2, binding.destroys);
        assertTrue(view.active);
    }

    @Test public void openRetainsPositiveCleanupHandleAfterRevocation() throws Exception {
        View view = new View();
        Binding binding = new Binding();
        binding.onOpen = () -> view.active = false;
        binding.diagnostic = new int[]{2, 0, 0, 0, 13};
        SshConfigEvaluator evaluator = SshConfigEvaluator.open(view, request(), binding);
        assertNotNull(evaluator);
        assertTrue(rejected(evaluator::run) instanceof SshConfigEvaluator.EvaluationException);
        evaluator.close();
        assertEquals(1, binding.closes);
        assertEquals(1, binding.destroys);
    }

    @Test public void failedOpenWithoutTokenWipesRequestAndSanitizesFailures() throws Exception {
        Binding binding = new Binding();
        View view = new View();
        binding.failOpen = true;
        rejected(() -> SshConfigEvaluator.open(view, request(), binding));
        wiped(binding.requestBorrow);
        assertEquals(0, binding.closes);
        binding.failOpen = false;
        binding.openedToken = 0;
        rejected(() -> SshConfigEvaluator.open(view, request(), binding));
        view.failToken = true;
        int opens = binding.opens;
        rejected(() -> SshConfigEvaluator.open(view, request(), binding));
        assertEquals(opens, binding.opens);
        assertTrue(view.active);
    }

    @Test public void ownerOperationsRejectOtherThreadsButCancelRemainsAvailable() throws Exception {
        Binding binding = new Binding();
        SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                rejected(evaluator::run);
                rejected(evaluator::summary);
                rejected(evaluator::close);
                evaluator.cancel();
            } catch (Throwable failure) { failed.set(failure); }
        });
        other.start();
        other.join(5000);
        assertFalse(other.isAlive());
        assertEquals(null, failed.get());
        assertEquals(0, binding.runs);
        assertEquals(1, binding.cancels);
        rejected(evaluator::run);
        evaluator.close();
    }

    @Test public void cancellationDoesNotWaitForRunningNativeOperation() throws Exception {
        Binding binding = new Binding();
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding);
        binding.onRun = () -> {
            entered.countDown();
            assertTrue("cancel was blocked by run", cancelled.await(5, TimeUnit.SECONDS));
        };
        Thread other = new Thread(() -> {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                evaluator.cancel();
            } catch (Throwable failure) { failed.set(failure); }
            finally { cancelled.countDown(); }
        });
        other.start();
        rejected(evaluator::run);
        other.join(5000);
        assertEquals(null, failed.get());
        assertEquals(1, binding.cancels);
        rejected(evaluator::summary);
        evaluator.close();
    }

    @Test public void cancellationDuringSummaryPreventsPublication() throws Exception {
        Binding binding = new Binding();
        SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding);
        evaluator.run();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        binding.onSummary = () -> {
            Thread other = new Thread(() -> {
                try { evaluator.cancel(); }
                catch (Throwable failure) { failed.set(failure); }
            });
            other.start();
            other.join(5000);
            assertFalse(other.isAlive());
            assertEquals(null, failed.get());
        };
        rejected(evaluator::summary);
        wiped(binding.summaryBorrow);
        evaluator.close();
    }

    @Test public void revocationDuringSummaryAndRunPreventsSuccessfulPublication() throws Exception {
        View view = new View();
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(view, request(), binding)) {
            binding.onRun = () -> view.active = false;
            rejected(evaluator::run);
            rejected(evaluator::summary);
        }
        view.active = true;
        binding.onRun = null;
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(view, request(), binding)) {
            evaluator.run();
            binding.onSummary = () -> view.active = false;
            rejected(evaluator::summary);
            wiped(binding.summaryBorrow);
        }
    }

    @Test public void reentryAcrossObjectsIsRejectedAndErrorClearsEntry() throws Exception {
        Binding first = new Binding(), second = new Binding();
        SshConfigEvaluator one = SshConfigEvaluator.open(new View(), request(), first);
        SshConfigEvaluator two = SshConfigEvaluator.open(new View(), request(), second);
        AssertionError expected = new AssertionError("test error");
        first.onRun = () -> {
            rejected(one::run);
            rejected(one::close);
            rejected(two::run);
            rejected(one::cancel);
            rejected(() -> SshConfigEvaluator.open(new View(), request(), second));
            throw expected;
        };
        try { one.run(); fail("Error must propagate"); }
        catch (AssertionError actual) { assertTrue(actual == expected); }
        one.close();
        two.run();
        two.close();
        assertEquals(1, second.opens);
        assertEquals(1, second.runs);
    }

    @Test public void linkageFailuresAreBoundedAndCloseRemainsRetryable() throws Exception {
        Binding binding = new Binding() {
            boolean fail = true;
            @Override public int[] run(long handle) { throw new UnsatisfiedLinkError("private test symbol"); }
            @Override public void close(long handle) throws IOException {
                if (fail) { fail = false; throw new UnsatisfiedLinkError("private test symbol"); }
                super.close(handle);
            }
        };
        SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding);
        rejected(evaluator::run);
        rejected(evaluator::close);
        evaluator.close();
        assertEquals(1, binding.destroys);
    }

    @Test public void ioFailureConsumesRunButStillAllowsCleanup() throws Exception {
        Binding binding = new Binding();
        binding.failRun = true;
        SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding);
        rejected(evaluator::run);
        binding.failRun = false;
        rejected(evaluator::run);
        rejected(evaluator::summary);
        evaluator.close();
        assertEquals(1, binding.runs);
        assertEquals(1, binding.closes);
        assertEquals(1, binding.destroys);
    }

    @Test public void errorDuringCleanupOrGetterDoesNotStickEntryGuard() throws Exception {
        AssertionError expected = new AssertionError("test error");
        Binding binding = new Binding() {
            boolean closeError = true;
            @Override public void close(long handle) throws IOException {
                if (closeError) { closeError = false; throw expected; }
                super.close(handle);
            }
        };
        SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request(), binding);
        evaluator.run();
        binding.onSummary = () -> { throw expected; };
        try { evaluator.summary(); fail("Error must propagate"); }
        catch (AssertionError actual) { assertTrue(actual == expected); }
        binding.onSummary = null;
        assertNotNull(evaluator.summary());
        try { evaluator.close(); fail("Error must propagate"); }
        catch (AssertionError actual) { assertTrue(actual == expected); }
        evaluator.close();
        assertEquals(1, binding.destroys);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
