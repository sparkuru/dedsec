package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Supplied-candidate ownership and independent CSMI vectors; no native parser claims. */
public final class SshManifestInspectorTest {
    private interface Action { void run() throws Exception; }
    private static final class Clock implements SshManifestInspector.Clock {
        long time = 1000;
        @Override public long now() { return time; }
    }

    private static final class Binding implements SshManifestInspector.NativeBinding {
        final List<byte[]> bodies = new ArrayList<>(), passphrases = new ArrayList<>();
        final List<Long> deadlines = new ArrayList<>();
        final List<Integer> types = new ArrayList<>();
        byte[] response;
        Action during;
        @Override public byte[] inspect(int type, byte[] body, byte[] passphrase, long deadline)
                throws IOException {
            bodies.add(body);
            passphrases.add(passphrase);
            deadlines.add(deadline);
            types.add(type);
            if (during != null) {
                try { during.run(); }
                catch (IOException | RuntimeException | Error error) { throw error; }
                catch (Exception error) { throw new AssertionError(error); }
            }
            return response == null ? parsed(type) : response;
        }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id,
            String identity, String body) {
        return new VaultManifest.FileObject(type, id, "ssh/" + id, identity, bytes(body),
                VaultCollections.list());
    }

    private static VaultManifest.Entry phrase(String id, String identity, String value) {
        return new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE, id,
                "key:" + identity, "", "", 0, 0, bytes(value));
    }

    private static VaultManifest.Entry password(String id, String identity, String value) {
        return new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, id,
                identity, "", "", 0, 0, bytes(value));
    }

    private static VaultManifest.Entry totp() {
        return new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "otp", "otp", "", "SHA1",
                6, 30, bytes("0123456789"));
    }

    private static VaultManifest manifest(VaultManifest.FileObject... files) throws Exception {
        return new VaultManifest(VaultCollections.list(), Arrays.asList(files));
    }

    private static byte[] frame(int status, int kind, int flags, int records, int line,
            boolean digest) {
        ByteBuffer wire = ByteBuffer.allocate(60);
        wire.putInt(0x43534d49).putInt(1).putInt(status).putInt(kind).putInt(flags)
                .putInt(records).putInt(line);
        if (digest) wire.put((byte)0x80);
        return wire.array();
    }

    private static byte[] parsed(int type) {
        return type == 5 ? frame(0, 0, 4, 0, 0, false)
                : frame(0, 1, type == 4 ? 6 : 4, 1, 0, true);
    }

    private static byte[] integer(byte[] original, int offset, int value) {
        byte[] wire = original.clone();
        ByteBuffer.wrap(wire).putInt(offset, value);
        return wire;
    }

    private static void rejected(Action action) throws Exception {
        try { action.run(); fail("expected inspection rejection"); }
        catch (IOException expected) { }
    }

    private static void wiped(byte[] value) {
        assertArrayEquals(new byte[value.length], value);
    }

    private static SshManifestInspector.Report inspect(VaultManifest manifest, Binding binding)
            throws IOException {
        return new SshManifestInspector(new Clock(), binding).inspect(manifest, 31_000,
                new SshManifestInspector.Cancellation());
    }

    @Test public void differentSuppliedCandidatesWithSameMetadataInspectTheirActualBytes() throws Exception {
        try (VaultManifest source = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "key", "same", "source"));
                VaultManifest candidate = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "key", "same", "candidate"))) {
            List<String> observed = new ArrayList<>();
            SshManifestInspector inspector = new SshManifestInspector(new Clock(),
                    (type, body, passphrase, deadline) -> {
                        observed.add(new String(body, StandardCharsets.UTF_8));
                        byte[] wire = parsed(type);
                        try {
                            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
                            System.arraycopy(digest, 0, wire, 28, 32);
                        } catch (java.security.NoSuchAlgorithmException failure) {
                            throw new AssertionError(failure);
                        }
                        return wire;
                    });
            SshManifestInspector.Report a = inspector.inspect(source, 31_000,
                    new SshManifestInspector.Cancellation());
            SshManifestInspector.Report b = inspector.inspect(candidate, 31_000,
                    new SshManifestInspector.Cancellation());
            assertEquals(Arrays.asList("source", "candidate"), observed);
            assertFalse(a.entries.get(0).publicBlobDigest.equals(b.entries.get(0).publicBlobDigest));
            assertArrayEquals(bytes("source"), source.files.get(0).content);
            assertArrayEquals(bytes("candidate"), candidate.files.get(0).content);
        }
    }

    @Test public void everyFileIncludingOrphansHasAnOrderedExplicitOutcome() throws Exception {
        VaultManifest.FileType[] types = VaultManifest.FileType.values();
        VaultManifest.FileObject[] files = new VaultManifest.FileObject[types.length];
        for (int i = 0; i < types.length; i++) files[i] = file(types[i], "f" + i, "i" + i, "body" + i);
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(files)) {
            SshManifestInspector.Report report = inspect(manifest, binding);
            assertTrue(report.contentOnly);
            assertFalse(report.admissionComplete);
            assertEquals(5, report.entries.size());
            assertEquals(Arrays.asList(2, 3, 4, 5), binding.types);
            for (int i = 0; i < files.length; i++) {
                SshManifestInspector.Entry entry = report.entries.get(i);
                assertEquals(files[i].type, entry.type);
                assertEquals(files[i].id, entry.id);
                assertEquals(files[i].path, entry.path);
                assertEquals(files[i].identity, entry.identity);
            }
            assertEquals(SshManifestInspector.Status.CONFIG_DEFERRED, report.entries.get(0).status);
            assertEquals("", report.entries.get(0).publicBlobDigest);
            assertTrue(report.entries.get(3).certificate);
            assertEquals(SshManifestInspector.KeyKind.NONE, report.entries.get(4).keyKind);
            for (byte[] body : binding.bodies) wiped(body);
        }
    }

    @Test public void allBodiesAreDeepCopiedBeforeFirstNativeCall() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(
                file(VaultManifest.FileType.PUBLIC_KEY, "first", "first", "first bytes"),
                file(VaultManifest.FileType.PUBLIC_KEY, "second", "second", "second bytes"))) {
            binding.during = () -> {
                int call = binding.bodies.size() - 1;
                assertNotSame(manifest.files.get(call).content, binding.bodies.get(call));
                if (call == 0) Arrays.fill(manifest.files.get(1).content, (byte)'x');
                else assertArrayEquals(bytes("second bytes"), binding.bodies.get(call));
            };
            inspect(manifest, binding);
            assertArrayEquals(bytes("first bytes"), manifest.files.get(0).content);
            assertEquals('x', manifest.files.get(1).content[0]);
            for (byte[] body : binding.bodies) wiped(body);
        }
    }

    @Test public void passphrasesUseExactTypedIdentityWithoutPasswordFallback() throws Exception {
        VaultManifest.FileObject a = file(VaultManifest.FileType.PRIVATE_KEY, "a", "same", "a");
        VaultManifest.FileObject b = file(VaultManifest.FileType.PRIVATE_KEY, "b", "Same", "b");
        VaultManifest.Entry passphrase = phrase("pass", "same", "selected");
        VaultManifest.Entry password = password("password", "key:Same", "unrelated");
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(passphrase, password, totp()), Arrays.asList(a, b))) {
            binding.during = () -> {
                if (binding.types.size() == 1) {
                    assertNotSame(passphrase.secret, binding.passphrases.get(0));
                    assertArrayEquals(bytes("selected"), binding.passphrases.get(0));
                } else assertNull(binding.passphrases.get(1));
            };
            inspect(manifest, binding);
            wiped(binding.passphrases.get(0));
            assertArrayEquals(bytes("selected"), passphrase.secret);
            assertArrayEquals(bytes("unrelated"), password.secret);
        }
    }

    @Test public void allRelatedPassphrasesAreCopiedBeforeAnyNativeCall() throws Exception {
        VaultManifest.Entry a = phrase("pa", "a", "first secret");
        VaultManifest.Entry b = phrase("pb", "b", "second secret");
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(a, b), Arrays.asList(
                file(VaultManifest.FileType.PRIVATE_KEY, "a", "a", "a"),
                file(VaultManifest.FileType.PRIVATE_KEY, "b", "b", "b")))) {
            binding.during = () -> {
                if (binding.types.size() == 1) Arrays.fill(b.secret, (byte)'x');
                else assertArrayEquals(bytes("second secret"), binding.passphrases.get(1));
            };
            inspect(manifest, binding);
            for (byte[] passphrase : binding.passphrases) wiped(passphrase);
        }
    }

    @Test public void multipleMatchingPassphrasesAreExplicitWithoutTrialDecryption() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(
                phrase("one", "key", "one"), phrase("two", "key", "two")), Arrays.asList(
                file(VaultManifest.FileType.PRIVATE_KEY, "key", "key", "private"),
                file(VaultManifest.FileType.KNOWN_HOSTS, "hosts", "hosts", "")))) {
            SshManifestInspector.Report report = inspect(manifest, binding);
            assertEquals(SshManifestInspector.Status.AMBIGUOUS_PASSPHRASE, report.entries.get(0).status);
            assertEquals("", report.entries.get(0).publicBlobDigest);
            assertEquals(Arrays.asList(5), binding.types);
            assertNull(binding.passphrases.get(0));
        }
    }

    @Test public void sharedExactPassphraseCanServeDistinctPrivateFilesWithoutPublicPairInference() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(VaultCollections.list(phrase("pass", "id", "secret")),
                Arrays.asList(file(VaultManifest.FileType.PRIVATE_KEY, "key", "id", "private"),
                        file(VaultManifest.FileType.PRIVATE_KEY, "other", "id", "other"),
                        file(VaultManifest.FileType.PUBLIC_KEY, "pub", "id", "unrelated public")))) {
            inspect(manifest, binding);
            assertSame(binding.passphrases.get(0), binding.passphrases.get(1));
            assertNull(binding.passphrases.get(2));
            wiped(binding.passphrases.get(0));
            assertEquals(Arrays.asList(2, 2, 3), binding.types);
        }
    }

    @Test public void absentPassphraseIsExplicitAndNativeContentDeterminesNeed() throws Exception {
        Binding binding = new Binding();
        binding.response = frame(3, 0, 5, 0, 0, false);
        try (VaultManifest manifest = manifest(file(VaultManifest.FileType.PRIVATE_KEY, "key", "key", "encrypted"))) {
            SshManifestInspector.Entry entry = inspect(manifest, binding).entries.get(0);
            assertNull(binding.passphrases.get(0));
            assertEquals(SshManifestInspector.Status.NEEDS_PASSPHRASE, entry.status);
            assertTrue(entry.encrypted);
            assertEquals(SshManifestInspector.KeyKind.NONE, entry.keyKind);
        }
    }

    @Test public void emptyAndTotpOnlyManifestsDoNotInventAnSshRoot() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest empty = VaultManifest.empty();
                VaultManifest totp = new VaultManifest(VaultCollections.list(totp()), VaultCollections.list())) {
            assertTrue(inspect(empty, binding).entries.isEmpty());
            assertTrue(inspect(totp, binding).entries.isEmpty());
            assertTrue(binding.bodies.isEmpty());
            assertArrayEquals(bytes("0123456789"), totp.entries.get(0).secret);
        }
    }

    @Test public void reportIsImmutableHistoricalMetadataAfterCallerClose() throws Exception {
        Binding binding = new Binding();
        VaultManifest manifest = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "key", "identity", "public"));
        SshManifestInspector.Report report;
        try { report = inspect(manifest, binding); }
        finally { manifest.close(); }
        try { report.entries.clear(); fail("mutable report"); }
        catch (UnsupportedOperationException expected) { }
        assertEquals("identity", report.entries.get(0).identity);
        assertEquals(64, report.entries.get(0).publicBlobDigest.length());
        assertFalse(report.admissionComplete);
    }

    @Test public void nativeIoFailureWipesOwnedBodyAndPassphraseButPreservesCaller() throws Exception {
        Binding binding = new Binding();
        binding.during = () -> { throw new IOException("synthetic native failure"); };
        try (VaultManifest manifest = privateManifest()) {
            rejected(() -> inspect(manifest, binding));
            wiped(binding.bodies.get(0));
            wiped(binding.passphrases.get(0));
            assertArrayEquals(bytes("private"), manifest.files.get(0).content);
            assertArrayEquals(bytes("secret"), manifest.entries.get(0).secret);
        }
    }

    @Test public void nativeErrorWipesLoansAndClearsGlobalReentryGuard() throws Exception {
        Binding binding = new Binding();
        AssertionError error = new AssertionError("synthetic");
        binding.during = () -> { throw error; };
        try (VaultManifest manifest = privateManifest()) {
            try { inspect(manifest, binding); fail("expected Error"); }
            catch (AssertionError actual) { assertSame(error, actual); }
            wiped(binding.bodies.get(0));
            wiped(binding.passphrases.get(0));
            binding.during = null;
            assertEquals(1, inspect(manifest, binding).entries.size());
        }
    }

    @Test public void linkageErrorStillWipesEveryBorrowAndPermitsLaterInspection() throws Exception {
        Binding binding = new Binding();
        LinkageError error = new UnsatisfiedLinkError("synthetic");
        binding.during = () -> { throw error; };
        try (VaultManifest manifest = privateManifest()) {
            try { inspect(manifest, binding); fail("expected linkage failure"); }
            catch (LinkageError actual) { assertSame(error, actual); }
            wiped(binding.bodies.get(0));
            wiped(binding.passphrases.get(0));
            binding.during = null;
            assertEquals(1, inspect(manifest, binding).entries.size());
        }
    }

    @Test public void malformedWireWipesReturnedMetadataAndAllBorrowedArrays() throws Exception {
        Binding binding = new Binding();
        binding.response = new byte[61];
        try (VaultManifest manifest = privateManifest()) {
            rejected(() -> inspect(manifest, binding));
            wiped(binding.response);
            wiped(binding.bodies.get(0));
            wiped(binding.passphrases.get(0));
        }
    }

    @Test public void preCancelledWorkNeverCallsClockOrNativeAndCannotBeReset() throws Exception {
        Binding binding = new Binding();
        SshManifestInspector.Cancellation cancellation = new SshManifestInspector.Cancellation();
        cancellation.cancel();
        cancellation.cancel();
        SshManifestInspector inspector = new SshManifestInspector(() -> {
            throw new AssertionError("cancel should precede clock");
        }, binding);
        try (VaultManifest manifest = privateManifest()) {
            rejected(() -> inspector.inspect(manifest, 31_000, cancellation));
            assertTrue(binding.bodies.isEmpty());
            assertTrue(cancellation.cancelled());
            assertArrayEquals(bytes("private"), manifest.files.get(0).content);
        }
    }

    @Test public void cancelDuringNativeCallPreventsFurtherFilesAndPublication() throws Exception {
        Binding binding = new Binding();
        SshManifestInspector.Cancellation cancellation = new SshManifestInspector.Cancellation();
        binding.during = cancellation::cancel;
        binding.response = parsed(3);
        try (VaultManifest manifest = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "a", "a", "a"),
                file(VaultManifest.FileType.PUBLIC_KEY, "b", "b", "b"))) {
            SshManifestInspector inspector = new SshManifestInspector(new Clock(), binding);
            rejected(() -> inspector.inspect(manifest, 31_000, cancellation));
            assertEquals(1, binding.bodies.size());
            wiped(binding.bodies.get(0));
            wiped(binding.response);
        }
    }

    @Test public void crossThreadCancelCanCompleteWhileNativeCallIsInProgress() throws Exception {
        Binding binding = new Binding();
        SshManifestInspector.Cancellation cancellation = new SshManifestInspector.Cancellation();
        binding.during = () -> {
            Thread cancel = new Thread(cancellation::cancel);
            cancel.start();
            cancel.join(2000);
            assertFalse(cancel.isAlive());
        };
        try (VaultManifest manifest = privateManifest()) {
            rejected(() -> new SshManifestInspector(new Clock(), binding).inspect(manifest, 31_000, cancellation));
            wiped(binding.bodies.get(0));
            wiped(binding.passphrases.get(0));
        }
    }

    @Test public void originalMonotonicDeadlineIsPassedUnchangedAcrossAllFiles() throws Exception {
        Binding binding = new Binding();
        Clock clock = new Clock();
        binding.during = () -> clock.time += 10;
        try (VaultManifest manifest = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "a", "a", "a"),
                file(VaultManifest.FileType.PUBLIC_KEY, "b", "b", "b"))) {
            new SshManifestInspector(clock, binding).inspect(manifest, 1060,
                    new SshManifestInspector.Cancellation());
            assertEquals(Arrays.asList(1060L, 1060L), binding.deadlines);
        }
    }

    @Test public void expiryAfterNativePreventsPartialReportAndCleansLoans() throws Exception {
        Binding binding = new Binding();
        Clock clock = new Clock();
        binding.during = () -> clock.time = 1060;
        binding.response = parsed(2);
        try (VaultManifest manifest = privateManifest()) {
            rejected(() -> new SshManifestInspector(clock, binding).inspect(manifest, 1060,
                    new SshManifestInspector.Cancellation()));
            wiped(binding.bodies.get(0));
            wiped(binding.passphrases.get(0));
            wiped(binding.response);
        }
    }

    @Test public void deadlineAdmissionRejectsPastOverflowAndOverSixtySeconds() throws Exception {
        Binding binding = new Binding();
        Clock clock = new Clock();
        SshManifestInspector inspector = new SshManifestInspector(clock, binding);
        try (VaultManifest manifest = VaultManifest.empty()) {
            for (long deadline : new long[]{Long.MIN_VALUE, -1, 0, 999, 1000, 61_001, Long.MAX_VALUE})
                rejected(() -> inspector.inspect(manifest, deadline, new SshManifestInspector.Cancellation()));
            assertTrue(inspector.inspect(manifest, 61_000,
                    new SshManifestInspector.Cancellation()).entries.isEmpty());
            clock.time = -1;
            rejected(() -> inspector.inspect(manifest, 1000, new SshManifestInspector.Cancellation()));
        }
        assertTrue(binding.bodies.isEmpty());
    }

    @Test public void finalPublicationChecksCancellationAfterOwnedBuffersAreWiped() throws Exception {
        Binding binding = new Binding();
        SshManifestInspector.Cancellation cancellation = new SshManifestInspector.Cancellation();
        SshManifestInspector.Clock clock = () -> {
            if (!binding.bodies.isEmpty() && binding.bodies.get(0)[0] == 0) cancellation.cancel();
            return 1000;
        };
        try (VaultManifest manifest = privateManifest()) {
            rejected(() -> new SshManifestInspector(clock, binding).inspect(manifest, 31_000, cancellation));
            assertTrue(cancellation.cancelled());
            wiped(binding.bodies.get(0));
            wiped(binding.passphrases.get(0));
        }
    }

    @Test public void clockErrorDuringCopyClearsEntryGuardAndPreservesCaller() throws Exception {
        Binding binding = new Binding();
        int[] calls = {0};
        AssertionError error = new AssertionError("copy checkpoint");
        SshManifestInspector inspector = new SshManifestInspector(() -> {
            if (++calls[0] == 3) throw error;
            return 1000;
        }, binding);
        try (VaultManifest manifest = privateManifest()) {
            try { inspector.inspect(manifest, 31_000, new SshManifestInspector.Cancellation()); fail("expected Error"); }
            catch (AssertionError actual) { assertSame(error, actual); }
            assertTrue(binding.bodies.isEmpty());
            assertArrayEquals(bytes("private"), manifest.files.get(0).content);
            assertArrayEquals(bytes("secret"), manifest.entries.get(0).secret);
            assertEquals(1, inspector.inspect(manifest, 31_000,
                    new SshManifestInspector.Cancellation()).entries.size());
        }
    }

    @Test public void threadLocalRejectsSameAndDifferentInspectorReentry() throws Exception {
        Binding binding = new Binding(), otherBinding = new Binding();
        SshManifestInspector inspector = new SshManifestInspector(new Clock(), binding);
        SshManifestInspector other = new SshManifestInspector(new Clock(), otherBinding);
        try (VaultManifest manifest = privateManifest()) {
            binding.during = () -> {
                rejected(() -> inspector.inspect(manifest, 31_000, new SshManifestInspector.Cancellation()));
                rejected(() -> other.inspect(manifest, 31_000, new SshManifestInspector.Cancellation()));
            };
            assertEquals(1, inspector.inspect(manifest, 31_000,
                    new SshManifestInspector.Cancellation()).entries.size());
            assertEquals(1, binding.bodies.size());
            assertTrue(otherBinding.bodies.isEmpty());
        }
    }

    @Test public void clockCallbackCannotReenterBeforeFirstCopy() throws Exception {
        Binding binding = new Binding();
        SshManifestInspector[] inspector = new SshManifestInspector[1];
        try (VaultManifest manifest = privateManifest()) {
            inspector[0] = new SshManifestInspector(() -> {
                try { rejected(() -> inspector[0].inspect(manifest, 31_000, new SshManifestInspector.Cancellation())); }
                catch (Exception failure) { throw new AssertionError(failure); }
                return 1000;
            }, binding);
            assertEquals(1, inspector[0].inspect(manifest, 31_000,
                    new SshManifestInspector.Cancellation()).entries.size());
            assertEquals(1, binding.bodies.size());
        }
    }

    @Test public void allTruncatedFramesTrailingBytesAndNullAreRejected() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "key", "key", "key"))) {
            byte[] original = parsed(3);
            for (int length = 0; length < 60; length++) {
                binding.response = Arrays.copyOf(original, length);
                rejected(() -> inspect(manifest, binding));
                wiped(binding.response);
            }
            binding.response = Arrays.copyOf(original, 61);
            rejected(() -> inspect(manifest, binding));
            SshManifestInspector inspector = new SshManifestInspector(new Clock(), (a, b, c, d) -> null);
            rejected(() -> inspector.inspect(manifest, 31_000, new SshManifestInspector.Cancellation()));
        }
    }

    @Test public void wireHeaderEnumsAndReservedFlagsAreRejectedIndependently() throws Exception {
        Binding binding = new Binding();
        byte[] original = parsed(3);
        int[][] fields = {{0, 0}, {4, 0}, {4, 2}, {8, -1}, {8, 5}, {12, -1}, {12, 6},
                {16, 0}, {16, 8}, {16, -1}, {24, -1}, {24, 16385}};
        try (VaultManifest manifest = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "key", "key", "key"))) {
            for (int[] field : fields) {
                binding.response = integer(original, field[0], field[1]);
                rejected(() -> inspect(manifest, binding));
                wiped(binding.response);
            }
        }
    }

    @Test public void wireTypeEncryptedCertificateAndPassphraseStatusesCannotCross() throws Exception {
        Binding binding = new Binding();
        for (VaultManifest.FileType type : new VaultManifest.FileType[]{VaultManifest.FileType.PRIVATE_KEY,
                VaultManifest.FileType.PUBLIC_KEY, VaultManifest.FileType.CERTIFICATE,
                VaultManifest.FileType.KNOWN_HOSTS}) {
            try (VaultManifest manifest = manifest(file(type, "key", "key", "key"))) {
                List<byte[]> invalid = new ArrayList<>();
                if (type != VaultManifest.FileType.PRIVATE_KEY) {
                    invalid.add(frame(1, 0, 5, 0, 1, false));
                    invalid.add(frame(3, 0, 5, 0, 0, false));
                    invalid.add(frame(4, 0, 5, 0, 0, false));
                } else {
                    invalid.add(frame(3, 0, 4, 0, 0, false));
                    invalid.add(frame(4, 0, 4, 0, 0, false));
                }
                invalid.add(frame(1, 0, 6, 0, 1, false));
                invalid.add(frame(0, 1, type == VaultManifest.FileType.CERTIFICATE ? 4 : 6, 1, 0, true));
                for (byte[] wire : invalid) {
                    binding.response = wire;
                    rejected(() -> inspect(manifest, binding));
                }
            }
        }
    }

    @Test public void keyFramesRequireOneRecordNonzeroPublicDigestAndNoSuccessLine() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(file(VaultManifest.FileType.PUBLIC_KEY, "key", "key", "key"))) {
            for (byte[] wire : new byte[][]{frame(0, 0, 4, 1, 0, true), frame(0, 1, 4, 0, 0, true),
                    frame(0, 1, 4, 2, 0, true), frame(0, 1, 4, -1, 0, true), frame(0, 1, 4, 1, 0, false),
                    frame(0, 1, 4, 1, 1, true), frame(1, 1, 4, 0, 1, false),
                    frame(1, 0, 4, 1, 1, false), frame(1, 0, 4, 0, 1, true)}) {
                binding.response = wire;
                rejected(() -> inspect(manifest, binding));
            }
            for (int kind = 1; kind <= 5; kind++) {
                binding.response = frame(0, kind, 4, 1, 0, true);
                assertEquals(SshManifestInspector.KeyKind.values()[kind], inspect(manifest, binding).entries.get(0).keyKind);
            }
        }
    }

    @Test public void knownHostsCountsAndFailureLinesAreStrictAndZeroDigestIsRequired() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(file(VaultManifest.FileType.KNOWN_HOSTS, "hosts", "hosts", "hosts"))) {
            for (byte[] wire : new byte[][]{frame(0, 1, 4, 1, 0, false), frame(0, 0, 4, -1, 0, false),
                    frame(0, 0, 4, 16385, 0, false), frame(0, 0, 4, 0, 0, true),
                    frame(0, 0, 4, 0, 1, false), frame(1, 0, 4, 1, 1, false)}) {
                binding.response = wire;
                rejected(() -> inspect(manifest, binding));
            }
            binding.response = frame(0, 0, 4, 16384, 0, false);
            assertEquals(16384, inspect(manifest, binding).entries.get(0).records);
            binding.response = frame(1, 0, 4, 0, 16384, false);
            SshManifestInspector.Entry entry = inspect(manifest, binding).entries.get(0);
            assertEquals(SshManifestInspector.Status.MALFORMED, entry.status);
            assertEquals(16384, entry.line);
            assertEquals("", entry.publicBlobDigest);
        }
    }

    @Test public void allNativeFailureStatusesRemainContentOutcomesWithNoApproval() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = privateManifest()) {
            for (int status = 1; status <= 4; status++) {
                binding.response = frame(status, 0, status >= 3 ? 5 : 4, 0, 0, false);
                SshManifestInspector.Report report = inspect(manifest, binding);
                assertEquals(SshManifestInspector.Status.values()[status], report.entries.get(0).status);
                assertTrue(report.contentOnly);
                assertFalse(report.admissionComplete);
                assertEquals("", report.entries.get(0).publicBlobDigest);
            }
        }
    }

    @Test public void invalidApiInputsDoNotEnterNativeAndDoNotPoisonThreadGuard() throws Exception {
        Binding binding = new Binding();
        SshManifestInspector inspector = new SshManifestInspector(new Clock(), binding);
        rejected(() -> inspector.inspect(null, 31_000, new SshManifestInspector.Cancellation()));
        try (VaultManifest manifest = VaultManifest.empty()) {
            rejected(() -> inspector.inspect(manifest, 31_000, null));
            assertTrue(inspector.inspect(manifest, 31_000,
                    new SshManifestInspector.Cancellation()).entries.isEmpty());
        }
        assertTrue(binding.bodies.isEmpty());
    }

    private static VaultManifest privateManifest() throws Exception {
        return new VaultManifest(VaultCollections.list(phrase("pass", "key", "secret")),
                VaultCollections.list(file(VaultManifest.FileType.PRIVATE_KEY, "key", "key", "private")));
    }
}
