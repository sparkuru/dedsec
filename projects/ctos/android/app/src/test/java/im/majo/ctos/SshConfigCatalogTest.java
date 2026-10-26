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

/** Independent CSCD vectors and fake-native lifecycle tests; no JNI execution. */
public final class SshConfigCatalogTest {
    private interface Action { void run() throws Exception; }

    private static final class View implements SshConfigEvaluator.ViewBinding {
        volatile boolean active = true;
        boolean failToken;
        @Override public long token() throws VaultException {
            if (failToken) throw new VaultException("TEST", "private test path");
            return 51;
        }
        @Override public boolean active() { return active; }
    }

    private static class OldBinding implements SshConfigEvaluator.NativeBinding {
        int opens, runs, summaries, cancels, closes, destroys;
        @Override public long open(long view, byte[] request) { opens++; return 52; }
        @Override public int[] run(long handle) throws IOException { runs++; return new int[5]; }
        @Override public byte[] summary(long handle) { summaries++; return null; }
        @Override public void cancel(long handle) { cancels++; }
        @Override public void close(long handle) throws IOException { closes++; }
        @Override public void destroy(long handle) throws IOException { destroys++; }
    }

    private static final class Binding extends OldBinding {
        int catalogOpens, catalogs;
        boolean failClose, failDestroy;
        long token = 52;
        byte[] response, borrowed;
        int[] diagnostic = new int[5], diagnosticBorrow;
        Action onOpen, onRun, onCatalog;

