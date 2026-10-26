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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Explicit supplied-pair ownership and independent CSKA vectors; no crypto parser claims. */
public final class SshManifestKeyAssociationInspectorTest {
    private interface Action { void run() throws Exception; }
    private static final class Clock implements SshManifestKeyAssociationInspector.Clock {
        long time = 1000;
        Runnable during;
        @Override public long now() {
            if (during != null) during.run();
            return time;
        }
    }
    private static final class Binding implements SshManifestKeyAssociationInspector.NativeBinding {
        final List<byte[]> privateBodies = new ArrayList<>(), peerBodies = new ArrayList<>();
        final List<byte[]> phrases = new ArrayList<>(), wires = new ArrayList<>();
        final List<Integer> types = new ArrayList<>();
        final List<Long> deadlines = new ArrayList<>();
        byte[] response;
        boolean returnNull;
        Action during;
        @Override public byte[] verify(int type, byte[] privateBody, byte[] phrase,
                byte[] peerBody, long deadline) throws IOException {
            privateBodies.add(privateBody); peerBodies.add(peerBody); phrases.add(phrase);
            types.add(type); deadlines.add(deadline);
            if (during != null) {
                try { during.run(); }
                catch (IOException | RuntimeException | Error error) { throw error; }
                catch (Exception error) { throw new AssertionError(error); }
            }
            if (returnNull) return null;
            byte[] wire = response == null ? frame(0, 1, 28, 0, type, true) : response.clone();
            wires.add(wire);
            return wire;
        }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id,
            String identity, byte[] body) {
        return new VaultManifest.FileObject(type, id, "ssh/" + id, identity, body,
                VaultCollections.list());
    }
    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id,
            String identity, String body) { return file(type, id, identity, bytes(body)); }
    private static VaultManifest.Entry phrase(String id, String identity, String body) {
        return new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE, id,
                "key:" + identity, "", "", 0, 0, bytes(body));
    }
    private static VaultManifest simple() throws Exception {
        return new VaultManifest(Arrays.asList(phrase("phraseA", "identity", "first phrase"),
                phrase("phraseB", "identity", "selected phrase")), Arrays.asList(
                file(VaultManifest.FileType.PRIVATE_KEY, "priv", "identity", "private bytes"),
                file(VaultManifest.FileType.PUBLIC_KEY, "pub", "different", "public bytes"),
                file(VaultManifest.FileType.CERTIFICATE, "cert", "third", "certificate bytes")));
    }
    private static SshManifestKeyAssociationInspector.Association pair(String privateId,
            String peerId, String phraseId) {
        return new SshManifestKeyAssociationInspector.Association(privateId, peerId, phraseId);
    }
    private static List<SshManifestKeyAssociationInspector.Association> selected(String phraseId) {
        return Collections.singletonList(pair("priv", "pub", phraseId));
    }
    private static byte[] frame(int status, int kind, int flags, int stage, int type,
            boolean digest) {
        ByteBuffer wire = ByteBuffer.allocate(60);
        wire.putInt(0x43534b41).putInt(1).putInt(status).putInt(kind).putInt(flags)
                .putInt(stage).putInt(type);
        if (digest) wire.put((byte)0x80);
        return wire.array();
    }
    private static byte[] integer(byte[] original, int offset, int value) {
        byte[] result = original.clone();
        ByteBuffer.wrap(result).putInt(offset, value);
        return result;
    }
    private static void rejected(Action action) throws Exception {
        try { action.run(); fail("expected association inspection rejection"); }
        catch (IOException expected) { }
    }
    private static boolean zero(byte[] value) {
        if (value == null) return true;
        for (byte b : value) if (b != 0) return false;
        return true;
    }
    private static void clean(Binding binding) {
        for (List<byte[]> values : Arrays.asList(binding.privateBodies, binding.peerBodies,
                binding.phrases, binding.wires)) {
            for (byte[] value : values) if (value != null) assertArrayEquals(new byte[value.length], value);
        }
    }
    private static SshManifestKeyAssociationInspector.Report inspect(VaultManifest manifest,
            List<SshManifestKeyAssociationInspector.Association> pairs, Binding binding)
            throws IOException {
        return new SshManifestKeyAssociationInspector(new Clock(), binding).inspect(manifest,
                pairs, 31_000, new SshManifestKeyAssociationInspector.Cancellation());
    }

    @Test public void actualSuppliedSourceAndCandidateBytesDetermineTheirIndependentResults() throws Exception {
        List<String> observed = new ArrayList<>();
        SshManifestKeyAssociationInspector.NativeBinding binding = (type, privateBody, phrase,
                peerBody, deadline) -> {
            observed.add(new String(privateBody, StandardCharsets.UTF_8));
            byte[] wire = frame(0, 1, 28, 0, type, true);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(peerBody);
                System.arraycopy(digest, 0, wire, 28, 32);
            } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
            return wire;
        };
        try (VaultManifest source = new VaultManifest(VaultCollections.list(), Arrays.asList(
                    file(VaultManifest.FileType.PRIVATE_KEY, "priv", "same", "source private"),
                    file(VaultManifest.FileType.PUBLIC_KEY, "pub", "same", "source public")));
                VaultManifest candidate = new VaultManifest(VaultCollections.list(), Arrays.asList(
                    file(VaultManifest.FileType.PRIVATE_KEY, "priv", "same", "candidate private"),
                    file(VaultManifest.FileType.PUBLIC_KEY, "pub", "same", "candidate public")))) {
            SshManifestKeyAssociationInspector.Report a = new SshManifestKeyAssociationInspector(
                    new Clock(), binding).inspect(source, selected(null), 31_000,
                    new SshManifestKeyAssociationInspector.Cancellation());
            SshManifestKeyAssociationInspector.Report b = new SshManifestKeyAssociationInspector(
                    new Clock(), binding).inspect(candidate, selected(null), 31_000,
                    new SshManifestKeyAssociationInspector.Cancellation());
            assertEquals(Arrays.asList("source private", "candidate private"), observed);
            assertFalse(a.entries.get(0).publicSubjectDigest.equals(b.entries.get(0).publicSubjectDigest));
            assertArrayEquals(bytes("source private"), source.files.get(0).content);
            assertArrayEquals(bytes("candidate private"), candidate.files.get(0).content);
        }
    }

    @Test public void exactIdsChooseOrdinalsAndOneOfSeveralSameIdentityPhrases() throws Exception {
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "password", "target:host", "", "", 0, 0, bytes("unrelated password"));
        VaultManifest.Entry otp = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "otp", "otp",
                "", "SHA1", 6, 30, bytes("0123456789"));
        VaultManifest.Entry a = phrase("phraseA", "identity", "first phrase");
        VaultManifest.Entry b = phrase("phraseB", "identity", "selected phrase");
        Binding binding = new Binding();
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(otp, password, a, b), Arrays.asList(
                file(VaultManifest.FileType.CONFIG, "config", "config", "unselected config"),
                file(VaultManifest.FileType.CERTIFICATE, "cert", "unrelated-name", "certificate"),
                file(VaultManifest.FileType.PRIVATE_KEY, "priv", "identity", "private"),
                file(VaultManifest.FileType.PUBLIC_KEY, "pub", "another-name", "public")))) {
            binding.during = () -> {
                int call = binding.types.size() - 1;
                assertArrayEquals(bytes("private"), binding.privateBodies.get(call));
                assertArrayEquals(bytes("selected phrase"), binding.phrases.get(call));
                assertNotSame(manifest.files.get(2).content, binding.privateBodies.get(call));
                assertNotSame(b.secret, binding.phrases.get(call));
                assertArrayEquals(bytes(call == 0 ? "public" : "certificate"), binding.peerBodies.get(call));
            };
            SshManifestKeyAssociationInspector.Report report = inspect(manifest, Arrays.asList(
                    pair("priv", "pub", "phraseB"), pair("priv", "cert", "phraseB")), binding);
            assertEquals(Arrays.asList(3, 4), binding.types);
            for (int i = 0; i < 2; i++) {
                SshManifestKeyAssociationInspector.Entry entry = report.entries.get(i);
                assertEquals("priv", entry.privateFileId); assertEquals("phraseB", entry.passphraseEntryId);
                assertEquals(2, entry.privateOrdinal); assertEquals(3, entry.passphraseOrdinal);
                assertEquals(i == 0 ? "pub" : "cert", entry.peerFileId);
                assertEquals(i == 0 ? 3 : 1, entry.peerOrdinal);
                assertEquals(i == 0 ? VaultManifest.FileType.PUBLIC_KEY : VaultManifest.FileType.CERTIFICATE,
                        entry.peerType);
            }
            assertSame(binding.privateBodies.get(0), binding.privateBodies.get(1));
            assertSame(binding.phrases.get(0), binding.phrases.get(1));
            assertArrayEquals(bytes("selected phrase"), b.secret);
            assertArrayEquals(bytes("first phrase"), a.secret);
            assertArrayEquals(bytes("unrelated password"), password.secret);
            assertArrayEquals(bytes("0123456789"), otp.secret);
            assertArrayEquals(bytes("unselected config"), manifest.files.get(0).content);
            clean(binding);
        }
    }

    @Test public void omittedPhraseNeverGuessesFromMatchingIdentity() throws Exception {
        Binding binding = new Binding();
        binding.response = frame(4, 0, 5, 1, 3, false);
        try (VaultManifest manifest = simple()) {
            SshManifestKeyAssociationInspector.Report report = inspect(manifest, selected(null), binding);
            assertNull(binding.phrases.get(0));
            assertEquals(-1, report.entries.get(0).passphraseOrdinal);
            assertNull(report.entries.get(0).passphraseEntryId);
            assertEquals(SshManifestKeyAssociationInspector.Status.NEEDS_PASSPHRASE,
                    report.entries.get(0).status);
            assertArrayEquals(bytes("first phrase"), manifest.entries.get(0).secret);
            assertArrayEquals(bytes("selected phrase"), manifest.entries.get(1).secret);
            clean(binding);
        }
    }

    @Test public void sharedSelectedFilesAndPhrasesAreCopiedOnceBeforeFirstBinding() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest manifest = simple()) {
            binding.during = () -> {
                int call = binding.types.size() - 1;
                if (call == 0) {
                    Arrays.fill(manifest.files.get(2).content, (byte)'x');
                    Arrays.fill(manifest.entries.get(1).secret, (byte)'y');
                } else if (call == 1) {
                    assertArrayEquals(bytes("certificate bytes"), binding.peerBodies.get(call));
                    assertArrayEquals(bytes("selected phrase"), binding.phrases.get(call));
                } else {
                    assertArrayEquals(bytes("public bytes"), binding.peerBodies.get(call));
                    assertNull(binding.phrases.get(call));
                }
            };
            inspect(manifest, Arrays.asList(pair("priv", "pub", "phraseB"),
                    pair("priv", "cert", "phraseB"), pair("priv", "pub", null)), binding);
            assertSame(binding.privateBodies.get(0), binding.privateBodies.get(2));
            assertSame(binding.peerBodies.get(0), binding.peerBodies.get(2));
            assertSame(binding.phrases.get(0), binding.phrases.get(1));
            assertNull(binding.phrases.get(2));
            clean(binding);
        }
    }

    @Test public void invalidIdsTypesMetadataAndTupleListsRejectBeforeBinding() throws Exception {
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "password", "key:identity", "", "", 0, 0, bytes("password"));
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(phrase("phraseA", "identity", "phrase"),
                    phrase("otherPhrase", "other", "other"), password), Arrays.asList(
                    file(VaultManifest.FileType.PRIVATE_KEY, "priv", "identity", "private"),
                    file(VaultManifest.FileType.PUBLIC_KEY, "pub", "peer", "public"),
                    file(VaultManifest.FileType.PRIVATE_KEY, "other", "other", "other"),
                    file(VaultManifest.FileType.CONFIG, "config", "config", "config")))) {
            List<List<SshManifestKeyAssociationInspector.Association>> invalid = new ArrayList<>();
            invalid.add(Collections.emptyList());
            invalid.add(Collections.singletonList(null));
            invalid.add(Collections.singletonList(pair(null, "pub", null)));
            invalid.add(Collections.singletonList(pair("priv", null, null)));
            invalid.add(Collections.singletonList(pair("missing", "pub", null)));
            invalid.add(Collections.singletonList(pair("priv", "missing", null)));
            invalid.add(Collections.singletonList(pair("pub", "pub", null)));
            invalid.add(Collections.singletonList(pair("priv", "priv", null)));
            invalid.add(Collections.singletonList(pair("priv", "config", null)));
            invalid.add(Collections.singletonList(pair("priv", "pub", "missing")));
            invalid.add(Collections.singletonList(pair("priv", "pub", "password")));
            invalid.add(Collections.singletonList(pair("priv", "pub", "otherPhrase")));
            invalid.add(Arrays.asList(pair("priv", "pub", "phraseA"), pair("priv", "pub", "phraseA")));
            invalid.add(Collections.nCopies(129, pair("priv", "pub", null)));
            for (List<SshManifestKeyAssociationInspector.Association> pairs : invalid) {
                Binding binding = new Binding();
                rejected(() -> inspect(manifest, pairs, binding));
                assertTrue(binding.privateBodies.isEmpty());
            }
            manifest.entries.get(0).secret[0] = 0;
            Binding binding = new Binding();
            rejected(() -> inspect(manifest, selected("phraseA"), binding));
            assertTrue(binding.privateBodies.isEmpty());
        }
    }

    @Test public void nullArgumentsAndInvalidDependenciesDoNotInvokeBinding() throws Exception {
        Binding binding = new Binding();
        rejected(() -> inspect(null, selected(null), binding));
        try (VaultManifest manifest = simple()) {
            rejected(() -> inspect(manifest, null, binding));
            rejected(() -> new SshManifestKeyAssociationInspector(new Clock(), binding).inspect(
                    manifest, selected(null), 31_000, null));
        }
        assertTrue(binding.privateBodies.isEmpty());
        try { new SshManifestKeyAssociationInspector(null, binding); fail("clock required"); }
        catch (IllegalArgumentException expected) { }
        try { new SshManifestKeyAssociationInspector(new Clock(), null); fail("binding required"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void materialFailureIsRetainedAndAllOwnedArraysAndWireAreWiped() throws Exception {
        try (VaultManifest manifest = simple()) {
            for (int status = 0; status <= 5; status++) {
                Binding binding = new Binding();
                int stage = status == 0 ? 0 : status == 1 ? 3 : 1;
                binding.response = frame(status, status == 0 ? 1 : 0,
                        status == 0 ? 28 : status >= 4 ? 5 : 4, stage, 3, status == 0);
                SshManifestKeyAssociationInspector.Report report = inspect(manifest, selected("phraseB"), binding);
                assertEquals(SshManifestKeyAssociationInspector.Status.values()[status], report.entries.get(0).status);
                assertEquals(status == 0, report.allRequestedPairsMatch);
                if (status != 0) assertEquals("", report.entries.get(0).publicSubjectDigest);
                clean(binding);
                assertArrayEquals(bytes("private bytes"), manifest.files.get(0).content);
                assertArrayEquals(bytes("selected phrase"), manifest.entries.get(1).secret);
            }
        }
    }

    @Test public void ioRuntimeAndErrorFailuresWipeOwnedInputsAndDoNotPoisonGuard() throws Exception {
        try (VaultManifest manifest = simple()) {
            for (Throwable failure : new Throwable[]{new IOException("io"),
                    new IllegalStateException("runtime"), new AssertionError("error"), new LinkageError("linkage")}) {
                Binding binding = new Binding();
                binding.during = () -> {
                    if (failure instanceof IOException) throw (IOException)failure;
                    if (failure instanceof RuntimeException) throw (RuntimeException)failure;
                    throw (Error)failure;
                };
                try { inspect(manifest, selected("phraseB"), binding); fail("failure required"); }
                catch (IOException | RuntimeException | Error observed) { assertSame(failure, observed); }
                clean(binding);
                assertArrayEquals(bytes("private bytes"), manifest.files.get(0).content);
                assertArrayEquals(bytes("selected phrase"), manifest.entries.get(1).secret);
                assertTrue(inspect(manifest, selected(null), new Binding()).allRequestedPairsMatch);
            }
        }
    }

    @Test public void laterFailureReturnsNoPrefixAndWipesPreviouslyReturnedWire() throws Exception {
        Binding binding = new Binding();
        binding.during = () -> { if (binding.types.size() == 2) throw new IOException("second pair"); };
        try (VaultManifest manifest = simple()) {
            rejected(() -> inspect(manifest, Arrays.asList(pair("priv", "pub", "phraseA"),
                    pair("priv", "cert", "phraseB")), binding));
            assertEquals(2, binding.types.size()); assertEquals(1, binding.wires.size());
            clean(binding);
        }
    }

    @Test public void everyWireStatusAndStageCombinationHasIndependentClosure() throws Exception {
        try (VaultManifest manifest = simple()) {
            for (int status = 0; status <= 5; status++) {
                for (int stage = 0; stage <= 4; stage++) {
                    Binding binding = new Binding();
                    binding.response = frame(status, status == 0 ? 1 : 0,
                            status == 0 ? 28 : status >= 4 ? 5 : 4, stage, 3, status == 0);
                    boolean accepted = status == 0 ? stage == 0 : status == 1 ? stage == 3 || stage == 4
                            : status == 2 || status == 3 ? stage == 1 || stage == 2 : stage == 1;
                    if (accepted) {
                        assertEquals(SshManifestKeyAssociationInspector.Stage.values()[stage],
                                inspect(manifest, selected(null), binding).entries.get(0).stage);
                    } else rejected(() -> inspect(manifest, selected(null), binding));
                    clean(binding);
                }
            }
        }
    }

    @Test public void everyFlagCombinationRequiresPairMarkerAndWholeProofOrNoProof() throws Exception {
        try (VaultManifest manifest = simple()) {
            for (int status : new int[]{0, 1, 4, 5}) {
                for (int flags = 0; flags < 64; flags++) {
                    Binding binding = new Binding();
                    binding.response = frame(status, status == 0 ? 1 : 0, flags,
                            status == 0 ? 0 : status == 1 ? 3 : 1, 3, status == 0);
                    boolean accepted = status == 0 ? flags == 28 || flags == 29
                            : status == 1 ? flags == 4 || flags == 5 : flags == 5;
                    if (accepted) assertEquals((flags & 1) != 0,
                            inspect(manifest, selected(null), binding).entries.get(0).encrypted);
                    else rejected(() -> inspect(manifest, selected(null), binding));
                    clean(binding);
                }
            }
        }
    }

    @Test public void truncatedTrailingNullHeadersEnumsAndPeerTypesAreRejected() throws Exception {
        try (VaultManifest manifest = simple()) {
            byte[] valid = frame(0, 1, 28, 0, 3, true);
            List<byte[]> invalid = new ArrayList<>();
            for (int size = 0; size < 60; size++) invalid.add(Arrays.copyOf(valid, size));
            invalid.add(Arrays.copyOf(valid, 61));
            int[][] fields = {{0, 0}, {4, 0}, {4, 2}, {8, -1}, {8, 6}, {12, -1}, {12, 6},
                    {16, -1}, {20, -1}, {20, 5}, {24, 2}, {24, 4}, {24, 5}};
            for (int[] field : fields) invalid.add(integer(valid, field[0], field[1]));
            for (byte[] wire : invalid) {
                Binding binding = new Binding(); binding.response = wire;
                rejected(() -> inspect(manifest, selected(null), binding));
                clean(binding);
            }
            Binding nullBinding = new Binding(); nullBinding.returnNull = true;
            rejected(() -> inspect(manifest, selected(null), nullBinding));
            clean(nullBinding);
            Binding wrongCertificate = new Binding(); wrongCertificate.response = valid;
            rejected(() -> inspect(manifest, Collections.singletonList(pair("priv", "cert", null)), wrongCertificate));
            clean(wrongCertificate);
        }
    }

    @Test public void kindAndDigestBelongOnlyToWholeSuccessfulProof() throws Exception {
        try (VaultManifest manifest = simple()) {
            for (int kind = 1; kind <= 5; kind++) {
                Binding binding = new Binding(); binding.response = frame(0, kind, 28, 0, 3, true);
                SshManifestKeyAssociationInspector.Entry entry = inspect(manifest, selected(null), binding).entries.get(0);
                assertEquals(SshManifestKeyAssociationInspector.KeyKind.values()[kind], entry.keyKind);
                assertEquals("80" + String.join("", Collections.nCopies(31, "00")), entry.publicSubjectDigest);
                clean(binding);
            }
            for (byte[] wire : new byte[][]{frame(0, 0, 28, 0, 3, true), frame(0, 1, 28, 0, 3, false),
                    frame(1, 1, 4, 3, 3, false), frame(1, 0, 4, 3, 3, true),
                    frame(2, 1, 4, 1, 3, false), frame(3, 0, 4, 2, 3, true)}) {
                Binding binding = new Binding(); binding.response = wire;
                rejected(() -> inspect(manifest, selected(null), binding));
                clean(binding);
            }
        }
    }

    @Test public void reportIsImmutableAndNeverPromotesPairProofToAdmissionOrTrust() throws Exception {
        Binding binding = new Binding();
        List<SshManifestKeyAssociationInspector.Association> pairs = new ArrayList<>(selected(null));
        try (VaultManifest manifest = simple()) {
            SshManifestKeyAssociationInspector.Report report = inspect(manifest, pairs, binding);
            pairs.clear();
            assertEquals(1, report.entries.size());
            assertTrue(report.explicitPairsOnly); assertTrue(report.allRequestedPairsMatch);
            assertFalse(report.admissionComplete); assertFalse(report.trustChecked);
            assertFalse(report.credentialTargetsChecked);
            try { report.entries.clear(); fail("immutable entries required"); }
            catch (UnsupportedOperationException expected) { }
            clean(binding);
        }
    }

    @Test public void mutableCallerListCannotChangeAlreadySelectedPairsDuringBinding() throws Exception {
        Binding binding = new Binding();
        List<SshManifestKeyAssociationInspector.Association> pairs = new ArrayList<>(Arrays.asList(
                pair("priv", "pub", null), pair("priv", "cert", "phraseB")));
        binding.during = pairs::clear;
        try (VaultManifest manifest = simple()) {
            SshManifestKeyAssociationInspector.Report report = inspect(manifest, pairs, binding);
            assertTrue(pairs.isEmpty());
            assertEquals(2, report.entries.size());
            assertEquals(Arrays.asList(3, 4), binding.types);
            assertEquals("cert", report.entries.get(1).peerFileId);
            assertEquals("phraseB", report.entries.get(1).passphraseEntryId);
            clean(binding);
        }
    }

    @Test public void originalDeadlineIsPassedToEveryPairAndNeverRenewed() throws Exception {
        Clock clock = new Clock();
        Binding binding = new Binding();
        binding.during = () -> clock.time += 1000;
        try (VaultManifest manifest = simple()) {
            new SshManifestKeyAssociationInspector(clock, binding).inspect(manifest,
                    Arrays.asList(pair("priv", "pub", null), pair("priv", "cert", null)), 31_000,
                    new SshManifestKeyAssociationInspector.Cancellation());
            assertEquals(Arrays.asList(31_000L, 31_000L), binding.deadlines);
            clean(binding);
            for (long deadline : new long[]{0, 1000, 61_001}) {
                Binding rejected = new Binding();
                rejected(() -> new SshManifestKeyAssociationInspector(new Clock(), rejected).inspect(
                        manifest, selected(null), deadline, new SshManifestKeyAssociationInspector.Cancellation()));
                assertTrue(rejected.types.isEmpty());
            }
            Clock negative = new Clock(); negative.time = -1;
            Binding rejected = new Binding();
            rejected(() -> new SshManifestKeyAssociationInspector(negative, rejected).inspect(
                    manifest, selected(null), 31_000, new SshManifestKeyAssociationInspector.Cancellation()));
            assertTrue(rejected.types.isEmpty());
        }
    }

    @Test public void cancelAndExpiryDuringBindingRejectPublicationAndWipe() throws Exception {
        try (VaultManifest manifest = simple()) {
            for (boolean cancel : new boolean[]{false, true}) {
                Clock clock = new Clock(); Binding binding = new Binding();
                SshManifestKeyAssociationInspector.Cancellation cancellation = new SshManifestKeyAssociationInspector.Cancellation();
                binding.during = () -> { if (cancel) cancellation.cancel(); else clock.time = 31_000; };
                rejected(() -> new SshManifestKeyAssociationInspector(clock, binding).inspect(manifest,
                        selected("phraseB"), 31_000, cancellation));
                assertEquals(1, binding.types.size()); clean(binding);
            }
            Binding binding = new Binding();
            SshManifestKeyAssociationInspector.Cancellation cancellation = new SshManifestKeyAssociationInspector.Cancellation();
            cancellation.cancel();
            rejected(() -> new SshManifestKeyAssociationInspector(new Clock(), binding).inspect(
                    manifest, selected(null), 31_000, cancellation));
            assertTrue(binding.types.isEmpty());
        }
    }

    @Test public void finalFenceRunsAfterOwnedArraysHaveBeenWiped() throws Exception {
        Clock clock = new Clock(); Binding binding = new Binding();
        SshManifestKeyAssociationInspector.Cancellation cancellation = new SshManifestKeyAssociationInspector.Cancellation();
        clock.during = () -> {
            if (!binding.privateBodies.isEmpty() && zero(binding.privateBodies.get(0))
                    && zero(binding.peerBodies.get(0)) && zero(binding.phrases.get(0))) cancellation.cancel();
        };
        try (VaultManifest manifest = simple()) {
            rejected(() -> new SshManifestKeyAssociationInspector(clock, binding).inspect(manifest,
                    selected("phraseB"), 31_000, cancellation));
            assertTrue(cancellation.cancelled()); clean(binding);
        }
    }

    @Test public void ownerThreadAndOneAttemptAreEnforcedIncludingAfterFailure() throws Exception {
        Clock clock = new Clock(); Binding binding = new Binding();
        SshManifestKeyAssociationInspector inspector = new SshManifestKeyAssociationInspector(clock, binding);
        try (VaultManifest manifest = simple()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try { inspector.inspect(manifest, selected(null), 31_000,
                        new SshManifestKeyAssociationInspector.Cancellation()); }
                catch (Throwable error) { failure.set(error); }
            });
            thread.start(); thread.join(5000);
            assertFalse(thread.isAlive()); assertTrue(failure.get() instanceof IOException);
            assertTrue(binding.types.isEmpty());
            assertTrue(inspector.inspect(manifest, selected(null), 31_000,
                    new SshManifestKeyAssociationInspector.Cancellation()).allRequestedPairsMatch);
            rejected(() -> inspector.inspect(manifest, selected(null), 31_000,
                    new SshManifestKeyAssociationInspector.Cancellation()));
            assertEquals(1, binding.types.size()); clean(binding);
            Binding second = new Binding();
            SshManifestKeyAssociationInspector failed = new SshManifestKeyAssociationInspector(new Clock(), second);
            rejected(() -> failed.inspect(null, selected(null), 31_000,
                    new SshManifestKeyAssociationInspector.Cancellation()));
            rejected(() -> failed.inspect(manifest, selected(null), 31_000,
                    new SshManifestKeyAssociationInspector.Cancellation()));
            assertTrue(second.types.isEmpty());
        }
    }

    @Test public void sameAndCrossInstanceReentryRejectWithoutConsumingUnusedSibling() throws Exception {
        Binding binding = new Binding(), siblingBinding = new Binding();
        SshManifestKeyAssociationInspector sibling = new SshManifestKeyAssociationInspector(new Clock(), siblingBinding);
        SshManifestKeyAssociationInspector[] outer = new SshManifestKeyAssociationInspector[1];
        outer[0] = new SshManifestKeyAssociationInspector(new Clock(), binding);
        try (VaultManifest manifest = simple()) {
            binding.during = () -> {
                rejected(() -> outer[0].inspect(manifest, selected(null), 31_000,
                        new SshManifestKeyAssociationInspector.Cancellation()));
                rejected(() -> sibling.inspect(manifest, selected(null), 31_000,
                        new SshManifestKeyAssociationInspector.Cancellation()));
                assertTrue(siblingBinding.types.isEmpty());
            };
            assertTrue(outer[0].inspect(manifest, selected(null), 31_000,
                    new SshManifestKeyAssociationInspector.Cancellation()).allRequestedPairsMatch);
            assertTrue(sibling.inspect(manifest, selected(null), 31_000,
                    new SshManifestKeyAssociationInspector.Cancellation()).allRequestedPairsMatch);
            clean(binding); clean(siblingBinding);
        }
    }

    @Test public void repeatedWorkBudgetAdmitsExactly64MiBAndRejectsOneAdditionalUniqueTuple() throws Exception {
        byte[] privateBody = new byte[1024 * 1024], peerBody = new byte[1024 * 1024];
        Arrays.fill(privateBody, (byte)'p'); Arrays.fill(peerBody, (byte)'q');
        List<VaultManifest.Entry> phrases = new ArrayList<>();
        List<SshManifestKeyAssociationInspector.Association> pairs = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            phrases.add(phrase("phrase" + i, "identity", "secret" + i));
            pairs.add(pair("priv", "pub", "phrase" + i));
        }
        try (VaultManifest manifest = new VaultManifest(phrases, Arrays.asList(
                file(VaultManifest.FileType.PRIVATE_KEY, "priv", "identity", privateBody),
                file(VaultManifest.FileType.PUBLIC_KEY, "pub", "peer", peerBody)))) {
            Binding boundary = new Binding();
            assertTrue(inspect(manifest, pairs.subList(0, 32), boundary).allRequestedPairsMatch);
            assertEquals(32, boundary.types.size());
            for (int i = 1; i < 32; i++) {
                assertSame(boundary.privateBodies.get(0), boundary.privateBodies.get(i));
                assertSame(boundary.peerBodies.get(0), boundary.peerBodies.get(i));
            }
            clean(boundary);
            Binding excess = new Binding();
            rejected(() -> inspect(manifest, pairs, excess));
            assertTrue(excess.types.isEmpty());
            assertArrayEquals(privateBody, manifest.files.get(0).content);
            assertArrayEquals(peerBody, manifest.files.get(1).content);
            assertArrayEquals(bytes("secret32"), manifest.entries.get(32).secret);
        }
    }

    @Test public void maximum128DistinctSmallPairsAreAdmittedWithoutNameGuessing() throws Exception {
        List<VaultManifest.Entry> phrases = new ArrayList<>();
        List<SshManifestKeyAssociationInspector.Association> pairs = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            phrases.add(phrase("phrase" + i, "identity", "secret" + i));
            pairs.add(pair("priv", "pub", "phrase" + i));
        }
        Binding binding = new Binding();
        binding.during = () -> assertArrayEquals(bytes("secret" + (binding.types.size() - 1)),
                binding.phrases.get(binding.phrases.size() - 1));
        try (VaultManifest manifest = new VaultManifest(phrases, Arrays.asList(
                file(VaultManifest.FileType.PRIVATE_KEY, "priv", "identity", "private"),
                file(VaultManifest.FileType.PUBLIC_KEY, "pub", "peer", "public")))) {
            assertEquals(128, inspect(manifest, pairs, binding).entries.size());
            assertEquals(128, binding.types.size()); clean(binding);
        }
    }
}
