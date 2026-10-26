package im.majo.ctos;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Real encrypted mutation/read pipeline; native parsing and Android transport are separate gates. */
public final class SshSavedPasswordTest {
    private static final byte[] KEY = new byte[32];
    private static final byte[] OTP = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    private static final byte[] SECRET = " exact synthetic password 雪 ".getBytes(StandardCharsets.UTF_8);
    private static final class Clock implements VaultSession.Clock, SshPasswordManifestValidator.Clock, SshRoleContext.Clock {
        long now = 1000;
        @Override public long now() { return now; }
    }
    private static final class Store implements AppSecrets.Store {
        byte[] bytes;
        int writes;
        Runnable beforeCommit;
        boolean writing;
        @Override public byte[] read() { assertFalse("No AtomicFile read inside transaction", writing); return bytes.clone(); }
        @Override public byte[] quickMaterial() { assertFalse(writing); return new byte[0]; }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard) throws VaultException {
            writing = true;
            try {
                if (beforeCommit != null) beforeCommit.run();
                guard.beforeCommit(); bytes = next.clone(); writes++;
            } finally { writing = false; }
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Clock clock = new Clock();
        final Store store = new Store();
        final AppSecrets vault = new AppSecrets(store, clock);
        final AtomicInteger validations = new AtomicInteger();
        Fixture() throws Exception {
            this(null, new byte[0]);
        }
        Fixture(SshPasswordProfile profile, byte[] knownHosts) throws Exception {
            List<VaultManifest.FileObject> files = profile == null ? List.of() : List.of(
                    new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, SshPasswordProfile.CONFIG_ID,
                            SshPasswordProfile.CONFIG_PATH, SshPasswordProfile.CONFIG_IDENTITY, profile.configBytes(),
                            List.of(SshPasswordProfile.HOSTS_PATH)),
                    new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, SshPasswordProfile.HOSTS_ID,
                            SshPasswordProfile.HOSTS_PATH, SshPasswordProfile.HOSTS_IDENTITY, knownHosts, List.of()));
            try (VaultManifest source = new VaultManifest(List.of(new VaultManifest.Entry(VaultManifest.EntryType.TOTP,
                    "otp", "otp:fixture", "Fixture", "SHA1", 6, 30, OTP)), files)) {
                byte[] plain = source.encode();
                try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], KEY, plain).encode(); }
                finally { VaultCodec.wipe(plain); }
            }
            vault.attachSshValidator(this::validate);
            vault.session.foreground(true); vault.session.unlock(KEY);
        }
        void validate(VaultManifest source) throws VaultException {
            assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
            SshPasswordProfile.checkShape(source); validations.incrementAndGet();
        }
        AppSecrets.SshEntryMutationContext context(SshPasswordProfile profile) throws Exception {
            return vault.newPasswordMutationContext(vault.session.epoch(), 60000, () -> { }, profile, false);
        }
        void save(SshPasswordProfile profile, byte[] secret) throws Exception {
            AppSecrets.SshEntryMutationContext context = context(profile);
            VaultOperation operation = vault.beginPasswordMutation(context, "fixture", true);
            try { vault.savePassword(operation, secret, () -> { }); }
            finally { vault.cancelOperation(operation); context.cancel(); }
        }
        void forget(SshPasswordProfile profile, boolean all) throws Exception {
            AppSecrets.SshEntryMutationContext context = all
                    ? vault.newPasswordForgetAllContext(vault.session.epoch(), 60000, () -> { }) : context(profile);
            VaultOperation operation = vault.beginPasswordMutation(context, "", false);
            try { vault.forgetPassword(operation, () -> { }); }
            finally { vault.cancelOperation(operation); context.cancel(); }
        }
        VaultManifest read() throws Exception {
            byte[] plain = VaultEnvelope.parse(store.bytes).decrypt(KEY);
            try { return VaultManifest.decode(plain); } finally { VaultCodec.wipe(plain); }
        }
        @Override public void close() { vault.session.lock(); VaultCodec.wipe(store.bytes); }
    }
    private interface Action { void run() throws Exception; }
    private static void code(String expected, Action action) throws Exception {
        try { action.run(); fail("Expected " + expected); }
        catch (VaultException failure) { assertEquals(expected, failure.code); }
    }
    private static SshPasswordProfile profile(String host, int port, String user) throws Exception {
        return new SshPasswordProfile(host, port, user);
    }
    private static byte[] nonce() { byte[] value = new byte[32]; value[0] = 1; return value; }

    private static SshRoleContext role(Fixture fixture, SshSavedPassword saved) throws Exception {
        return SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                123, 0, "connection", saved.target, saved.profile.host, saved.profile.port, "view",
                nonce(), nonce(), 31000), () -> true, fixture.clock);
    }

    private static void assertPublicFiles(VaultManifest manifest, byte[] config, byte[] knownHosts) {
        assertEquals(2, manifest.files.size());
        for (VaultManifest.FileObject file : manifest.files) {
            if (file.type == VaultManifest.FileType.CONFIG) {
                assertEquals(SshPasswordProfile.CONFIG_ID, file.id);
                assertEquals(SshPasswordProfile.CONFIG_PATH, file.path);
                assertEquals(SshPasswordProfile.CONFIG_IDENTITY, file.identity);
                assertEquals(List.of(SshPasswordProfile.HOSTS_PATH), file.references);
                assertArrayEquals(config, file.content);
            } else {
                assertEquals(VaultManifest.FileType.KNOWN_HOSTS, file.type);
                assertEquals(SshPasswordProfile.HOSTS_ID, file.id);
                assertEquals(SshPasswordProfile.HOSTS_PATH, file.path);
                assertEquals(SshPasswordProfile.HOSTS_IDENTITY, file.identity);
                assertTrue(file.references.isEmpty());
                assertArrayEquals(knownHosts, file.content);
            }
        }
    }

    @Test public void tupleDigestHasIndependentLengthFramedGoldenAndPreservesExactUsername() throws Exception {
        SshPasswordProfile profile = profile("fixture.invalid", 2222, " user雪 ");
        SshSavedPassword saved = new SshSavedPassword(profile);
        byte[] host = profile.host.getBytes(StandardCharsets.UTF_8), user = profile.username.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = ByteBuffer.allocate(16 + host.length + user.length).putInt(0x43535057)
                .putInt(host.length).put(host).putInt(2222).putInt(user.length).put(user).array();
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder expected = new StringBuilder();
        for (byte value : digest) expected.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        assertEquals("target:ctos-password-" + expected, saved.target);
        assertNotEquals(saved.target, new SshSavedPassword(profile("fixture.invalid", 22, " user雪 ")).target);
        assertNotEquals(saved.target, new SshSavedPassword(profile("fixture.invalid", 2222, "user雪")).target);
        assertNotEquals(saved.target, new SshSavedPassword(profile("other.invalid", 2222, " user雪 ")).target);
    }

    @Test public void explicitSaveWritesOneAtomicAccountAndSecretAndSurvivesDecode() throws Exception {
        try (Fixture f = new Fixture()) {
            SshPasswordProfile profile = profile("fixture.invalid", 2222, " user ");
            long epoch = f.vault.session.epoch(); f.save(profile, SECRET);
            assertEquals(1, f.store.writes); assertEquals(epoch + 1, f.vault.session.epoch());
            try (VaultManifest manifest = f.read()) {
                assertEquals(2, manifest.entries.size()); assertEquals(1, manifest.accounts.size());
                SshSavedPassword saved = new SshSavedPassword(profile);
                assertTrue(saved.present(manifest.accounts));
                assertArrayEquals(SECRET, manifest.entries.get(1).secret);
                assertArrayEquals(OTP, manifest.entries.get(0).secret);
                assertNull(SshPasswordProfile.checkShape(manifest));
            }
            assertFalse(new String(f.store.bytes, StandardCharsets.ISO_8859_1).contains("exact synthetic password"));
        }
    }

    @Test public void multipleTargetsReplacementAndForgetKeepUnrelatedTotpAndTrust() throws Exception {
        SshPasswordProfile first = profile("fixture.invalid", 22, "user"), second = profile("fixture.invalid", 2222, "user");
        byte[] config = first.configBytes();
        // This suite injects the native parser boundary; it asserts exact preservation, not key parsing.
        byte[] knownHosts = "# retain reviewed trust\nfixture.invalid ssh-ed25519 synthetic-native-validated-body\n"
                .getBytes(StandardCharsets.US_ASCII);
        try (Fixture f = new Fixture(first, knownHosts)) {
            f.save(first, SECRET); f.save(second, "second".getBytes(StandardCharsets.UTF_8));
            f.save(first, "replacement".getBytes(StandardCharsets.UTF_8));
            try (VaultManifest manifest = f.read()) {
                assertEquals(3, manifest.entries.size()); assertEquals(2, manifest.accounts.size());
                assertPublicFiles(manifest, config, knownHosts);
                assertArrayEquals(OTP, manifest.entries.get(0).secret);
            }
            f.forget(first, false);
            try (VaultManifest manifest = f.read()) {
                assertEquals(2, manifest.entries.size()); assertEquals(1, manifest.accounts.size());
                assertFalse(new SshSavedPassword(first).present(manifest.accounts));
                assertTrue(new SshSavedPassword(second).present(manifest.accounts));
                assertPublicFiles(manifest, config, knownHosts);
                assertArrayEquals(OTP, manifest.entries.get(0).secret);
            }
            f.forget(null, true);
            try (VaultManifest manifest = f.read()) {
                assertEquals(1, manifest.entries.size()); assertTrue(manifest.accounts.isEmpty());
                assertArrayEquals(OTP, manifest.entries.get(0).secret);
                assertPublicFiles(manifest, config, knownHosts);
            }
        }
    }

    @Test public void accountOnlySavedStateCanBeForgottenWithoutAnyRecentTuple() throws Exception {
        try (Fixture f = new Fixture()) {
            f.save(profile("fixture.invalid", 22, "user"), SECRET);
            AppSecrets.BundleContext context = f.vault.newBundleContext(60000, () -> { });
            try (SshPasswordProfile.Snapshot snapshot = f.vault.passwordProfile(context)) {
                assertNull(snapshot.profile); assertEquals(1, snapshot.accounts.size());
            } finally { f.vault.cancelBundleContext(context); }
            f.forget(null, true);
            try (VaultManifest manifest = f.read()) { assertTrue(manifest.accounts.isEmpty()); }
        }
    }

    @Test public void malformedOrUnboundSavedPasswordsNeverReachStorage() throws Exception {
        try (Fixture f = new Fixture()) {
            SshPasswordProfile profile = profile("fixture.invalid", 22, "user");
            for (byte[] bad : new byte[][] {new byte[0], new byte[]{0}, new byte[]{(byte)0xc0, (byte)0x80}, new byte[8193]}) {
                AppSecrets.SshEntryMutationContext context = f.context(profile);
                VaultOperation operation = f.vault.beginPasswordMutation(context, "fixture", true);
                try { code("PARAMETERS", () -> f.vault.savePassword(operation, bad, () -> { })); }
                finally { f.vault.cancelOperation(operation); context.cancel(); }
            }
            assertEquals(0, f.store.writes);
            SshSavedPassword saved = new SshSavedPassword(profile);
            try (VaultManifest orphan = new VaultManifest(List.of(new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                    saved.entryId, saved.target, "", "", 0, 0, SECRET)), List.of())) {
                code("SSH_PROFILE_UNSUPPORTED", () -> SshPasswordProfile.checkShape(orphan));
            }
        }
    }

    @Test public void originalEpochDeadlineCancellationAndRegistrationRejectBeforeWrite() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            try (Fixture f = new Fixture()) {
                SshPasswordProfile profile = profile("fixture.invalid", 22, "user");
                AppSecrets.SshEntryMutationContext context = f.context(profile);
                VaultOperation operation = f.vault.beginPasswordMutation(context, "fixture", true);
                String expected;
                if (mode == 0) { context.cancel(); expected = "VAULT_CANCELLED"; }
                else if (mode == 1) { f.clock.now = 60000; expected = "VAULT_EXPIRED"; }
                else if (mode == 2) { f.vault.session.lock(); expected = "VAULT_CHANGED"; }
                else { f.vault.attachSshValidator(f::validate); expected = "VAULT_CHANGED"; }
                byte[] before = f.store.bytes.clone();
                code(expected, () -> f.vault.savePassword(operation, SECRET, () -> { }));
                assertEquals(0, f.store.writes); assertArrayEquals(before, f.store.bytes);
            }
        }
    }

    @Test public void cancellationInsideFinalCommitClaimPreservesEntirePriorAccountState() throws Exception {
        try (Fixture f = new Fixture()) {
            SshPasswordProfile profile = profile("fixture.invalid", 22, "user");
            f.save(profile, SECRET); byte[] before = f.store.bytes.clone();
            AppSecrets.SshEntryMutationContext context = f.context(profile);
            VaultOperation operation = f.vault.beginPasswordMutation(context, "", false);
            f.store.beforeCommit = context::cancel;
            code("VAULT_CANCELLED", () -> f.vault.forgetPassword(operation, () -> { }));
            assertArrayEquals(before, f.store.bytes); assertEquals(1, f.store.writes);
        }
    }

    @Test public void selectedReadBindsActualAccountToRolePortUsernameAndEntry() throws Exception {
        try (Fixture f = new Fixture()) {
            SshPasswordProfile profile = profile("fixture.invalid", 2222, "user");
            f.save(profile, SECRET); SshSavedPassword saved = new SshSavedPassword(profile);
            for (int mode = 0; mode < 4; mode++) {
                String user = mode == 1 ? "other" : "user";
                int port = mode == 2 ? 22 : 2222;
                String entryId = mode == 3 ? "other" : saved.entryId;
                SshRoleContext role = SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                        123, 0, "connection", saved.target, "fixture.invalid", port, "view",
                        nonce(), nonce(), 31000), () -> true, f.clock);
                SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, user, entryId, f.clock);
                try (VaultManifest manifest = f.read()) {
                    if (mode == 0) SshSavedPassword.requireSelection(manifest, selection);
                    else code("SSH_AUTH_SELECTION", () -> SshSavedPassword.requireSelection(manifest, selection));
                }
            }
        }
    }

    @Test public void typedReadRepeatsAccountCheckAtEffectAndDoesNotCopyAfterCancellation() throws Exception {
        try (Fixture f = new Fixture()) {
            SshPasswordProfile profile = profile("fixture.invalid", 2222, "user");
            f.save(profile, SECRET); SshSavedPassword saved = new SshSavedPassword(profile);
            SshRoleContext role = SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                    123, 0, "connection", saved.target, profile.host, profile.port, "view",
                    nonce(), nonce(), 31000), () -> true, f.clock);
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, profile.username, saved.entryId, f.clock);
            AppSecrets.SshSecretReadContext context = f.vault.newSavedPasswordReadContext(31000, () -> { }, selection);
            VaultOperation operation = f.vault.beginSshCredential(saved.entryId, saved.target, "connection",
                    VaultManifest.EntryType.SSH_PASSWORD, context);
            AtomicBoolean received = new AtomicBoolean(); context.cancel();
            code("VAULT_CANCELLED", () -> f.vault.sshCredential(operation, saved.entryId, saved.target, "connection",
                    VaultManifest.EntryType.SSH_PASSWORD, () -> { }, bytes -> received.set(true)));
            assertFalse(received.get());
        }
    }

    @Test public void selectedBridgeReadsSavedPasswordOnceAfterMatchingSyntheticProofsAndWipesItsLoan() throws Exception {
        try (Fixture f = new Fixture()) {
            SshSavedPassword saved = new SshSavedPassword(profile("fixture.invalid", 2222, " exact user "));
            f.save(saved.profile, SECRET);
            SshRoleContext role = role(f, saved);
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(
                    role, saved.profile.username, saved.entryId, f.clock);
            SshHostKeyAuthority authority = new SshHostKeyAuthority();
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                    SshHostKeyAuthority.Classification.KNOWN, "ssh-ed25519", "SHA256:" + "A".repeat(43), nonce(), nonce());
            SshHostKeyAuthority.Proof hostKey = authority.requireVerified(role, evidence, null);
            SshKexProof kex = SshKexProof.fromNative(role, hostKey, 1, nonce());
            SshCredentialBridge bridge = new SshCredentialBridge(f.vault, authority, f.clock);
            AtomicInteger copies = new AtomicInteger();
            SshCredentialBridge.Request noKex = bridge.openSelection(role, hostKey, selection);
            code("SSH_KEX_REQUIRED", () -> bridge.release(noKex, null, selection, bytes -> copies.incrementAndGet()));
            assertEquals(0, copies.get());

            byte[][] loan = new byte[1][];
            SshCredentialBridge.Request request = bridge.openSelection(role, hostKey, selection, kex, () -> { });
            bridge.release(request, kex, selection, bytes -> {
                assertArrayEquals(SECRET, bytes);
                loan[0] = bytes;
                copies.incrementAndGet();
            });
            assertEquals(1, copies.get());
            assertNotNull(loan[0]);
            assertArrayEquals(new byte[SECRET.length], loan[0]);
            code("VAULT_CANCELLED", () -> bridge.release(request, kex, selection, bytes -> copies.incrementAndGet()));
            assertEquals(1, copies.get());
        }
    }

    @Test public void savedNamespaceRejectsNoSelectionAndSelectionForAnotherAccountBeforeRead() throws Exception {
        try (Fixture f = new Fixture()) {
            SshSavedPassword first = new SshSavedPassword(profile("fixture.invalid", 22, "first"));
            SshSavedPassword second = new SshSavedPassword(profile("fixture.invalid", 22, "second"));
            f.save(first.profile, SECRET); f.save(second.profile, "second".getBytes(StandardCharsets.UTF_8));
            code("SSH_AUTH_SELECTION", () -> f.vault.beginSshCredential(first.entryId, first.target, "connection",
                    VaultManifest.EntryType.SSH_PASSWORD, f.vault.newSshSecretReadContext(31000, () -> { })));
            SshRoleContext role = role(f, first);
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(
                    role, first.profile.username, first.entryId, f.clock);
            AppSecrets.SshSecretReadContext context = f.vault.newSavedPasswordReadContext(31000, () -> { }, selection);
            code("SSH_AUTH_SELECTION", () -> f.vault.beginSshCredential(second.entryId, second.target, "connection",
                    VaultManifest.EntryType.SSH_PASSWORD, context));
        }
    }

    @Test public void authenticatedSourceAccountReplacementAfterBeginCannotReleaseOldSelectedSecret() throws Exception {
        try (Fixture f = new Fixture()) {
            SshSavedPassword saved = new SshSavedPassword(profile("fixture.invalid", 2222, "user"));
            f.save(saved.profile, SECRET);
            SshRoleContext role = role(f, saved);
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(
                    role, saved.profile.username, saved.entryId, f.clock);
            AppSecrets.SshSecretReadContext context = f.vault.newSavedPasswordReadContext(31000, () -> { }, selection);
            VaultOperation operation = f.vault.beginSshCredential(saved.entryId, saved.target, "connection",
                    VaultManifest.EntryType.SSH_PASSWORD, context);
            VaultEnvelope envelope = VaultEnvelope.parse(f.store.bytes);
            try (VaultManifest source = f.read(); VaultManifest changed = SshSavedPassword.candidate(source, saved, false, null)) {
                byte[] plain = changed.encode();
                try {
                    VaultCodec.wipe(f.store.bytes);
                    f.store.bytes = VaultEnvelope.encrypt(envelope.id, envelope.generation, envelope.salt, KEY, plain).encode();
                } finally { VaultCodec.wipe(plain); }
            }
            AtomicBoolean copied = new AtomicBoolean();
            code("VAULT_CHANGED", () -> f.vault.sshCredential(operation, saved.entryId, saved.target, "connection",
                    VaultManifest.EntryType.SSH_PASSWORD, () -> { }, bytes -> copied.set(true)));
            assertFalse(copied.get());
            assertEquals(1, f.store.writes);
            try (VaultManifest actual = f.read()) { assertTrue(actual.accounts.isEmpty()); }
        }
    }
}
