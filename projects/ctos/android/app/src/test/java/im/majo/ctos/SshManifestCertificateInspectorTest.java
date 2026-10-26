package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Independent CSCF vectors and actual-loan ownership, not native parser evidence. */
public final class SshManifestCertificateInspectorTest {
    private interface Action { void run() throws Exception; }
    private static final class Clock implements SshManifestCertificateInspector.Clock {
        long time = 1000;
        Runnable during;
        @Override public long now() { if (during != null) during.run(); return time; }
    }
    private static final class Binding implements SshManifestCertificateInspector.NativeBinding {
        final List<byte[]> bodies = new ArrayList<>(), wires = new ArrayList<>();
        final List<Long> deadlines = new ArrayList<>();
        byte[] response;
        boolean returnNull;
        Action during;
        @Override public byte[] inspectCertificate(byte[] body, long deadline) throws IOException {
            bodies.add(body); deadlines.add(deadline);
            if (during != null) {
                try { during.run(); }
                catch (IOException | RuntimeException | Error error) { throw error; }
                catch (Exception error) { throw new AssertionError(error); }
            }
            if (returnNull) return null;
            byte[] wire = response == null ? facts() : response.clone();
            wires.add(wire); return wire;
        }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id, byte[] body) {
        return new VaultManifest.FileObject(type, id, "ssh/" + id, "identity-" + id, body, VaultCollections.list());
    }
    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id, String body) {
        return file(type, id, bytes(body));
    }
    private static VaultManifest.FileObject certificate(String id, String body) {
        return file(VaultManifest.FileType.CERTIFICATE, id, body);
    }
    private static VaultManifest manifest(VaultManifest.FileObject... files) throws Exception {
        return new VaultManifest(VaultCollections.list(), Arrays.asList(files));
    }
    private static VaultManifest one() throws Exception { return manifest(certificate("certificate", "actual certificate")); }
    private static byte[] facts() {
        ByteBuffer buffer = ByteBuffer.allocate(168);
        buffer.putInt(0x43534346).putInt(1).putInt(0).putInt(7).putInt(2).putInt(1)
                .putInt(1).putInt(1).putInt(1).putInt(0).putInt(0).putInt(1)
                .putLong(3).putLong(100).putLong(200);
        byte[] wire = buffer.array(); wire[72] = 1; wire[104] = 2; wire[136] = 3;
        return wire;
    }
    private static byte[] failure(int status, int line) {
        byte[] wire = new byte[168];
        ByteBuffer.wrap(wire).putInt(0x43534346).putInt(1).putInt(status).putInt(1).putInt(44, line);
        return wire;
    }
    private static byte[] integer(byte[] source, int offset, int value) {
        byte[] wire = source.clone(); ByteBuffer.wrap(wire).putInt(offset, value); return wire;
    }
    private static byte[] number(byte[] source, int offset, long value) {
        byte[] wire = source.clone(); ByteBuffer.wrap(wire).putLong(offset, value); return wire;
    }
    private static void rejected(Action action) throws Exception {
        try { action.run(); fail("expected certificate inspection rejection"); }
        catch (IOException expected) { }
    }
    private static void structuralRejection(Action action) throws Exception {
        try { action.run(); fail("expected v1 structural rejection"); }
        catch (VaultException expected) { }
    }
    private static boolean zero(byte[] value) {
        for (byte b : value) if (b != 0) return false;
        return true;
    }
    private static void clean(Binding binding) {
        for (List<byte[]> arrays : Arrays.asList(binding.bodies, binding.wires))
            for (byte[] value : arrays) assertArrayEquals(new byte[value.length], value);
    }
    private static SshManifestCertificateInspector.Report inspect(VaultManifest manifest, Binding binding) throws IOException {
        return new SshManifestCertificateInspector(new Clock(), binding).inspect(manifest, 31_000,
                new SshManifestCertificateInspector.Cancellation());
    }
    private static void rejectsWire(VaultManifest manifest, byte[] wire) throws Exception {
        Binding binding = new Binding(); binding.response = wire;
        rejected(() -> inspect(manifest, binding)); clean(binding);
    }

    @Test public void sourceAndCandidateWithSameMetadataUseTheirActualIndependentBytes() throws Exception {
        List<String> observed = new ArrayList<>(); List<byte[]> bodies = new ArrayList<>(), wires = new ArrayList<>();
        SshManifestCertificateInspector.NativeBinding binding = (body, deadline) -> {
            observed.add(new String(body, StandardCharsets.UTF_8)); bodies.add(body);
            byte[] wire = facts();
            try { System.arraycopy(MessageDigest.getInstance("SHA-256").digest(body), 0, wire, 72, 32); }
            catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
            wires.add(wire); return wire;
        };
        try (VaultManifest source = manifest(certificate("same", "source"));
                VaultManifest candidate = manifest(certificate("same", "candidate"))) {
            SshManifestCertificateInspector.Report a = new SshManifestCertificateInspector(new Clock(), binding)
                    .inspect(source, 31_000, new SshManifestCertificateInspector.Cancellation());
            SshManifestCertificateInspector.Report b = new SshManifestCertificateInspector(new Clock(), binding)
                    .inspect(candidate, 31_000, new SshManifestCertificateInspector.Cancellation());
            assertEquals(Arrays.asList("source", "candidate"), observed);
            assertFalse(a.entries.get(0).facts.certificateDigest.equals(b.entries.get(0).facts.certificateDigest));
            assertArrayEquals(bytes("source"), source.files.get(0).content);
            assertArrayEquals(bytes("candidate"), candidate.files.get(0).content);
            for (byte[] value : bodies) assertTrue(zero(value));
            for (byte[] value : wires) assertTrue(zero(value));
        }
    }

    @Test public void everyActualCertificateKeepsOrdinalAndOtherMaterialsRemainUntouched() throws Exception {
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "password", "target:opaque", "", "", 0, 0, bytes("password bytes"));
        VaultManifest.Entry phrase = new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE,
                "phrase", "key:identity-private", "", "", 0, 0, bytes("phrase bytes"));
        VaultManifest.Entry otp = new VaultManifest.Entry(VaultManifest.EntryType.TOTP,
                "otp", "otp", "", "SHA1", 6, 30, bytes("0123456789"));
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(password, phrase, otp), Arrays.asList(
                file(VaultManifest.FileType.CONFIG, "config", "config bytes"), certificate("first", "first certificate"),
                file(VaultManifest.FileType.PRIVATE_KEY, "private", "private bytes"),
                file(VaultManifest.FileType.PUBLIC_KEY, "public", "public bytes"), certificate("second", "second certificate"),
                file(VaultManifest.FileType.KNOWN_HOSTS, "hosts", "trust bytes")))) {
            binding.during = () -> {
                int call = binding.bodies.size() - 1, ordinal = call == 0 ? 1 : 4;
                assertArrayEquals(bytes(call == 0 ? "first certificate" : "second certificate"), binding.bodies.get(call));
                assertNotSame(manifest.files.get(ordinal).content, binding.bodies.get(call));
            };
            SshManifestCertificateInspector.Report report = inspect(manifest, binding);
            assertEquals(2, binding.bodies.size()); assertEquals(2, report.entries.size());
            for (int i = 0; i < 2; i++) {
                SshManifestCertificateInspector.Entry entry = report.entries.get(i);
                int ordinal = i == 0 ? 1 : 4;
                assertEquals(ordinal, entry.ordinal); assertEquals(manifest.files.get(ordinal).id, entry.id);
                assertEquals(manifest.files.get(ordinal).path, entry.path);
                assertEquals(manifest.files.get(ordinal).identity, entry.identity);
            }
            assertArrayEquals(bytes("private bytes"), manifest.files.get(2).content);
            assertArrayEquals(bytes("config bytes"), manifest.files.get(0).content);
            assertArrayEquals(bytes("password bytes"), password.secret); assertArrayEquals(bytes("phrase bytes"), phrase.secret);
            assertArrayEquals(bytes("0123456789"), otp.secret); clean(binding);
        }
    }

    @Test public void allSelectedCertificateCopiesExistBeforeFirstNativeCall() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(certificate("first", "first"), certificate("second", "second"))) {
            binding.during = () -> {
                if (binding.bodies.size() == 1) Arrays.fill(manifest.files.get(1).content, (byte)'x');
                else assertArrayEquals(bytes("second"), binding.bodies.get(1));
            };
            inspect(manifest, binding); clean(binding);
        }
    }

    @Test public void successPreservesUnsignedBitsAndThreeDistinctPublicDigestRoles() throws Exception {
        try (VaultManifest manifest = one()) {
            long[] values = {0, 1, Long.MAX_VALUE, Long.MIN_VALUE, -1};
            for (long serial : values) for (long after : values) for (long before : values) {
                Binding binding = new Binding(); binding.response = number(number(number(facts(), 48, serial), 56, after), 64, before);
                SshManifestCertificateInspector.Facts facts = inspect(manifest, binding).entries.get(0).facts;
                assertEquals(serial, facts.serialBits); assertEquals(after, facts.validAfterBits); assertEquals(before, facts.validBeforeBits);
                assertTrue(facts.certificateDigest.startsWith("01")); assertTrue(facts.subjectDigest.startsWith("02")); assertTrue(facts.caDigest.startsWith("03"));
                clean(binding);
            }
        }
    }

    @Test public void userHostKindsCountsAndSectionLengthsRemainFactsWithoutApplicabilityClaims() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int type : new int[]{1, 2}) for (int subject = 1; subject <= 5; subject++) {
                Binding binding = new Binding(); byte[] wire = integer(integer(facts(), 16, type), 20, subject);
                wire = integer(integer(integer(wire, 32, 256), 36, 1048576), 40, 1048576); binding.response = wire;
                SshManifestCertificateInspector.Report report = inspect(manifest, binding);
                SshManifestCertificateInspector.Facts result = report.entries.get(0).facts;
                assertEquals(SshManifestCertificateInspector.CertificateType.values()[type - 1], result.certificateType);
                assertEquals(SshManifestCertificateInspector.KeyKind.values()[subject], result.subjectKind);
                assertEquals(256, result.principalCount); assertEquals(1048576, result.criticalByteLength);
                assertEquals(1048576, result.extensionByteLength); assertFalse(report.principalApplicabilityChecked);
                assertFalse(report.timeValidityChecked); clean(binding);
            }
            Binding binding = new Binding(); binding.response = integer(facts(), 32, 0);
            assertEquals(0, inspect(manifest, binding).entries.get(0).facts.principalCount); clean(binding);
        }
    }

    @Test public void everyCaKindAndSignatureAlgorithmCombinationHasClosedMapping() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int ca = 1; ca <= 5; ca++) for (int algorithm = 1; algorithm <= 6; algorithm++) {
                Binding binding = new Binding(); binding.response = integer(integer(facts(), 24, ca), 28, algorithm);
                boolean allowed = ca < 5 ? algorithm == ca : algorithm >= 5;
                if (allowed) {
                    SshManifestCertificateInspector.Facts result = inspect(manifest, binding).entries.get(0).facts;
                    assertEquals(SshManifestCertificateInspector.KeyKind.values()[ca], result.caKind);
                    assertEquals(SshManifestCertificateInspector.SignatureAlgorithm.values()[algorithm], result.signatureAlgorithm);
                } else rejected(() -> inspect(manifest, binding));
                clean(binding);
            }
        }
    }

    @Test public void materialFailuresHaveNullFactsAndDoNotSuppressOtherCertificateRows() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(certificate("bad", "bad"), certificate("unsupported", "unsupported"), certificate("good", "good"))) {
            binding.during = () -> binding.response = binding.bodies.size() == 1 ? failure(1, 4)
                    : binding.bodies.size() == 2 ? failure(2, 7) : facts();
            SshManifestCertificateInspector.Report report = inspect(manifest, binding);
            assertEquals(3, report.entries.size()); assertFalse(report.allCertificateFactsAvailable);
            assertEquals(SshManifestCertificateInspector.Status.MALFORMED, report.entries.get(0).status);
            assertEquals(4, report.entries.get(0).sourceLine); assertNull(report.entries.get(0).facts);
            assertEquals(SshManifestCertificateInspector.Status.UNSUPPORTED, report.entries.get(1).status);
            assertEquals(7, report.entries.get(1).sourceLine); assertNull(report.entries.get(1).facts);
            assertEquals(SshManifestCertificateInspector.Status.FACTS, report.entries.get(2).status); clean(binding);
        }
    }

    @Test public void allStatusFlagBytesRequireFactsOnlyAndWholeProofOrNoProof() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int status = 0; status <= 2; status++) for (int flags = 0; flags < 256; flags++) {
                Binding binding = new Binding(); binding.response = integer(status == 0 ? facts() : failure(status, 0), 12, flags);
                if (flags == (status == 0 ? 7 : 1)) inspect(manifest, binding);
                else rejected(() -> inspect(manifest, binding));
                clean(binding);
            }
        }
    }

    @Test public void successRejectsInvalidTypesKindsAlgorithmsCountsLengthsAndSourceLines() throws Exception {
        int[][] invalids = {{16, 0}, {16, 3}, {20, 0}, {20, 6}, {24, 0}, {24, 6}, {28, 0}, {28, 7},
                {32, -1}, {32, 257}, {36, -1}, {36, 1048577}, {40, -1}, {40, 1048577}, {44, -1}, {44, 0}, {44, 16385}};
        try (VaultManifest manifest = one()) {
            for (int[] pair : invalids) rejectsWire(manifest, integer(facts(), pair[0], pair[1]));
            Binding binding = new Binding(); binding.response = integer(facts(), 44, 16384);
            assertEquals(16384, inspect(manifest, binding).entries.get(0).sourceLine); clean(binding);
        }
    }

    @Test public void failureRejectsEveryNonzeroFactFieldIncludingHighBitUnsignedWords() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int status : new int[]{1, 2}) {
                for (int offset = 16; offset <= 40; offset += 4) rejectsWire(manifest, integer(failure(status, 0), offset, 1));
                for (int offset : new int[]{48, 56, 64}) for (long value : new long[]{1, Long.MIN_VALUE, -1})
                    rejectsWire(manifest, number(failure(status, 0), offset, value));
                rejectsWire(manifest, integer(failure(status, 0), 44, -1));
                rejectsWire(manifest, integer(failure(status, 0), 44, 16385));
                for (int line : new int[]{0, 1, 16384}) {
                    Binding binding = new Binding(); binding.response = failure(status, line);
                    assertEquals(line, inspect(manifest, binding).entries.get(0).sourceLine); clean(binding);
                }
            }
        }
    }

    @Test public void allThreeDigestsMustBePresentOnSuccessAndZeroOnFailure() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int offset : new int[]{72, 104, 136}) {
                byte[] missing = facts(); Arrays.fill(missing, offset, offset + 32, (byte)0); rejectsWire(manifest, missing);
                for (int status : new int[]{1, 2}) {
                    byte[] unexpected = failure(status, 0); unexpected[offset + 31] = (byte)0x80; rejectsWire(manifest, unexpected);
                }
            }
        }
    }

    @Test public void otherProtocolMagicVersionUnknownStatusTruncationAndTrailingBytesReject() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int magic : new int[]{0, 0x43534d49, 0x43534b41, 0x43534b50}) rejectsWire(manifest, integer(facts(), 0, magic));
            for (int value : new int[]{-1, 0, 2}) rejectsWire(manifest, integer(facts(), 4, value));
            for (int value : new int[]{-1, 3, Integer.MAX_VALUE}) rejectsWire(manifest, integer(facts(), 8, value));
            for (int length = 0; length < 168; length++) rejectsWire(manifest, Arrays.copyOf(facts(), length));
            rejectsWire(manifest, Arrays.copyOf(facts(), 169));
            Binding binding = new Binding(); binding.returnNull = true;
            rejected(() -> inspect(manifest, binding)); clean(binding);
        }
    }

    @Test public void ioRuntimeLinkageAndErrorWipeAllOwnedBodiesAndReleaseThreadGuard() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int mode = 0; mode < 4; mode++) {
                Binding binding = new Binding(); final int selected = mode;
                binding.during = () -> {
                    if (selected == 0) throw new IOException("synthetic");
                    if (selected == 1) throw new IllegalStateException("synthetic");
                    if (selected == 2) throw new UnsatisfiedLinkError("synthetic");
                    throw new AssertionError("synthetic");
                };
                try { inspect(manifest, binding); fail("expected failure"); }
                catch (IOException | IllegalStateException | UnsatisfiedLinkError | AssertionError expected) {
                    assertEquals("synthetic", expected.getMessage());
                }
                clean(binding); assertArrayEquals(bytes("actual certificate"), manifest.files.get(0).content);
                assertTrue(inspect(manifest, new Binding()).allCertificateFactsAvailable);
            }
        }
    }

    @Test public void laterCertificateFailureReturnsNoPrefixAndWipesEarlierWire() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(certificate("first", "first"), certificate("second", "second"))) {
            binding.during = () -> { if (binding.bodies.size() == 2) throw new IOException("later"); };
            rejected(() -> inspect(manifest, binding)); assertEquals(2, binding.bodies.size()); clean(binding);
        }
    }

    @Test public void oneOriginalDeadlineIsPassedToAllCallsAndNeverRenewed() throws Exception {
        Clock clock = new Clock(); Binding binding = new Binding(); binding.during = () -> clock.time += 1000;
        try (VaultManifest manifest = manifest(certificate("first", "first"), certificate("second", "second"))) {
            new SshManifestCertificateInspector(clock, binding).inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation());
            assertEquals(Arrays.asList(31_000L, 31_000L), binding.deadlines); clean(binding);
        }
    }

    @Test public void cancellationOrExpiryAfterNativePreventsPublicationAndWipes() throws Exception {
        try (VaultManifest manifest = one()) {
            for (boolean cancel : new boolean[]{false, true}) {
                Clock clock = new Clock(); Binding binding = new Binding();
                SshManifestCertificateInspector.Cancellation cancellation = new SshManifestCertificateInspector.Cancellation();
                binding.during = () -> { if (cancel) cancellation.cancel(); else clock.time = 31_000; };
                rejected(() -> new SshManifestCertificateInspector(clock, binding).inspect(manifest, 31_000, cancellation));
                clean(binding);
            }
        }
    }

    @Test public void finalFenceOccursAfterOwnedBodiesAndWiresHaveBeenWiped() throws Exception {
        Clock clock = new Clock(); Binding binding = new Binding();
        SshManifestCertificateInspector.Cancellation cancellation = new SshManifestCertificateInspector.Cancellation();
        clock.during = () -> {
            if (!binding.bodies.isEmpty() && zero(binding.bodies.get(0)) && zero(binding.wires.get(0))) cancellation.cancel();
        };
        try (VaultManifest manifest = one()) {
            rejected(() -> new SshManifestCertificateInspector(clock, binding).inspect(manifest, 31_000, cancellation));
            assertTrue(cancellation.cancelled()); clean(binding);
        }
    }

    @Test public void preCancelledExactDeadlineAndExcessBudgetAvoidNative() throws Exception {
        try (VaultManifest manifest = one()) {
            Binding binding = new Binding(); SshManifestCertificateInspector.Cancellation cancellation = new SshManifestCertificateInspector.Cancellation();
            cancellation.cancel();
            rejected(() -> new SshManifestCertificateInspector(new Clock(), binding).inspect(manifest, 31_000, cancellation));
            for (long deadline : new long[]{-1, 0, 1000, 61_001, Long.MAX_VALUE})
                rejected(() -> new SshManifestCertificateInspector(new Clock(), binding).inspect(manifest, deadline, new SshManifestCertificateInspector.Cancellation()));
            assertTrue(binding.bodies.isEmpty());
        }
    }

    @Test public void eightMiBAnd128CertificateOutputBoundariesAreAdmittedWithoutRepeatedCopies() throws Exception {
        byte[] body = new byte[1048576]; Arrays.fill(body, (byte)'c');
        List<VaultManifest.FileObject> files = new ArrayList<>();
        for (int i = 0; i < 8; i++) files.add(file(VaultManifest.FileType.CERTIFICATE, "cert" + i, body));
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(VaultCollections.list(), files)) {
            assertEquals(8, inspect(manifest, binding).entries.size()); assertEquals(8, binding.bodies.size()); clean(binding);
        }
        final List<VaultManifest.FileObject> tooManyBytes = new ArrayList<>(files);
        tooManyBytes.add(file(VaultManifest.FileType.CERTIFICATE, "overflow", body));
        structuralRejection(() -> new VaultManifest(VaultCollections.list(), tooManyBytes));
        files = new ArrayList<>();
        for (int i = 0; i < 128; i++) files.add(certificate("cert" + i, "actual" + i));
        binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(VaultCollections.list(), files)) {
            assertEquals(128, inspect(manifest, binding).entries.size()); assertEquals(128, binding.wires.size()); clean(binding);
        }
        final List<VaultManifest.FileObject> tooMany = new ArrayList<>(files);
        tooMany.add(certificate("overflow", "extra"));
        structuralRejection(() -> new VaultManifest(VaultCollections.list(), tooMany));
    }

    @Test public void emptyAndNonCertificateOnlyManifestsAvoidNativeButKeepOriginalLifetime() throws Exception {
        for (boolean empty : new boolean[]{false, true}) {
            Binding binding = new Binding();
            try (VaultManifest manifest = empty ? VaultManifest.empty() : manifest(file(VaultManifest.FileType.PRIVATE_KEY, "private", "private"))) {
                SshManifestCertificateInspector.Report report = inspect(manifest, binding);
                assertTrue(report.entries.isEmpty()); assertTrue(report.allCertificateFactsAvailable); assertTrue(binding.bodies.isEmpty());
            }
        }
        Clock clock = new Clock(); Binding binding = new Binding(); int[] calls = {0};
        clock.during = () -> { if (++calls[0] > 1) clock.time = 31_000; };
        try (VaultManifest manifest = VaultManifest.empty()) {
            rejected(() -> new SshManifestCertificateInspector(clock, binding).inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation()));
            assertTrue(binding.bodies.isEmpty());
        }
    }

    @Test public void maximumCredentialMetadataDoesNotProduceCertificateWorkOrSecretCopies() throws Exception {
        List<VaultManifest.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 1024; i++) entries.add(new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "entry" + i, "target:opaque" + i, "", "", 0, 0, bytes("unrelated synthetic secret")));
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(entries, VaultCollections.list())) {
            SshManifestCertificateInspector.Report report = inspect(manifest, binding);
            assertTrue(report.entries.isEmpty()); assertTrue(binding.bodies.isEmpty());
            assertTrue(report.allCertificateFactsAvailable); assertFalse(report.admissionComplete);
            for (VaultManifest.Entry entry : entries) assertArrayEquals(bytes("unrelated synthetic secret"), entry.secret);
        }
    }

    @Test public void immutableFactsOnlyReportNeverPromotesToTrustTimeOrAdmission() throws Exception {
        try (VaultManifest manifest = one()) {
            SshManifestCertificateInspector.Report report = inspect(manifest, new Binding());
            assertTrue(report.allCertificateFilesInspected && report.factsOnly && report.allCertificateFactsAvailable);
            assertFalse(report.admissionComplete || report.trustChecked || report.timeValidityChecked
                    || report.principalApplicabilityChecked || report.revocationChecked);
            try { report.entries.clear(); fail("immutable list required"); }
            catch (UnsupportedOperationException expected) { }
            assertEquals(SshManifestCertificateInspector.Status.FACTS, report.entries.get(0).status);
        }
    }

    @Test public void creatorThreadAndSingleAttemptAreEnforcedAfterSuccessOrFailure() throws Exception {
        Binding binding = new Binding(); SshManifestCertificateInspector inspector = new SshManifestCertificateInspector(new Clock(), binding);
        try (VaultManifest manifest = one()) {
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { inspector.inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation()); }
                catch (Throwable failure) { error.set(failure); }
            });
            worker.start(); worker.join(5000); assertFalse(worker.isAlive()); assertTrue(error.get() instanceof IOException);
            assertTrue(binding.bodies.isEmpty());
            assertTrue(inspector.inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation()).allCertificateFactsAvailable);
            rejected(() -> inspector.inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation()));
            clean(binding);
            SshManifestCertificateInspector failed = new SshManifestCertificateInspector(new Clock(), new Binding());
            rejected(() -> failed.inspect(null, 31_000, new SshManifestCertificateInspector.Cancellation()));
            rejected(() -> failed.inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation()));
        }
    }

    @Test public void sameAndCrossInstanceReentryDoNotConsumeUnusedSibling() throws Exception {
        Binding binding = new Binding(), siblingBinding = new Binding();
        SshManifestCertificateInspector sibling = new SshManifestCertificateInspector(new Clock(), siblingBinding);
        SshManifestCertificateInspector[] outer = {new SshManifestCertificateInspector(new Clock(), binding)};
        try (VaultManifest manifest = one()) {
            binding.during = () -> {
                rejected(() -> outer[0].inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation()));
                rejected(() -> sibling.inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation()));
                assertTrue(siblingBinding.bodies.isEmpty());
            };
            assertTrue(outer[0].inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation()).allCertificateFactsAvailable);
            assertTrue(sibling.inspect(manifest, 31_000, new SshManifestCertificateInspector.Cancellation()).allCertificateFactsAvailable);
            clean(binding); clean(siblingBinding);
        }
    }

    @Test public void nullArgumentsAndDependenciesRejectWithoutNative() throws Exception {
        Binding binding = new Binding();
        rejected(() -> new SshManifestCertificateInspector(new Clock(), binding).inspect(null, 31_000, new SshManifestCertificateInspector.Cancellation()));
        try (VaultManifest manifest = one()) {
            rejected(() -> new SshManifestCertificateInspector(new Clock(), binding).inspect(manifest, 31_000, null));
        }
        assertTrue(binding.bodies.isEmpty());
        try { new SshManifestCertificateInspector(null, binding); fail("clock required"); }
        catch (IllegalArgumentException expected) { }
        try { new SshManifestCertificateInspector(new Clock(), null); fail("binding required"); }
        catch (IllegalArgumentException expected) { }
    }
}
