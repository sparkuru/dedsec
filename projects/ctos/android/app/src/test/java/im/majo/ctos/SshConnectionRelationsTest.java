package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

/** Actual encrypted graph transactions; native parsing/signing is an injected, separate gate. */
public final class SshConnectionRelationsTest {
    private static final byte[] KEY = new byte[32];
    private static final byte[] OTP = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    private static final byte[] PASSWORD = "synthetic saved password".getBytes(StandardCharsets.UTF_8);
    private static final String KEY_PATH = ".ssh/id_ctos_test";
    private static final SshPasswordProfile PASSWORD_PROFILE = passwordProfile();

    private static SshPasswordProfile passwordProfile() {
        try { return new SshPasswordProfile("password.invalid", 2222, "password-user"); }
        catch (VaultException error) { throw new AssertionError(error); }
    }

    private static final class Clock implements VaultSession.Clock, SshPasswordManifestValidator.Clock, SshRoleContext.Clock {
        long now = 1000;
        @Override public long now() { return now; }
    }

    private static final class Store implements AppSecrets.Store {
        byte[] bytes;
        int writes;
        boolean writing;
        Runnable beforeCommit;
        @Override public byte[] read() { assertFalse("No transaction-time reread", writing); return bytes.clone(); }
        @Override public byte[] quickMaterial() { assertFalse(writing); return new byte[0]; }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard) throws VaultException {
            writing = true;
            try {
                if (beforeCommit != null) beforeCommit.run();
                guard.beforeCommit();
                bytes = next.clone();
                writes++;
            } finally { writing = false; }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Clock clock = new Clock();
        final Store store = new Store();
        final AppSecrets vault = new AppSecrets(store, clock);
        boolean rejectKeys;
        Fixture() throws Exception {
            try (VaultManifest initial = baseline()) {
                byte[] plain = initial.encode();
                try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], KEY, plain).encode(); }
                finally { VaultCodec.wipe(plain); }
            }
            vault.attachSshValidator(new SshPasswordManifestValidator(clock, (manifest, profile, deadline) -> {
                assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
                assertTrue(deadline > clock.now);
                SshConnectionMaterial.checkShape(manifest);
                if (rejectKeys && !manifest.keyBindings.isEmpty())
                    throw new VaultException("SSH_PROFILE_VALIDATION", "Injected native material rejection");
            }));
            vault.session.foreground(true); vault.session.unlock(KEY);
        }
        SshVaultAdapter adapter(SshBundleProposal approved) {
            return new SshVaultAdapter(vault, (c, t) -> { throw VaultException.invalid(); },
                    p -> { throw VaultException.invalid(); }, actual -> {
                        if (actual != approved) throw new VaultException("VAULT_OPERATION", "Exact approval required");
                    });
        }
        void commit(SshBundleProposal proposal) throws Exception {
            AppSecrets.BundleContext context = vault.newBundleContext(60000, () -> { });
            SshVaultAdapter adapter = adapter(proposal);
            VaultOperation operation = null;
            try { operation = adapter.bundleRequest(proposal, context); adapter.commitBundle(operation, proposal, () -> { }); }
            finally { adapter.cancel(operation); vault.cancelBundleContext(context); }
        }
        VaultManifest read() throws Exception {
            byte[] plain = VaultEnvelope.parse(store.bytes).decrypt(KEY);
            try { return VaultManifest.decode(plain); }
            finally { VaultCodec.wipe(plain); }
        }
        @Override public void close() { vault.session.lock(); VaultCodec.wipe(store.bytes); }
    }

    private static final class KeyFixture implements AutoCloseable {
        final byte[] blob, privateBody, publicLine;
        final VaultManifest.FileObject privateFile, publicFile, config, hosts;
        final VaultSshMetadata.KeyBinding binding;
        KeyFixture(int variant) throws Exception {
            // Predictable public test vectors only, never operational credentials.
            byte[] seed = hex(variant == 0
                    ? "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
                    : "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
            byte[] pub = hex(variant == 0
                    ? "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
                    : "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
            byte[] algorithm = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
            try {
                blob = frame(out -> { string(out, algorithm); string(out, pub); });
                byte[] secret = frame(out -> {
                    out.writeInt(0x12345678); out.writeInt(0x12345678);
                    string(out, algorithm); string(out, pub);
                    out.writeInt(64); out.write(seed); out.write(pub);
                    string(out, new byte[0]);
                });
                byte[] privateBlock = Arrays.copyOf(secret, secret.length + 8 - secret.length % 8);
                for (int i = secret.length; i < privateBlock.length; i++) privateBlock[i] = (byte)(i - secret.length + 1);
                byte[] container;
                try {
                    container = frame(out -> {
                        out.write("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII));
                        string(out, "none".getBytes(StandardCharsets.US_ASCII));
                        string(out, "none".getBytes(StandardCharsets.US_ASCII));
                        string(out, new byte[0]); out.writeInt(1); string(out, blob); string(out, privateBlock);
                    });
                } finally { VaultCodec.wipe(secret); VaultCodec.wipe(privateBlock); }
                try {
                    privateBody = ("-----BEGIN OPENSSH PRIVATE KEY-----\n"
                            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(container)
                            + "\n-----END OPENSSH PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);
                } finally { VaultCodec.wipe(container); }
                publicLine = ("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + "\n")
                        .getBytes(StandardCharsets.US_ASCII);
            } finally { VaultCodec.wipe(seed); VaultCodec.wipe(pub); }
            String identity = "ctos-key-" + SshConnectionMaterial.hex(VaultCodec.digest(blob));
            privateFile = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                    SshConnectionMaterial.id("private", KEY_PATH), KEY_PATH, identity, privateBody, List.of());
            publicFile = new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                    SshConnectionMaterial.id("public", KEY_PATH), KEY_PATH + ".pub", identity, publicLine, List.of());
            binding = new VaultSshMetadata.KeyBinding(SshConnectionMaterial.bindingId(KEY_PATH, identity),
                    privateFile.id, publicFile.id, List.of(), null);
            hosts = SshConnectionMaterial.emptyHosts();
            byte[] body = ("Host target\n HostName key.invalid\n User key-user\n Port 2200\n"
                    + " IdentityFile ~/" + KEY_PATH + "\n IdentitiesOnly yes\n"
                    + " UserKnownHostsFile ~/.ssh/known_hosts\n").getBytes(StandardCharsets.US_ASCII);
            try { config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                    SshConnectionMaterial.id("config", SshConnectionMaterial.ROOT), SshConnectionMaterial.ROOT,
                    SshConnectionMaterial.id("config-owner", SshConnectionMaterial.ROOT), body,
                    List.of(SshConnectionMaterial.HOSTS, KEY_PATH)); }
            finally { VaultCodec.wipe(body); }
        }
        List<VaultManifest.FileObject> files() { return List.of(privateFile, publicFile, hosts, config); }
        SshConnectionMaterial.Key key() throws Exception {
            // This declaration view borrows arrays owned and wiped by this fixture.
            VaultManifest view = new VaultManifest(List.of(), List.of(privateFile, publicFile), List.of(binding), List.of());
            return new SshConnectionMaterial.Key(view, binding);
        }
        SshBundleProposal proposal(VaultManifest source) throws Exception {
            List<SshBundleProposal.Change> edits = new ArrayList<>();
            try {
                for (VaultManifest.FileObject file : files()) edits.add(SshBundleProposal.Change.add(file));
                List<VaultSshMetadata.Account> accounts = new ArrayList<>(source.accounts);
                accounts.add(new SshConnectionMaterial.KeyAccount(
                        new SshPasswordProfile("key.invalid", 2200, "key-user"), key()).declaration());
                return new SshBundleProposal(edits, new SshBundleProposal.Relations(source, List.of(binding), accounts));
            } finally { for (SshBundleProposal.Change edit : edits) edit.close(); }
        }
        @Override public void close() {
            VaultCodec.wipe(blob); VaultCodec.wipe(privateBody); VaultCodec.wipe(publicLine);
            for (VaultManifest.FileObject file : files()) VaultCodec.wipe(file.content);
        }
    }

    private interface Writer { void write(DataOutputStream out) throws Exception; }
    private interface Action { void run() throws Exception; }
    private static byte[] frame(Writer writer) throws Exception {
        try (VaultCodec.SecretBuffer bytes = new VaultCodec.SecretBuffer(); DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out); return bytes.toByteArray();
        }
    }
    private static void string(DataOutputStream out, byte[] bytes) throws Exception { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    private static void code(String expected, Action action) throws Exception {
        try { action.run(); fail("Expected " + expected); }
        catch (VaultException error) { assertEquals(expected, error.code); }
    }

    private static VaultManifest baseline() throws Exception {
        SshSavedPassword password = new SshSavedPassword(PASSWORD_PROFILE);
        byte[] trust = "# retained trust\nfixture.invalid ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea\n"
                .getBytes(StandardCharsets.US_ASCII);
        return new VaultManifest(List.of(
                new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "otp", "otp:fixture", "OTP", "SHA1", 6, 30, OTP),
                new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, password.entryId, password.target,
                        "Saved", "", 0, 0, PASSWORD)), List.of(
                new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, SshPasswordProfile.CONFIG_ID,
                        SshPasswordProfile.CONFIG_PATH, SshPasswordProfile.CONFIG_IDENTITY,
                        PASSWORD_PROFILE.configBytes(), List.of(SshPasswordProfile.HOSTS_PATH)),
                new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, SshPasswordProfile.HOSTS_ID,
                        SshPasswordProfile.HOSTS_PATH, SshPasswordProfile.HOSTS_IDENTITY, trust, List.of())),
                List.of(), List.of(password.account()));
    }

    private static void assertUnrelated(VaultManifest manifest, boolean passwordPresent) throws Exception {
        assertArrayEquals(OTP, manifest.entries.stream().filter(e -> e.type == VaultManifest.EntryType.TOTP).findFirst().get().secret);
        assertEquals(passwordPresent ? 2 : 1, manifest.entries.size());
        if (passwordPresent) {
            assertArrayEquals(PASSWORD, manifest.entries.stream().filter(e -> e.type == VaultManifest.EntryType.SSH_PASSWORD)
                    .findFirst().get().secret);
            assertTrue(new SshSavedPassword(PASSWORD_PROFILE).present(manifest.accounts));
        } else assertTrue(manifest.accounts.stream().noneMatch(a -> a.authKind == VaultSshMetadata.AuthKind.PASSWORD));
        try (VaultManifest original = baseline()) {
            for (VaultManifest.FileObject file : original.files)
                assertEquals(SshBundleProposal.objectBinding(file),
                        SshBundleProposal.objectBinding(SshConnectionMaterial.file(manifest, file.path)));
        }
    }

    @Test public void filesAndKeyRelationsCommitTogetherWithoutReplacingOtherBusinessData() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0);
                VaultManifest source = fixture.read(); SshBundleProposal proposal = key.proposal(source)) {
            long epoch = fixture.vault.session.epoch(); fixture.commit(proposal);
            assertEquals(1, fixture.store.writes); assertEquals(epoch + 1, fixture.vault.session.epoch());
            try (VaultManifest actual = fixture.read()) {
                assertEquals(6, actual.files.size()); assertEquals(1, actual.keyBindings.size());
                assertEquals(2, actual.accounts.size()); SshConnectionMaterial.checkShape(actual);
                assertArrayEquals(key.privateBody, SshConnectionMaterial.file(actual, KEY_PATH).content);
                assertArrayEquals(key.publicLine, SshConnectionMaterial.file(actual, KEY_PATH + ".pub").content);
                assertUnrelated(actual, true);
            }
        }
    }

    private static SshRoleContext keyRole(Fixture fixture, KeyFixture key) throws Exception {
        SshConnectionMaterial.KeyAccount account = new SshConnectionMaterial.KeyAccount(
                new SshPasswordProfile("key.invalid", 2200, "key-user"), key.key());
        return SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                123, 0, "key-connection", account.target, "key.invalid", 2200, "key-view",
                nonce(), nonce(), 31000), () -> true, fixture.clock);
    }

    private static byte[] nonce() { byte[] value = new byte[32]; value[0] = 1; return value; }

    @Test public void selectedKeyBridgeReleasesActualPrivateBodyOnceAndWipesLoan() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0);
                VaultManifest source = fixture.read(); SshBundleProposal proposal = key.proposal(source)) {
            fixture.commit(proposal);
            try (SshRoleContext role = keyRole(fixture, key)) {
                SshAuthenticationSelection selection = SshAuthenticationSelection.privateKey(role, "key-user",
                        KEY_PATH, key.privateFile.identity, key.blob, fixture.clock);
                SshHostKeyAuthority authority = new SshHostKeyAuthority();
                SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                        SshHostKeyAuthority.Classification.KNOWN, "ssh-ed25519", "SHA256:" + "A".repeat(43),
                        new byte[32], new byte[32]);
                SshHostKeyAuthority.Proof hostKey = authority.requireVerified(role, evidence, null);
                SshKexProof kex = SshKexProof.fromNative(role, hostKey, 1, nonce());
                SshCredentialBridge bridge = new SshCredentialBridge(fixture.vault, authority, fixture.clock);
                SshCredentialBridge.Request request = bridge.openSelection(role, hostKey, selection, kex, () -> { });
                java.util.concurrent.atomic.AtomicInteger copies = new java.util.concurrent.atomic.AtomicInteger();
                byte[][] loan = new byte[1][];
                bridge.release(request, kex, selection, bytes -> {
                    assertArrayEquals(key.privateBody, bytes); loan[0] = bytes; copies.incrementAndGet();
                });
                assertEquals(1, copies.get()); assertNotNull(loan[0]);
                assertArrayEquals(new byte[key.privateBody.length], loan[0]);
                code("VAULT_CANCELLED", () -> bridge.release(request, kex, selection, bytes -> copies.incrementAndGet()));
                assertEquals(1, copies.get());
            }
        }
    }

    @Test public void canonicalKeyReadRejectsAbsentSelectionAndDifferentExactResource() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0);
                VaultManifest source = fixture.read(); SshBundleProposal proposal = key.proposal(source)) {
            fixture.commit(proposal);
            try (SshRoleContext role = keyRole(fixture, key)) {
                AppSecrets.SshSecretReadContext absent = fixture.vault.newSshSecretReadContext(31000, () -> { });
                try {
                    code("SSH_AUTH_SELECTION", () -> fixture.vault.beginSshPrivateKey(KEY_PATH,
                            key.privateFile.identity, role.targetIdentity, role.connectionId, absent));
                } finally { absent.cancel(); }
                SshAuthenticationSelection selection = SshAuthenticationSelection.privateKey(role, "key-user",
                        KEY_PATH, key.privateFile.identity, key.blob, fixture.clock);
                AppSecrets.SshSecretReadContext exact = fixture.vault.newSavedPasswordReadContext(31000, () -> { }, selection);
                try {
                    code("SSH_AUTH_SELECTION", () -> fixture.vault.beginSshPrivateKey(KEY_PATH,
                            "different-identity", role.targetIdentity, role.connectionId, exact));
                } finally { exact.cancel(); }
            }
        }
    }

    @Test public void canonicalKeyReadCannotCopyAfterFinalCancellationOrAuthenticatedSourceReplacement() throws Exception {
        for (boolean replace : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0);
                    VaultManifest source = fixture.read(); SshBundleProposal proposal = key.proposal(source)) {
                fixture.commit(proposal);
                try (SshRoleContext role = keyRole(fixture, key)) {
                    SshAuthenticationSelection selection = SshAuthenticationSelection.privateKey(role, "key-user",
                            KEY_PATH, key.privateFile.identity, key.blob, fixture.clock);
                    AppSecrets.SshSecretReadContext context = fixture.vault.newSavedPasswordReadContext(31000, () -> { }, selection);
                    VaultOperation operation = fixture.vault.beginSshPrivateKey(KEY_PATH, key.privateFile.identity,
                            role.targetIdentity, role.connectionId, context);
                    try {
                        if (replace) {
                            VaultEnvelope previous = VaultEnvelope.parse(fixture.store.bytes);
                            try (VaultManifest current = fixture.read(); VaultManifest changed = new VaultManifest(
                                    current.entries, current.files, current.keyBindings,
                                    current.accounts.stream().filter(a -> a.authKind != VaultSshMetadata.AuthKind.KEY)
                                            .collect(java.util.stream.Collectors.toList()))) {
                                byte[] plain = changed.encode();
                                try {
                                    VaultCodec.wipe(fixture.store.bytes);
                                    fixture.store.bytes = VaultEnvelope.encrypt(previous.id, previous.generation,
                                            previous.salt, KEY, plain).encode();
                                } finally { VaultCodec.wipe(plain); }
                            }
                        }
                        java.util.concurrent.atomic.AtomicBoolean copied = new java.util.concurrent.atomic.AtomicBoolean();
                        AppSecrets.EffectGuard finalGuard = () -> { if (!replace) context.cancel(); };
                        code(replace ? "VAULT_CHANGED" : "VAULT_CANCELLED", () -> fixture.vault.sshPrivateKey(operation,
                                KEY_PATH, key.privateFile.identity, role.targetIdentity, role.connectionId,
                                finalGuard, bytes -> copied.set(true)));
                        assertFalse(copied.get()); assertEquals(1, fixture.store.writes);
                    } finally { fixture.vault.cancelOperation(operation); context.cancel(); }
                }
            }
        }
    }

    @Test public void nativeCandidateRejectionCannotLeaveFilesOrMetadataPartiallyPersisted() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0);
                VaultManifest source = fixture.read(); SshBundleProposal proposal = key.proposal(source)) {
            byte[] before = fixture.store.bytes.clone(); fixture.rejectKeys = true;
            code("SSH_PROFILE_VALIDATION", () -> fixture.commit(proposal));
            assertEquals(0, fixture.store.writes); assertArrayEquals(before, fixture.store.bytes);
            try (VaultManifest actual = fixture.read()) { assertEquals(2, actual.files.size()); assertTrue(actual.keyBindings.isEmpty()); }
        }
    }

    @Test public void finalCommitCancellationKeepsEntireOldEncryptedGraph() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0);
                VaultManifest source = fixture.read(); SshBundleProposal proposal = key.proposal(source)) {
            byte[] before = fixture.store.bytes.clone();
            AppSecrets.BundleContext context = fixture.vault.newBundleContext(60000, () -> { });
            SshVaultAdapter adapter = fixture.adapter(proposal);
            VaultOperation operation = adapter.bundleRequest(proposal, context);
            fixture.store.beforeCommit = () -> fixture.vault.cancelBundleContext(context);
            try { code("VAULT_CANCELLED", () -> adapter.commitBundle(operation, proposal, () -> { })); }
            finally { adapter.cancel(operation); fixture.vault.cancelBundleContext(context); }
            assertEquals(0, fixture.store.writes); assertArrayEquals(before, fixture.store.bytes);
        }
    }

    @Test public void fileOnlyHalfImportAndRetargetedRelationProposalAreRejectedWithoutWrites() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0); VaultManifest source = fixture.read()) {
            List<SshBundleProposal.Change> changes = new ArrayList<>();
            try {
                for (VaultManifest.FileObject file : key.files()) changes.add(SshBundleProposal.Change.add(file));
                try (SshBundleProposal half = new SshBundleProposal(changes)) {
                    code("SSH_PROFILE_UNSUPPORTED", () -> fixture.commit(half));
                }
                try (SshBundleProposal approved = key.proposal(source)) {
                    SshConnectionMaterial.KeyAccount other = new SshConnectionMaterial.KeyAccount(
                            new SshPasswordProfile("other.invalid", 2200, "key-user"), key.key());
                    List<VaultSshMetadata.Account> accounts = new ArrayList<>(source.accounts); accounts.add(other.declaration());
                    try (SshBundleProposal retargeted = new SshBundleProposal(changes,
                            new SshBundleProposal.Relations(source, List.of(key.binding), accounts))) {
                        assertNotEquals(approved.binding, retargeted.binding);
                        AppSecrets.BundleContext context = fixture.vault.newBundleContext(60000, () -> { });
                        SshVaultAdapter adapter = fixture.adapter(approved);
                        VaultOperation operation = adapter.bundleRequest(approved, context);
                        try { code("VAULT_OPERATION", () -> fixture.vault.commitSshBundle(operation, retargeted, () -> { })); }
                        finally { adapter.cancel(operation); fixture.vault.cancelBundleContext(context); }
                    }
                }
            } finally { for (SshBundleProposal.Change change : changes) change.close(); }
            assertEquals(0, fixture.store.writes);
            try (VaultManifest actual = fixture.read()) { assertUnrelated(actual, true); }
        }
    }

    @Test public void reviewedSnapshotCannotBeReplacedBeforeBundleAdmission() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0)) {
            AppSecrets.BundleContext context = fixture.vault.newBundleContext(60000, () -> { });
            try (SshConnectionMaterial.Snapshot snapshot = fixture.vault.sshLibrarySnapshot(context);
                    SshBundleProposal proposal = key.proposal(snapshot.manifest)) {
                VaultEnvelope previous = VaultEnvelope.parse(fixture.store.bytes);
                byte[] plain = snapshot.manifest.encode();
                try {
                    byte[] replacement = VaultEnvelope.encrypt(previous.id, previous.generation, previous.salt, KEY, plain).encode();
                    VaultCodec.wipe(fixture.store.bytes); fixture.store.bytes = replacement;
                } finally { VaultCodec.wipe(plain); }
                byte[] beforeAdmission = fixture.store.bytes.clone();
                code("VAULT_CHANGED", () -> fixture.adapter(proposal).bundleRequest(proposal, context));
                assertArrayEquals(beforeAdmission, fixture.store.bytes); assertEquals(0, fixture.store.writes);
            } finally { fixture.vault.cancelBundleContext(context); }
        }
    }

    @Test public void forgettingAllPasswordsPreservesCanonicalKeyGraphTotpAndTrust() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0);
                VaultManifest source = fixture.read(); SshBundleProposal proposal = key.proposal(source)) {
            fixture.commit(proposal);
            try (VaultManifest before = fixture.read()) {
                AppSecrets.SshEntryMutationContext context = fixture.vault.newPasswordForgetAllContext(
                        fixture.vault.session.epoch(), 60000, () -> { });
                VaultOperation operation = fixture.vault.beginPasswordMutation(context, "", false);
                try { fixture.vault.forgetPassword(operation, () -> { }); }
                finally { fixture.vault.cancelOperation(operation); context.cancel(); }
                try (VaultManifest actual = fixture.read()) {
                    assertEquals(2, fixture.store.writes); assertEquals(1, actual.keyBindings.size());
                    assertEquals(1, actual.accounts.size());
                    assertEquals(VaultSshMetadata.AuthKind.KEY, actual.accounts.get(0).authKind);
                    SshConnectionMaterial.checkShape(actual); assertUnrelated(actual, false);
                    for (VaultManifest.FileObject file : before.files)
                        assertEquals(SshBundleProposal.objectBinding(file),
                                SshBundleProposal.objectBinding(SshConnectionMaterial.file(actual, file.path)));
                    assertEquals(before.keyBindings.get(0).id, actual.keyBindings.get(0).id);
                    assertEquals(before.accounts.stream().filter(a -> a.authKind == VaultSshMetadata.AuthKind.KEY)
                            .findFirst().get().id, actual.accounts.get(0).id);
                }
            }
        }
    }

    @Test public void relationMetadataIsImmutableAndBoundToExactAuthenticatedSource() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture key = new KeyFixture(0); VaultManifest source = fixture.read()) {
            List<VaultSshMetadata.KeyBinding> keys = new ArrayList<>(List.of(key.binding));
            List<VaultSshMetadata.Account> accounts = new ArrayList<>(source.accounts);
            accounts.add(new SshConnectionMaterial.KeyAccount(
                    new SshPasswordProfile("key.invalid", 2200, "key-user"), key.key()).declaration());
            SshBundleProposal.Relations relations = new SshBundleProposal.Relations(source, keys, accounts);
            keys.clear(); accounts.clear();
            List<SshBundleProposal.Change> changes = new ArrayList<>();
            try {
                for (VaultManifest.FileObject file : key.files()) changes.add(SshBundleProposal.Change.add(file));
                try (SshBundleProposal proposal = new SshBundleProposal(changes, relations);
                        VaultManifest candidate = proposal.applyTo(source)) {
                    assertEquals(1, candidate.keyBindings.size()); assertEquals(2, candidate.accounts.size());
                    SshConnectionMaterial.checkShape(candidate);
                    VaultSshMetadata.Account original = source.accounts.get(0);
                    VaultSshMetadata.Account altered = new VaultSshMetadata.Account(original.id, original.targetIdentity,
                            original.host, original.port, "different-user", original.authKind, original.credentialId);
                    // A nonowning structural view isolates the metadata-CAS check before semantic validation.
                    VaultManifest changed = new VaultManifest(source.entries, source.files, source.keyBindings, List.of(altered));
                    code("VAULT_CHANGED", () -> proposal.applyTo(changed));
                }
            } finally { for (SshBundleProposal.Change change : changes) change.close(); }
            assertEquals(0, fixture.store.writes);
        }
    }

    @Test public void replacingSamePathWithAnotherKeyInvalidatesOldBindingAndOnlyItsKeyAccounts() throws Exception {
        try (Fixture fixture = new Fixture(); KeyFixture first = new KeyFixture(0); KeyFixture next = new KeyFixture(1)) {
            try (VaultManifest source = fixture.read(); SshBundleProposal initial = first.proposal(source)) { fixture.commit(initial); }
            try (VaultManifest source = fixture.read()) {
                List<VaultSshMetadata.Account> accounts = new ArrayList<>();
                for (VaultSshMetadata.Account account : source.accounts)
                    if (!account.credentialId.equals(first.binding.id)) accounts.add(account);
                accounts.add(new SshConnectionMaterial.KeyAccount(
                        new SshPasswordProfile("key.invalid", 2200, "key-user"), next.key()).declaration());
                List<SshBundleProposal.Change> changes = List.of(
                        SshBundleProposal.Change.replace(SshConnectionMaterial.file(source, KEY_PATH), next.privateFile),
                        SshBundleProposal.Change.replace(SshConnectionMaterial.file(source, KEY_PATH + ".pub"), next.publicFile));
                try (SshBundleProposal replacement = new SshBundleProposal(changes,
                        new SshBundleProposal.Relations(source, List.of(next.binding), accounts))) {
                    fixture.commit(replacement);
                } finally { for (SshBundleProposal.Change change : changes) change.close(); }
                try (VaultManifest actual = fixture.read()) {
                    assertNotEquals(first.binding.id, next.binding.id);
                    code("SSH_KEY_MISSING", () -> SshConnectionMaterial.key(actual, first.binding.id));
                    assertEquals(next.privateFile.identity, SshConnectionMaterial.key(actual, next.binding.id).identity);
                    assertArrayEquals(next.privateBody, SshConnectionMaterial.file(actual, KEY_PATH).content);
                    assertArrayEquals(next.publicLine, SshConnectionMaterial.file(actual, KEY_PATH + ".pub").content);
                    assertUnrelated(actual, true); SshConnectionMaterial.checkShape(actual);
                    for (VaultManifest.FileObject file : source.files) if (file.type == VaultManifest.FileType.CONFIG
                            || file.type == VaultManifest.FileType.KNOWN_HOSTS)
                        assertEquals(SshBundleProposal.objectBinding(file),
                                SshBundleProposal.objectBinding(SshConnectionMaterial.file(actual, file.path)));
                    for (VaultSshMetadata.Account account : actual.accounts)
                        if (account.authKind == VaultSshMetadata.AuthKind.KEY)
                            assertEquals(next.binding.id, account.credentialId);
                }
            }
            assertEquals(2, fixture.store.writes);
        }
    }
}