        Binding() throws IOException { response = frame(15, 2, new String[]{"a", "别名🚀"}, new int[]{2, 7}); }
        private static void action(Action action) throws IOException {
            if (action == null) return;
            try { action.run(); }
            catch (IOException failure) { throw failure; }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new IOException("test callback"); }
        }
        @Override public long openCatalog(long view) throws IOException {
            assertEquals(51, view);
            catalogOpens++;
            action(onOpen);
            return token;
        }
        @Override public int[] run(long handle) throws IOException {
            assertEquals(52, handle);
            runs++;
            action(onRun);
            diagnosticBorrow = diagnostic == null ? null : diagnostic.clone();
            return diagnosticBorrow;
        }
        @Override public byte[] catalog(long handle) throws IOException {
            assertEquals(52, handle);
            catalogs++;
            action(onCatalog);
            borrowed = response == null ? null : response.clone();
            return borrowed;
        }
        @Override public void close(long handle) throws IOException {
            super.close(handle);
            if (failClose) throw new IOException("private test close");
        }
        @Override public void destroy(long handle) throws IOException {
            super.destroy(handle);
            if (failDestroy) throw new IOException("private test destroy");
        }
    }

    private static byte[] frame(int flags, int files, String[] aliases, int[] sites) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x43534344);
        out.writeInt(1);
        out.writeInt(flags);
        out.writeInt(files);
        out.writeInt(aliases.length);
        out.writeInt(sites.length / 2);
        for (int i = 0; i < aliases.length; i++) {
            byte[] text = aliases[i].getBytes(StandardCharsets.UTF_8);
            out.writeInt(i % 128 + 1);
            out.writeInt(i + 1);
            out.writeInt(text.length);
            out.write(text);
        }
        for (int source : sites) out.writeInt(source);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] integer(byte[] wire, int offset, int value) {
        byte[] changed = wire.clone();
        ByteBuffer.wrap(changed).putInt(offset, value);
        return changed;
    }

    private static IOException rejected(Action action) throws Exception {
        try { action.run(); fail("expected bounded failure"); }
        catch (IOException expected) {
            assertFalse(expected.getMessage().contains("private test"));
            assertEquals(null, expected.getCause());
            return expected;
        }
        throw new AssertionError();
    }

    private static void wiped(byte[] wire) {
        assertNotNull(wire);
        assertArrayEquals(new byte[wire.length], wire);
    }

    @Test public void explicitModeUsesNoRequestAndDecodesIndependentUtf8Vector() throws Exception {
        Binding binding = new Binding();
        View view = new View();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(view, binding)) {
            assertEquals(1, binding.catalogOpens);
            assertEquals(0, binding.opens);
            rejected(evaluator::catalog);
            evaluator.run();
            assertArrayEquals(new int[5], binding.diagnosticBorrow);
            SshConfigEvaluator.Catalog catalog = evaluator.catalog();
            assertTrue(catalog.declarationsOnly && catalog.omittedSelectors && catalog.hasMatch
                    && catalog.includesIncomplete);
            assertEquals(2, catalog.scannedFiles);
            assertEquals("a", catalog.entries.get(0).alias);
            assertEquals("别名🚀", catalog.entries.get(1).alias);
            assertEquals(2, catalog.entries.get(1).object);
            assertEquals(2, catalog.entries.get(1).line);
            assertEquals(2, catalog.deferredSites.get(0).object);
            assertEquals(7, catalog.deferredSites.get(0).line);
            wiped(binding.borrowed);
            assertEquals(0, binding.summaries);
        }
        assertTrue(view.active);
        assertEquals(1, binding.closes);
        assertEquals(1, binding.destroys);
    }

    @Test public void catalogListsAreImmutableAndEveryGetterDecodesFresh() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            SshConfigEvaluator.Catalog first = evaluator.catalog();
            try { first.entries.clear(); fail("mutable entries"); }
            catch (UnsupportedOperationException expected) { }
            try { first.deferredSites.clear(); fail("mutable sites"); }
            catch (UnsupportedOperationException expected) { }
            binding.response = frame(1, 1, new String[]{"next"}, new int[0]);
            SshConfigEvaluator.Catalog second = evaluator.catalog();
            assertEquals("a", first.entries.get(0).alias);
            assertEquals("next", second.entries.get(0).alias);
            assertFalse(second.includesIncomplete || second.hasMatch || second.omittedSelectors);
            assertEquals(2, binding.catalogs);
        }
    }

    @Test public void emptyCatalogAndExactMaximumFrameAreAccepted() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            binding.response = frame(1, 1, new String[0], new int[0]);
            assertTrue(evaluator.catalog().entries.isEmpty());
            String[] aliases = new String[256];
            int[] sites = new int[512];
            for (int i = 0; i < 256; i++) {
                aliases[i] = String.format(java.util.Locale.ROOT, "%03d", i)
                        + repeat("x", 252);
                sites[2 * i] = i % 128 + 1;
                sites[2 * i + 1] = 16384 - i;
            }
            binding.response = frame(9, 128, aliases, sites);
            assertEquals(70424, binding.response.length);
            SshConfigEvaluator.Catalog result = evaluator.catalog();
            assertEquals(256, result.entries.size());
            assertEquals(256, result.deferredSites.size());
        }
    }

    @Test public void everyTruncatedFrameAndTrailingByteAreRejectedAndWiped() throws Exception {
        Binding binding = new Binding();
        byte[] original = binding.response;
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            for (int length = 0; length < original.length; length++) {
                binding.response = Arrays.copyOf(original, length);
                rejected(evaluator::catalog);
                wiped(binding.borrowed);
            }
            binding.response = Arrays.copyOf(original, original.length + 1);
            rejected(evaluator::catalog);
            binding.response = new byte[70425];
            rejected(evaluator::catalog);
            wiped(binding.borrowed);
            binding.response = null;
            rejected(evaluator::catalog);
        }
    }

    @Test public void headerFlagsCountsAndIncompleteConsistencyAreStrict() throws Exception {
        Binding binding = new Binding();
        byte[] original = binding.response;
        int[][] bad = {{0, 0}, {4, 0}, {4, 2}, {8, 0}, {8, 14}, {8, 31}, {8, 7},
                {12, 0}, {12, 129}, {12, -1}, {16, -1}, {16, 257}, {20, -1}, {20, 257},
                {16, 0}, {20, 0}};
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            for (int[] field : bad) {
                binding.response = integer(original, field[0], field[1]);
                rejected(evaluator::catalog);
                wiped(binding.borrowed);
            }
            binding.response = frame(9, 1, new String[0], new int[0]);
            rejected(evaluator::catalog);
        }
    }

    @Test public void aliasAndDeferredSourcesRejectZeroOverflowAndDuplicates() throws Exception {
        Binding binding = new Binding();
        byte[] one = frame(9, 1, new String[]{"a"}, new int[]{1, 1});
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            for (int offset : new int[]{24, 37}) {
                for (int bad : new int[]{0, -1, 129}) {
                    binding.response = integer(one, offset, bad);
                    rejected(evaluator::catalog);
                }
            }
            for (int offset : new int[]{28, 41}) {
                for (int bad : new int[]{0, -1, 16385}) {
                    binding.response = integer(one, offset, bad);
                    rejected(evaluator::catalog);
                }
            }
            binding.response = frame(1, 1, new String[]{"same", "same"}, new int[0]);
            rejected(evaluator::catalog);
            binding.response = frame(9, 1, new String[0], new int[]{1, 1, 1, 1});
            rejected(evaluator::catalog);
            binding.response = frame(1, 1, new String[]{"Name", "name"}, new int[0]);
            assertEquals(2, evaluator.catalog().entries.size());
        }
    }

    @Test public void aliasTextRejectsEmptyOversizeControlsAndMalformedUtf8() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            for (String bad : new String[]{"", repeat("a", 256), repeat("é", 128),
                    "a\0b", "a\nb", "a\u007fb", "a\u0085b"}) {
                binding.response = frame(1, 1, new String[]{bad}, new int[0]);
                rejected(evaluator::catalog);
                wiped(binding.borrowed);
            }
            byte[][] malformed = {{(byte)0xff}, {(byte)0xc0, (byte)0x80},
                    {(byte)0xed, (byte)0xa0, (byte)0x80}, {(byte)0xf0, (byte)0x9f}};
            for (byte[] text : malformed) {
                byte[] prefix = frame(1, 1, new String[]{"x"}, new int[0]);
                binding.response = Arrays.copyOf(prefix, 36 + text.length);
                ByteBuffer.wrap(binding.response).putInt(32, text.length);
                System.arraycopy(text, 0, binding.response, 36, text.length);
                rejected(evaluator::catalog);
            }
            binding.response = integer(frame(1, 1, new String[]{"a"}, new int[0]), 32, -1);
            rejected(evaluator::catalog);
            binding.response = frame(1, 1, new String[]{repeat("é", 127) + "x"}, new int[0]);
            assertEquals(255, evaluator.catalog().entries.get(0).alias.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    @Test public void oldBindingCatalogMethodsFailClosedWithoutOrdinaryFallback() throws Exception {
        OldBinding binding = new OldBinding();
        rejected(() -> SshConfigEvaluator.openCatalog(new View(), binding));
        rejected(() -> binding.catalog(52));
        assertEquals(0, binding.opens + binding.runs + binding.summaries + binding.closes);
    }

    @Test public void modeGettersNeverCallTheOtherNativeGetter() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            rejected(evaluator::summary);
            assertEquals(0, binding.summaries);
        }
        SshConfigEvaluator.Request request = new SshConfigEvaluator.Request(
                "actual", "", "", "local", "local.example", "", "", 0, 1000);
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(new View(), request, binding)) {
            evaluator.run();
            rejected(evaluator::catalog);
            assertEquals(0, binding.catalogs);
        }
    }

    @Test public void badInputsAndUnavailableViewFailBeforeNativeAdmission() throws Exception {
        Binding binding = new Binding();
        rejected(() -> SshConfigEvaluator.openCatalog((SshPublicFileLease)null));
        rejected(() -> SshConfigEvaluator.openCatalog(null, binding));
        rejected(() -> SshConfigEvaluator.openCatalog(new View(), null));
        View view = new View();
        view.active = false;
        rejected(() -> SshConfigEvaluator.openCatalog(view, binding));
        view.active = true;
        view.failToken = true;
        rejected(() -> SshConfigEvaluator.openCatalog(view, binding));
        assertEquals(0, binding.catalogOpens);
        view.failToken = false;
        binding.token = 0;
        rejected(() -> SshConfigEvaluator.openCatalog(view, binding));
    }

    @Test public void positiveHandleIsRetainedIfLeaseRevokesDuringOpen() throws Exception {
        Binding binding = new Binding();
        View view = new View();
        binding.onOpen = () -> view.active = false;
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(view, binding)) {
            binding.diagnostic = new int[]{2, 0, 0, 0, 125};
            SshConfigEvaluator.EvaluationException failure = (SshConfigEvaluator.EvaluationException)
                    rejected(evaluator::run);
            assertEquals(125, failure.diagnostic.errorNumber);
        }
        assertEquals(1, binding.closes);
        assertEquals(1, binding.destroys);
    }

    @Test public void runIsOneShotAndLocatedFailureKeepsSafeTypedDiagnostic() throws Exception {
        Binding binding = new Binding();
        binding.diagnostic = new int[]{1, 3, 128, 16385, 7};
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            SshConfigEvaluator.EvaluationException failure = (SshConfigEvaluator.EvaluationException)
                    rejected(evaluator::run);
            assertEquals(3, failure.diagnostic.reason);
            assertEquals(128, failure.diagnostic.object);
            assertEquals(16385, failure.diagnostic.line);
            assertArrayEquals(new int[5], binding.diagnosticBorrow);
            rejected(evaluator::run);
            rejected(evaluator::catalog);
            assertEquals(1, binding.runs);
            assertEquals(0, binding.catalogs);
        }
    }

    @Test public void revokeAndCancelDenyGetterWithoutCachedAuthority() throws Exception {
        Binding binding = new Binding();
        View view = new View();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(view, binding)) {
            evaluator.run();
            SshConfigEvaluator.Catalog historical = evaluator.catalog();
            view.active = false;
            rejected(evaluator::catalog);
            assertEquals(1, binding.catalogs);
            assertEquals("a", historical.entries.get(0).alias);
        }
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            evaluator.cancel();
            rejected(evaluator::catalog);
        }
    }

    @Test public void getterRechecksLeaseAfterNativeReturnsAndWipesResult() throws Exception {
        Binding binding = new Binding();
        View view = new View();
        binding.onCatalog = () -> view.active = false;
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(view, binding)) {
            evaluator.run();
            rejected(evaluator::catalog);
            wiped(binding.borrowed);
        }
    }

    @Test public void ownerOperationsRejectOtherThreadButCancelWorks() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    rejected(evaluator::run);
                    rejected(evaluator::catalog);
                    rejected(evaluator::summary);
                    rejected(evaluator::close);
                    evaluator.cancel();
                } catch (Throwable problem) { failure.set(problem); }
            });
            other.start();
            other.join(2000);
            assertFalse(other.isAlive());
            assertEquals(null, failure.get());
            assertEquals(1, binding.cancels);
            assertEquals(0, binding.closes);
        }
    }

    @Test public void crossThreadCancelDoesNotWaitForBlockedRun() throws Exception {
        Binding binding = new Binding();
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        AtomicReference<SshConfigEvaluator> current = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        binding.onRun = () -> {
            entered.countDown();
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        };
        Thread cancel = new Thread(() -> {
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                current.get().cancel();
            } catch (Throwable problem) { failure.set(problem); }
            finally { cancelled.countDown(); }
        });
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            current.set(evaluator);
            cancel.start();
            rejected(evaluator::run);
            cancel.join(2000);
            assertFalse(cancel.isAlive());
            assertEquals(null, failure.get());
            assertEquals(1, binding.cancels);
            rejected(evaluator::catalog);
        }
    }

    @Test public void reentryDuringGetterAndOpenRejectsBeforeNativeRecursion() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            binding.onCatalog = () -> {
                rejected(evaluator::catalog);
                rejected(evaluator::close);
                rejected(() -> SshConfigEvaluator.openCatalog(new View(), binding));
            };
            evaluator.catalog();
            assertEquals(1, binding.catalogs);
            assertEquals(1, binding.catalogOpens);
        }
    }

    @Test public void errorsClearEntryGuardAndRetainCloseableOwner() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            evaluator.run();
            binding.onCatalog = () -> { throw new AssertionError("synthetic"); };
            try { evaluator.catalog(); fail("expected Error"); }
            catch (AssertionError expected) { assertEquals("synthetic", expected.getMessage()); }
            binding.onCatalog = null;
            assertEquals(2, evaluator.catalog().entries.size());
        }
        binding.onRun = () -> { throw new AssertionError("synthetic run"); };
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding)) {
            try { evaluator.run(); fail("expected Error"); }
            catch (AssertionError expected) { assertEquals("synthetic run", expected.getMessage()); }
            rejected(evaluator::run);
        }
    }

    @Test public void closeAndDestroyFailuresRetainTheirIndependentRetryPoints() throws Exception {
        Binding binding = new Binding();
        SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(new View(), binding);
        evaluator.run();
        binding.failClose = true;
        rejected(evaluator::close);
        assertEquals(0, binding.destroys);
        binding.failClose = false;
        binding.failDestroy = true;
        rejected(evaluator::close);
        assertEquals(2, binding.closes);
        rejected(evaluator::catalog);
        binding.failDestroy = false;
        evaluator.close();
        evaluator.close();
        assertEquals(2, binding.closes);
        assertEquals(2, binding.destroys);
        rejected(evaluator::run);
        rejected(evaluator::catalog);
    }

    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }
}
