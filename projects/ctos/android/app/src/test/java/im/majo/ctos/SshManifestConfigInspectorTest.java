package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Managed ownership and independently assembled wire vectors; no parser/runtime claims. */
public final class SshManifestConfigInspectorTest {
    private interface Action { void run() throws Exception; }
    private interface Hook { void at(String stage) throws IOException; }
    private static final class Clock implements SshManifestConfigInspector.Clock {
        long time = 1000;
        @Override public long now() { return time; }
    }
    private static final class Binding implements SshConfigEvaluator.NativeBinding {
        int reserves, loads, runs, cancels, closes, destroys, root;
        int closeFailures, destroyFailures;
        int[] usage = {1, 1, 1};
        byte[] response;
        Hook hook = stage -> { };
        final List<byte[]> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        final List<int[]> usages = new ArrayList<>(), allowances = new ArrayList<>();
        final List<Integer> roots = new ArrayList<>();
        final List<String> loadedBodies = new ArrayList<>();
        final List<Long> deadlines = new ArrayList<>();
        @Override public long open(long view, byte[] request) { throw new AssertionError("No live view"); }
        @Override public long reserveDetached(boolean catalog) throws IOException {
            assertTrue(catalog);
            reserves++;
            hook.at("reserve");
            return reserves;
        }
        @Override public void loadDetached(long token, byte[] wire, int root, long deadline,
                byte[] request) throws IOException {
            assertNull(request);
            this.root = root;
            loads++;
            inputs.add(wire);
            roots.add(root);
            deadlines.add(deadline);
            ByteBuffer input = ByteBuffer.wrap(wire);
            assertEquals(0x43534449, input.getInt());
            assertEquals(1, input.getInt());
            int count = input.getInt();
            allowances.add(new int[]{input.getInt(), input.getInt(), input.getInt()});
            for (int i = 0; i < count; i++) {
                int type = input.getInt();
                int pathLength = input.getInt();
                input.position(input.position() + pathLength);
                int bodyLength = input.getInt();
                byte[] body = new byte[bodyLength];
                input.get(body);
                if (type != 1) assertEquals(0, bodyLength);
                else loadedBodies.add(new String(body, StandardCharsets.UTF_8));
            }
            assertFalse(input.hasRemaining());
            hook.at("load");
        }
        @Override public int[] run(long token) throws IOException {
            runs++;
            hook.at("run");
            return new int[5];
        }
        @Override public byte[] summary(long token) { throw new AssertionError("No effective request"); }
        @Override public byte[] catalog(long token) throws IOException {
            hook.at("catalog");
            byte[] wire = response == null ? catalogFrame(root + 1, 1, 1, 0, 6) : response.clone();
            outputs.add(wire);
            return wire;
        }
        @Override public int[] usage(long token) throws IOException {
            hook.at("usage");
            int[] values = usage == null ? null : usage.clone();
            if (values != null) usages.add(values);
            return values;
        }
        @Override public void cancel(long token) throws IOException { cancels++; hook.at("cancel"); }
        @Override public void close(long token) throws IOException {
            closes++;
            hook.at("close");
            if (closeFailures-- > 0) throw new IOException("cleanup");
        }
        @Override public void destroy(long token) throws IOException {
            destroys++;
            hook.at("destroy");
            if (destroyFailures-- > 0) throw new IOException("cleanup");
        }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id, byte[] body) {
        return new VaultManifest.FileObject(type, id, "ssh/" + id, id, body, VaultCollections.list());
    }
    private static VaultManifest.FileObject config(String id) {
        return file(VaultManifest.FileType.CONFIG, id, bytes("Host " + id + "\n"));
    }
    private static VaultManifest manifest(VaultManifest.FileObject... files) throws Exception {
        return new VaultManifest(VaultCollections.list(), Arrays.asList(files));
    }
    private static byte[] catalogFrame(int object, int scanned, int aliases, int deferred, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(24 + aliases * (12 + length) + deferred * 8);
        buffer.putInt(0x43534344).putInt(1).putInt(deferred == 0 ? 1 : 9)
                .putInt(scanned).putInt(aliases).putInt(deferred);
        for (int i = 0; i < aliases; i++) {
            byte[] alias = new byte[length];
            Arrays.fill(alias, (byte)'a');
            byte[] number = bytes(Integer.toString(i));
            System.arraycopy(number, 0, alias, 0, number.length);
            buffer.putInt(object).putInt(i + 1).putInt(length).put(alias);
        }
        for (int i = 0; i < deferred; i++) buffer.putInt(object).putInt(i + 1);
        return buffer.array();
    }
    private static void rejected(Action action) throws Exception {
        try { action.run(); fail("expected rejection"); }
        catch (IOException expected) { }
    }
    private static void wiped(Binding binding) {
        for (byte[] wire : binding.inputs) assertArrayEquals(new byte[wire.length], wire);
        for (byte[] wire : binding.outputs) assertArrayEquals(new byte[wire.length], wire);
        for (int[] usage : binding.usages) assertArrayEquals(new int[usage.length], usage);
    }
    private static SshManifestConfigInspector.Report inspect(VaultManifest manifest, Binding binding)
            throws IOException {
        try (SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
            return job.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation());
        }
    }

    @Test public void suppliedCandidateOnlyConfigBodiesAllOrphanRootsAndOriginalOrdinals() throws Exception {
        Binding binding = new Binding();
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "password", "target:one", "", "", 0, 0, bytes("password-secret"));
        VaultManifest.Entry phrase = new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE,
                "phrase", "key:key", "", "", 0, 0, bytes("phrase-secret"));
        try (VaultManifest source = manifest(config("source"));
                VaultManifest candidate = new VaultManifest(Arrays.asList(password, phrase), Arrays.asList(
                        file(VaultManifest.FileType.PRIVATE_KEY, "key", bytes("private-secret")),
                        config("candidate"), file(VaultManifest.FileType.KNOWN_HOSTS, "hosts", bytes("host-secret")),
                        config("orphan")))) {
            SshManifestConfigInspector.Report report = inspect(candidate, binding);
            assertEquals(Arrays.asList(1, 3), binding.roots);
            assertEquals(Arrays.asList("Host candidate\n", "Host orphan\n", "Host candidate\n", "Host orphan\n"), binding.loadedBodies);
            assertEquals(Arrays.asList(31_000L, 31_000L), binding.deadlines);
            assertEquals(2, report.configFiles);
            assertEquals(2, report.processedRootCount);
            assertTrue(report.declarationsOnly);
            assertFalse(report.admissionComplete);
            assertEquals(2, report.roots.get(0).object);
            assertEquals(4, report.roots.get(1).catalog.entries.get(0).object);
            assertEquals("ssh/orphan", report.roots.get(1).path);
            assertEquals(2, report.usage.opens);
            assertEquals(2, binding.closes);
            assertEquals(2, binding.destroys);
            try { report.roots.clear(); fail(); } catch (UnsupportedOperationException expected) { }
            assertArrayEquals(bytes("private-secret"), candidate.files.get(0).content);
            assertArrayEquals(bytes("password-secret"), candidate.entries.get(0).secret);
            assertArrayEquals(bytes("Host source\n"), source.files.get(0).content);
            wiped(binding);
        }
    }

    @Test public void configCopiesAreFrozenBeforeFirstNativeCallAndCallerIsNotClosed() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(config("one"), config("two"))) {
            binding.hook = stage -> {
                if (stage.equals("reserve") && binding.reserves == 1)
                    Arrays.fill(manifest.files.get(1).content, (byte)'x');
            };
            inspect(manifest, binding);
            assertEquals("Host two\n", binding.loadedBodies.get(3));
            assertArrayEquals(bytes("Host one\n"), manifest.files.get(0).content);
            wiped(binding);
        }
    }

    @Test public void emptyAndPureTotpAndNoConfigNeverAcquireNative() throws Exception {
        Binding binding = new Binding();
        VaultManifest.Entry otp = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "otp", "otp",
                "", "SHA1", 6, 30, bytes("0123456789"));
        try (VaultManifest empty = VaultManifest.empty();
                VaultManifest pure = new VaultManifest(Arrays.asList(otp), VaultCollections.list());
                VaultManifest keys = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "public", bytes("public")))) {
            for (VaultManifest input : Arrays.asList(empty, pure, keys)) {
                SshManifestConfigInspector.Report report = inspect(input, binding);
                assertEquals(0, report.processedRootCount);
                assertEquals(0, report.catalogBytes);
                assertEquals(0, report.usage.opens);
                assertTrue(report.declarationsOnly);
                assertFalse(report.admissionComplete);
            }
            assertEquals(0, binding.reserves);
            assertArrayEquals(bytes("0123456789"), pure.entries.get(0).secret);
        }
    }

    @Test public void originalWorkBudgetIsCarriedAndExactAggregateIsAllowed() throws Exception {
        Binding binding = new Binding();
        binding.usage = new int[]{1023, 64 * 1024 * 1024 - 2, 16383};
        binding.hook = stage -> {
            if (stage.equals("usage") && binding.root == 1) binding.usage = new int[]{1, 2, 1};
        };
        try (VaultManifest manifest = manifest(config("one"), config("two"))) {
            SshManifestConfigInspector.Report report = inspect(manifest, binding);
            assertArrayEquals(new int[]{1024, 64 * 1024 * 1024, 16384}, binding.allowances.get(0));
            assertArrayEquals(new int[]{1, 2, 1}, binding.allowances.get(1));
            assertEquals(1024, report.usage.opens);
            assertEquals(64 * 1024 * 1024, report.usage.readBytes);
            assertEquals(16384, report.usage.lines);
            wiped(binding);
        }
    }

    @Test public void excessOrMalformedUsageRejectsWholeReportAndWipesReturnedArrays() throws Exception {
        int[][] invalid = {null, {}, {1, 1}, {0, 0, 0}, {-1, 0, 0}, {1025, 0, 0},
                {1, -1, 0}, {1, 67108865, 0}, {1, 0, -1}, {1, 0, 16385}};
        for (int[] values : invalid) {
            Binding binding = new Binding();
            binding.usage = values;
            try (VaultManifest manifest = manifest(config("one"))) {
                rejected(() -> inspect(manifest, binding));
                assertEquals(1, binding.destroys);
                wiped(binding);
            }
        }
        for (int dimension = 0; dimension < 3; dimension++) {
            Binding binding = new Binding();
            binding.usage = new int[]{1023, 67108863, 16383};
            final int selected = dimension;
            binding.hook = stage -> {
                if (stage.equals("usage") && binding.root == 1) {
                    binding.usage = new int[]{1, 1, 1};
                    binding.usage[selected] = 2;
                }
            };
            try (VaultManifest manifest = manifest(config("one"), config("two"))) {
                rejected(() -> inspect(manifest, binding));
                assertEquals(2, binding.destroys);
                wiped(binding);
            }
        }
    }

    @Test public void catalogSourceMustBeActualConfigAndScannedCountCannotExceedConfigCount() throws Exception {
        for (int[] fields : new int[][]{{1, 1}, {3, 1}, {2, 2}, {2, 129}}) {
            Binding binding = new Binding();
            binding.response = catalogFrame(fields[0], fields[1], 1, 0, 6);
            try (VaultManifest manifest = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "key", bytes("key")), config("one"))) {
                rejected(() -> inspect(manifest, binding));
                wiped(binding);
            }
        }
        Binding binding = new Binding();
        binding.response = catalogFrame(1, 1, 0, 1, 6);
        try (VaultManifest manifest = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "key", bytes("key")), config("one"))) {
            rejected(() -> inspect(manifest, binding));
            wiped(binding);
        }
    }

    @Test public void malformedCatalogFramesFailClosedWithoutTruncation() throws Exception {
        byte[] valid = catalogFrame(1, 1, 1, 0, 6);
        List<byte[]> frames = new ArrayList<>();
        frames.add(new byte[0]);
        frames.add(Arrays.copyOf(valid, valid.length - 1));
        frames.add(Arrays.copyOf(valid, valid.length + 1));
        for (int offset : new int[]{0, 4, 8, 12, 16, 20, 24, 28, 32}) {
            byte[] wire = valid.clone();
            ByteBuffer.wrap(wire).putInt(offset, -1);
            frames.add(wire);
        }
        for (byte[] wire : frames) {
            Binding binding = new Binding();
            binding.response = wire;
            try (VaultManifest manifest = manifest(config("one"))) {
                rejected(() -> inspect(manifest, binding));
                assertEquals(1, binding.destroys);
                wiped(binding);
            }
        }
    }

    private static VaultManifest many(int count, int size) throws Exception {
        List<VaultManifest.FileObject> files = new ArrayList<>();
        for (int i = 0; i < count; i++) files.add(file(VaultManifest.FileType.CONFIG, "c" + i, new byte[size]));
        return new VaultManifest(VaultCollections.list(), files);
    }

    @Test public void aggregateCatalogBytesAndRowsHaveIndependentStrictLimits() throws Exception {
        Binding bytes = new Binding();
        bytes.response = catalogFrame(1, 1, 256, 0, 255);
        try (VaultManifest manifest = many(15, 0)) {
            assertEquals(15 * bytes.response.length, inspect(manifest, bytes).catalogBytes);
            wiped(bytes);
        }
        Binding tooManyBytes = new Binding();
        tooManyBytes.response = bytes.response;
        try (VaultManifest manifest = many(16, 0)) {
            rejected(() -> inspect(manifest, tooManyBytes));
            assertEquals(16, tooManyBytes.runs);
            wiped(tooManyBytes);
        }
        Binding rows = new Binding();
        rows.response = catalogFrame(1, 1, 256, 256, 6);
        try (VaultManifest manifest = many(8, 0)) { assertEquals(8, inspect(manifest, rows).processedRootCount); }
        Binding tooManyRows = new Binding();
        tooManyRows.response = rows.response;
        try (VaultManifest manifest = many(9, 0)) {
            rejected(() -> inspect(manifest, tooManyRows));
            assertEquals(9, tooManyRows.runs);
            wiped(tooManyRows);
        }
    }

    @Test public void copyAmplificationIsRejectedBeforeNativeAcquisition() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = many(9, 900_000)) {
            rejected(() -> inspect(manifest, binding));
            assertEquals(0, binding.reserves);
            assertEquals(900_000, manifest.files.get(0).content.length);
        }
        try (VaultManifest manifest = many(8, 1024 * 1024)) {
            // The exact64MiB repeated-body allowance passes Java preflight.
            assertEquals(8, inspect(manifest, binding).processedRootCount);
            wiped(binding);
        }
    }

    @Test public void originalDeadlineIsCheckedAtEveryNativeStageAndCleanup() throws Exception {
        for (String selected : Arrays.asList("reserve", "load", "run", "usage", "catalog", "close", "destroy")) {
            Clock clock = new Clock();
            Binding binding = new Binding();
            binding.hook = stage -> { if (stage.equals(selected)) clock.time = 31_000; };
            try (VaultManifest manifest = manifest(config("one"));
                    SshManifestConfigInspector job = new SshManifestConfigInspector(clock, binding)) {
                rejected(() -> job.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()));
                assertEquals(1, binding.destroys);
                assertFalse(job.hasPendingCleanup());
                wiped(binding);
            }
        }
    }

    @Test public void deadlineAdmissionRejectsExpiredAndRenewedBudget() throws Exception {
        for (long deadline : new long[]{0, 1000, 61_001, Long.MAX_VALUE}) {
            Binding binding = new Binding();
            try (VaultManifest manifest = manifest(config("one"));
                    SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
                rejected(() -> job.inspect(manifest, deadline, new SshManifestConfigInspector.Cancellation()));
                assertEquals(0, binding.reserves);
            }
        }
    }

    @Test public void cancellationBeforeAndDuringEveryStageRejectsAndRetainsNoResult() throws Exception {
        for (String selected : Arrays.asList("before", "reserve", "load", "run", "usage", "catalog", "close", "destroy")) {
            Binding binding = new Binding();
            SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
            if (selected.equals("before")) cancellation.cancel();
            binding.hook = stage -> { if (stage.equals(selected)) cancellation.cancel(); };
            try (VaultManifest manifest = manifest(config("one"));
                    SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
                rejected(() -> job.inspect(manifest, 31_000, cancellation));
                assertEquals(selected.equals("before") ? 0 : 1, binding.destroys);
                assertFalse(job.hasPendingCleanup());
                if (selected.equals("reserve")) {
                    assertEquals(1, binding.cancels);
                    assertEquals(0, binding.loads);
                }
                wiped(binding);
            }
        }
    }

    @Test public void pausedNativeWorkNeverHoldsCancellationMonitor() throws Exception {
        for (String selected : Arrays.asList("reserve", "load", "run", "catalog")) {
            Binding binding = new Binding();
            CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
            binding.hook = stage -> {
                if (!stage.equals(selected)) return;
                entered.countDown();
                try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("pause timeout"); }
                catch (InterruptedException failure) { throw new IOException("interrupted"); }
            };
            SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
            AtomicReference<Throwable> result = new AtomicReference<>();
            try (VaultManifest manifest = manifest(config("one"))) {
                Thread worker = new Thread(() -> {
                    try (SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
                        job.inspect(manifest, 31_000, cancellation);
                        result.set(new AssertionError("cancelled success"));
                    } catch (IOException expected) { }
                    catch (Throwable failure) { result.set(failure); }
                });
                worker.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Thread canceller = new Thread(cancellation::cancel);
                canceller.start();
                canceller.join(1000);
                boolean prompt = !canceller.isAlive();
                resume.countDown();
                worker.join(5000);
                canceller.join(5000);
                assertTrue("cancel must return while native is paused", prompt);
                assertFalse(worker.isAlive());
                assertNull(result.get());
                assertEquals(1, binding.destroys);
                wiped(binding);
            }
        }
    }

    @Test public void oneShotJobAndCancellationCannotBeReused() throws Exception {
        Binding binding = new Binding();
        SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
        try (VaultManifest manifest = manifest(config("one"));
                SshManifestConfigInspector first = new SshManifestConfigInspector(new Clock(), binding);
                SshManifestConfigInspector second = new SshManifestConfigInspector(new Clock(), binding)) {
            first.inspect(manifest, 31_000, cancellation);
            rejected(() -> first.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()));
            rejected(() -> second.inspect(manifest, 31_000, cancellation));
            assertEquals(1, binding.reserves);
        }
    }

    @Test public void crossInstanceSameThreadReentryIsRejectedAndGuardReleasesAfterError() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(config("one"));
                SshManifestConfigInspector first = new SshManifestConfigInspector(new Clock(), binding);
                SshManifestConfigInspector nested = new SshManifestConfigInspector(new Clock(), new Binding())) {
            binding.hook = stage -> {
                if (!stage.equals("run")) return;
                try { nested.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
                catch (IOException expected) { }
                throw new AssertionError("native error");
            };
            try { first.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
            catch (AssertionError expected) { assertEquals("native error", expected.getMessage()); }
            assertEquals(1, binding.destroys);
            assertEquals(1, nested.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()).processedRootCount);
            wiped(binding);
        }
    }

    @Test public void loadAndRunErrorsStillWipeAndCloseTheAlreadyRetainedOwner() throws Exception {
        for (String selected : Arrays.asList("load", "run", "usage", "catalog")) {
            for (int kind = 0; kind < 3; kind++) {
                final int failureKind = kind;
                Binding binding = new Binding();
                binding.hook = stage -> {
                    if (!stage.equals(selected)) return;
                    if (failureKind == 0) throw new IOException("native input detail");
                    if (failureKind == 1) throw new UnsatisfiedLinkError("missing");
                    throw new AssertionError("native error");
                };
                try (VaultManifest manifest = manifest(config("one"));
                        SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
                    try { job.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
                    catch (IOException expected) { assertFalse(expected.getMessage().contains("input detail")); }
                    catch (AssertionError expected) {
                        assertEquals(2, failureKind);
                        assertEquals("native error", expected.getMessage());
                    }
                    assertFalse(job.hasPendingCleanup());
                    assertEquals(1, binding.destroys);
                    wiped(binding);
                }
            }
        }
    }

    @Test public void closeOrDestroyFailureKeepsExactOwnerForExplicitRetryEvenAfterExpiryAndCancel() throws Exception {
        for (boolean destroy : new boolean[]{false, true}) {
            Clock clock = new Clock();
            Binding binding = new Binding();
            if (destroy) binding.destroyFailures = 1; else binding.closeFailures = 1;
            SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
            try (VaultManifest manifest = manifest(config("one"))) {
                SshManifestConfigInspector job = new SshManifestConfigInspector(clock, binding);
                rejected(() -> job.inspect(manifest, 31_000, cancellation));
                assertTrue(job.hasPendingCleanup());
                assertEquals(1, binding.closes);
                assertEquals(destroy ? 1 : 0, binding.destroys);
                wiped(binding);
                clock.time = 99_000;
                cancellation.cancel();
                job.close();
                assertFalse(job.hasPendingCleanup());
                assertEquals(destroy ? 1 : 2, binding.closes);
                assertEquals(destroy ? 2 : 1, binding.destroys);
                job.close();
            }
        }
    }

    @Test public void primaryErrorSurvivesFailedCleanupAndOwnerRemainsReviewable() throws Exception {
        Binding binding = new Binding();
        binding.closeFailures = 1;
        binding.hook = stage -> { if (stage.equals("load")) throw new AssertionError("primary"); };
        try (VaultManifest manifest = manifest(config("one"))) {
            SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding);
            try { job.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
            catch (AssertionError expected) {
                assertEquals("primary", expected.getMessage());
                assertEquals(1, expected.getSuppressed().length);
            }
            assertTrue(job.hasPendingCleanup());
            wiped(binding);
            job.close();
            assertFalse(job.hasPendingCleanup());
        }
    }

    @Test public void ownerThreadRequiredForInspectAndCleanup() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(config("one"));
                SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    rejected(() -> job.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()));
                    rejected(job::close);
                } catch (Throwable failure) { result.set(failure); }
            });
            other.start(); other.join(5000);
            assertFalse(other.isAlive());
            assertNull(result.get());
            assertEquals(1, job.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()).processedRootCount);
        }
    }

    @Test public void arbitraryManagedRelativePathsAndExactUtf8BoundaryRemainSupported() throws Exception {
        StringBuilder longPath = new StringBuilder();
        for (int i = 0; i < 256; i++) longPath.append('\u00e9');
        Binding binding = new Binding();
        VaultManifest.FileObject plain = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "plain", "config", "plain", bytes("Host plain\n"), VaultCollections.list());
        VaultManifest.FileObject utf8 = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "utf8", longPath.toString(), "utf8", bytes("Host utf8\n"), VaultCollections.list());
        try (VaultManifest manifest = manifest(plain, utf8)) {
            SshManifestConfigInspector.Report report = inspect(manifest, binding);
            assertEquals("config", report.roots.get(0).path);
            assertEquals(longPath.toString(), report.roots.get(1).path);
            assertEquals(2, binding.loads);
            wiped(binding);
        }
    }

    @Test public void malformedUtf16IsoControlAndDirectoryCollisionRejectBeforeReserve() throws Exception {
        for (String path : Arrays.asList("config\ud800", "config\udc00", "config\u0085")) {
            Binding binding = new Binding();
            VaultManifest.FileObject file = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                    "bad", path, "bad", bytes("Host bad\n"), VaultCollections.list());
            try (VaultManifest manifest = manifest(file)) {
                rejected(() -> inspect(manifest, binding));
                assertEquals(0, binding.reserves);
                assertArrayEquals(bytes("Host bad\n"), file.content);
            }
        }
        Binding binding = new Binding();
        VaultManifest.FileObject parent = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "parent", "relative", "parent", new byte[0], VaultCollections.list());
        VaultManifest.FileObject child = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "child", "relative/child", "child", new byte[0], VaultCollections.list());
        try (VaultManifest manifest = manifest(parent, child)) {
            rejected(() -> inspect(manifest, binding));
            assertEquals(0, binding.reserves);
        }
    }

    @Test public void zeroConfigStillChecksCancellationAndDeadlineAfterJavaCleanup() throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            Binding binding = new Binding();
            SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
            int[] calls = {0};
            SshManifestConfigInspector.Clock clock = () -> {
                if (++calls[0] == 2) {
                    if (cancel) cancellation.cancel();
                    else return 31_000;
                }
                return 1000;
            };
            try (VaultManifest manifest = VaultManifest.empty();
                    SshManifestConfigInspector job = new SshManifestConfigInspector(clock, binding)) {
                rejected(() -> job.inspect(manifest, 31_000, cancellation));
                assertEquals(0, binding.reserves);
            }
        }
    }

    @Test public void cancellationErrorPropagatesAfterMarkingJobAndStillAllowsOwnerCleanup() throws Exception {
        Binding binding = new Binding();
        SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
        AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
        binding.hook = stage -> {
            if (stage.equals("cancel")) throw new AssertionError("cancel error");
            if (!stage.equals("run")) return;
            Thread canceller = new Thread(() -> {
                try { cancellation.cancel(); }
                catch (Throwable failure) { cancelFailure.set(failure); }
            });
            canceller.start();
            try { canceller.join(5000); }
            catch (InterruptedException failure) { throw new IOException("interrupted"); }
            assertFalse(canceller.isAlive());
        };
        try (VaultManifest manifest = manifest(config("one"));
                SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
            rejected(() -> job.inspect(manifest, 31_000, cancellation));
            assertTrue(cancelFailure.get() instanceof AssertionError);
            assertEquals("cancel error", cancelFailure.get().getMessage());
            assertFalse(job.hasPendingCleanup());
            assertEquals(1, binding.destroys);
            wiped(binding);
        }
    }

    @Test public void cleanupErrorAlsoKeepsOwnerForRetry() throws Exception {
        Binding binding = new Binding();
        binding.hook = stage -> {
            if (stage.equals("close") && binding.closes == 1) throw new AssertionError("close error");
        };
        try (VaultManifest manifest = manifest(config("one"))) {
            SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding);
            try { job.inspect(manifest, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
            catch (AssertionError expected) { assertEquals("close error", expected.getMessage()); }
            assertTrue(job.hasPendingCleanup());
            assertEquals(0, binding.destroys);
            wiped(binding);
            job.close();
            assertFalse(job.hasPendingCleanup());
            assertEquals(1, binding.destroys);
        }
    }
}
