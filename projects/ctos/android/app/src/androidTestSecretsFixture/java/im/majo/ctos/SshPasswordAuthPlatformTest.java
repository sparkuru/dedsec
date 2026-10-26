package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;

/** Isolated encrypted KNOWN-host password chain using only synthetic in-memory data. */
public final class SshPasswordAuthPlatformTest {
    private static final String METHOD =
            "im.majo.ctos.SshPasswordAuthPlatformTest#syntheticPasswordAuthenticationChain";
    private static final String KEY_METHOD =
            "im.majo.ctos.SshPasswordAuthPlatformTest#syntheticPrivateKeyAuthenticationChain";
    private static final String ENCRYPTED_METHOD =
            "im.majo.ctos.SshPasswordAuthPlatformTest#syntheticEncryptedPrivateKeyAuthenticationChain";
    private static final String NEW_METHOD =
            "im.majo.ctos.SshPasswordAuthPlatformTest#syntheticNewHostAuthenticationChain";
    private static final String SHELL_METHOD =
            "im.majo.ctos.SshPasswordAuthPlatformTest#syntheticShellChannelChain";
    private static final String TERMINAL_METHOD =
            "im.majo.ctos.SshPasswordAuthPlatformTest#syntheticTerminalControllerChain";
    private static final String MANAGED_METHOD =
            "im.majo.ctos.SshPasswordAuthPlatformTest#syntheticManagedHostChain";
    private static final String PASSPHRASE_ID = "fixture-passphrase";
    private static final String KEY_PATH = "keys/fixture";
    private static final String KEY_IDENTITY = "synthetic-fixture-identity";
    private static final String CONNECTION = "auth-platform-fixture";
    private static final String HOST = "fixture.invalid";
    private static final String TARGET = "target:fixture-user@fixture.invalid:22";
    private static final String USERNAME = "fixture-user";
    private static final String ENTRY_ID = "fixture-password";
    private static final int TURN_LIMIT = 4096;
    private static final int STEP_BUDGET = 4096;

    private static final class MemoryStore implements AppSecrets.Store, AutoCloseable {
        byte[] bytes;
        Runnable beforeRead;
        int reads;
        int commits;

        @Override public byte[] read() {
            reads++;
            Runnable action = beforeRead;
            beforeRead = null;
            if (action != null) action.run();
            return bytes == null ? null : bytes.clone();
        }

        @Override public byte[] quickMaterial() { return new byte[0]; }

        @Override public void commit(byte[] envelope, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            guard.beforeCommit();
            byte[] replacement = envelope.clone();
            VaultCodec.wipe(bytes);
            bytes = replacement;
            commits++;
        }

        @Override public void close() {
            beforeRead = null;
            VaultCodec.wipe(bytes);
            bytes = null;
        }
    }

    private static final class SocketPair implements AutoCloseable {
        final ParcelFileDescriptor peer;
        final int clientFd;
        boolean transferred;

        private SocketPair(ParcelFileDescriptor peer, int clientFd) {
            this.peer = peer;
            this.clientFd = clientFd;
        }

        static SocketPair open() throws Exception {
            FileDescriptor first = new FileDescriptor();
            FileDescriptor second = new FileDescriptor();
            ParcelFileDescriptor client = null;
            ParcelFileDescriptor peer = null;
            int raw = -1;
            boolean retained = false;
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, first, second);
            try {
                client = ParcelFileDescriptor.dup(first);
                peer = ParcelFileDescriptor.dup(second);
                for (FileDescriptor fd : new FileDescriptor[]{client.getFileDescriptor(),
                        peer.getFileDescriptor()}) {
                    int status = Os.fcntlInt(fd, OsConstants.F_GETFL, 0);
                    Os.fcntlInt(fd, OsConstants.F_SETFL, status | OsConstants.O_NONBLOCK);
                    int flags = Os.fcntlInt(fd, OsConstants.F_GETFD, 0);
                    Os.fcntlInt(fd, OsConstants.F_SETFD, flags | OsConstants.FD_CLOEXEC);
                }
                raw = client.detachFd();
                client = null;
                SocketPair result = new SocketPair(peer, raw);
                retained = true;
                return result;
            } finally {
                try { Os.close(first); }
                finally {
                    try { Os.close(second); }
                    finally {
                        if (client != null) client.close();
                        if (!retained && peer != null) peer.close();
                        if (!retained && raw >= 0) closeRaw(raw);
                    }
                }
            }
        }

        private static void closeRaw(int fd) throws IOException {
            try (ParcelFileDescriptor ignored = ParcelFileDescriptor.adoptFd(fd)) {
                // Ownership was never transferred to the production session.
            }
        }

