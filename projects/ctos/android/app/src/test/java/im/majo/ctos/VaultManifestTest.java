package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Pure schema evidence; declarations do not establish key mathematics or remote authority. */
public final class VaultManifestTest {
    // Independent big-endian vectors; file digests are SHA256 of bytes 33/44/55/66 (hex).
    private static final String LEGACY_SSH =
            "0000000100000002010000000270770000000e7461726765743a726177255b785d000000015000000000000000000000" +
            "000000000001110200000006706872617365000000076b65793a72617700000001510000000000000000000000000000" +
            "00012200000004010000000470726976000000066b6579732f61000000037261774e07408562bedb8b60ce05c1decfe3" +
            "ad16b72230967de01f640b7e4729b49fce00000001330000000002000000037075620000000a6b6579732f612e707562" +
            "00000009756e72656c617465643f39d5c348e5b79d06e842c114e6cc571583bbf44e4b0ebfda1a01ec05745d43000000" +
            "0144000000000300000005636572744200000006636572742f62000000086465666572726564a25513c7e0f6eaa80a33" +
            "37ee18081b9e2ed09e00af8531c8f7bb2542764027e70000000155000000000300000005636572744100000006636572" +
            "742f61000000086465666572726564252f10c83610ebca1a059c0bae8255eba2f95be4d1d7bcfa89d7248a82d9f11100" +
            "0000016600000000";
    private static final String METADATA =
            "000000010000000462696e6400000004707269760000000370756200000006706872617365000000" +
            "02000000056365727442000000056365727441000000020000000f70617373776f72644163636f756e740000000e7461" +
            "726765743a726177255b785d0000000d484f53542e4578616d706c652e000008ae0000000c20e794a8e688b7f09f9880" +
            "20010000000270770000000a6b65794163636f756e740000000a7461726765743a6b65790000000e5b666538303a3a31" +
            "25657468305d0000ffff00000005616c696365020000000462696e64";
    private static final String LEGACY_TOTP =
            "0000000100000001000000000269640000000e4973737565723a6163636f756e740000000b4c656761637920544f5450" +
            "0000000453484131000000060000001e00000014313233343536373839303132333435363738393000000000";
    private static final String TARGET = "target:raw%[x]";

    private interface Attempt { void run() throws Exception; }
    private interface Extension { void write(DataOutputStream out) throws Exception; }

    private static void invalid(Attempt attempt) throws Exception {
        try { attempt.run(); fail("Expected structural rejection"); }
        catch (VaultException failure) {
            assertEquals("VAULT_CONTENT", failure.code);
            assertEquals("The backup contains unsupported or invalid content", failure.getMessage());
        }
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }

    private static byte[] golden() { return hex("00000002" + LEGACY_SSH.substring(8) + METADATA); }
    private static VaultManifest base() throws Exception {
        return VaultManifest.decode(hex("00000002" + LEGACY_SSH.substring(8) + "0000000000000000"));
    }

    private static VaultSshMetadata.KeyBinding binding(String id, String privateId, String publicId,
            List<String> certificates, String phrase) throws VaultException {
        return new VaultSshMetadata.KeyBinding(id, privateId, publicId, certificates, phrase);
    }

    private static VaultSshMetadata.KeyBinding binding() throws VaultException {
        return binding("bind", "priv", "pub", List.of("certB", "certA"), "phrase");
    }

    private static VaultSshMetadata.Account account(String id, String target, VaultSshMetadata.AuthKind kind,
            String credential) throws VaultException {
        return new VaultSshMetadata.Account(id, target, "HOST.Example.", 2222, " 用户😀 ", kind, credential);
    }

    private static VaultSshMetadata.Account passwordAccount() throws VaultException {
        return account("passwordAccount", TARGET, VaultSshMetadata.AuthKind.PASSWORD, "pw");
    }

