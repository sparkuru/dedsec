package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/** Real encrypted synthetic sources; no JNI, device, account or semantic-provider claim. */
public final class SshSecretReadValidationTest {
    private static final byte[] KEY = new byte[32];
    private static final String TARGET = "target:test@example.invalid:22";
    private Clock clock;
    private MemoryStore store;
    private AppSecrets vault;
    private VaultRequestPhase phase;
    private Runnable beforePublish, afterPublish;
    private AtomicInteger received;

    private static final class Clock implements VaultSession.Clock, SshRoleContext.Clock {
        volatile long now = 100;
        Runnable onRead;
        @Override public long now() { if (onRead != null) onRead.run(); return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        Runnable onRead;
        int reads;
        @Override public byte[] read() {
            reads++;
            if (onRead != null) onRead.run();
            return bytes.clone();
        }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException { guard.beforeCommit(); bytes = next.clone(); }
    }
    private interface Action { void run() throws Exception; }
    private static final class View implements SshRoleContext.PublicView {
        volatile boolean active = true;
        @Override public boolean active() { return active; }
        synchronized void revoke() { active = false; }
    }

    @Before public void setup() throws Exception {
        clock = new Clock(); store = new MemoryStore(); phase = new VaultRequestPhase();
        beforePublish = null; afterPublish = null; received = new AtomicInteger();
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "password", TARGET, "Synthetic", "", 0, 0, new byte[] {9, 8, 7});
        VaultManifest.Entry passphrase = new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE,
                "passphrase", "key:key-one", "Synthetic key", "", 0, 0, new byte[] {3, 2, 1});
        VaultManifest.FileObject key = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "key", "key", "key-one", new byte[] {5, 6}, List.of());
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "config", "config", "config", new byte[] {1}, List.of("key", "known_hosts"));
        VaultManifest.FileObject hosts = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "known_hosts", "known_hosts", "known_hosts", new byte[] {2}, List.of());
        try (VaultManifest manifest = new VaultManifest(List.of(password, passphrase), List.of(config, key, hosts))) {
            byte[] plain = manifest.encode();
            try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], KEY, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        }
        vault = new AppSecrets(store, clock); vault.session.foreground(true); vault.session.unlock(KEY);
        vault.attachSshValidator(loan -> { });
    }

    private AppSecrets.EffectGuard origin() {
        return new AppSecrets.EffectGuard() {
            @Override public void check() throws VaultException {
                if (phase.cancelled()) throw new VaultException("VAULT_CANCELLED", "Synthetic cancellation");
            }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
                assertFalse(Thread.holdsLock(vault.publicationLock));
                if (beforePublish != null) beforePublish.run();
                phase.publish(() -> { check(); publication.check(); });
                if (afterPublish != null) afterPublish.run();
            }
        };
    }
    private AppSecrets.SshSecretReadContext context() throws VaultException {
        return vault.newSshSecretReadContext(vault.session.epoch(), 900, origin());
    }
    private VaultOperation admit(int kind, AppSecrets.SshSecretReadContext context) throws VaultException {
        if (kind == 0) return vault.beginSshCredential("password", TARGET, "connection",
                VaultManifest.EntryType.SSH_PASSWORD, context);
        if (kind == 1) return vault.beginSshPrivateKey("key", "key-one", TARGET, "connection", context);
        return vault.beginSshPrivateKeyBundle("key", "key-one", "passphrase", TARGET, "connection", context);
    }
    private void read(int kind, VaultOperation operation) throws VaultException {
        read(kind, operation, bytes -> {
            assertFalse(Thread.holdsLock(vault));
            assertTrue(Thread.holdsLock(vault.session)); assertTrue(Thread.holdsLock(vault.publicationLock));
            assertTrue(Thread.holdsLock(operation));
            received.incrementAndGet();
            if (kind == 0) assertArrayEquals(new byte[] {9, 8, 7}, bytes);
            else if (kind == 1) assertArrayEquals(new byte[] {5, 6}, bytes);
            else {
                ByteBuffer bundle = ByteBuffer.wrap(bytes);
                assertEquals(0x43534b42, bundle.getInt()); assertEquals(1, bundle.getInt());
                assertEquals(2, bundle.getInt()); assertEquals(5, bundle.get()); assertEquals(6, bundle.get());
                assertEquals(3, bundle.getInt()); assertEquals(3, bundle.get()); assertEquals(2, bundle.get());
                assertEquals(1, bundle.get()); assertFalse(bundle.hasRemaining());
            }
        });
    }
    private void read(int kind, VaultOperation operation, SshVaultAdapter.CredentialReceiver receiver) throws VaultException {
        if (kind == 0) vault.sshCredential(operation, "password", TARGET, "connection",
                VaultManifest.EntryType.SSH_PASSWORD, () -> { }, receiver);
        else if (kind == 1) vault.sshPrivateKey(operation, "key", "key-one", TARGET, "connection", () -> { }, receiver);
        else vault.sshPrivateKeyBundle(operation, "key", "key-one", "passphrase", TARGET,
                "connection", () -> { }, receiver);
    }
    private void detached() {
        assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(phase));
        assertFalse(Thread.holdsLock(vault.session)); assertFalse(Thread.holdsLock(vault.publicationLock));
    }
    private static Object field(Object owner, String name) {
        try {
            java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true); return field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private byte[][] sourceBuffers() {
        Object work = field(vault, "sshSecretReadWork"); assertNotNull(work);
        return new byte[][] {(byte[]) field(work, "key"), (byte[]) field(work, "ciphertext")};
    }
    private static void wiped(byte[] bytes) { assertNotNull(bytes); assertArrayEquals(new byte[bytes.length], bytes); }
    private static void wiped(VaultManifest loan) {
        assertNotNull(loan);
        for (VaultManifest.Entry entry : loan.entries) wiped(entry.secret);
        for (VaultManifest.FileObject file : loan.files) wiped(file.content);
    }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }
    private static Thread start(Action action, AtomicReference<Throwable> outcome) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable failure) { outcome.set(failure); } });
        thread.start(); return thread;
    }
    private static void await(CountDownLatch latch) throws VaultException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Synthetic timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new VaultException("VAULT_CANCELLED", "Interrupted"); }
    }
    private void invalidate(int kind, AppSecrets.SshSecretReadContext context, VaultOperation operation,
            AppSecrets.SshContentValidator validator) {
        switch (kind) {
            case 0: phase.cancel(); break;
            case 1: context.cancel(); break;
            case 2: vault.session.lock(); break;
            case 3: vault.session.foreground(false); break;
            case 4: clock.now = 900; break;
            case 5: vault.attachSshValidator(validator); break;
            case 6: vault.attachSshValidator(null); break;
            case 7: synchronized (vault.publicationLock) { vault.contentRevision++; } break;
            case 8: store.bytes[store.bytes.length - 1] ^= 1; break;
            case 9: vault.cancelOperation(operation); break;
            default: throw new AssertionError();
        }
    }
    private static String invalidationCode(int kind) {
        return kind <= 1 || kind == 9 ? "VAULT_CANCELLED" : kind == 3 ? "VAULT_FOREGROUND_REQUIRED"
                : kind == 4 ? "VAULT_EXPIRED" : "VAULT_CHANGED";
    }

    @Test public void pausedTypedAdmissionAndEffectStayCancellableAndRejectEveryInvalidatedSource() throws Exception {
        for (int type = 0; type < 3; type++) for (boolean effect : new boolean[] {false, true})
                for (int kind = 0; kind < 10; kind++) {
            if (!effect && kind == 9) continue;
            setup(); final int selected = type, invalidation = kind;
            AtomicBoolean armed = new AtomicBoolean(); AtomicReference<VaultManifest> retained = new AtomicReference<>();
            AtomicReference<byte[][]> captured = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AppSecrets.SshContentValidator validator = loan -> {
                detached();
                if (armed.get()) { retained.set(loan); captured.set(sourceBuffers()); entered.countDown(); await(release); }
            };
            vault.attachSshValidator(validator);
            AppSecrets.SshSecretReadContext context = context(); VaultOperation operation = effect ? admit(type, context) : null;
            armed.set(true); AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = start(() -> { if (effect) read(selected, operation); else admit(selected, context); }, outcome);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<Throwable> mutatorError = new AtomicReference<>(); CountDownLatch changed = new CountDownLatch(1);
            Thread mutator = start(() -> {
                invalidate(invalidation, context, operation, validator);
                synchronized (vault) { changed.countDown(); }
            }, mutatorError);
            try { assertTrue("Invalidation blocked behind validator", changed.await(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); worker.join(5000); mutator.join(5000); }
            assertFalse(worker.isAlive()); assertFalse(mutator.isAlive()); assertNull(mutatorError.get());
            assertTrue(outcome.get() instanceof VaultException);
            assertEquals(invalidationCode(kind), ((VaultException) outcome.get()).code);
            assertEquals(0, received.get()); wiped(retained.get());
            for (byte[] bytes : captured.get()) wiped(bytes);
            if (effect) assertTrue(operation.consumed);
        }
    }

    @Test public void independentValidatorLoansRejectMutationAndWipeOnEveryThrowable() throws Exception {
        for (boolean effect : new boolean[] {false, true}) for (int type = 0; type < 3; type++)
                for (int kind = 0; kind < 5; kind++) {
            setup(); final int problem = kind; AtomicBoolean armed = new AtomicBoolean();
            AtomicReference<VaultManifest> retained = new AtomicReference<>(); AtomicReference<byte[][]> buffers = new AtomicReference<>();
            vault.attachSshValidator(loan -> {
                detached(); if (!armed.get()) return; retained.set(loan); buffers.set(sourceBuffers());
                switch (problem) {
                    case 0: loan.entries.get(0).secret[0] ^= 1; break;
                    case 1: loan.close(); break;
                    case 2: throw new VaultException("FIXTURE", "Validator");
                    case 3: throw new IllegalStateException("Validator");
                    case 4: throw new AssertionError("Validator");
                    default: throw new AssertionError();
                }
            });
            AppSecrets.SshSecretReadContext context = context(); VaultOperation operation = effect ? admit(type, context) : null;
            armed.set(true); Throwable failure = null; byte[] original = store.bytes.clone();
            try { if (effect) read(type, operation); else admit(type, context); } catch (Throwable error) { failure = error; }
            assertNotNull(failure); assertEquals(0, received.get()); wiped(retained.get());
            for (byte[] bytes : buffers.get()) wiped(bytes);
            assertArrayEquals(original, store.bytes); assertNull(field(vault, "sshSecretReadWork"));
            armed.set(false); read(type, admit(type, context()));
        }
    }

    @Test public void wrongBindingsBusyAndReplayCannotStealOrRestoreAnOperation() throws Exception {
        VaultOperation operation = admit(0, context());
        rejects("VAULT_OPERATION", () -> vault.sshCredential(operation, "password", TARGET, "wrong",
                VaultManifest.EntryType.SSH_PASSWORD, () -> { }, bytes -> fail()));
        rejects("VAULT_OPERATION", () -> vault.sshCredential(operation, "password", TARGET, "connection",
                VaultManifest.EntryType.SSH_KEY_PASSPHRASE, () -> { }, bytes -> fail()));
        rejects("VAULT_OPERATION", () -> read(1, operation)); assertFalse(operation.consumed);
        AppSecrets foreign = new AppSecrets(store, clock);
        rejects("VAULT_OPERATION", () -> foreign.sshCredential(operation, "password", TARGET, "connection",
                VaultManifest.EntryType.SSH_PASSWORD, () -> { }, bytes -> fail()));
        AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
        // Attach before admission: registration identity must not change afterward.
        vault.attachSshValidator(loan -> {
            if (armed.get()) {
                calls.incrementAndGet();
                try { read(0, operation); fail(); } catch (VaultException error) { assertEquals("BUSY", error.code); }
            }
        });
        VaultOperation other = admit(1, context()); armed.set(true); read(1, other);
        assertEquals(1, calls.get()); assertFalse(operation.consumed);
        // The old registration is stale even though its capability survived BUSY.
        rejects("VAULT_CHANGED", () -> read(0, operation)); assertTrue(operation.consumed);
        rejects("VAULT_OPERATION", () -> read(0, operation));
        armed.set(false); VaultOperation valid = admit(0, context()); read(0, valid);
        rejects("VAULT_OPERATION", () -> read(0, valid));
        valid.cancel(); rejects("VAULT_CANCELLED", () -> read(0, valid));
    }

    @Test public void sourceAndPublicationSeamsRejectBeforeCopyAndWipeAfterReceiverFailure() throws Exception {
        for (int type = 0; type < 3; type++) for (int seam = 0; seam < 6; seam++) {
            setup(); VaultOperation operation = admit(type, context()); final int kind = type;
            if (seam == 0) beforePublish = () -> operation.cancel();
            if (seam == 1) beforePublish = () -> { synchronized (vault.publicationLock) { vault.contentRevision++; } };
            if (seam == 2) {
                int finalRead = store.reads + 2;
                store.onRead = () -> { if (store.reads == finalRead) operation.cancel(); };
            }
            if (seam < 3) {
                rejects(seam == 1 ? "VAULT_CHANGED" : "VAULT_CANCELLED", () -> read(kind, operation));
                assertEquals(0, received.get());
            } else {
                AtomicReference<byte[]> borrowed = new AtomicReference<>(); final int failureKind = seam;
                Throwable outcome = null;
                try { read(type, operation, bytes -> {
                    borrowed.set(bytes);
                    if (failureKind == 3) throw new VaultException("FIXTURE", "Receiver");
                    if (failureKind == 4) throw new IllegalStateException("Receiver");
                    throw new AssertionError("Receiver");
                }); } catch (Throwable failure) { outcome = failure; }
                assertNotNull(outcome); wiped(borrowed.get());
            }
            assertNull(field(vault, "sshSecretReadWork")); assertTrue(operation.consumed);
        }
    }

    @Test public void contextCreationIsMetadataOnlyAndBudgetDoesNotRestartAfterAdmission() throws Exception {
        long epoch = vault.session.epoch();
        AppSecrets.SshSecretReadContext context = vault.newSshSecretReadContext(epoch, 101, origin());
        assertEquals(0, store.reads); VaultOperation operation = admit(0, context);
        assertEquals(101, operation.deadline); clock.now = 101;
        rejects("VAULT_EXPIRED", () -> read(0, operation)); assertEquals(0, received.get());
        rejects("VAULT_OPERATION", () -> admit(0, context));
    }

    private SshRoleContext role(View view) throws VaultException {
        byte[] nonce = new byte[32]; Arrays.fill(nonce, (byte) 1);
        return SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT, 42, 0,
                "connection", TARGET, "example.invalid", 22, "view", nonce, nonce, 900), view, clock);
    }
    private SshHostKeyAuthority.Proof proof(SshRoleContext role) throws VaultException {
        return new SshHostKeyAuthority().requireVerified(role, SshHostKeyAuthority.Evidence.fromNative(role,
                SshHostKeyAuthority.Classification.KNOWN, "ssh-ed25519", "SHA256:" + "A".repeat(43),
                new byte[32], new byte[32]), null);
    }
    private SshKexProof kex(SshRoleContext role, SshHostKeyAuthority.Proof proof) throws VaultException {
        byte[] digest = new byte[32]; digest[0] = 1; return SshKexProof.fromNative(role, proof, 1, digest);
    }

    @Test public void selectedAdmissionRetainsOriginalCallbackGuardAndRoleBudget() throws Exception {
        for (int failure = 0; failure < 4; failure++) {
            setup(); View view = new View(); SshRoleContext role = role(view); SshHostKeyAuthority.Proof proof = proof(role);
            SshKexProof kex = kex(role, proof);
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, "test", "password", clock);
            final int problem = failure;
            vault.attachSshValidator(loan -> {
                detached();
                if (problem == 0) phase.cancel();
                else if (problem == 1) clock.now = 900;
                else if (problem == 2) role.close();
                else view.revoke();
            });
            SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
            rejects(problem == 0 ? "VAULT_CANCELLED" : problem == 3 ? "SSH_FILE_VIEW" : "SSH_ROLE_EXPIRED",
                    () -> bridge.openSelection(role, proof, selection, kex, origin()));
            assertEquals(0, received.get());
        }
    }

    @Test public void finalCopyHoldsExactRequestOperationRoleViewAndPreservesOriginPublish() throws Exception {
        View view = new View(); SshRoleContext role = role(view); SshHostKeyAuthority.Proof proof = proof(role);
        SshKexProof kex = kex(role, proof);
        SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, "test", "password", clock);
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        AppSecrets.EffectGuard origin = origin();
        SshCredentialBridge.Request request = bridge.openSelection(role, proof, selection, kex, origin);
        assertEquals(role.deadline, request.operation.deadline);
        AtomicReference<byte[]> borrowed = new AtomicReference<>(); AtomicInteger publications = new AtomicInteger();
        beforePublish = publications::incrementAndGet;
        bridge.release(request, kex, selection, origin, bytes -> {
            assertFalse(Thread.holdsLock(vault)); assertTrue(Thread.holdsLock(phase));
            assertTrue(Thread.holdsLock(request)); assertTrue(Thread.holdsLock(vault.publicationLock));
            assertTrue(Thread.holdsLock(vault.session)); assertTrue(Thread.holdsLock(request.operation));
            assertTrue(Thread.holdsLock(role)); assertTrue(Thread.holdsLock(view));
            assertArrayEquals(new byte[] {9, 8, 7}, bytes); borrowed.set(bytes);
        });
        assertEquals(1, publications.get()); wiped(borrowed.get());
    }

    @Test public void publicationHookFailureCancelsProvisionalOperationAndProviderRejectsLateReturn() throws Exception {
        afterPublish = () -> { throw new AssertionError("After issuance"); };
        AppSecrets.SshSecretReadContext context = context();
        try { admit(0, context); fail(); } catch (AssertionError expected) { assertEquals("After issuance", expected.getMessage()); }
        VaultOperation issued = (VaultOperation) field(context, "issued"); assertNotNull(issued); assertTrue(issued.cancelled);
        assertNull(field(vault, "sshSecretReadWork"));
        afterPublish = null;
        View view = new View(); SshRoleContext role = role(view); SshHostKeyAuthority.Proof proof = proof(role);
        SshKexProof kex = kex(role, proof);
        SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, "test", "password", clock);
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        SshManagedProvider provider = new SshManagedProvider(bridge, clock, null, null);
        AtomicInteger publications = new AtomicInteger();
        afterPublish = () -> { if (publications.incrementAndGet() == 2) phase.cancel(); };
        rejects("VAULT_CANCELLED", () -> provider.credentialBytes(role, proof, kex,
                SshCredentialBridge.Kind.PASSWORD, selection, origin()));
        assertEquals(2, publications.get()); assertNull(field(vault, "sshSecretReadWork"));
    }

    @Test public void finalRoleViewRequestAndOperationInvalidationPreventReceiverEntry() throws Exception {
        for (int kind = 0; kind < 5; kind++) {
            setup(); View view = new View(); SshRoleContext role = role(view); SshHostKeyAuthority.Proof proof = proof(role);
            SshKexProof kex = kex(role, proof);
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, "test", "password", clock);
            SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
            AppSecrets.EffectGuard original = origin();
            SshCredentialBridge.Request request = bridge.openSelection(role, proof, selection, kex, original);
            final int invalidation = kind;
            beforePublish = () -> {
                if (invalidation == 0) request.close();
                else if (invalidation == 1) request.operation.cancel();
                else if (invalidation == 2) role.close();
                else if (invalidation == 3) view.revoke();
                else vault.session.lock();
            };
            String expected = kind == 0 ? "VAULT_CANCELLED" : kind == 1 ? "VAULT_OPERATION"
                    : kind == 2 ? "SSH_ROLE_EXPIRED" : kind == 3 ? "SSH_FILE_VIEW" : "VAULT_CHANGED";
            rejects(expected, () -> bridge.release(request, kex, selection, original, bytes -> received.incrementAndGet()));
            assertEquals(0, received.get());
        }
    }

    @Test public void copyAndEveryRevocationAreOrderedByTheirExactFence() throws Exception {
        for (int kind = 0; kind < 6; kind++) {
            setup(); View view = new View(); SshRoleContext role = role(view); SshHostKeyAuthority.Proof proof = proof(role);
            SshKexProof kex = kex(role, proof);
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, "test", "password", clock);
            SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
            SshCredentialBridge.Request request = bridge.openSelection(role, proof, selection);
            CountDownLatch copying = new CountDownLatch(1), mutationStarted = new CountDownLatch(1), mutated = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicInteger order = new AtomicInteger();
            final int invalidation = kind;
            Thread mutator = start(() -> {
                await(copying); mutationStarted.countDown();
                if (invalidation == 0) request.close();
                else if (invalidation == 1) request.operation.cancel();
                else if (invalidation == 2) role.close();
                else if (invalidation == 3) view.revoke();
                else if (invalidation == 4) vault.session.lock();
                else ((AppSecrets.SshSecretReadContext) field(request.operation.sshSecretReadState(), "context")).cancel();
                assertEquals(1, order.getAndIncrement()); mutated.countDown();
            }, failure);
            try {
                bridge.release(request, kex, selection, bytes -> {
                    copying.countDown(); await(mutationStarted);
                    // Test-only pause at the bounded copy seam proves revocation cannot pass it.
                    try { assertFalse(mutated.await(50, TimeUnit.MILLISECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                    assertEquals(0, order.getAndIncrement());
                });
            } catch (VaultException expectedLateInvalidation) { /* The completed copy cannot be retracted. */ }
            finally { copying.countDown(); mutator.join(5000); }
            assertFalse(mutator.isAlive()); assertNull(failure.get()); assertEquals(2, order.get());
        }
    }

    @Test public void concurrentPublicAdmissionCannotExceedSharedOperationCapacity() throws Exception {
        for (int count = 0; count < 63; count++) admit(0, context());
        CountDownLatch secretReady = new CountDownLatch(1), publicReady = new CountDownLatch(1), secretRegistered = new CountDownLatch(1);
        AppSecrets.EffectGuard secretGuard = new AppSecrets.EffectGuard() {
            @Override public void check() { }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                assertFalse(Thread.holdsLock(vault)); secretReady.countDown(); await(publicReady);
                publication.check(); secretRegistered.countDown();
            }
        };
        AppSecrets.SshSecretReadContext secretContext = vault.newSshSecretReadContext(900, secretGuard);
        AtomicReference<Throwable> secretFailure = new AtomicReference<>();
        Thread secret = start(() -> admit(0, secretContext), secretFailure);
        assertTrue(secretReady.await(5, TimeUnit.SECONDS));
        AppSecrets.EffectGuard publicGuard = new AppSecrets.EffectGuard() {
            @Override public void check() { }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                // Public admission has completed its early size check while holding core.
                assertTrue(Thread.holdsLock(vault)); publicReady.countDown(); await(secretRegistered);
                publication.check();
            }
        };
        AppSecrets.SshPublicReadContext publicContext = vault.newSshPublicReadContext(900, publicGuard);
        try { rejects("BUSY", () -> vault.beginSshFiles("config", "connection", publicContext)); }
        finally { publicReady.countDown(); secret.join(5000); }
        assertFalse(secret.isAlive()); assertNull(secretFailure.get());
        assertEquals(64, ((java.util.Map<?, ?>) field(vault, "operations")).size());
    }

    @Test public void publicSnapshotDoesNotAcquireRoleBeforeThePublicationFence() throws Exception {
        View view = new View(); SshRoleContext role = role(view); AtomicInteger checked = new AtomicInteger();
        vault.attachSshValidator(loan -> {
            detached();
            Object work = field(vault, "sshPublicReadWork");
            Object context = field(work, "context");
            AppSecrets.EffectGuard guard = (AppSecrets.EffectGuard) field(context, "guard");
            assertTrue(guard instanceof AppSecrets.SshRoleGuard);
            guard.publish(() -> {
                // This exact production snapshot guard previously held role here, inverting
                // the secret path's publication/session/operation -> role order.
                assertFalse(Thread.holdsLock(role)); assertFalse(Thread.holdsLock(view));
                synchronized (vault.publicationLock) { synchronized (vault.session) {
                    ((AppSecrets.SshRoleGuard) guard).withRole(() -> {
                        assertTrue(Thread.holdsLock(role)); assertTrue(Thread.holdsLock(view));
                        checked.incrementAndGet();
                    });
                } }
            });
        });
        SshVaultAdapter adapter = new SshVaultAdapter(vault, (connection, target) -> { });
        try (SshManagedKnownHostsProvider.SnapshotReceipt receipt = SshManagedKnownHostsProvider.snapshotFor(
                adapter, role, clock, "config", "known_hosts", "known_hosts", "known_hosts")) {
            assertNotNull(receipt); assertEquals(2, checked.get());
        }
    }

    @Test public void bundleOperationRegistrationTakesPublicationBeforeSession() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AppSecrets.EffectGuard guard = new AppSecrets.EffectGuard() {
            @Override public void check() { }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                clock.onRead = () -> {
                    if (Thread.holdsLock(vault.session)) {
                        assertTrue("Session-first registration would deadlock secret release",
                                Thread.holdsLock(vault.publicationLock));
                        inside.incrementAndGet();
                    }
                };
                try { publication.check(); } finally { clock.onRead = null; }
            }
        };
        VaultManifest.FileObject added = new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                "new-public", "new-public", "new-public", new byte[] {1, 2}, List.of());
        try (SshBundleProposal.Change change = SshBundleProposal.Change.add(added);
                SshBundleProposal proposal = new SshBundleProposal(List.of(change))) {
            VaultOperation operation = vault.beginSshBundle(proposal, vault.newBundleContext(900, guard), () -> { });
            assertNotNull(operation); assertTrue(inside.get() > 0);
        } finally { VaultCodec.wipe(added.content); }
    }
}
