package im.majo.ctos;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Real core bundle and NEW trust CAS, with only parser/native proof/view boundaries injected. */
public final class SshTransientPasswordTest {
    private static final byte[] KEY = new byte[32];
    private static final byte[] SECRET = "synthetic-one-attempt-password-雪".getBytes(StandardCharsets.UTF_8);
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);
    private static final class Clock implements VaultSession.Clock, SshRoleContext.Clock,
            SshPasswordManifestValidator.Clock {
        long now = 1000;
        @Override public long now() { return now; }
    }
    private static final class Store implements AppSecrets.Store {
        byte[] bytes;
        int writes;
        @Override public byte[] read() { return bytes.clone(); }
        @Override public byte[] quickMaterial() { return new byte[0]; }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard) throws VaultException {
            guard.beforeCommit(); bytes = next.clone(); writes++;
        }
    }
    private static final class View implements SshRoleContext.PublicView, VaultSession.Revocation {
        boolean active = true;
        @Override public boolean active() { return active; }
        @Override public void revoke() { active = false; }
    }
    private static final class Fixture implements AutoCloseable {
        final Clock clock = new Clock();
        final Store store = new Store();
        final AppSecrets vault = new AppSecrets(store, clock);
        final SshPasswordProfile profile = new SshPasswordProfile("fixture.invalid", 2222, "actual user");
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<String> transcript = new ArrayList<>();
        final SshTransientPassword password;
        final View view = new View();
        final SshRoleContext role;
        final SshAuthenticationSelection selection;
        final SshHostKeyAuthority authority;
        final SshManagedProvider managed;
        SshHostKeyAuthority.Proof proof;
        SshKexProof kex;
        long expectedEpoch;
        boolean viewAttached;

        Fixture() throws Exception { this(SECRET); }
        Fixture(byte[] selectedPassword) throws Exception {
            try (VaultManifest empty = new VaultManifest(List.of(), List.of())) {
                byte[] plain = empty.encode();
                try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], KEY, plain).encode(); }
                finally { VaultCodec.wipe(plain); }
            }
            vault.attachSshValidator(new SshPasswordManifestValidator(clock, (m, p, d) -> transcript.add("pinned-check")));
            vault.session.foreground(true); vault.session.unlock(KEY); expectedEpoch = vault.session.epoch();
            password = new SshTransientPassword(vault, profile, selectedPassword, 60000, this::live, clock);
            AppSecrets.BundleContext original = vault.newBundleContext(60000, this::live);
            transcript.add("request-captured");
            try (SshPasswordProfile.Snapshot snapshot = vault.passwordProfile(original);
                    SshBundleProposal proposal = snapshot.changes(profile)) {
                SshVaultAdapter adapter = new SshVaultAdapter(vault, (c, t) -> { throw VaultException.invalid(); },
                        p -> { throw VaultException.invalid(); }, exact -> {
                            live(); if (exact != proposal) throw VaultException.invalid();
                        });
                VaultOperation operation = adapter.bundleRequest(proposal, original);
                transcript.add("source-candidate-admitted");
                try { adapter.commitBundle(operation, proposal, this::live); }
                finally { adapter.cancel(operation); }
                expectedEpoch++;
                live(); transcript.add("profile-atomic-store");
            } finally { vault.cancelBundleContext(original); }
            vault.session.attachRevocation(expectedEpoch, view);
            viewAttached = true;
            byte[] nonce = new byte[32], connection = new byte[32];
            Arrays.fill(nonce, (byte)1); Arrays.fill(connection, (byte)2);
            role = SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT, 42, 0,
                    "actual-connection", SshPasswordProfile.TARGET, profile.host, profile.port,
                    "actual-public-view", nonce, connection, 60000), view, clock);
            selection = SshAuthenticationSelection.password(role, profile.username, password.entryId, clock);
            password.bind(role, selection); transcript.add("selected-source-bound");
            authority = new SshHostKeyAuthority();
            managed = new SshManagedProvider(new SshCredentialBridge(vault, authority, clock, password), clock,
                    null, null, (b, r, p, k) -> { throw new AssertionError("Legacy credential path"); });
        }
        void live() throws VaultException {
            if (cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic cancellation");
            if (!vault.session.foreground()) throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Foreground required");
            if (!vault.session.unlocked()) throw new VaultException("VAULT_LOCKED", "Locked");
            if (!viewAttached) vault.session.assertEpoch(expectedEpoch);
            else if (!view.active()) throw new VaultException("VAULT_CHANGED", "Revoked");
        }
        void known() throws Exception {
            byte[] digest = VaultCodec.digest(new byte[0]);
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                    SshHostKeyAuthority.Classification.KNOWN, "ssh-ed25519", FINGERPRINT, digest, digest);
            proof = authority.requireVerified(role, evidence, null); signedKex();
        }
        void signedKex() throws Exception {
            byte[] digest = new byte[32]; digest[0] = 7;
            kex = SshKexProof.fromNative(role, proof, 1, digest); transcript.add("signed-kex");
        }
        byte[] take(AppSecrets.EffectGuard callback) throws Exception {
            return managed.credentialBytes(role, proof, kex, SshCredentialBridge.Kind.PASSWORD, selection, callback);
        }
        void assertWiped() throws Exception {
            java.lang.reflect.Field field = SshTransientPassword.class.getDeclaredField("bytes"); field.setAccessible(true);
            byte[] value = (byte[])field.get(password); assertArrayEquals(new byte[value.length], value);
        }
        @Override public void close() {
            password.close(); role.close(); vault.session.lock(); VaultCodec.wipe(store.bytes);
        }
    }
    private interface Action { void run() throws Exception; }
    private static void code(String expected, Action action) throws Exception {
        try { action.run(); fail("Expected " + expected); }
        catch (VaultException failure) { assertEquals(expected, failure.code); }
    }

    @Test public void validUtf8DecodeAndFlushUnderflowPreserveAsciiMultibyteAndExactByteLimit() throws Exception {
        byte[] limit = new byte[8192]; Arrays.fill(limit, (byte)'x');
        byte[] multibyteLimit = "🙂".repeat(2048).getBytes(StandardCharsets.UTF_8);
        for (byte[] expected : new byte[][] {
                "synthetic ASCII password".getBytes(StandardCharsets.UTF_8),
                "密码-é-🙂".getBytes(StandardCharsets.UTF_8), limit, multibyteLimit }) {
            byte[] original = expected.clone();
            try (Fixture f = new Fixture(expected)) {
                f.known(); byte[] released = f.take(() -> { });
                try { assertArrayEquals(original, released); }
                finally { VaultCodec.wipe(released); }
                assertArrayEquals("The constructor and consumption own only their copies", original, expected);
                f.assertWiped();
            } finally { VaultCodec.wipe(original); VaultCodec.wipe(expected); }
        }
    }

    @Test public void malformedTruncatedOverlongSurrogateAndOverboundUtf8FailWithFixedError() throws Exception {
        byte[] overbound = new byte[8193]; Arrays.fill(overbound, (byte)'x');
        byte[][] invalid = {
                new byte[0], overbound,
                {'a', (byte)0xc3, '('}, // Invalid continuation after decoded public prefix.
                {(byte)0xe2, (byte)0x82}, // Incomplete input at end-of-input must not be flushed as valid.
                {(byte)0xf0, (byte)0x9f, (byte)0x99},
                {(byte)0xc0, (byte)0xaf}, // Overlong form.
                {(byte)0xed, (byte)0xa0, (byte)0x80}, // Encoded UTF-16 surrogate.
                {(byte)0xf4, (byte)0x90, (byte)0x80, (byte)0x80}, // Beyond U+10FFFF.
                {(byte)0x80}, {'a', 0, 'b'}
        };
        Clock clock = new Clock();
        AppSecrets vault = new AppSecrets(new Store(), clock);
        SshPasswordProfile profile = new SshPasswordProfile("fixture.invalid", 22, "user");
        for (byte[] input : invalid) {
            byte[] original = input.clone();
            try {
                code("SSH_CREDENTIAL_PROVIDER", () -> {
                    try (SshTransientPassword unexpected = new SshTransientPassword(vault, profile, input, 60000,
                            () -> { throw new AssertionError("Construction must not publish a password"); }, clock)) {
                        fail("Malformed or overbound password must be rejected");
                    }
                });
                assertArrayEquals(original, input);
            } finally { VaultCodec.wipe(original); VaultCodec.wipe(input); }
        }
    }

    @Test public void selectedSourceCopiesOnceAfterProofAndNeverPersistsPassword() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.transcript.indexOf("request-captured") < f.transcript.indexOf("profile-atomic-store"));
            assertTrue(f.transcript.contains("source-candidate-admitted"));
            f.known(); byte[] result = f.take(() -> { });
            try { assertArrayEquals(SECRET, result); } finally { VaultCodec.wipe(result); }
            f.assertWiped(); code("SSH_CREDENTIAL_PROVIDER", () -> f.take(() -> { }));
            byte[] plain = VaultEnvelope.parse(f.store.bytes).decrypt(KEY);
            try (VaultManifest saved = VaultManifest.decode(plain)) {
                assertTrue(saved.entries.isEmpty()); assertEquals(2, saved.files.size());
                assertFalse(new String(plain, StandardCharsets.UTF_8).contains(new String(SECRET, StandardCharsets.UTF_8)));
            } finally { VaultCodec.wipe(plain); }
        }
    }

    @Test public void actualNewTrustCasKeepsExactViewAndSelectedPasswordAliveAcrossItsEpoch() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] body = "fixture.invalid ssh-ed25519 synthetic-native-candidate\n".getBytes(StandardCharsets.US_ASCII);
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(f.role,
                    SshHostKeyAuthority.Classification.NEW, "ssh-ed25519", FINGERPRINT,
                    VaultCodec.digest(new byte[0]), VaultCodec.digest(body));
            SshHostKeyAuthority.Review[] approved = new SshHostKeyAuthority.Review[1];
            SshHostKeyReviewMailbox[] owner = new SshHostKeyReviewMailbox[1];
            try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(f.role, f.clock, f::live,
                    ticket -> { assertEquals(f.profile.host, ticket.host); assertTrue(owner[0].resolve(ticket, true)); })) {
                owner[0] = mailbox;
                SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
                    mailbox.requireApproved(review); approved[0] = review;
                });
                SshVaultAdapter adapter = new SshVaultAdapter(f.vault, (c, t) -> {
                    if (approved[0] == null || !f.role.connectionId.equals(c) || !f.role.targetIdentity.equals(t))
                        throw VaultException.invalid();
                }, proposal -> {
                    if (approved[0] == null || !proposal.matchesRole(f.role)) throw VaultException.invalid();
                    assertArrayEquals(approved[0].baseDigest(), proposal.baseDigest());
                    assertArrayEquals(approved[0].replacementDigest(), proposal.newDigest());
                });
                SshManagedKnownHostsProvider.SnapshotReceipt snapshot = SshManagedKnownHostsProvider.snapshotFor(adapter,
                        f.role, f.clock, SshPasswordProfile.CONFIG_PATH, SshPasswordProfile.HOSTS_PATH,
                        SshPasswordProfile.HOSTS_ID, SshPasswordProfile.HOSTS_IDENTITY);
                SshManagedKnownHostsProvider.KnownHostsCandidate candidate =
                        SshManagedKnownHostsProvider.candidateFor(snapshot, evidence, body);
                try (SshManagedKnownHostsProvider provider = SshManagedKnownHostsProvider.bind(adapter, snapshot, candidate)) {
                    SshManagedProvider managed = new SshManagedProvider(new SshCredentialBridge(f.vault, authority, f.clock),
                            f.clock, provider, provider, null);
                    try (SshTrustProposal proposal = managed.proposalFor(evidence)) {
                        f.proof = authority.requireVerified(f.role, evidence, proposal);
                        long epoch = f.vault.session.epoch();
                        assertEquals(SshTrustProposal.CasStatus.COMMITTED, managed.writeBack(evidence, f.proof, proposal).status);
                        assertEquals(epoch + 1, f.vault.session.epoch()); assertTrue(f.view.active());
                        assertSame(f.view, f.vault.session.attachedRevocation(epoch + 1));
                        assertEquals(2, f.store.writes); f.signedKex();
                        byte[] result = f.take(() -> { });
                        try { assertArrayEquals(SECRET, result); } finally { VaultCodec.wipe(result); }
                        f.assertWiped();
                    }
                }
            }
        }
    }

    @Test public void missingProofWrongSelectionLockCancelAndDeadlineCannotCopy() throws Exception {
        try (Fixture f = new Fixture()) {
            code("SSH_CREDENTIAL_PROVIDER", () -> f.password.take(f.role, null, null, f.selection, () -> { }));
            f.assertWiped();
        }
        try (Fixture f = new Fixture()) {
            f.known();
            SshAuthenticationSelection other = SshAuthenticationSelection.password(f.role, f.profile.username,
                    f.password.entryId, f.clock);
            code("SSH_CREDENTIAL_PROVIDER", () -> f.password.take(f.role, f.proof, f.kex, other, () -> { }));
            f.assertWiped();
        }
        try (Fixture f = new Fixture()) {
            f.known(); f.vault.session.lock();
            code("SSH_FILE_VIEW", () -> f.take(() -> { }));
        }
        try (Fixture f = new Fixture()) {
            f.known(); f.cancelled.set(true);
            code("SSH_CANCELLED", () -> f.take(() -> { })); f.assertWiped();
        }
        try (Fixture f = new Fixture()) {
            f.known(); f.clock.now = 60000;
            code("SSH_ROLE_EXPIRED", () -> f.take(() -> { }));
        }
    }

    @Test public void cancellationAfterGuardedCopyWipesOutputAndSourceInsteadOfPublishing() throws Exception {
        try (Fixture f = new Fixture()) {
            f.known();
            AppSecrets.EffectGuard late = new AppSecrets.EffectGuard() {
                @Override public void check() throws VaultException { f.live(); }
                @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                    publication.check(); f.cancelled.set(true);
                }
            };
            code("SSH_CANCELLED", () -> f.take(late)); f.assertWiped();
        }
    }
}