    private static void text(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] wire(Extension extension) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(2);
        out.write(hex(LEGACY_SSH.substring(8)));
        extension.write(out);
        return buffer.toByteArray();
    }

    private static void wireBinding(DataOutputStream out, int certificateCount) throws Exception {
        text(out, "bind"); text(out, "priv"); text(out, ""); text(out, "");
        out.writeInt(certificateCount);
    }

    private static void wireAccount(DataOutputStream out, int port, String username, int kind) throws Exception {
        text(out, "a"); text(out, TARGET); text(out, "HOST.Example.");
        out.writeInt(port); text(out, username); out.writeByte(kind); text(out, "pw");
    }

    private static void rejectDecode(byte[] input) throws Exception {
        byte[] original = input.clone();
        invalid(() -> VaultManifest.decode(input));
        assertArrayEquals("Decode must not wipe the caller's input", original, input);
    }

    @Test public void allOrdinaryConstructorsAndEmptyUseTheSoleCurrentLayout() throws Exception {
        byte[] golden = wire(out -> { out.writeInt(0); out.writeInt(0); });
        try (VaultManifest decoded = base()) {
            assertTrue(decoded.keyBindings.isEmpty());
            assertTrue(decoded.accounts.isEmpty());
            assertEquals(TARGET, decoded.entries.get(0).identity);
            assertArrayEquals(golden, decoded.encode());
            VaultManifest constructed = new VaultManifest(decoded.entries, decoded.files);
            assertArrayEquals(golden, constructed.encode());
            VaultManifest explicit = new VaultManifest(decoded.entries, decoded.files, List.of(), List.of());
            assertArrayEquals(golden, explicit.encode());
        }
        try (VaultManifest empty = VaultManifest.empty()) {
            assertArrayEquals(hex("0000000200000000000000000000000000000000"), empty.encode());
        }
    }

    @Test public void currentGoldenFreezesEveryFieldOrderOptionalAndAuthTag() throws Exception {
        byte[] golden = golden();
        try (VaultManifest decoded = VaultManifest.decode(golden)) {
            VaultSshMetadata.KeyBinding binding = decoded.keyBindings.get(0);
            assertEquals("bind", binding.id);
            assertEquals("priv", binding.privateFileId);
            assertEquals("pub", binding.publicFileId);
            assertEquals("phrase", binding.passphraseEntryId);
            assertEquals(List.of("certB", "certA"), binding.certificateFileIds);
            VaultSshMetadata.Account password = decoded.accounts.get(0);
            assertEquals("passwordAccount", password.id);
            assertEquals(TARGET, password.targetIdentity);
            assertEquals("HOST.Example.", password.host);
            assertEquals(2222, password.port);
            assertEquals(" 用户😀 ", password.username);
            assertEquals(VaultSshMetadata.AuthKind.PASSWORD, password.authKind);
            assertEquals("pw", password.credentialId);
            VaultSshMetadata.Account key = decoded.accounts.get(1);
            assertEquals("keyAccount", key.id);
            assertEquals("target:key", key.targetIdentity);
            assertEquals("[fe80::1%eth0]", key.host);
            assertEquals(65535, key.port);
            assertEquals("alice", key.username);
            assertEquals(VaultSshMetadata.AuthKind.KEY, key.authKind);
            assertEquals("bind", key.credentialId);
            assertArrayEquals(golden, decoded.encode());
        }
        try (VaultManifest old = base()) {
            VaultManifest constructed = new VaultManifest(old.entries, old.files, List.of(binding()),
                    List.of(passwordAccount(), new VaultSshMetadata.Account("keyAccount", "target:key",
                            "[fe80::1%eth0]", 65535, "alice", VaultSshMetadata.AuthKind.KEY, "bind")));
            assertArrayEquals(golden, constructed.encode());
        }
    }

    @Test public void emptyMetadataNeverCreatesBindingsOrSelectsAnotherLayout() throws Exception {
        try (VaultManifest empty = new VaultManifest(List.of(), List.of(), List.of(), List.of())) {
            assertArrayEquals(hex("0000000200000000000000000000000000000000"), empty.encode());
            try (VaultManifest copy = VaultManifest.decode(empty.encode())) {
                assertArrayEquals(empty.encode(), copy.encode());
            }
        }
        try (VaultManifest old = base()) {
            VaultManifest current = new VaultManifest(old.entries, old.files, List.of(), List.of());
            assertTrue(current.keyBindings.isEmpty());
            assertTrue(current.accounts.isEmpty());
            byte[] expected = wire(out -> { out.writeInt(0); out.writeInt(0); });
            assertArrayEquals(expected, current.encode());
        }
    }

    @Test public void deliveredEmptyAndTotpInputsMapDirectlyToTheSoleModel() throws Exception {
        byte[] legacy = hex(LEGACY_TOTP);
        byte[] original = legacy.clone();
        try (VaultManifest decoded = VaultManifest.decode(legacy)) {
            assertEquals(1, decoded.entries.size()); assertTrue(decoded.files.isEmpty());
            assertTrue(decoded.keyBindings.isEmpty()); assertTrue(decoded.accounts.isEmpty());
            VaultManifest.Entry entry = decoded.entries.get(0);
            assertEquals(VaultManifest.EntryType.TOTP, entry.type); assertEquals("id", entry.id);
            assertEquals("Issuer:account", entry.identity); assertEquals("Legacy TOTP", entry.label);
            assertEquals("SHA1", entry.algorithm); assertEquals(6, entry.digits); assertEquals(30, entry.period);
            assertArrayEquals("12345678901234567890".getBytes(StandardCharsets.US_ASCII), entry.secret);
            byte[] current = hex("00000002" + LEGACY_TOTP.substring(8) + "0000000000000000");
            assertArrayEquals(current, decoded.encode());
            VaultManifest projected = decoded.withObjects(decoded.entries, decoded.files);
            assertArrayEquals(current, projected.encode());
            try (VaultManifest roundtrip = VaultManifest.decode(current)) {
                assertArrayEquals(current, roundtrip.encode());
                assertArrayEquals(entry.secret, roundtrip.entries.get(0).secret);
            }
        }
        assertArrayEquals(original, legacy);
        try (VaultManifest empty = VaultManifest.decode(hex("000000010000000000000000"))) {
            assertArrayEquals(hex("0000000200000000000000000000000000000000"), empty.encode());
        }
    }

    @Test public void deprecatedSshInputsAreNotAnotherSupportedMainline() throws Exception {
        rejectDecode(hex(LEGACY_SSH));
        try (VaultManifest objects = base()) {
            VaultManifest[] unsupported = {
                new VaultManifest(List.of(objects.entries.get(0)), List.of()),
                new VaultManifest(List.of(objects.entries.get(1)), List.of(objects.files.get(0))),
                new VaultManifest(List.of(), List.of(objects.files.get(1)))
            };
            for (VaultManifest manifest : unsupported) {
                byte[] current = manifest.encode();
                byte[] legacy = Arrays.copyOf(current, current.length - 8);
                ByteBuffer.wrap(legacy).putInt(1);
                rejectDecode(legacy);
                assertArrayEquals(current, manifest.encode());
            }
        }
    }

    @Test public void legacyTotpInputStillRejectsMalformedSemanticsBoundsAndFraming() throws Exception {
        byte[] legacy = hex(LEGACY_TOTP);
        for (int length = 0; length < legacy.length; length++) rejectDecode(Arrays.copyOf(legacy, length));
        rejectDecode(Arrays.copyOf(legacy, legacy.length + 1));
        rejectDecode(hex(LEGACY_TOTP.replace("000000060000001e", "000000070000001e")));
        rejectDecode(hex(LEGACY_TOTP.replace("53484131", "53484132")));
        rejectDecode(hex("0000000100000401"));
        rejectDecode(hex("000000010000000000000001"));
    }

    @Test public void nullOptionalsHaveExactlyEmptyWireTextsAndPrivateOnlyIsValid() throws Exception {
        byte[] expected = wire(out -> {
            out.writeInt(1); wireBinding(out, 0); out.writeInt(0);
        });
        try (VaultManifest decoded = VaultManifest.decode(expected)) {
            assertNull(decoded.keyBindings.get(0).publicFileId);
            assertNull(decoded.keyBindings.get(0).passphraseEntryId);
            assertTrue(decoded.keyBindings.get(0).certificateFileIds.isEmpty());
            assertArrayEquals(expected, decoded.encode());
        }
        invalid(() -> binding("b", "priv", "", List.of(), null));
        invalid(() -> binding("b", "priv", null, List.of(), ""));
    }

    @Test public void metadataListsAreImmutableSnapshotsOfCallerLists() throws Exception {
        List<String> certificates = new ArrayList<>(List.of("certB", "certA"));
        VaultSshMetadata.KeyBinding binding = binding("bind", "priv", "pub", certificates, "phrase");
        List<VaultSshMetadata.KeyBinding> bindings = new ArrayList<>(List.of(binding));
        List<VaultSshMetadata.Account> accounts = new ArrayList<>(List.of(passwordAccount()));
        try (VaultManifest old = base()) {
            VaultManifest current = new VaultManifest(old.entries, old.files, bindings, accounts);
            certificates.clear(); bindings.clear(); accounts.clear();
            assertEquals(List.of("certB", "certA"), binding.certificateFileIds);
            assertEquals(1, current.keyBindings.size()); assertEquals(1, current.accounts.size());
            try { current.keyBindings.clear(); fail("Mutable key bindings"); } catch (UnsupportedOperationException expected) { }
            try { current.accounts.clear(); fail("Mutable accounts"); } catch (UnsupportedOperationException expected) { }
            try { binding.certificateFileIds.clear(); fail("Mutable certificates"); } catch (UnsupportedOperationException expected) { }
        }
    }

    @Test public void withObjectsPreservesMetadataAndCallerOwnership() throws Exception {
        try (VaultManifest source = VaultManifest.decode(golden())) {
            List<VaultManifest.Entry> entries = new ArrayList<>();
            for (VaultManifest.Entry entry : source.entries) entries.add(new VaultManifest.Entry(entry.type,
                    entry.id, entry.identity, entry.label, entry.algorithm, entry.digits, entry.period, entry.secret));
            List<VaultManifest.FileObject> files = new ArrayList<>();
            for (VaultManifest.FileObject file : source.files) files.add(new VaultManifest.FileObject(file.type,
                    file.id, file.path, file.identity, file.content, file.references));
            try (VaultManifest candidate = source.withObjects(entries, files)) {
                assertSame(source.keyBindings.get(0), candidate.keyBindings.get(0));
                assertSame(source.accounts.get(0), candidate.accounts.get(0));
                assertSame(entries.get(0), candidate.entries.get(0));
                assertSame(files.get(0), candidate.files.get(0));
                assertNotSame(source.entries.get(0).secret, candidate.entries.get(0).secret);
                assertArrayEquals(golden(), candidate.encode());
            }
            assertArrayEquals(new byte[] {0}, entries.get(0).secret);
            assertArrayEquals(new byte[] {0x11}, source.entries.get(0).secret);
            assertArrayEquals(new byte[] {0x33}, source.files.get(0).content);
            assertArrayEquals(golden(), source.encode());
        }
        try (VaultManifest old = base()) {
            assertArrayEquals(old.encode(), old.withObjects(old.entries, old.files).encode());
        }
    }

    @Test public void withObjectsRejectsRemovedOrRetypedReferencedObjectsWithoutPruning() throws Exception {
        try (VaultManifest source = VaultManifest.decode(golden())) {
            for (int index = 0; index < source.files.size(); index++) {
                List<VaultManifest.FileObject> missing = new ArrayList<>(source.files);
                missing.remove(index);
                invalid(() -> source.withObjects(source.entries, missing));
            }
            for (int index = 0; index < source.entries.size(); index++) {
                List<VaultManifest.Entry> missing = new ArrayList<>(source.entries);
                missing.remove(index);
                invalid(() -> source.withObjects(missing, source.files));
            }
            List<VaultManifest.FileObject> retyped = new ArrayList<>(source.files);
            VaultManifest.FileObject original = retyped.get(1);
            retyped.set(1, new VaultManifest.FileObject(VaultManifest.FileType.CERTIFICATE, original.id,
                    original.path, original.identity, original.content, List.of()));
            invalid(() -> source.withObjects(source.entries, retyped));
            assertArrayEquals(new byte[] {0x44}, retyped.get(1).content);
            assertArrayEquals(golden(), source.encode());
            VaultCodec.wipe(retyped.get(1).content);
        }
    }

    @Test public void bindingReferencesRequireExactIdsAndDeclaredTypes() throws Exception {
        try (VaultManifest old = base()) {
            for (String privateId : List.of("missing", "keys/a", "pub", "certA", "pw")) {
                invalid(() -> new VaultManifest(old.entries, old.files,
                        List.of(binding("b", privateId, null, List.of(), null)), List.of()));
            }
            for (String publicId : List.of("missing", "priv", "certA", "pw")) {
                invalid(() -> new VaultManifest(old.entries, old.files,
                        List.of(binding("b", "priv", publicId, List.of(), null)), List.of()));
            }
            for (String certificateId : List.of("missing", "priv", "pub", "pw")) {
                invalid(() -> new VaultManifest(old.entries, old.files,
                        List.of(binding("b", "priv", null, List.of(certificateId), null)), List.of()));
            }
            for (String phraseId : List.of("missing", "pw", "priv")) {
                invalid(() -> new VaultManifest(old.entries, old.files,
                        List.of(binding("b", "priv", null, List.of(), phraseId)), List.of()));
            }
        }
        invalid(() -> binding("b", "priv", null, List.of("certA", "certA"), null));
    }

    @Test public void passphraseIdentityMustMatchItsExplicitPrivateAndHasOneSelectedOwner() throws Exception {
        try (VaultManifest old = base()) {
            List<VaultManifest.FileObject> files = new ArrayList<>(old.files);
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "second", "keys/b",
                    "other", new byte[] {0x77}, List.of()));
            invalid(() -> new VaultManifest(old.entries, files,
                    List.of(binding("b", "second", null, List.of(), "phrase")), List.of()));
            files.set(4, new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "second", "keys/b",
                    "raw", new byte[] {0x77}, List.of()));
            invalid(() -> new VaultManifest(old.entries, files, List.of(binding(),
                    binding("b2", "second", null, List.of(), "phrase")), List.of()));
            invalid(() -> new VaultManifest(old.entries, old.files, List.of(binding(),
                    binding("b2", "priv", null, List.of(), null)), List.of()));
            VaultCodec.wipe(files.get(4).content);
        }
    }

    @Test public void publicAndCertificateSharingIsExplicitlyAllowedAcrossDifferentPrivates() throws Exception {
        try (VaultManifest old = base()) {
            List<VaultManifest.FileObject> files = new ArrayList<>(old.files);
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "second", "keys/b",
                    "raw", new byte[] {0x77}, List.of()));
            VaultManifest shared = new VaultManifest(old.entries, files, List.of(binding(),
                    binding("b2", "second", "pub", List.of("certA", "certB"), null)), List.of());
            assertEquals(2, shared.keyBindings.size());
            assertArrayEquals(new byte[] {0x77}, files.get(4).content);
            VaultCodec.wipe(files.get(4).content);
        }
    }

    @Test public void globalIdsCannotCollideBetweenAnyOldOrNewKinds() throws Exception {
        try (VaultManifest old = base()) {
            for (String id : List.of("pw", "priv", "pub", "certA")) {
                invalid(() -> new VaultManifest(old.entries, old.files,
                        List.of(binding(id, "priv", null, List.of(), null)), List.of()));
            }
            for (String id : List.of("pw", "priv", "pub", "bind")) {
                invalid(() -> new VaultManifest(old.entries, old.files, List.of(binding()),
                        List.of(account(id, TARGET, VaultSshMetadata.AuthKind.PASSWORD, "pw"))));
            }
            invalid(() -> new VaultManifest(old.entries, old.files, List.of(binding()), List.of(
                    passwordAccount(), account("passwordAccount", "target:other", VaultSshMetadata.AuthKind.KEY, "bind"))));
            List<VaultManifest.FileObject> files = new ArrayList<>(old.files);
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "second", "keys/b",
                    "raw", new byte[] {0x77}, List.of()));
            invalid(() -> new VaultManifest(old.entries, files, List.of(binding(),
                    binding("bind", "second", null, List.of(), null)), List.of()));
            VaultCodec.wipe(files.get(4).content);
        }
    }

    @Test public void accountCredentialsRequireExactTargetTypeAndKeyBindingId() throws Exception {
        try (VaultManifest old = base()) {
            for (String id : List.of("missing", "phrase", "priv", "bind")) {
                invalid(() -> new VaultManifest(old.entries, old.files, List.of(binding()),
                        List.of(account("a", TARGET, VaultSshMetadata.AuthKind.PASSWORD, id))));
            }
            invalid(() -> new VaultManifest(old.entries, old.files, List.of(binding()),
                    List.of(account("a", "target:other", VaultSshMetadata.AuthKind.PASSWORD, "pw"))));
            for (String id : List.of("missing", "pw", "priv", "pub")) {
                invalid(() -> new VaultManifest(old.entries, old.files, List.of(binding()),
                        List.of(account("a", TARGET, VaultSshMetadata.AuthKind.KEY, id))));
            }
        }
    }

    @Test public void accountsRequireUniqueRawTargetsButMayShareTupleAndKeyBinding() throws Exception {
        try (VaultManifest old = base()) {
            VaultSshMetadata.Account first = account("a", "target:first", VaultSshMetadata.AuthKind.KEY, "bind");
            VaultSshMetadata.Account second = account("b", "target:second", VaultSshMetadata.AuthKind.KEY, "bind");
            VaultManifest valid = new VaultManifest(old.entries, old.files, List.of(binding()), List.of(first, second));
            assertEquals(2, valid.accounts.size());
            assertEquals(first.host, second.host); assertEquals(first.username, second.username);
            invalid(() -> new VaultManifest(old.entries, old.files, List.of(binding()), List.of(first,
                    account("b", "target:first", VaultSshMetadata.AuthKind.KEY, "bind"))));
        }
    }

    @Test public void newIdsAndNullableMetadataInputsUseFixedStructuralErrors() throws Exception {
        for (String id : Arrays.asList(null, "", "bad/id", "a.b", "é", "a".repeat(129), "bad\ud800")) {
            invalid(() -> binding(id, "priv", null, List.of(), null));
            invalid(() -> binding("b", id, null, List.of(), null));
            invalid(() -> account(id, TARGET, VaultSshMetadata.AuthKind.PASSWORD, "pw"));
            invalid(() -> account("a", TARGET, VaultSshMetadata.AuthKind.PASSWORD, id));
        }
        invalid(() -> binding("b", "priv", null, null, null));
        invalid(() -> binding("b", "priv", null, Arrays.asList((String) null), null));
        invalid(() -> account("a", TARGET, null, "pw"));
        invalid(() -> new VaultManifest(null, List.of(), List.of(), List.of()));
        invalid(() -> new VaultManifest(List.of(), null, List.of(), List.of()));
        invalid(() -> new VaultManifest(List.of(), List.of(), null, List.of()));
        invalid(() -> new VaultManifest(List.of(), List.of(), List.of(), null));
        invalid(() -> new VaultManifest(List.of(), List.of(), Arrays.asList((VaultSshMetadata.KeyBinding) null), List.of()));
        invalid(() -> new VaultManifest(List.of(), List.of(), List.of(), Arrays.asList((VaultSshMetadata.Account) null)));
    }

    @Test public void targetAndHostTextRemainRawAsciiPredicatesNotParsers() throws Exception {
        for (String target : Arrays.asList(null, "", "key:x", "target:", "target:a b", "target:é",
                "target:" + "x".repeat(501), "target:x\ud800")) {
            invalid(() -> account("a", target, VaultSshMetadata.AuthKind.KEY, "bind"));
        }
        for (String host : Arrays.asList(null, "", "a".repeat(254), "user@host", "https://host", "a b", "é", "a\ud800")) {
            invalid(() -> new VaultSshMetadata.Account("a", TARGET, host, 22, "u", VaultSshMetadata.AuthKind.KEY, "bind"));
        }
        for (String host : List.of("HOST.Example.", "[fe80::1%eth0]", "-", "[", "a".repeat(253))) {
            VaultSshMetadata.Account account = new VaultSshMetadata.Account("a", "target:" + "x".repeat(500),
                    host, 1, " 用户😀 ", VaultSshMetadata.AuthKind.KEY, "bind");
            assertEquals(host, account.host);
            assertEquals("target:" + "x".repeat(500), account.targetIdentity);
        }
    }

    @Test public void usernameStrictUtf8BoundsControlsAndSpellingMatchSelectionPrimitive() throws Exception {
        for (String username : Arrays.asList(null, "", "x".repeat(256), "é".repeat(128), "x\u0000", "x\u001f",
                "x\u007f", "x\u0085", "x\u009f", "x\ud800", "x\udc00")) {
            invalid(() -> new VaultSshMetadata.Account("a", TARGET, "host", 22, username,
                    VaultSshMetadata.AuthKind.KEY, "bind"));
            invalid(() -> VaultCodec.sshText(username, 255));
        }
        for (String username : List.of(" 用户😀 ", "é".repeat(127) + "x", " ", "a\u200b")) {
            VaultSshMetadata.Account account = new VaultSshMetadata.Account("a", TARGET, "host", 22, username,
                    VaultSshMetadata.AuthKind.KEY, "bind");
            assertEquals(username, account.username);
            byte[] encoded = VaultCodec.sshText(username, 255);
            assertArrayEquals(username.getBytes(StandardCharsets.UTF_8), encoded);
            VaultCodec.wipe(encoded);
        }
        for (int port : new int[] {-1, 0, 65536, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            invalid(() -> new VaultSshMetadata.Account("a", TARGET, "host", port, "u", VaultSshMetadata.AuthKind.KEY, "bind"));
        }
    }

    @Test public void existingEntryAndFileTextSemanticsRemainUnchanged() throws Exception {
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "pw",
                "key:legacy", "old\ud800label", "", 0, 0, new byte[] {9});
        VaultManifest.FileObject file = new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                "f", "public", "old\ud800identity", new byte[] {8}, List.of());
        VaultManifest manifest = new VaultManifest(List.of(password), List.of(file));
        try (VaultManifest decoded = VaultManifest.decode(manifest.encode())) {
            assertEquals(2, ByteBuffer.wrap(decoded.encode()).getInt());
            assertEquals("key:legacy", decoded.entries.get(0).identity);
            assertEquals("old?label", decoded.entries.get(0).label);
            assertEquals("old?identity", decoded.files.get(0).identity);
        }
        VaultCodec.wipe(password.secret); VaultCodec.wipe(file.content);
    }

    @Test public void sharedTextExtractionKeepsSelectionErrorsAndExactUsernameBytes() throws Exception {
        byte[] nonce = new byte[32]; Arrays.fill(nonce, (byte) 1);
        SshRoleContext.Clock clock = () -> 1000;
        SshRoleContext.Bootstrap bootstrap = new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                1, 0, "connection", "target:selection", "host", 22, "view", nonce, nonce, 2000);
        try (SshRoleContext role = SshRoleContext.issue(bootstrap, () -> true, clock)) {
            for (String username : Arrays.asList(null, "", "é".repeat(128), "bad\u0085", "bad\ud800")) {
                try {
                    SshAuthenticationSelection.password(role, username, "password", clock);
                    fail("Invalid username accepted");
                } catch (VaultException failure) {
                    assertEquals("SSH_AUTH_SELECTION", failure.code);
                    assertEquals("The SSH authentication selection does not match", failure.getMessage());
                }
            }
            SshAuthenticationSelection selected = SshAuthenticationSelection.password(role, " 用户😀 ", "password", clock);
            assertEquals(" 用户😀 ", selected.username);
            assertArrayEquals(" 用户😀 ".getBytes(StandardCharsets.UTF_8), selected.usernameBytes());
        }
    }

    @Test public void countBoundsAreCheckedBeforeAnyDeclaredRowsAreRead() throws Exception {
        for (int count : new int[] {-1, 129, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            rejectDecode(wire(out -> out.writeInt(count)));
            rejectDecode(wire(out -> { out.writeInt(1); wireBinding(out, count); }));
        }
        for (int count : new int[] {-1, 1025, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            rejectDecode(wire(out -> { out.writeInt(0); out.writeInt(count); }));
        }
        try (VaultManifest old = base()) {
            invalid(() -> new VaultManifest(old.entries, old.files, Collections.nCopies(129, binding()), List.of()));
            invalid(() -> new VaultManifest(old.entries, old.files, List.of(), Collections.nCopies(1025, passwordAccount())));
        }
        List<String> certificates = new ArrayList<>();
        for (int i = 0; i < 128; i++) certificates.add("c" + i);
        assertEquals(128, binding("b", "priv", null, certificates, null).certificateFileIds.size());
        certificates.add("tooMany");
        invalid(() -> binding("b", "priv", null, certificates, null));
    }

    @Test public void maximumBindingAccountAndFileCountsRemainRepresentable() throws Exception {
        List<VaultManifest.FileObject> files = new ArrayList<>();
        List<VaultSshMetadata.KeyBinding> bindings = new ArrayList<>();
        List<VaultSshMetadata.Account> accounts = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "f" + i,
                    "key/" + i, "identity" + i, new byte[] {(byte) i}, List.of()));
            bindings.add(binding("b" + i, "f" + i, null, List.of(), null));
        }
        for (int i = 0; i < 1024; i++) accounts.add(account("a" + i, "target:" + i,
                VaultSshMetadata.AuthKind.KEY, "b" + i % 128));
        try (VaultManifest manifest = new VaultManifest(List.of(), files, bindings, accounts);
                VaultManifest decoded = VaultManifest.decode(manifest.encode())) {
            assertEquals(128, decoded.files.size()); assertEquals(128, decoded.keyBindings.size());
            assertEquals(1024, decoded.accounts.size());
            assertArrayEquals(manifest.encode(), decoded.encode());
        }
    }

    @Test public void allAvailableCertificatesCanBeOrderedWithoutInventingPublicMaterial() throws Exception {
        List<VaultManifest.FileObject> files = new ArrayList<>();
        List<String> certificates = new ArrayList<>();
        files.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "private", "key",
                "identity", new byte[0], List.of()));
        for (int i = 126; i >= 0; i--) {
            certificates.add("c" + i);
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.CERTIFICATE, "c" + i,
                    "cert/" + i, "deferred", new byte[0], List.of()));
        }
        try (VaultManifest manifest = new VaultManifest(List.of(), files,
                List.of(binding("b", "private", null, certificates, null)), List.of());
                VaultManifest decoded = VaultManifest.decode(manifest.encode())) {
            assertEquals(certificates, decoded.keyBindings.get(0).certificateFileIds);
            assertNull(decoded.keyBindings.get(0).publicFileId);
        }
    }

    @Test public void encodedPlaintextCapRejectsLargeValidComponentsWithoutWipingCallerSecrets() throws Exception {
        List<VaultManifest.Entry> entries = new ArrayList<>();
        List<VaultManifest.FileObject> files = new ArrayList<>();
        byte[] secret = new byte[8192]; Arrays.fill(secret, (byte) 7);
        byte[] body = new byte[VaultManifest.MAX_FILE_BYTES]; Arrays.fill(body, (byte) 8);
        for (int i = 0; i < 1024; i++) entries.add(new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "e" + i, "target:" + i, "", "", 0, 0, secret));
        for (int i = 0; i < 8; i++) files.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "f" + i, "key/" + i, "deferred", body, List.of()));
        try (VaultManifest manifest = new VaultManifest(entries, files, List.of(), List.of())) {
            invalid(manifest::encode);
            assertArrayEquals(secret, entries.get(0).secret);
            assertArrayEquals(body, files.get(0).content);
        } finally { VaultCodec.wipe(secret); VaultCodec.wipe(body); }
        rejectDecode(new byte[VaultEnvelope.MAX_PLAINTEXT + 1]);
    }

    @Test public void unknownWireTagsAuthTagsMalformedUtf8AndPortsRejectRawWire() throws Exception {
        for (int tag : new int[] {-1, 0, 3, Integer.MAX_VALUE}) {
            byte[] bytes = golden(); ByteBuffer.wrap(bytes).putInt(tag); rejectDecode(bytes);
        }
        for (int kind : new int[] {0, 3, 127, 255}) {
            rejectDecode(wire(out -> { out.writeInt(0); out.writeInt(1); wireAccount(out, 22, "u", kind); }));
        }
        for (int port : new int[] {-1, 0, 65536, Integer.MAX_VALUE}) {
            rejectDecode(wire(out -> { out.writeInt(0); out.writeInt(1); wireAccount(out, port, "u", 1); }));
        }
        rejectDecode(wire(out -> {
            out.writeInt(0); out.writeInt(1); text(out, "a"); text(out, TARGET); text(out, "host");
            out.writeInt(22); out.writeInt(2); out.write(new byte[] {(byte) 0xc0, (byte) 0xaf});
            out.writeByte(1); text(out, "pw");
        }));
        rejectDecode(wire(out -> { out.writeInt(1); out.writeInt(-1); }));
        rejectDecode(wire(out -> { out.writeInt(1); out.writeInt(129); out.write(new byte[129]); }));
        rejectDecode(wire(out -> { out.writeInt(1); text(out, "b"); text(out, "priv"); out.writeInt(129); }));
    }

    @Test public void everyTruncationAndAnyTrailingByteRejectsWithoutChangingInput() throws Exception {
        byte[] golden = golden();
        for (int length = 0; length < golden.length; length++) rejectDecode(Arrays.copyOf(golden, length));
        rejectDecode(Arrays.copyOf(golden, golden.length + 1));
        byte[] legacyWithTail = Arrays.copyOf(hex(LEGACY_TOTP), hex(LEGACY_TOTP).length + 8);
        rejectDecode(legacyWithTail);
        byte[] missingSections = hex("00000002" + LEGACY_SSH.substring(8));
        rejectDecode(missingSections);
    }

    @Test public void metadataOnlyChangesAlterExactEncodingAndKeepSourceArraysIntact() throws Exception {
        try (VaultManifest source = VaultManifest.decode(golden())) {
            VaultSshMetadata.Account changed = new VaultSshMetadata.Account("passwordAccount", TARGET,
                    "host.example.", 2222, " 用户😀 ", VaultSshMetadata.AuthKind.PASSWORD, "pw");
            VaultManifest alternate = new VaultManifest(source.entries, source.files, source.keyBindings,
                    List.of(changed, source.accounts.get(1)));
            assertFalse(Arrays.equals(source.encode(), alternate.encode()));
            assertArrayEquals(golden(), source.encode());
            assertArrayEquals(new byte[] {0x11}, source.entries.get(0).secret);
            assertArrayEquals(new byte[] {0x33}, source.files.get(0).content);
        }
    }
}