        @Override public void close() throws IOException {
            try { if (!transferred) closeRaw(clientFd); }
            finally { peer.close(); }
        }
    }

    private interface CheckedAction { void run() throws Exception; }

    private static final class Cleanup {
        Throwable failure;

        void run(CheckedAction action) {
            try { action.run(); }
            catch (Throwable current) {
                if (failure == null) failure = current;
                else if (failure != current) failure.addSuppressed(current);
            }
        }

        void finish() throws Exception {
            if (failure instanceof Error) throw (Error)failure;
            if (failure instanceof Exception) throw (Exception)failure;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final SshRoleContext.Clock clock = SystemClock::elapsedRealtime;
        final SshRoleRegistry registry = new SshRoleRegistry(clock);
        final MemoryStore store = new MemoryStore();
        AppSecrets vault;
        SocketPair sockets;
        SshAuthFixturePeer peer;
        SshPublicFileLease view;
        SshNativeRoleBootstrap.Issued issued;
        SshNativeCallbackAdapter callback;
        SshNativeSession session;
        byte[] keyBlob;
        byte[] fingerprint;
        byte[] expectedReceipt;
        int legacyCalls;
        int identityKind;
        boolean encryptedIdentity;
        boolean wrongPassphrase;
        boolean newHost;
        VaultOperation publicRequest;
        SshManagedKnownHostsProvider trustProvider;
        SshKnownHostsCandidateHandoff handoff;
        SshHostKeyAuthority.Review approvedReview;
        long initialEpoch;
        int hostApprovals;
        SshHostKeyReviewMailbox mailbox;
        java.util.concurrent.FutureTask<Boolean> reviewResponse;
        int reviewAnswer = 1; // approve=1, reject=0, cancel=2; synthetic fixture only
        int reviewRequests;
        byte[] identityPublic;
        byte[] identityPrivate;

        static Fixture open(int mode) throws Exception {
            return open(mode, 0);
        }

        static Fixture open(int mode, int identityKind) throws Exception {
            return open(mode, identityKind, false);
        }

        static Fixture open(int mode, int identityKind, boolean encrypted) throws Exception {
            return open(mode, identityKind, encrypted, false);
        }

        static Fixture open(int mode, int identityKind, boolean encrypted, boolean newHost) throws Exception {
            return open(mode, identityKind, encrypted, newHost, false);
        }

        static Fixture open(int mode, int identityKind, boolean encrypted, boolean newHost,
                boolean managedHost) throws Exception {
            Fixture result = new Fixture();
            try {
                result.initialize(mode, identityKind, encrypted, newHost, managedHost);
                return result;
            } catch (Exception | Error failure) {
                try { result.close(); }
                catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private void initialize(int mode, int identityKind, boolean encrypted, boolean newHost,
                boolean managedHost) throws Exception {
            this.newHost = newHost;
            this.identityKind = identityKind;
            this.encryptedIdentity = encrypted;
            this.wrongPassphrase = mode == SshAuthFixturePeer.WRONG_PASSPHRASE;
            sockets = SocketPair.open();
            peer = SshAuthFixturePeer.open(sockets.peer.getFd(), mode, identityKind, encrypted);
            keyBlob = peer.hostKeyBlob();
            fingerprint = VaultCodec.digest(keyBlob);
            if (identityKind != 0) {
                byte[][] material = peer.identityMaterial();
                assertNotNull(material);
                assertEquals(2, material.length);
                identityPublic = material[0];
                identityPrivate = material[1];
                assertNotNull(identityPublic); assertNotNull(identityPrivate);
            }
            try { initializeVault(); }
            finally { VaultCodec.wipe(identityPrivate); identityPrivate = null; }
            SshVaultAdapter publicFiles = new SshVaultAdapter(vault, (connection, target) -> {
                throw new VaultException("SSH_HOST_KEY_REQUIRED", "A live native proof is required");
            });
            VaultOperation request = publicFiles.fileRequest("ssh/config", CONNECTION);
            publicRequest = request;
            view = publicFiles.openPublicFiles(request, "ssh/config", CONNECTION);
            assertTrue(view.active());
            long deadline = Math.min(request.deadline, SystemClock.elapsedRealtime() + 20_000L);
            issued = issueRole(registry, view, clock, deadline);
            initialEpoch = vault.session.epoch();
            if (managedHost && newHost) {
                mailbox = new SshHostKeyReviewMailbox(issued.role, clock,
                        () -> callback.requireAuthenticationSelectionLive(), ticket -> {
                            reviewRequests++;
                            assertEquals(HOST, ticket.host); assertEquals(22, ticket.port);
                            assertEquals(CONNECTION, ticket.connectionId); assertEquals(TARGET, ticket.targetIdentity);
                            reviewResponse = new java.util.concurrent.FutureTask<>(() -> {
                                if (reviewAnswer == 2) { mailbox.cancel(); return true; }
                                return mailbox.resolve(ticket, reviewAnswer == 1);
                            });
                            new Thread(reviewResponse, "synthetic-host-review-ui").start();
                        });
            }
            SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
                if (!newHost) throw VaultException.invalid();
                assertEquals(issued.role.binding(), review.roleBinding);
                assertEquals(CONNECTION, review.connectionId); assertEquals(TARGET, review.targetIdentity);
                assertEquals(HOST, review.host); assertEquals(22, review.port);
                assertEquals(SshHostKeyAuthority.Classification.NEW, review.classification);
                assertEquals("ssh-ed25519", review.keyType);
                assertEquals("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(fingerprint), review.fingerprint);
                assertArrayEquals(VaultCodec.digest(new byte[0]), review.baseDigest());
                byte[] known = knownBody(keyBlob);
                try { assertArrayEquals(VaultCodec.digest(known), review.replacementDigest()); }
                finally { VaultCodec.wipe(known); }
                if (mailbox != null) mailbox.requireApproved(review);
                approvedReview = review; hostApprovals++;
            });
            if (newHost) {
                SshVaultAdapter trustFiles = new SshVaultAdapter(vault, (connection, target) -> {
                    if (approvedReview == null || !CONNECTION.equals(connection) || !TARGET.equals(target)
                            || !issued.role.binding().equals(approvedReview.roleBinding))
                        throw new VaultException("SSH_HOST_KEY_REVIEW", "Exact synthetic host approval required");
                }, proposal -> {
                    if (approvedReview == null) throw new VaultException("SSH_HOST_KEY_REVIEW", "Approval required");
                    assertEquals("ssh/known_hosts", proposal.path);
                    assertEquals("fixture-trust", proposal.objectId);
                    assertEquals("fixture trust", proposal.objectIdentity);
                    assertArrayEquals(approvedReview.baseDigest(), proposal.baseDigest());
                    byte[] known = knownBody(keyBlob), actual = proposal.newContent();
                    try { assertArrayEquals(known, actual); }
                    finally { VaultCodec.wipe(known); VaultCodec.wipe(actual); }
                });
                SshManagedKnownHostsProvider.SnapshotReceipt snapshot = SshManagedKnownHostsProvider.snapshotFor(
                        trustFiles, issued.role, clock, "ssh/config", "ssh/known_hosts", "fixture-trust", "fixture trust");
                trustProvider = SshManagedKnownHostsProvider.bindForNativeHandoff(trustFiles, snapshot);
                handoff = trustProvider.nativeCandidateHandoff();
            }
            SshCredentialBridge bridge = new SshCredentialBridge(vault, authority, clock);
            SshManagedProvider provider = new SshManagedProvider(bridge, clock, trustProvider, trustProvider,
                    (ignoredBridge, ignoredRole, ignoredProof, ignoredKind) -> {
                        legacyCalls++;
                        throw new VaultException("SSH_CREDENTIAL_PROVIDER", "Legacy selection is forbidden");
                    });
            callback = SshNativeCallbackAdapter.bind(issued, registry, authority, clock, provider, handoff);
            SshAuthenticationSelection selection = identityKind == 0
                    ? SshAuthenticationSelection.password(issued.role, USERNAME, ENTRY_ID, clock)
                    : SshAuthenticationSelection.privateKey(issued.role, USERNAME, KEY_PATH,
                            KEY_IDENTITY, identityPublic, encrypted ? PASSPHRASE_ID : "", clock);
            callback.selectAuthentication(selection);
            byte[] wire = selection.encode(issued.token, clock);
            try { expectedReceipt = VaultCodec.digest(wire); }
            finally { VaultCodec.wipe(wire); }
            session = managedHost
                    ? SshNativeSession.openManaged(callback, view, sockets.clientFd,
                            "auth-fixture-candidate", STEP_BUDGET)
                    : SshNativeSession.open(callback, view, sockets.clientFd, keyBlob, fingerprint,
                            "auth-fixture-candidate", STEP_BUDGET);
            sockets.transferred = true;
            assertReceipt();
        }

        private void initializeVault() throws Exception {
            char[] unlock = "synthetic fixture vault password".toCharArray();
            byte[] salt = new byte[16];
            byte[] material = null;
            byte[] plain = null;
            VaultEnvelope envelope = null;
            try (VaultManifest manifest = manifest(keyBlob, identityPrivate, encryptedIdentity, wrongPassphrase, newHost)) {
                material = VaultEnvelope.derive(unlock, salt);
                plain = manifest.encode();
                envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, material, plain);
                store.bytes = envelope.encode();
                vault = new AppSecrets(store, SystemClock::elapsedRealtime);
                vault.attachSshValidator(SshPasswordAuthPlatformTest::validateFixtureManifest);
                vault.session.foreground(true);
                vault.unlock(unlock, () -> { });
                assertTrue(vault.session.unlocked());
            } finally {
                Arrays.fill(unlock, '\0');
                VaultCodec.wipe(salt);
                VaultCodec.wipe(material);
                VaultCodec.wipe(plain);
                if (envelope != null) {
                    VaultCodec.wipe(envelope.salt);
                    VaultCodec.wipe(envelope.nonce);
                    VaultCodec.wipe(envelope.header);
                    VaultCodec.wipe(envelope.ciphertext);
                }
            }
        }

        void assertReceipt() throws Exception {
            byte[] receipt = session.authenticationSelectionReceipt();
            try { assertArrayEquals(expectedReceipt, receipt); }
            finally { VaultCodec.wipe(receipt); }
        }

        void ready() throws Exception {
            boolean complete = false;
            for (int turn = 0; turn < TURN_LIMIT; turn++) {
                peer.step();
                if (session.step().complete) {
                    complete = true;
                    break;
                }
            }
            assertTrue("Real encrypted KEX must complete within the bounded pump", complete);
            assertNotNull(callback.hostProof());
            assertEquals(newHost ? SshHostKeyAuthority.Classification.NEW : SshHostKeyAuthority.Classification.KNOWN,
                    callback.hostProof().classification);
            assertEquals(newHost ? initialEpoch + 1 : initialEpoch, vault.session.epoch());
            assertEquals(newHost ? 1 : 0, store.commits);
            assertEquals(newHost ? 1 : 0, hostApprovals);
            if (mailbox != null) {
                assertEquals(1, reviewRequests);
                assertTrue(reviewResponse.get(2, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertTrue(view.active());
            assertNotNull(callback.kexProof());
            assertEquals(0, legacyCalls);
            assertReceipt();
            session.beginUserauthService();
            boolean ready = false;
            for (int turn = 0; turn < TURN_LIMIT; turn++) {
                if (session.userauthServiceReady()) {
                    ready = true;
                    break;
                }
                peer.step();
                assertTrue("KEX milestone remains complete during service", session.step().complete);
            }
            assertTrue("Service must become ready within the bounded pump", ready);
            if (identityKind == 0)
                assertEquals(SshNativeSession.PasswordAuthResult.NOT_STARTED, session.passwordAuthResult());
            else assertEquals(SshNativeSession.PrivateKeyAuthResult.NOT_STARTED, session.privateKeyAuthResult());
            assertArrayEquals(new int[]{1, 0, 0, 0}, peer.summary());
            assertReceipt();
        }

        SshNativeSession.PasswordAuthResult finishPassword() throws Exception {
            for (int turn = 0; turn < TURN_LIMIT; turn++) {
                SshNativeSession.PasswordAuthResult result = session.passwordAuthResult();
                if (result != SshNativeSession.PasswordAuthResult.PENDING) return result;
                peer.step();
                assertTrue("Authentication preserves the KEX milestone", session.step().complete);
            }
            fail("Password result must arrive within the bounded pump");
            throw new AssertionError();
        }

        SshNativeSession.PrivateKeyAuthResult finishPrivateKey() throws Exception {
            for (int turn = 0; turn < TURN_LIMIT; turn++) {
                SshNativeSession.PrivateKeyAuthResult result = session.privateKeyAuthResult();
                if (result != SshNativeSession.PrivateKeyAuthResult.PENDING) return result;
                peer.step();
                assertTrue("Authentication preserves the KEX milestone", session.step().complete);
            }
            fail("Private-key result must arrive within the bounded pump");
            throw new AssertionError();
        }

        @Override public void close() throws Exception {
            Cleanup cleanup = new Cleanup();
            cleanup.run(() -> { if (mailbox != null) mailbox.close(); });
            cleanup.run(() -> {
                if (reviewResponse != null) reviewResponse.get(2, java.util.concurrent.TimeUnit.SECONDS);
            });
            cleanup.run(() -> { if (session != null) session.close(); });
            cleanup.run(() -> {
                if (session != null) {
                    if (!session.closed()) session.close();
                    assertTrue("Production session cleanup must finish", session.closed());
                    session.close();
                }
            });
            cleanup.run(() -> {
                if (issued != null && issued.role.active()) {
                    if (callback != null) callback.close();
                    else SshNativeRoleBootstrap.release(issued, registry,
                            SshNativeRoleBootstrap.productionRelease());
                }
            });
            cleanup.run(() -> { if (handoff != null) handoff.close(); });
            cleanup.run(() -> { if (trustProvider != null) trustProvider.close(); });
            cleanup.run(() -> { if (view != null) view.close(); });
            cleanup.run(() -> {
                if (peer != null) {
                    peer.close();
                    peer.close();
                }
            });
            cleanup.run(() -> { if (sockets != null) sockets.close(); });
            cleanup.run(() -> { if (vault != null) vault.lock(); });
            cleanup.run(registry::close);
            cleanup.run(store::close);
            cleanup.run(() -> {
                assertEquals("All owned managed roles must be released", 0, registry.size());
                if (view != null) assertFalse("The real native lease must be revoked", view.active());
                assertTrue("No persisted fixture vault", store.bytes == null);
                assertEquals("Selected callbacks must never use the legacy source", 0, legacyCalls);
            });
            VaultCodec.wipe(keyBlob);
            VaultCodec.wipe(fingerprint);
            VaultCodec.wipe(expectedReceipt);
            VaultCodec.wipe(identityPublic);
            VaultCodec.wipe(identityPrivate);
            cleanup.finish();
        }
    }

    private static byte[] knownBody(byte[] hostKey) {
        return (HOST + " ssh-ed25519 " + Base64.getEncoder().encodeToString(hostKey)
                + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static VaultManifest manifest(byte[] hostKey, byte[] privateMaterial, boolean encrypted, boolean wrongPassphrase, boolean newHost) throws VaultException {
        byte[] password = "fixture-password".getBytes(StandardCharsets.US_ASCII);
        byte[] passphrase = (wrongPassphrase ? "wrong-passphrase" : "fixture-key-passphrase").getBytes(StandardCharsets.US_ASCII);
        byte[] config = ("Host " + HOST + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] known = newHost ? new byte[0] : knownBody(hostKey);
        VaultManifest.Entry entry = null;
        VaultManifest.FileObject root = null;
        VaultManifest.FileObject trust = null;
        VaultManifest.FileObject identity = null;
        VaultManifest.Entry passphraseEntry = null;
        boolean retained = false;
        try {
            entry = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                    ENTRY_ID, TARGET, "synthetic password", "", 0, 0, password);
            root = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                    "fixture-config", "ssh/config", "fixture config", config,
                    privateMaterial == null ? VaultCollections.list("ssh/known_hosts")
                            : VaultCollections.list("ssh/known_hosts", KEY_PATH));
            trust = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                    "fixture-trust", "ssh/known_hosts", "fixture trust", known, VaultCollections.list());
            identity = privateMaterial == null ? null
                    : new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                            "fixture-identity", KEY_PATH, KEY_IDENTITY, privateMaterial, VaultCollections.list());
            passphraseEntry = encrypted ? new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE,
                    PASSPHRASE_ID, "key:" + KEY_IDENTITY, "synthetic key passphrase", "", 0, 0, passphrase) : null;
            VaultManifest result = new VaultManifest(passphraseEntry == null
                    ? VaultCollections.list(entry) : VaultCollections.list(entry, passphraseEntry), identity == null
                    ? VaultCollections.list(root, trust) : VaultCollections.list(root, trust, identity));
            retained = true;
            return result;
        } finally {
            VaultCodec.wipe(password);
            VaultCodec.wipe(passphrase);
            VaultCodec.wipe(config);
            VaultCodec.wipe(known);
            if (!retained) {
                if (entry != null) VaultCodec.wipe(entry.secret);
                if (root != null) VaultCodec.wipe(root.content);
                if (trust != null) VaultCodec.wipe(trust.content);
                if (identity != null) VaultCodec.wipe(identity.content);
                if (passphraseEntry != null) VaultCodec.wipe(passphraseEntry.secret);
            }
        }
    }

    private static void validateFixtureManifest(VaultManifest manifest) throws VaultException {
        boolean key = manifest.files.size() == 3;
        boolean encrypted = manifest.entries.size() == 2;
        if ((manifest.entries.size() != 1 && !encrypted) || encrypted && !key
                || (manifest.files.size() != 2 && !key)
                || manifest.entries.get(0).type != VaultManifest.EntryType.SSH_PASSWORD
                || !ENTRY_ID.equals(manifest.entries.get(0).id)
                || !TARGET.equals(manifest.entries.get(0).identity)
                || manifest.files.get(0).type != VaultManifest.FileType.CONFIG
                || !"ssh/config".equals(manifest.files.get(0).path)
                || !(key ? VaultCollections.list("ssh/known_hosts", KEY_PATH)
                        : VaultCollections.list("ssh/known_hosts")).equals(manifest.files.get(0).references)
                || manifest.files.get(1).type != VaultManifest.FileType.KNOWN_HOSTS
                || !"ssh/known_hosts".equals(manifest.files.get(1).path)) throw VaultException.invalid();
        if (encrypted && (manifest.entries.get(1).type != VaultManifest.EntryType.SSH_KEY_PASSPHRASE
                || !PASSPHRASE_ID.equals(manifest.entries.get(1).id)
                || !("key:" + KEY_IDENTITY).equals(manifest.entries.get(1).identity))) throw VaultException.invalid();
        if (key && (manifest.files.get(2).type != VaultManifest.FileType.PRIVATE_KEY
                || !KEY_PATH.equals(manifest.files.get(2).path)
                || !KEY_IDENTITY.equals(manifest.files.get(2).identity)
                || !manifest.files.get(2).references.isEmpty())) throw VaultException.invalid();
    }

    private static SshNativeRoleBootstrap.Issued issueRole(SshRoleRegistry registry,
            SshPublicFileLease view, SshRoleContext.Clock clock, long deadline) throws Exception {
        byte[] nonce = new byte[32];
        byte[] binding = new byte[32];
        byte[] wire = null;
        Arrays.fill(nonce, (byte)3);
        Arrays.fill(binding, (byte)4);
        try {
            SshRoleContext.Bootstrap bootstrap = new SshRoleContext.Bootstrap(
                    SshRoleContext.Program.CLIENT, Process.myPid(), 0, CONNECTION, TARGET, HOST, 22,
                    "auth-platform-view", nonce, binding, deadline);
            wire = SshNativeRoleBootstrap.open(bootstrap);
            return SshNativeRoleBootstrap.issue(wire, registry, view, clock,
                    SshNativeRoleBootstrap.productionOwnership());
        } finally {
            VaultCodec.wipe(nonce);
            VaultCodec.wipe(binding);
            VaultCodec.wipe(wire);
        }
    }

    private static void expectCode(String code, CheckedAction action) throws Exception {
        try {
            action.run();
            fail("The synthetic operation must reject");
        } catch (VaultException failure) {
            assertEquals(code, failure.code);
        }
    }

    private static void typedResult(int mode, SshNativeSession.PasswordAuthResult expected)
            throws Exception {
        try (Fixture fixture = Fixture.open(mode)) {
            fixture.ready();
            fixture.session.beginPasswordAuth();
            assertEquals(expected, fixture.finishPassword());
            assertEquals(expected, fixture.session.passwordAuthResult());
            fixture.assertReceipt();
            assertArrayEquals(new int[]{1, 1, 1, 1}, fixture.peer.summary());
            int reads = fixture.store.reads;
            expectCode("SSH_SESSION_PHASE", fixture.session::beginPasswordAuth);
            expectCode("SSH_SESSION_PHASE", fixture.session::step);
            assertEquals("Duplicate local calls must not read credentials", reads, fixture.store.reads);
            assertArrayEquals(new int[]{1, 1, 1, 1}, fixture.peer.summary());
        }
    }

    private static void cancelAtProvider() throws Exception {
        try (Fixture fixture = Fixture.open(SshAuthFixturePeer.SUCCESS)) {
            fixture.ready();
            int[] cancellations = {0};
            fixture.store.beforeRead = () -> {
                cancellations[0]++;
                try { fixture.session.cancel(); }
                catch (VaultException failure) {
                    throw new IllegalStateException("Synthetic cancellation unavailable");
                }
            };
            expectCode("SSH_SESSION_CANCELLED", fixture.session::beginPasswordAuth);
            assertEquals(1, cancellations[0]);
            assertArrayEquals(new int[]{1, 0, 0, 0}, fixture.peer.summary());
            int reads = fixture.store.reads;
            expectCode("SSH_SESSION_CANCELLED", fixture.session::beginPasswordAuth);
            assertEquals(reads, fixture.store.reads);
        }
    }

    private static void cancelPending() throws Exception {
        try (Fixture fixture = Fixture.open(SshAuthFixturePeer.HOLD)) {
            fixture.ready();
            fixture.session.beginPasswordAuth();
            boolean received = false;
            for (int turn = 0; turn < TURN_LIMIT; turn++) {
                fixture.peer.step();
                if (fixture.peer.summary()[1] == 1) {
                    received = true;
                    break;
                }
                fixture.session.step();
            }
            assertTrue("Fixed peer must decrypt the pending request", received);
            assertArrayEquals(new int[]{1, 1, 0, 1}, fixture.peer.summary());
            assertEquals(SshNativeSession.PasswordAuthResult.PENDING, fixture.session.passwordAuthResult());
            int reads = fixture.store.reads;
            fixture.session.cancel();
            expectCode("SSH_SESSION_CANCELLED", fixture.session::step);
            expectCode("SSH_SESSION_CANCELLED", fixture.session::passwordAuthResult);
            assertEquals(reads, fixture.store.reads);
        }
    }

    private static void malformedSuccess() throws Exception {
        try (Fixture fixture = Fixture.open(SshAuthFixturePeer.MALFORMED_SUCCESS)) {
            fixture.ready();
            fixture.session.beginPasswordAuth();
            try {
                fixture.finishPassword();
                fail("A success packet with trailing bytes must reject");
            } catch (VaultException failure) {
                assertEquals("SSH_SESSION_STEP", failure.code);
            }
            assertArrayEquals(new int[]{1, 1, 1, 1}, fixture.peer.summary());
        }
    }

    private static void typedKeyResult(int kind, int mode, SshNativeSession.PrivateKeyAuthResult expected)
            throws Exception {
        typedKeyResult(kind, mode, expected, false);
    }

    private static void typedKeyResult(int kind, int mode, SshNativeSession.PrivateKeyAuthResult expected,
            boolean encrypted) throws Exception {
        try (Fixture fixture = Fixture.open(mode, kind, encrypted)) {
            fixture.ready();
            fixture.session.beginPrivateKeyAuth();
            assertEquals(expected, fixture.finishPrivateKey());
            assertEquals(expected, fixture.session.privateKeyAuthResult());
            assertArrayEquals(new int[]{1, 1, 1, 1}, fixture.peer.summary());
            int reads = fixture.store.reads;
            expectCode("SSH_SESSION_PHASE", fixture.session::beginPrivateKeyAuth);
            expectCode("SSH_SESSION_PHASE", fixture.session::beginPasswordAuth);
            expectCode("SSH_SESSION_PHASE", fixture.session::passwordAuthResult);
            expectCode("SSH_SESSION_PHASE", fixture.session::step);
            assertEquals(reads, fixture.store.reads);
        }
    }

    @Test public void syntheticPrivateKeyAuthenticationChain() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("allowSshAuthFixture");
        assertTrue("Strict authentication fixture opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped authentication fixture execution required", "true".equals(optIn));
        assertEquals(KEY_METHOD, arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        SshAuthFixturePeer.load(target);
        try {
            for (int kind = 1; kind <= 5; kind++)
                typedKeyResult(kind, SshAuthFixturePeer.SUCCESS, SshNativeSession.PrivateKeyAuthResult.SUCCESS);
            typedKeyResult(1, SshAuthFixturePeer.REJECTED, SshNativeSession.PrivateKeyAuthResult.REJECTED);
            typedKeyResult(1, SshAuthFixturePeer.PARTIAL, SshNativeSession.PrivateKeyAuthResult.PARTIAL);
        } finally { SshMemoryPlatformTest.guard(); }
    }

    @Test public void syntheticEncryptedPrivateKeyAuthenticationChain() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("allowSshAuthFixture");
        assertTrue("Strict authentication fixture opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped authentication fixture execution required", "true".equals(optIn));
        assertEquals(ENCRYPTED_METHOD, arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        SshAuthFixturePeer.load(target);
        try {
            for (int kind = 1; kind <= 5; kind++)
                typedKeyResult(kind, SshAuthFixturePeer.SUCCESS, SshNativeSession.PrivateKeyAuthResult.SUCCESS, true);
            try (Fixture fixture = Fixture.open(SshAuthFixturePeer.WRONG_PASSPHRASE, 1, true)) {
                fixture.ready();
                expectCode("SSH_SESSION_AUTH", fixture.session::beginPrivateKeyAuth);
                assertArrayEquals(new int[]{1, 0, 0, 0}, fixture.peer.summary());
                int reads = fixture.store.reads;
                expectCode("SSH_SESSION_AUTH", fixture.session::beginPrivateKeyAuth);
                assertEquals(reads, fixture.store.reads);
            }
        } finally { SshMemoryPlatformTest.guard(); }
    }

    @Test public void syntheticNewHostAuthenticationChain() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("allowSshAuthFixture");
        assertTrue("Strict authentication fixture opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped authentication fixture execution required", "true".equals(optIn));
        assertEquals(NEW_METHOD, arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        SshAuthFixturePeer.load(target);
        try {
            for (int kind = 0; kind <= 5; kind++) {
                try (Fixture fixture = Fixture.open(SshAuthFixturePeer.SUCCESS, kind, kind != 0, true)) {
                    fixture.ready();
                    if (kind == 0) {
                        fixture.session.beginPasswordAuth();
                        assertEquals(SshNativeSession.PasswordAuthResult.SUCCESS, fixture.finishPassword());
                    } else {
                        fixture.session.beginPrivateKeyAuth();
                        assertEquals(SshNativeSession.PrivateKeyAuthResult.SUCCESS, fixture.finishPrivateKey());
                    }
                    assertTrue(fixture.view.active());
                    assertEquals(fixture.initialEpoch + 1, fixture.vault.session.epoch());
                    assertEquals(1, fixture.store.commits);
                    assertArrayEquals(new int[]{1, 1, 1, 1}, fixture.peer.summary());
                    int reads = fixture.store.reads;
                    fixture.vault.cancelOperation(fixture.publicRequest);
                    assertFalse(fixture.view.active());
                    if (kind == 0) expectCode("SSH_FILE_VIEW", fixture.session::passwordAuthResult);
                    else expectCode("SSH_FILE_VIEW", fixture.session::privateKeyAuthResult);
                    assertEquals(reads, fixture.store.reads);
                }
            }
        } finally { SshMemoryPlatformTest.guard(); }
    }

    private static void authenticateShell(Fixture fixture) throws Exception {
        fixture.ready();
        if (fixture.identityKind == 0) {
            fixture.session.beginPasswordAuth();
            assertEquals(SshNativeSession.PasswordAuthResult.SUCCESS, fixture.finishPassword());
        } else {
            fixture.session.beginPrivateKeyAuth();
            assertEquals(SshNativeSession.PrivateKeyAuthResult.SUCCESS, fixture.finishPrivateKey());
        }
    }

    private static void shellUntil(Fixture fixture, SshNativeSession.ShellPhase expected) throws Exception {
        for (int turn = 0; turn < TURN_LIMIT; turn++) {
            if (fixture.session.shellStatus().phase == expected) return;
            fixture.peer.step();
            fixture.session.step();
        }
        fail("Synthetic channel phase must settle within the bounded pump");
    }

    private static void writeShell(Fixture fixture, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            int offset = 0;
            for (int turn = 0; turn < TURN_LIMIT && offset < bytes.length; turn++) {
                byte[] remaining = java.util.Arrays.copyOfRange(bytes, offset, bytes.length);
                try { offset += fixture.session.writeShell(remaining); }
                finally { VaultCodec.wipe(remaining); }
                fixture.session.step();
                fixture.peer.step();
            }
            assertEquals("No stdin bytes lost under window/maxpacket backpressure", bytes.length, offset);
        } finally { VaultCodec.wipe(bytes); }
    }

    private static void shellRoundTrip(int identityKind, boolean newHost) throws Exception {
        shellRoundTrip(identityKind, newHost, false);
    }

    private static void shellRoundTrip(int identityKind, boolean newHost, boolean managedHost) throws Exception {
        try (Fixture fixture = Fixture.open(SshAuthFixturePeer.SHELL, identityKind,
                identityKind != 0, newHost, managedHost)) {
            if (managedHost) expectCode("SSH_SESSION_PHASE", () -> fixture.session.verifyHostKey(fixture.keyBlob));
            authenticateShell(fixture);
            fixture.session.beginShell(80, 24);
            shellUntil(fixture, SshNativeSession.ShellPhase.RUNNING);
            writeShell(fixture, "printf ctos-shell\r");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] expected = "ctos-shell 你好\r\n[session exited: 99]\r\n".getBytes(StandardCharsets.UTF_8);
            for (int turn = 0; turn < TURN_LIMIT && output.size() < expected.length; turn++) {
                fixture.peer.step();
                fixture.session.step();
                byte[] chunk = fixture.session.readShell(3);
                try { output.write(chunk); }
                finally { VaultCodec.wipe(chunk); }
            }
            assertArrayEquals(expected, output.toByteArray());
            assertEquals(SshNativeSession.ShellPhase.RUNNING, fixture.session.shellStatus().phase);
            boolean resized = false;
            for (int turn = 0; turn < TURN_LIMIT && !resized; turn++) {
                resized = fixture.session.resizeShell(132, 43);
                fixture.session.step();
                fixture.peer.step();
            }
            assertTrue("Resize must be accepted", resized);
            writeShell(fixture, "exit\r");
            shellUntil(fixture, SshNativeSession.ShellPhase.CLOSED);
            // Deliver the final client CLOSE to the peer without re-entering a stopped client.
            for (int turn = 0; turn < 8; turn++) fixture.peer.step();
            SshNativeSession.ShellStatus status = fixture.session.shellStatus();
            assertTrue(status.remoteEof);
            assertTrue(status.exitStatusKnown);
            assertEquals(23L, status.exitStatus);
            assertFalse(status.exitSignal);
            assertArrayEquals(new int[]{1, 1, 1, 23, 1, 0, 1, 1}, fixture.peer.shellSummary());
            expectCode("SSH_SESSION_PHASE", fixture.session::step);
        }
    }

    @Test public void syntheticManagedHostChain() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("allowSshAuthFixture");
        assertTrue("Strict managed host fixture opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped managed host execution required", "true".equals(optIn));
        assertEquals(MANAGED_METHOD, arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        SshAuthFixturePeer.load(target);
        try {
            for (boolean newHost : new boolean[]{false, true}) {
                shellRoundTrip(0, newHost, true);
                shellRoundTrip(1, newHost, true);
            }
            for (int answer : new int[]{0, 2}) {
                try (Fixture fixture = Fixture.open(SshAuthFixturePeer.SHELL, 0, false, true, true)) {
                    fixture.reviewAnswer = answer;
                    boolean denied = false;
                    for (int turn = 0; turn < TURN_LIMIT && !denied; turn++) {
                        fixture.peer.step();
                        try { fixture.session.step(); }
                        catch (VaultException expected) { denied = true; }
                    }
                    assertTrue("Rejected/cancelled exact review must terminate KEX", denied);
                    assertEquals(1, fixture.reviewRequests);
                    assertTrue(fixture.reviewResponse.get(2, java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals(0, fixture.hostApprovals);
                    assertEquals(0, fixture.store.commits);
                    assertEquals(fixture.initialEpoch, fixture.vault.session.epoch());
                    assertEquals(0, fixture.peer.summary()[1]);
                    assertEquals(0, fixture.legacyCalls);
                }
            }
        } finally { SshMemoryPlatformTest.guard(); }
    }

    @Test public void syntheticShellChannelChain() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("allowSshAuthFixture");
        assertTrue("Strict shell fixture opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped shell fixture execution required", "true".equals(optIn));
        assertEquals(SHELL_METHOD, arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        SshAuthFixturePeer.load(target);
        try {
            shellRoundTrip(0, false);
            shellRoundTrip(1, true);
            for (int mode : new int[]{SshAuthFixturePeer.OPEN_REJECT, SshAuthFixturePeer.PTY_REJECT,
                    SshAuthFixturePeer.SHELL_REJECT}) {
                try (Fixture fixture = Fixture.open(mode)) {
                    authenticateShell(fixture);
                    fixture.session.beginShell(80, 24);
                    shellUntil(fixture, SshNativeSession.ShellPhase.REJECTED);
                }
            }
            try (Fixture fixture = Fixture.open(SshAuthFixturePeer.SHELL_HOLD)) {
                authenticateShell(fixture);
                fixture.session.beginShell(80, 24);
                for (int turn = 0; turn < 64; turn++) {
                    fixture.peer.step();
                    fixture.session.step();
                }
                int reads = fixture.store.reads;
                fixture.session.cancel();
                expectCode("SSH_SESSION_CANCELLED", fixture.session::step);
                assertEquals(reads, fixture.store.reads);
            }
            try (Fixture fixture = Fixture.open(SshAuthFixturePeer.SHELL)) {
                authenticateShell(fixture);
                fixture.session.beginShell(80, 24);
                shellUntil(fixture, SshNativeSession.ShellPhase.RUNNING);
                fixture.vault.cancelOperation(fixture.publicRequest);
                assertFalse(fixture.view.active());
                expectCode("SSH_FILE_VIEW", () -> fixture.session.readShell(16));
            }
        } finally { SshMemoryPlatformTest.guard(); }
    }

    private static void controllerRoundTrip(boolean cancel) throws Exception {
        java.util.concurrent.ScheduledExecutorService owner =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<SshTerminalSession> controller = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> peerPump = new java.util.concurrent.atomic.AtomicReference<>();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] expected = "ctos-shell 你好\r\n[session exited: 99]\r\n".getBytes(StandardCharsets.UTF_8);
        owner.execute(() -> {
            Fixture fixture = null;
            try {
                fixture = Fixture.open(cancel ? SshAuthFixturePeer.SHELL_HOLD : SshAuthFixturePeer.SHELL);
                authenticateShell(fixture);
                final Fixture owned = fixture;
                SshTerminalSession terminal = new SshTerminalSession(fixture.session, () -> {
                    java.util.concurrent.ScheduledFuture<?> pump = peerPump.get();
                    if (pump != null) pump.cancel(false);
                    owned.close(); // Checks actual native/session/role/view and extra fixture cleanup.
                }, owner, new SshTerminalSession.Sink() {
                    @Override public void data(byte[] bytes, Runnable consumed) {
                        try {
                            output.write(bytes, 0, bytes.length);
                            if (output.size() == expected.length)
                                controller.get().write("exit\r".getBytes(StandardCharsets.UTF_8));
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                            controller.get().requestCancel();
                        } finally { consumed.run(); }
                    }
                    @Override public void state(String state, Long status, String reason) {
                        try {
                            if ("running".equals(state)) {
                                assertFalse("Pending cancellation scenario must not claim running", cancel);
                                controller.get().write("printf ctos-shell\r".getBytes(StandardCharsets.UTF_8));
                                controller.get().resize(132, 43);
                            } else if ("closed".equals(state) && !cancel) {
                                assertEquals(Long.valueOf(23), status);
                                assertArrayEquals(expected, output.toByteArray());
                            } else if ("failed".equals(state)) {
                                fail("Synthetic controller failure: " + reason);
                            }
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                            controller.get().requestCancel();
                        }
                    }
                }, released::countDown);
                controller.set(terminal);
                peerPump.set(owner.scheduleWithFixedDelay(() -> {
                    try { owned.peer.step(); }
                    catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        terminal.stop();
                    }
                }, 0, 1, java.util.concurrent.TimeUnit.MILLISECONDS));
                terminal.start(80, 24);
                if (cancel) owner.schedule(terminal::stop, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                if (fixture != null) {
                    try { fixture.close(); }
                    catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                }
                released.countDown();
            }
        });
        try {
            assertTrue("Actual owner controller must release all resources", released.await(30, java.util.concurrent.TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Synthetic controller chain failed", failure.get());
            assertTrue(controller.get().closed());
        } finally {
            if (controller.get() != null && !controller.get().closed()) {
                controller.get().requestCancel();
                owner.execute(controller.get()::stop);
                released.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            if (controller.get() == null || controller.get().closed()) owner.shutdown();
        }
        assertTrue("Confirmed released owner executor must terminate", owner.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test public void syntheticTerminalControllerChain() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("allowSshAuthFixture");
        assertTrue("Strict terminal fixture opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped terminal fixture execution required", "true".equals(optIn));
        assertEquals(TERMINAL_METHOD, arguments.getString("class"));
        SshAuthFixturePeer.load(SshMemoryPlatformTest.guard());
        try {
            controllerRoundTrip(false);
            controllerRoundTrip(true);
        } finally { SshMemoryPlatformTest.guard(); }
    }

    @Test public void syntheticPasswordAuthenticationChain() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("allowSshAuthFixture");
        assertTrue("Strict authentication fixture opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped authentication fixture execution required", "true".equals(optIn));
        assertEquals("Only the named authentication fixture method", METHOD, arguments.getString("class"));
        Context target = SshMemoryPlatformTest.guard();
        SshAuthFixturePeer.load(target);
        try {
            typedResult(SshAuthFixturePeer.SUCCESS, SshNativeSession.PasswordAuthResult.SUCCESS);
            typedResult(SshAuthFixturePeer.REJECTED, SshNativeSession.PasswordAuthResult.REJECTED);
            typedResult(SshAuthFixturePeer.PARTIAL, SshNativeSession.PasswordAuthResult.PARTIAL);
            typedResult(SshAuthFixturePeer.CHANGE_REQUIRED, SshNativeSession.PasswordAuthResult.CHANGE_REQUIRED);
            cancelAtProvider();
            cancelPending();
            malformedSuccess();
        } finally { SshMemoryPlatformTest.guard(); }
    }
}
