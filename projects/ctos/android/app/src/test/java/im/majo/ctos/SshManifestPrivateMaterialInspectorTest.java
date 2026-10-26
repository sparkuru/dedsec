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

/** Supplied-loan ownership and independent CSKP vectors; native crypto runs separately. */
public final class SshManifestPrivateMaterialInspectorTest {
    private interface Action { void run() throws Exception; }
    private static final class Clock implements SshManifestPrivateMaterialInspector.Clock {
        long time = 1000;
        Runnable during;
        @Override public long now() { if (during != null) during.run(); return time; }
    }
    private static final class Binding implements SshManifestPrivateMaterialInspector.NativeBinding {
        final List<byte[]> bodies = new ArrayList<>(), phrases = new ArrayList<>(), wires = new ArrayList<>();
        final List<Long> deadlines = new ArrayList<>();
        byte[] response;
        boolean returnNull;
        Action during;
        @Override public byte[] verifyPrivate(byte[] body, byte[] phrase, long deadline) throws IOException {
            bodies.add(body); phrases.add(phrase); deadlines.add(deadline);
            if (during != null) {
                try { during.run(); }
                catch (IOException | RuntimeException | Error error) { throw error; }
                catch (Exception error) { throw new AssertionError(error); }
            }
            if (returnNull) return null;
            byte[] result = response == null ? frame(0, 1, 28, 0, 2, true) : response.clone();
            wires.add(result);
            return result;
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id, String identity, byte[] body) {
        return new VaultManifest.FileObject(type, id, "ssh/" + id, identity, body, VaultCollections.list());
    }
    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id, String identity, String body) {
        return file(type, id, identity, bytes(body));
    }
    private static VaultManifest.FileObject key(String id, String identity, String body) {
        return file(VaultManifest.FileType.PRIVATE_KEY, id, identity, body);
    }
    private static VaultManifest.Entry phrase(String id, String identity, String body) {
        return new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE, id,
                "key:" + identity, "", "", 0, 0, bytes(body));
    }
    private static VaultManifest.Entry password(String id, String identity) {
        return new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, id,
                identity, "", "", 0, 0, bytes("unrelated password"));
    }
    private static VaultManifest.Entry totp() {
        return new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "otp", "otp", "", "SHA1",
                6, 30, bytes("0123456789"));
    }
    private static VaultManifest manifest(VaultManifest.FileObject... files) throws Exception {
        return new VaultManifest(VaultCollections.list(), Arrays.asList(files));
    }
    private static byte[] frame(int status, int kind, int flags, int stage, int type, boolean digest) {
        ByteBuffer buffer = ByteBuffer.allocate(60);
        buffer.putInt(0x43534b50).putInt(1).putInt(status).putInt(kind).putInt(flags).putInt(stage).putInt(type);
        if (digest) buffer.put((byte)0x80);
        return buffer.array();
    }
    private static byte[] integer(byte[] original, int offset, int value) {
        byte[] wire = original.clone(); ByteBuffer.wrap(wire).putInt(offset, value); return wire;
    }
    private static void rejected(Action action) throws Exception {
        try { action.run(); fail("expected private-material rejection"); }
        catch (IOException expected) { }
    }
    private static void structuralRejection(Action action) throws Exception {
        try { action.run(); fail("expected v1 structural rejection"); }
        catch (VaultException expected) { }
    }
    private static boolean zero(byte[] value) {
        if (value == null) return true;
        for (byte b : value) if (b != 0) return false;
        return true;
    }
    private static void clean(Binding binding) {
        for (List<byte[]> arrays : Arrays.asList(binding.bodies, binding.phrases, binding.wires))
            for (byte[] value : arrays) if (value != null) assertArrayEquals(new byte[value.length], value);
    }
    private static SshManifestPrivateMaterialInspector.Report inspect(VaultManifest manifest, Binding binding) throws IOException {
        return new SshManifestPrivateMaterialInspector(new Clock(), binding).inspect(manifest, 31_000,
                new SshManifestPrivateMaterialInspector.Cancellation());
    }
    private static VaultManifest one() throws Exception { return manifest(key("private", "identity", "private bytes")); }

    @Test public void eachActualSourceAndCandidateUsesItsOwnPrivateBytes() throws Exception {
        List<String> actual = new ArrayList<>();
        List<byte[]> loans = new ArrayList<>(), wires = new ArrayList<>();
        SshManifestPrivateMaterialInspector.NativeBinding binding = (body, phrase, deadline) -> {
            actual.add(new String(body, StandardCharsets.UTF_8)); loans.add(body);
            byte[] wire = frame(0, 1, 28, 0, 2, true);
            try { System.arraycopy(MessageDigest.getInstance("SHA-256").digest(body), 0, wire, 28, 32); }
            catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
            wires.add(wire); return wire;
        };
        try (VaultManifest source = manifest(key("private", "identity", "source"));
                VaultManifest candidate = manifest(key("private", "identity", "candidate"))) {
            SshManifestPrivateMaterialInspector.Report a = new SshManifestPrivateMaterialInspector(new Clock(), binding)
                    .inspect(source, 31_000, new SshManifestPrivateMaterialInspector.Cancellation());
            SshManifestPrivateMaterialInspector.Report b = new SshManifestPrivateMaterialInspector(new Clock(), binding)
                    .inspect(candidate, 31_000, new SshManifestPrivateMaterialInspector.Cancellation());
            assertEquals(Arrays.asList("source", "candidate"), actual);
            assertFalse(a.privateEntries.get(0).verification.publicSubjectDigest.equals(
                    b.privateEntries.get(0).verification.publicSubjectDigest));
            assertArrayEquals(bytes("source"), source.files.get(0).content);
            assertArrayEquals(bytes("candidate"), candidate.files.get(0).content);
            for (byte[] loan : loans) assertTrue(zero(loan));
            for (byte[] wire : wires) assertTrue(zero(wire));
        }
    }

    @Test public void allPrivateDeclarationsAndNonTotpCredentialsKeepActualOrdinals() throws Exception {
        Binding binding = new Binding();
        VaultManifest.Entry phrase = phrase("phrase", "encrypted", "selected phrase");
        VaultManifest.Entry password = password("password", "target:user@example.invalid:2222");
        VaultManifest.Entry otp = totp();
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(otp, password, phrase), Arrays.asList(
                file(VaultManifest.FileType.CONFIG, "config", "config", "unrelated config"),
                key("first", "encrypted", "first private"),
                file(VaultManifest.FileType.CERTIFICATE, "cert", "cert", "unrelated cert"),
                key("second", "clear", "second private"),
                file(VaultManifest.FileType.PUBLIC_KEY, "public", "public", "unrelated public"),
                file(VaultManifest.FileType.KNOWN_HOSTS, "known", "known", "unrelated trust")))) {
            binding.during = () -> {
                int i = binding.bodies.size() - 1;
                assertArrayEquals(bytes(i == 0 ? "first private" : "second private"), binding.bodies.get(i));
                assertNotSame(manifest.files.get(i == 0 ? 1 : 3).content, binding.bodies.get(i));
                if (i == 0) {
                    assertArrayEquals(bytes("selected phrase"), binding.phrases.get(i));
                    assertNotSame(phrase.secret, binding.phrases.get(i));
                } else assertNull(binding.phrases.get(i));
            };
            SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
            assertEquals(2, report.privateEntries.size()); assertEquals(2, report.credentialEntries.size());
            SshManifestPrivateMaterialInspector.PrivateEntry first = report.privateEntries.get(0);
            assertEquals("first", first.privateFileId); assertEquals(1, first.privateOrdinal);
            assertEquals("phrase", first.passphraseEntryId); assertEquals(2, first.passphraseOrdinal);
            assertEquals(SshManifestPrivateMaterialInspector.Ownership.OWNER_RESOLVED, first.ownership);
            assertEquals(3, report.privateEntries.get(1).privateOrdinal);
            assertEquals(SshManifestPrivateMaterialInspector.Ownership.NO_PASSPHRASE, report.privateEntries.get(1).ownership);
            assertEquals(-1, report.privateEntries.get(1).passphraseOrdinal);
            assertEquals("password", report.credentialEntries.get(0).entryId);
            assertEquals(1, report.credentialEntries.get(0).entryOrdinal);
            assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.TARGET_DEFERRED, report.credentialEntries.get(0).status);
            assertNull(report.credentialEntries.get(0).privateFileId);
            assertEquals(-1, report.credentialEntries.get(0).privateOrdinal);
            assertEquals("first", report.credentialEntries.get(1).privateFileId);
            assertEquals(1, report.credentialEntries.get(1).privateOrdinal);
            assertTrue(report.allPrivateDeclarationsVisited && report.allNonTotpCredentialsVisited);
            assertTrue(report.allPrivateMaterialsVerified && report.allPassphraseOwnersResolved);
            assertFalse(report.admissionComplete || report.keyAssociationsChecked || report.passwordTargetsChecked || report.trustChecked);
            assertArrayEquals(bytes("selected phrase"), phrase.secret);
            assertArrayEquals(bytes("unrelated password"), password.secret);
            assertArrayEquals(bytes("0123456789"), otp.secret);
            assertArrayEquals(bytes("unrelated config"), manifest.files.get(0).content);
            clean(binding);
        }
    }

    @Test public void duplicatePrivateIdentitiesWithoutPhraseAreEachCheckedWithNull() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = manifest(key("a", "same", "first"), key("b", "same", "second"))) {
            binding.during = () -> {
                assertNull(binding.phrases.get(binding.bodies.size() - 1));
                assertArrayEquals(bytes(binding.bodies.size() == 1 ? "first" : "second"), binding.bodies.get(binding.bodies.size() - 1));
            };
            SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
            assertEquals(2, binding.bodies.size()); assertTrue(report.allPrivateMaterialsVerified);
            assertTrue(report.allPassphraseOwnersResolved);
            for (SshManifestPrivateMaterialInspector.PrivateEntry entry : report.privateEntries) {
                assertEquals(SshManifestPrivateMaterialInspector.Ownership.NO_PASSPHRASE, entry.ownership);
                assertNull(entry.passphraseEntryId); assertEquals(-1, entry.passphraseOrdinal);
            }
            clean(binding);
        }
    }

    @Test public void onePhraseWithMultiplePrivateOwnersIsAmbiguousWithoutNative() throws Exception {
        Binding binding = new Binding();
        VaultManifest.Entry phrase = phrase("phrase", "same", "must not select");
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(phrase), Arrays.asList(
                key("a", "same", "first"), key("b", "same", "second")))) {
            SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
            assertTrue(binding.bodies.isEmpty()); assertFalse(report.allPrivateMaterialsVerified);
            assertFalse(report.allPassphraseOwnersResolved);
            for (SshManifestPrivateMaterialInspector.PrivateEntry entry : report.privateEntries) {
                assertEquals(SshManifestPrivateMaterialInspector.Ownership.OWNER_AMBIGUOUS, entry.ownership);
                assertNull(entry.passphraseEntryId); assertEquals(-1, entry.passphraseOrdinal); assertNull(entry.verification);
            }
            assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.AMBIGUOUS_PRIVATE, report.credentialEntries.get(0).status);
            assertNull(report.credentialEntries.get(0).privateFileId); assertEquals(-1, report.credentialEntries.get(0).privateOrdinal);
            assertArrayEquals(bytes("must not select"), phrase.secret);
            assertArrayEquals(bytes("first"), manifest.files.get(0).content);
        }
    }

    @Test public void duplicatePhrasesRetainUniquePrivateLocatorButSelectNeitherSecret() throws Exception {
        Binding binding = new Binding();
        VaultManifest.Entry a = phrase("a", "one", "identical phrase"), b = phrase("b", "one", "identical phrase");
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(a, b), Arrays.asList(key("private", "one", "private")))) {
            SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
            assertTrue(binding.bodies.isEmpty()); assertFalse(report.allPrivateMaterialsVerified || report.allPassphraseOwnersResolved);
            assertEquals(SshManifestPrivateMaterialInspector.Ownership.OWNER_AMBIGUOUS, report.privateEntries.get(0).ownership);
            assertNull(report.privateEntries.get(0).verification); assertNull(report.privateEntries.get(0).passphraseEntryId);
            for (SshManifestPrivateMaterialInspector.CredentialEntry entry : report.credentialEntries) {
                assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.AMBIGUOUS_PASSPHRASE, entry.status);
                assertEquals("private", entry.privateFileId); assertEquals(0, entry.privateOrdinal);
            }
            assertArrayEquals(bytes("identical phrase"), a.secret); assertArrayEquals(a.secret, b.secret);
        }
    }

    @Test public void ambiguousPrivateAndPhraseSetsDoNotHideIndependentPrivate() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(phrase("a", "same", "first"),
                phrase("b", "same", "second"), phrase("good", "unique", "good phrase")), Arrays.asList(
                key("first", "same", "first private"), key("second", "same", "second private"),
                key("third", "unique", "third private")))) {
            binding.during = () -> {
                assertArrayEquals(bytes("third private"), binding.bodies.get(0));
                assertArrayEquals(bytes("good phrase"), binding.phrases.get(0));
            };
            SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
            assertEquals(1, binding.bodies.size()); assertEquals(3, report.privateEntries.size());
            assertEquals(3, report.credentialEntries.size());
            assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.AMBIGUOUS_PRIVATE, report.credentialEntries.get(0).status);
            assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.AMBIGUOUS_PRIVATE, report.credentialEntries.get(1).status);
            assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.OWNER_RESOLVED, report.credentialEntries.get(2).status);
            assertEquals(SshManifestPrivateMaterialInspector.Status.MATCH, report.privateEntries.get(2).verification.status);
            clean(binding);
        }
    }

    @Test public void passwordsUseOnlyTypedNamespaceAndOpaqueTargetSuffix() throws Exception {
        Binding binding = new Binding();
        List<VaultManifest.Entry> entries = Arrays.asList(password("a", "target:not-a-parsed-account"),
                password("b", "target:user@example.invalid:2222"), password("c", "key:looks-like-private"));
        try (VaultManifest manifest = new VaultManifest(entries, VaultCollections.list())) {
            SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
            assertTrue(binding.bodies.isEmpty()); assertEquals(3, report.credentialEntries.size());
            assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.TARGET_DEFERRED, report.credentialEntries.get(0).status);
            assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.TARGET_DEFERRED, report.credentialEntries.get(1).status);
            assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.INVALID_NAMESPACE, report.credentialEntries.get(2).status);
            assertTrue(report.allPrivateMaterialsVerified && report.allPassphraseOwnersResolved);
            assertFalse(report.passwordTargetsChecked || report.admissionComplete);
            for (VaultManifest.Entry entry : entries) assertArrayEquals(bytes("unrelated password"), entry.secret);
        }
    }

    @Test public void v1StillRejectsOrphanAndWrongNamespacePhraseRatherThanWeakeningStructure() throws Exception {
        structuralRejection(() -> new VaultManifest(Arrays.asList(phrase("orphan", "absent", "phrase")), VaultCollections.list()));
        VaultManifest.Entry wrong = new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE,
                "wrong", "target:identity", "", "", 0, 0, bytes("phrase"));
        structuralRejection(() -> new VaultManifest(Arrays.asList(wrong), Arrays.asList(key("private", "identity", "body"))));
    }

    @Test public void chosenBodiesAndPhrasesAreCapturedBeforeFirstNativeCall() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(phrase("phrase", "second", "second phrase")),
                Arrays.asList(key("first", "first", "first private"), key("second", "second", "second private")))) {
            binding.during = () -> {
                if (binding.bodies.size() == 1) {
                    Arrays.fill(manifest.files.get(1).content, (byte)'x');
                    Arrays.fill(manifest.entries.get(0).secret, (byte)'y');
                } else {
                    assertArrayEquals(bytes("second private"), binding.bodies.get(1));
                    assertArrayEquals(bytes("second phrase"), binding.phrases.get(1));
                }
            };
            inspect(manifest, binding); clean(binding);
        }
    }

    @Test public void materialFailuresRemainPerPrivateAndOwnerResolutionDoesNotMeanDecryption() throws Exception {
        for (int status = 1; status <= 5; status++) {
            Binding binding = new Binding(); binding.response = frame(status, 0, status >= 4 ? 5 : 4,
                    status == 1 ? 4 : 1, 2, false);
            try (VaultManifest manifest = new VaultManifest(Arrays.asList(phrase("phrase", "identity", "phrase")),
                    Arrays.asList(key("private", "identity", "body")))) {
                SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
                assertEquals(SshManifestPrivateMaterialInspector.Status.values()[status], report.privateEntries.get(0).verification.status);
                assertFalse(report.allPrivateMaterialsVerified); assertTrue(report.allPassphraseOwnersResolved);
                assertEquals(SshManifestPrivateMaterialInspector.CredentialStatus.OWNER_RESOLVED, report.credentialEntries.get(0).status);
                assertEquals("", report.privateEntries.get(0).verification.publicSubjectDigest);
                clean(binding);
            }
        }
    }

    @Test public void allStatusStageCombinationsEnforcePrivateOnlyClosure() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int status = 0; status <= 5; status++) for (int stage = -1; stage <= 5; stage++) {
                Binding binding = new Binding(); binding.response = frame(status, status == 0 ? 1 : 0,
                        status == 0 ? 28 : status >= 4 ? 5 : 4, stage, 2, status == 0);
                boolean allowed = stage == (status == 0 ? 0 : status == 1 ? 4 : 1);
                if (allowed) inspect(manifest, binding); else rejected(() -> inspect(manifest, binding));
                clean(binding);
            }
        }
    }

    @Test public void allFlagBytesEnforceWholeProofAndRequiredPrivateMarker() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int status : new int[]{0, 1, 4}) for (int flags = 0; flags < 256; flags++) {
                Binding binding = new Binding(); binding.response = frame(status, status == 0 ? 1 : 0, flags,
                        status == 0 ? 0 : status == 1 ? 4 : 1, 2, status == 0);
                boolean allowed = status == 0 ? flags == 28 || flags == 29
                        : status == 4 ? flags == 5 : flags == 4 || flags == 5;
                if (allowed) inspect(manifest, binding); else rejected(() -> inspect(manifest, binding));
                clean(binding);
            }
        }
    }

    @Test public void magicVersionTypeLengthsAndEnumsRejectOtherProtocolsAndTrailingBytes() throws Exception {
        List<byte[]> invalid = new ArrayList<>(); byte[] good = frame(0, 1, 28, 0, 2, true);
        for (int n = 0; n < 60; n++) invalid.add(Arrays.copyOf(good, n));
        invalid.add(Arrays.copyOf(good, 61));
        invalid.add(integer(good, 0, 0x43534b41)); invalid.add(integer(good, 0, 0x43534d49));
        invalid.add(integer(good, 4, 0)); invalid.add(integer(good, 4, 2));
        for (int n : new int[]{-1, 0, 1, 3, 4, 5}) invalid.add(integer(good, 24, n));
        for (int n : new int[]{-1, 6, Integer.MAX_VALUE}) invalid.add(integer(good, 8, n));
        invalid.add(integer(good, 16, -1));
        try (VaultManifest manifest = one()) {
            for (byte[] wire : invalid) {
                Binding binding = new Binding(); binding.response = wire;
                rejected(() -> inspect(manifest, binding)); clean(binding);
            }
            Binding binding = new Binding(); binding.returnNull = true;
            rejected(() -> inspect(manifest, binding)); clean(binding);
        }
    }

    @Test public void keyKindAndPublicDigestAppearOnlyOnCompleteSuccessfulProof() throws Exception {
        try (VaultManifest manifest = one()) {
            for (int kind = -1; kind <= 6; kind++) for (boolean digest : new boolean[]{false, true}) {
                Binding binding = new Binding(); binding.response = frame(0, kind, 28, 0, 2, digest);
                if (kind >= 1 && kind <= 5 && digest) inspect(manifest, binding);
                else rejected(() -> inspect(manifest, binding));
                clean(binding);
            }
            for (int kind = 0; kind <= 5; kind++) for (boolean digest : new boolean[]{false, true}) {
                Binding binding = new Binding(); binding.response = frame(1, kind, 4, 4, 2, digest);
                if (kind == 0 && !digest) inspect(manifest, binding);
                else rejected(() -> inspect(manifest, binding));
                clean(binding);
            }
        }
    }

    @Test public void ioRuntimeLinkageAndErrorFailuresWipeAndReleaseThreadGuard() throws Exception {
        List<Throwable> failures = Arrays.asList(new IOException("injected"), new IllegalStateException("injected"),
                new UnsatisfiedLinkError("injected"), new AssertionError("injected"));
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(phrase("phrase", "identity", "phrase")),
                Arrays.asList(key("private", "identity", "body")))) {
            for (Throwable failure : failures) {
                Binding binding = new Binding(); binding.during = () -> {
                    if (failure instanceof IOException) throw (IOException)failure;
                    if (failure instanceof RuntimeException) throw (RuntimeException)failure;
                    throw (Error)failure;
                };
                try { inspect(manifest, binding); fail("expected injected failure"); }
                catch (Throwable actual) { assertTrue(actual == failure); }
                clean(binding); assertTrue(inspect(manifest, new Binding()).allPrivateMaterialsVerified);
                assertArrayEquals(bytes("body"), manifest.files.get(0).content);
                assertArrayEquals(bytes("phrase"), manifest.entries.get(0).secret);
            }
        }
    }

    @Test public void laterPrivateFailurePublishesNoPrefixAndWipesEarlierWire() throws Exception {
        Binding binding = new Binding();
        binding.during = () -> { if (binding.bodies.size() == 2) throw new IOException("second failed"); };
        try (VaultManifest manifest = manifest(key("a", "a", "first"), key("b", "b", "second"))) {
            rejected(() -> inspect(manifest, binding));
            assertEquals(2, binding.bodies.size()); assertEquals(1, binding.wires.size()); clean(binding);
        }
    }

    @Test public void oneOriginalDeadlineGovernsAllPrivateCallsWithoutRenewal() throws Exception {
        Clock clock = new Clock(); Binding binding = new Binding();
        binding.during = () -> clock.time += 1000;
        try (VaultManifest manifest = manifest(key("a", "a", "first"), key("b", "b", "second"))) {
            new SshManifestPrivateMaterialInspector(clock, binding).inspect(manifest, 31_000,
                    new SshManifestPrivateMaterialInspector.Cancellation());
            assertEquals(Arrays.asList(31_000L, 31_000L), binding.deadlines); clean(binding);
        }
    }

    @Test public void cancellationOrExpiryAfterNativeBlocksPublicationAndWipes() throws Exception {
        try (VaultManifest manifest = one()) {
            for (boolean cancel : new boolean[]{false, true}) {
                Clock clock = new Clock(); Binding binding = new Binding();
                SshManifestPrivateMaterialInspector.Cancellation cancellation = new SshManifestPrivateMaterialInspector.Cancellation();
                binding.during = () -> { if (cancel) cancellation.cancel(); else clock.time = 31_000; };
                rejected(() -> new SshManifestPrivateMaterialInspector(clock, binding).inspect(manifest, 31_000, cancellation));
                assertEquals(1, binding.bodies.size()); clean(binding);
            }
        }
    }

    @Test public void finalFenceRunsAfterOwnedInputsAndWireHaveBeenWiped() throws Exception {
        Clock clock = new Clock(); Binding binding = new Binding();
        SshManifestPrivateMaterialInspector.Cancellation cancellation = new SshManifestPrivateMaterialInspector.Cancellation();
        clock.during = () -> {
            if (!binding.bodies.isEmpty() && zero(binding.bodies.get(0)) && zero(binding.phrases.get(0))) cancellation.cancel();
        };
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(phrase("phrase", "identity", "phrase")),
                Arrays.asList(key("private", "identity", "body")))) {
            rejected(() -> new SshManifestPrivateMaterialInspector(clock, binding).inspect(manifest, 31_000, cancellation));
            assertTrue(cancellation.cancelled()); clean(binding);
        }
    }

    @Test public void preCancelledExactDeadlineAndOverlongBudgetNeverCallNative() throws Exception {
        try (VaultManifest manifest = one()) {
            for (long deadline : new long[]{0, 1000, 61_001, Long.MAX_VALUE}) {
                Binding binding = new Binding();
                rejected(() -> new SshManifestPrivateMaterialInspector(new Clock(), binding).inspect(manifest, deadline,
                        new SshManifestPrivateMaterialInspector.Cancellation()));
                assertTrue(binding.bodies.isEmpty());
            }
            Binding binding = new Binding(); SshManifestPrivateMaterialInspector.Cancellation cancellation = new SshManifestPrivateMaterialInspector.Cancellation();
            cancellation.cancel();
            rejected(() -> new SshManifestPrivateMaterialInspector(new Clock(), binding).inspect(manifest, 31_000, cancellation));
            assertTrue(binding.bodies.isEmpty());
        }
    }

    @Test public void selectedNoNulPhrasePolicyIsCheckedBeforeAnyNativeCall() throws Exception {
        Binding binding = new Binding();
        VaultManifest.Entry phrase = phrase("phrase", "identity", "valid phrase"); phrase.secret[2] = 0;
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(phrase), Arrays.asList(
                key("first", "clear", "first private"), key("second", "identity", "second private")))) {
            rejected(() -> inspect(manifest, binding)); assertTrue(binding.bodies.isEmpty());
            assertArrayEquals(bytes("first private"), manifest.files.get(0).content);
        }
    }

    @Test public void maximumPhraseAndEightMiBPrivateBudgetAreAdmittedOncePerKey() throws Exception {
        Binding binding = new Binding(); byte[] body = new byte[1024 * 1024]; Arrays.fill(body, (byte)'p');
        char[] phraseChars = new char[8192]; Arrays.fill(phraseChars, 's');
        List<VaultManifest.FileObject> files = new ArrayList<>();
        for (int i = 0; i < 8; i++) files.add(file(VaultManifest.FileType.PRIVATE_KEY, "key" + i, "identity" + i, body));
        VaultManifest.Entry phrase = phrase("phrase", "identity0", new String(phraseChars));
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(phrase), files)) {
            binding.during = () -> {
                assertEquals(1024 * 1024, binding.bodies.get(binding.bodies.size() - 1).length);
                if (binding.bodies.size() == 1) assertEquals(8192, binding.phrases.get(0).length);
            };
            SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
            assertEquals(8, binding.bodies.size()); assertEquals(8, report.privateEntries.size()); clean(binding);
        }
        List<VaultManifest.FileObject> tooManyBytes = new ArrayList<>(files);
        tooManyBytes.add(file(VaultManifest.FileType.PRIVATE_KEY, "overflow", "overflow", body));
        structuralRejection(() -> new VaultManifest(VaultCollections.list(), tooManyBytes));
    }

    @Test public void maximumDeclarationCountsAreVisitedWithoutSecretOrNativeWorkForPasswords() throws Exception {
        List<VaultManifest.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 1024; i++) entries.add(password("entry" + i, "target:opaque" + i));
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(entries, VaultCollections.list())) {
            SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
            assertEquals(1024, report.credentialEntries.size()); assertTrue(binding.bodies.isEmpty());
            assertFalse(report.passwordTargetsChecked); assertTrue(report.allPassphraseOwnersResolved);
        }
        List<VaultManifest.FileObject> files = new ArrayList<>();
        for (int i = 0; i < 128; i++) files.add(key("key" + i, "duplicate", "actual" + i));
        binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(VaultCollections.list(), files)) {
            assertEquals(128, inspect(manifest, binding).privateEntries.size()); assertEquals(128, binding.bodies.size()); clean(binding);
        }
    }

    @Test public void reportsAreImmutableAndEmptyOrPureTotpStillUseOriginalLifetime() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest empty = VaultManifest.empty();
                VaultManifest pure = new VaultManifest(Arrays.asList(totp()), VaultCollections.list())) {
            for (VaultManifest manifest : Arrays.asList(empty, pure)) {
                SshManifestPrivateMaterialInspector.Report report = inspect(manifest, binding);
                assertTrue(report.privateEntries.isEmpty() && report.credentialEntries.isEmpty());
                assertTrue(report.allPrivateMaterialsVerified && report.allPassphraseOwnersResolved);
                assertFalse(report.admissionComplete || report.passwordTargetsChecked || report.trustChecked || report.keyAssociationsChecked);
                try { report.privateEntries.clear(); fail("immutable private list"); } catch (UnsupportedOperationException expected) { }
                try { report.credentialEntries.clear(); fail("immutable credential list"); } catch (UnsupportedOperationException expected) { }
                rejected(() -> new SshManifestPrivateMaterialInspector(new Clock(), binding).inspect(manifest, 1000,
                        new SshManifestPrivateMaterialInspector.Cancellation()));
            }
            assertTrue(binding.bodies.isEmpty());
        }
    }

    @Test public void ownerThreadAndOneAttemptApplyAlsoAfterFailedAdmission() throws Exception {
        Binding binding = new Binding();
        SshManifestPrivateMaterialInspector inspector = new SshManifestPrivateMaterialInspector(new Clock(), binding);
        try (VaultManifest manifest = one()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try { inspector.inspect(manifest, 31_000, new SshManifestPrivateMaterialInspector.Cancellation()); }
                catch (Throwable error) { failure.set(error); }
            });
            other.start(); other.join(5000); assertFalse(other.isAlive()); assertTrue(failure.get() instanceof IOException);
            assertTrue(binding.bodies.isEmpty());
            assertTrue(inspector.inspect(manifest, 31_000, new SshManifestPrivateMaterialInspector.Cancellation()).allPrivateMaterialsVerified);
            rejected(() -> inspector.inspect(manifest, 31_000, new SshManifestPrivateMaterialInspector.Cancellation()));
            clean(binding);
            Binding second = new Binding();
            SshManifestPrivateMaterialInspector failed = new SshManifestPrivateMaterialInspector(new Clock(), second);
            rejected(() -> failed.inspect(null, 31_000, new SshManifestPrivateMaterialInspector.Cancellation()));
            rejected(() -> failed.inspect(manifest, 31_000, new SshManifestPrivateMaterialInspector.Cancellation()));
            assertTrue(second.bodies.isEmpty());
        }
    }

    @Test public void sameAndCrossInstanceReentryRejectWithoutConsumingSibling() throws Exception {
        Binding outerBinding = new Binding(), innerBinding = new Binding();
        SshManifestPrivateMaterialInspector inner = new SshManifestPrivateMaterialInspector(new Clock(), innerBinding);
        SshManifestPrivateMaterialInspector[] outer = {new SshManifestPrivateMaterialInspector(new Clock(), outerBinding)};
        try (VaultManifest manifest = one()) {
            outerBinding.during = () -> {
                rejected(() -> outer[0].inspect(manifest, 31_000, new SshManifestPrivateMaterialInspector.Cancellation()));
                rejected(() -> inner.inspect(manifest, 31_000, new SshManifestPrivateMaterialInspector.Cancellation()));
            };
            outer[0].inspect(manifest, 31_000, new SshManifestPrivateMaterialInspector.Cancellation());
            assertTrue(innerBinding.bodies.isEmpty());
            assertTrue(inner.inspect(manifest, 31_000, new SshManifestPrivateMaterialInspector.Cancellation()).allPrivateMaterialsVerified);
            clean(outerBinding); clean(innerBinding);
        }
    }

    @Test public void nullArgumentsAndDependenciesRejectWithoutNative() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = one()) {
            rejected(() -> new SshManifestPrivateMaterialInspector(new Clock(), binding).inspect(manifest, 31_000, null));
            rejected(() -> new SshManifestPrivateMaterialInspector(new Clock(), binding).inspect(null, 31_000,
                    new SshManifestPrivateMaterialInspector.Cancellation()));
            assertTrue(binding.bodies.isEmpty());
        }
        try { new SshManifestPrivateMaterialInspector(null, binding); fail("clock required"); } catch (IllegalArgumentException expected) { }
        try { new SshManifestPrivateMaterialInspector(new Clock(), null); fail("binding required"); } catch (IllegalArgumentException expected) { }
    }
}
