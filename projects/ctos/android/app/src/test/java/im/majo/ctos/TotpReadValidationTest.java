package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/** Synthetic core/host-effect seams only; no Android clipboard, Binder or lifecycle evidence. */
public final class TotpReadValidationTest {
    private static final byte[] KEY = new byte[32];
    private static final AppSecrets.EffectGuard OPEN = () -> { };
    private Clock clock;
    private MemoryStore store;
    private AppSecrets vault;
    private VaultRequestPhase phase;
    private Runnable afterPublish;

    private static final class Clock implements VaultSession.Clock {
        volatile long now;
        @Override public long now() { return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        Runnable onRead;
        int reads;
        @Override public byte[] read() {
            reads++;
            if (onRead != null) onRead.run();
            return bytes == null ? null : bytes.clone();
        }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            guard.beforeCommit(); bytes = next.clone();
        }
    }
    private interface Action { void run() throws Exception; }

    @Before public void setup() throws Exception { setup(true, false); }

    private void setup(boolean withSsh, boolean empty) throws Exception {
        afterPublish = null; phase = new VaultRequestPhase();
        clock = new Clock(); store = new MemoryStore();
        try (VaultManifest source = manifest(withSsh, empty)) {
            byte[] plain = source.encode();
            try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], KEY, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        }
        vault = new AppSecrets(store, clock);
        vault.session.foreground(true); vault.session.unlock(KEY);
        if (withSsh) vault.attachSshValidator(loan -> { });
    }

    private static VaultManifest manifest(boolean mixed, boolean empty) throws Exception {
        VaultManifest.Entry totp = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp", "Example:test",
                "Example", "SHA1", 6, 30, "12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        VaultManifest.Entry ssh = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "ssh",
                "target:test@example.invalid:22", "SSH", "", 0, 0, new byte[] {1, 2, 3});
        List<VaultManifest.Entry> entries = empty ? List.of() : mixed ? List.of(totp, ssh) : List.of(totp);
        // Bodies make retained-loan wiping cover both credential and file ownership.
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "config", "config", "config", new byte[] {4, 5, 6}, List.of());
        try { return new VaultManifest(entries, mixed ? List.of(config) : List.of()); }
        finally {
            if (empty) { VaultCodec.wipe(totp.secret); VaultCodec.wipe(ssh.secret); }
            else if (!mixed) VaultCodec.wipe(ssh.secret);
            if (!mixed) VaultCodec.wipe(config.content);
        }
    }

    private AppSecrets.EffectGuard origin() {
        return new AppSecrets.EffectGuard() {
            @Override public void check() throws VaultException {
                if (phase.cancelled()) throw new VaultException("VAULT_CANCELLED", "fixture origin cancelled");
            }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                assertFalse(Thread.holdsLock(vault.session));
                assertFalse(Thread.holdsLock(vault.publicationLock));
                phase.publish(() -> { check(); publication.check(); });
                if (afterPublish != null) afterPublish.run();
            }
        };
    }

    private AppSecrets.TotpReadContext context() throws VaultException {
        return vault.newTotpReadContext(vault.session.epoch(), 900, origin());
    }
    private VaultOperation admit(boolean list, AppSecrets.TotpReadContext context) throws VaultException {
        return list ? vault.beginTotpList(context) : vault.beginTotp("totp", context);
    }
    private void read(boolean list, VaultOperation operation) throws VaultException {
        if (list) assertEquals(59L, vault.totpList(operation, 59).get("unixSeconds"));
        else assertEquals("287082", vault.totpCode(operation, "totp", 59));
    }
    private void assertDetached() {
        assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(phase));
        assertFalse(Thread.holdsLock(vault.session)); assertFalse(Thread.holdsLock(vault.publicationLock));
    }
    private static void assertWiped(VaultManifest loan) {
        assertNotNull(loan);
        for (VaultManifest.Entry entry : loan.entries) assertArrayEquals(new byte[entry.secret.length], entry.secret);
        for (VaultManifest.FileObject file : loan.files) assertArrayEquals(new byte[file.content.length], file.content);
    }
    private static Object field(Object owner, String name) {
        try {
            java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private byte[][] capturedSource() {
        Object work = field(vault, "totpReadWork");
        assertNotNull(work);
        return new byte[][] {(byte[]) field(work, "key"), (byte[]) field(work, "ciphertext")};
    }
    private static void assertWiped(byte[][] captured) {
        assertNotNull(captured);
        for (byte[] bytes : captured) { assertNotNull(bytes); assertArrayEquals(new byte[bytes.length], bytes); }
    }
    private static void assertCode(String code, Throwable failure) {
        assertTrue("Expected " + code + " but got " + failure, failure instanceof VaultException);
        assertEquals(code, ((VaultException) failure).code);
    }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }
    private static Thread start(Action action, AtomicReference<Throwable> failure) {
        Thread worker = new Thread(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        worker.start(); return worker;
    }
    private static void await(CountDownLatch latch) throws VaultException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new VaultException("VAULT_CANCELLED", "Interrupted"); }
    }

    @Test public void pausedMixedAdmissionAndEffectAllowAllOriginalInvalidationsForListAndCode() throws Exception {
        for (boolean list : new boolean[] {false, true}) {
            for (boolean effect : new boolean[] {false, true}) {
                for (int invalidation = 0; invalidation < 8; invalidation++) {
                    if (!effect && invalidation == 7) continue;
                    setup(); pausedInvalidation(list, effect, invalidation);
                }
            }
        }
    }

    private void pausedInvalidation(boolean list, boolean effect, int invalidation) throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicReference<VaultManifest> retained = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AppSecrets.SshContentValidator validator = loan -> {
            assertDetached();
            if (armed.get()) { retained.set(loan); entered.countDown(); await(release); }
        };
        vault.attachSshValidator(validator);
        AppSecrets.TotpReadContext context = context();
        VaultOperation operation = effect ? admit(list, context) : null;
        byte[] original = store.bytes.clone();
        armed.set(true);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = start(() -> { if (effect) read(list, operation); else admit(list, context); }, outcome);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        AtomicReference<Throwable> mutatorFailure = new AtomicReference<>();
        CountDownLatch changed = new CountDownLatch(1);
        Thread mutator = start(() -> {
            switch (invalidation) {
                case 0: phase.cancel(); break;
                case 1: context.cancel(); break;
                case 2: vault.session.lock(); break;
                case 3: vault.session.foreground(false); break;
                case 4: clock.now = 900; break;
                case 5: vault.attachSshValidator(validator); break;
                case 6: vault.attachSshValidator(null); vault.attachSshValidator(loan -> { }); break;
                case 7: vault.cancelOperation(operation); break;
                default: throw new AssertionError();
            }
            synchronized (vault) { changed.countDown(); }
        }, mutatorFailure);
        try { assertTrue("invalidation blocked on validator", changed.await(2, TimeUnit.SECONDS)); }
        finally { release.countDown(); worker.join(5000); mutator.join(5000); }
        assertFalse(worker.isAlive()); assertFalse(mutator.isAlive()); assertNull(mutatorFailure.get());
        String expected = invalidation == 0 || invalidation == 1 || invalidation == 7 ? "VAULT_CANCELLED"
                : invalidation == 3 ? "VAULT_FOREGROUND_REQUIRED" : invalidation == 4 ? "VAULT_EXPIRED" : "VAULT_CHANGED";
        assertCode(expected, outcome.get()); assertWiped(retained.get()); assertArrayEquals(original, store.bytes);
        if (effect) {
            assertTrue(operation.consumed);
            rejects(invalidation == 7 || invalidation == 1 ? "VAULT_CANCELLED" : "VAULT_OPERATION", () -> read(list, operation));
        }
        // A new owner can read after all loan/capture cleanup, without reviving the old capability.
        armed.set(false); phase = new VaultRequestPhase(); clock.now = 0;
        vault.session.foreground(true); vault.session.unlock(KEY); vault.attachSshValidator(loan -> { });
        read(list, admit(list, context()));
    }

    @Test public void pureListAndCodeIgnoreAllProviderChurnIncludingFinalWorkerAndDeliverySeams() throws Exception {
        for (boolean list : new boolean[] {false, true}) {
            setup(false, false);
            AppSecrets.TotpReadContext context = context();
            vault.attachSshValidator(loan -> fail("Pure read called SSH provider"));
            store.onRead = () -> vault.attachSshValidator(null);
            VaultOperation operation = admit(list, context);
            vault.attachSshValidator(loan -> fail("Pure read called SSH provider"));
            read(list, operation);
            afterPublish = () -> vault.attachSshValidator(loan -> fail("Pure receipt called provider"));
            AtomicBoolean effect = new AtomicBoolean();
            vault.publishTotpRead(operation, () -> 59, () -> effect.set(true));
            assertTrue(effect.get());
            store.onRead = null;
            read(list, list ? vault.beginTotpList() : vault.beginTotp("totp"));
        }
    }

    @Test public void mixedRequiresOriginalRegistrationAndNeverSubstitutesCurrentProvider() throws Exception {
        for (boolean list : new boolean[] {false, true}) {
            setup(); vault.attachSshValidator(null);
            rejects("VAULT_SSH_VALIDATION_REQUIRED", () -> admit(list, context()));
            AtomicInteger oldCalls = new AtomicInteger(), newCalls = new AtomicInteger();
            vault.attachSshValidator(loan -> oldCalls.incrementAndGet());
            AppSecrets.TotpReadContext old = context();
            vault.attachSshValidator(loan -> newCalls.incrementAndGet());
            rejects("VAULT_CHANGED", () -> admit(list, old));
            assertEquals(0, oldCalls.get()); assertEquals(0, newCalls.get());
            VaultOperation operation = admit(list, context());
            vault.attachSshValidator(loan -> newCalls.incrementAndGet());
            rejects("VAULT_CHANGED", () -> read(list, operation));
            assertTrue(operation.consumed);
            rejects("VAULT_OPERATION", () -> read(list, operation));
        }
    }

    @Test public void contextCreationDoesNotAcquireCoreSessionOrStoreAndQueueBudgetNeverRenews() throws Exception {
        long epoch = vault.session.epoch();
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = start(() -> { synchronized (vault) { synchronized (vault.session) { held.countDown(); await(release); } } }, failure);
        assertTrue(held.await(5, TimeUnit.SECONDS));
        AtomicReference<AppSecrets.TotpReadContext> created = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread creator = start(() -> { created.set(vault.newTotpReadContext(epoch, 80, origin())); done.countDown(); }, failure);
        try { assertTrue("Context creation acquired owner locks", done.await(2, TimeUnit.SECONDS)); }
        finally { release.countDown(); creator.join(5000); holder.join(5000); }
        assertNull(failure.get()); assertEquals(0, store.reads);
        clock.now = 80;
        rejects("VAULT_EXPIRED", () -> vault.beginTotpList(created.get()));
        clock.now = 0;
        AppSecrets.TotpReadContext context = vault.newTotpReadContext(epoch, 80, origin());
        clock.now = 79;
        VaultOperation operation = vault.beginTotpList(context);
        assertEquals(epoch, operation.epoch); assertEquals(80, operation.deadline);
        clock.now = 80;
        rejects("VAULT_EXPIRED", () -> vault.totpList(operation, 59));
        rejects("VAULT_OPERATION", () -> vault.totpList(operation, 59));
        clock.now = 0;
        vault.attachSshValidator(loan -> clock.now = 120000);
        rejects("VAULT_EXPIRED", vault::beginTotpList);
    }

    @Test public void foreignReusedAndWrongPurposeOrResourceContextsNeverGrantOrConsumeAnotherRead() throws Exception {
        AppSecrets foreign = new AppSecrets(store, clock);
        AppSecrets.TotpReadContext context = context();
        rejects("VAULT_OPERATION", () -> foreign.beginTotpList(context));
        VaultOperation code = vault.beginTotp("totp", context);
        rejects("VAULT_OPERATION", () -> vault.beginTotpList(context));
        rejects("VAULT_OPERATION", () -> vault.totpList(code, 59));
        rejects("VAULT_OPERATION", () -> vault.totpCode(code, "other", 59));
        rejects("VAULT_OPERATION", () -> foreign.totpCode(code, "totp", 59));
        assertFalse(code.consumed);
        read(false, code);
        clock.now = code.deadline;
        rejects("VAULT_OPERATION", () -> vault.totpCode(code, "totp", 59));
        vault.cancelOperation(code);
        rejects("VAULT_CANCELLED", () -> vault.totpCode(code, "totp", 59));
    }

    @Test public void independentLoanMutationCloseAndAllThrowableFailuresWipeAndReleaseWorkSlot() throws Exception {
        for (boolean list : new boolean[] {false, true}) for (boolean effect : new boolean[] {false, true}) {
            for (int problem = 0; problem < 5; problem++) {
                setup();
                AtomicBoolean armed = new AtomicBoolean();
                AtomicReference<VaultManifest> retained = new AtomicReference<>();
                AtomicReference<byte[][]> captured = new AtomicReference<>();
                final int kind = problem;
                vault.attachSshValidator(loan -> {
                    assertDetached();
                    if (!armed.get()) return;
                    retained.set(loan);
                    captured.set(capturedSource());
                    switch (kind) {
                        case 0: loan.entries.get(0).secret[0] ^= 1; break;
                        case 1: loan.close(); break;
                        case 2: throw new VaultException("FIXTURE_VALIDATOR", "fixture");
                        case 3: throw new IllegalStateException("fixture");
                        case 4: throw new AssertionError("fixture");
                        default: throw new AssertionError();
                    }
                });
                AppSecrets.TotpReadContext context = context();
                VaultOperation operation = effect ? admit(list, context) : null;
                armed.set(true);
                byte[] original = store.bytes.clone();
                Throwable failure = null;
                try { if (effect) read(list, operation); else admit(list, context); }
                catch (Throwable caught) { failure = caught; }
                assertNotNull("Provider problem accepted", failure);
                if (kind <= 1) assertTrue(failure instanceof VaultException);
                if (kind == 2) assertCode("FIXTURE_VALIDATOR", failure);
                if (kind == 3) assertTrue(failure instanceof IllegalStateException);
                if (kind == 4) assertTrue(failure instanceof AssertionError);
                assertWiped(retained.get()); assertWiped(captured.get()); assertArrayEquals(original, store.bytes);
                if (effect) { assertTrue(operation.consumed); rejects("VAULT_OPERATION", () -> read(list, operation)); }
                armed.set(false);
                read(list, admit(list, context()));
            }
        }
    }

    @Test public void concurrentAndSynchronousReentryStayBusyUntilLoanCleanup() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicReference<VaultManifest> retained = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        vault.attachSshValidator(loan -> {
            if (!armed.get()) return;
            retained.set(loan);
            try { rejects("BUSY", vault::beginTotpList); }
            catch (Exception failure) { throw new AssertionError(failure); }
            entered.countDown(); await(release);
        });
        VaultOperation ready = vault.beginTotpList(context());
        armed.set(true);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = start(vault::beginTotpList, outcome);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            AppSecrets.TotpReadContext busy = context();
            rejects("BUSY", () -> vault.beginTotpList(busy));
            rejects("VAULT_OPERATION", () -> vault.beginTotpList(busy));
            rejects("BUSY", () -> vault.totpList(ready, 59));
            assertFalse("A rejected BUSY effect consumed its operation", ready.consumed);
            assertNotEquals(0, retained.get().entries.get(0).secret[0]);
        } finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertNull(outcome.get()); assertWiped(retained.get());
        armed.set(false);
        read(true, ready);
        read(true, vault.beginTotpList());
    }

    @Test public void finalWorkerStoreCiphertextMismatchRejectsAdmissionAndAcceptedEffect() throws Exception {
        for (boolean list : new boolean[] {false, true}) for (boolean effect : new boolean[] {false, true}) {
            setup();
            AppSecrets.TotpReadContext context = context();
            VaultOperation operation = effect ? admit(list, context) : null;
            byte[] original = store.bytes.clone();
            AtomicInteger reads = new AtomicInteger();
            store.onRead = () -> {
                assertFalse(Thread.holdsLock(phase));
                assertFalse(Thread.holdsLock(vault.publicationLock));
                assertFalse(Thread.holdsLock(vault.session));
                if (reads.incrementAndGet() == 2) store.bytes[store.bytes.length - 1] ^= 1;
            };
            rejects("VAULT_CHANGED", () -> { if (effect) read(list, operation); else admit(list, context); });
            assertEquals(2, reads.get());
            if (effect) {
                assertTrue(operation.consumed);
                assertTrue(operation.cancelled);
                rejects("VAULT_CANCELLED", () -> read(list, operation));
                rejects("VAULT_CANCELLED", () -> vault.publishTotpRead(operation, () -> 59, () -> fail()));
            }
            store.onRead = null; store.bytes = original;
            read(list, admit(list, context()));
        }
    }

    @Test public void completionPublicationThrowAfterCallbackRevokesExactCompletedReceipt() throws Exception {
        for (boolean list : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) {
            setup();
            AtomicInteger publications = new AtomicInteger();
            AppSecrets.EffectGuard guard = new AppSecrets.EffectGuard() {
                @Override public void check() { }
                @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                    phase.publish(publication);
                    if (publications.incrementAndGet() == 2) {
                        if (error) throw new AssertionError("fixture publication after callback");
                        throw new VaultException("FIXTURE_PUBLICATION", "fixture publication after callback");
                    }
                }
            };
            VaultOperation operation = admit(list, vault.newTotpReadContext(vault.session.epoch(), 900, guard));
            if (error) {
                try { read(list, operation); fail("Publication Error accepted"); }
                catch (AssertionError failure) { assertEquals("fixture publication after callback", failure.getMessage()); }
            } else rejects("FIXTURE_PUBLICATION", () -> read(list, operation));
            assertTrue(operation.consumed); assertTrue(operation.cancelled);
            rejects("VAULT_CANCELLED", () -> read(list, operation));
            rejects("VAULT_CANCELLED", () -> vault.publishTotpRead(operation, () -> 59, () -> fail("Failed read disclosed")));
            read(list, admit(list, context()));
        }
    }

    @Test public void finalWorkerStoreAndProvisionalPublicationSeamsRecheckEveryAuthority() throws Exception {
        for (boolean list : new boolean[] {false, true}) for (boolean effect : new boolean[] {false, true}) {
            for (boolean publication : new boolean[] {false, true}) for (int invalidation = 0; invalidation < 6; invalidation++) {
                setup();
                AppSecrets.TotpReadContext context = context();
                VaultOperation operation = effect ? admit(list, context) : null;
                final int kind = invalidation;
                Runnable invalidate = () -> {
                    switch (kind) {
                        case 0: context.cancel(); break;
                        case 1: vault.session.lock(); break;
                        case 2: clock.now = 900; break;
                        case 3: vault.attachSshValidator(loan -> { }); break;
                        case 4: vault.contentRevision++; break;
                        case 5: vault.session.foreground(false); break;
                        default: throw new AssertionError();
                    }
                };
                if (publication) afterPublish = invalidate;
                else {
                    AtomicInteger reads = new AtomicInteger();
                    store.onRead = () -> { if (reads.incrementAndGet() == 2) invalidate.run(); };
                }
                String expected = kind == 0 ? "VAULT_CANCELLED" : kind == 2 ? "VAULT_EXPIRED"
                        : kind == 5 ? "VAULT_FOREGROUND_REQUIRED" : "VAULT_CHANGED";
                rejects(expected, () -> { if (effect) read(list, operation); else admit(list, context); });
                if (effect && publication) assertTrue(operation.cancelled);
                store.onRead = null; afterPublish = null;
                phase = new VaultRequestPhase(); clock.now = 0; vault.session.foreground(true); vault.session.unlock(KEY);
                vault.attachSshValidator(loan -> { });
                read(list, admit(list, context()));
            }
        }
    }

    @Test public void cancelledProvisionalIssuanceNeverLeavesAnOperationOrCancelsAnotherOwner() throws Exception {
        AppSecrets.TotpReadContext first = context();
        afterPublish = first::cancel;
        rejects("VAULT_CANCELLED", () -> vault.beginTotpList(first));
        afterPublish = null;
        VaultOperation second = vault.beginTotpList(context());
        first.cancel();
        read(true, second);
        vault.publishTotpRead(second, () -> 59, () -> { });
        // Repeated cleanup cannot consume the 64-slot operation registry through orphan issuance.
        for (int i = 0; i < 70; i++) {
            AppSecrets.TotpReadContext cancelled = context();
            afterPublish = cancelled::cancel;
            rejects("VAULT_CANCELLED", () -> vault.beginTotpList(cancelled));
        }
        afterPublish = null;
        assertNotNull(vault.beginTotpList());
    }

    @Test public void successfulLoanIsWipedAndActualEffectHasOnlyPublicationSessionLocksAndNoStore() throws Exception {
        for (boolean list : new boolean[] {false, true}) {
            setup();
            AtomicReference<VaultManifest> retained = new AtomicReference<>();
            vault.attachSshValidator(loan -> { assertDetached(); retained.set(loan); });
            VaultOperation operation = admit(list, context());
            assertWiped(retained.get()); read(list, operation); assertWiped(retained.get());
            int reads = store.reads;
            AtomicInteger effects = new AtomicInteger();
            store.onRead = () -> fail("Store entered during final effect");
            vault.publishTotpRead(operation, () -> 59, () -> {
                assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(phase));
                assertTrue(Thread.holdsLock(vault.publicationLock)); assertTrue(Thread.holdsLock(vault.session));
                effects.incrementAndGet();
            });
            assertDetached(); assertEquals(reads, store.reads); assertEquals(1, effects.get());
            rejects("VAULT_OPERATION", () -> vault.publishTotpRead(operation, () -> 59, () -> effects.incrementAndGet()));
            assertEquals(1, effects.get());
            rejects("VAULT_OPERATION", () -> vault.totpPublication(operation));
        }
    }

    @Test public void unfinishedFailedOrCopiedReadStateCannotBeUsedAsAReceipt() throws Exception {
        VaultOperation unfinished = vault.beginTotpList(context());
        rejects("VAULT_OPERATION", () -> vault.publishTotpRead(unfinished, () -> 59, () -> fail()));
        rejects("PARAMETERS", () -> vault.totpList(unfinished, -1));
        rejects("VAULT_OPERATION", () -> vault.publishTotpRead(unfinished, () -> 59, () -> fail()));
        VaultOperation success = vault.beginTotpList(context()); read(true, success);
        VaultEnvelope envelope = VaultEnvelope.parse(store.bytes);
        VaultOperation fake = new VaultOperation(VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_LIST,
                "totp-list", "", "", envelope, success.epoch, success.deadline);
        fake.attachTotpRead(success.totpReadState()); fake.consumed = true;
        rejects("VAULT_OPERATION", () -> vault.publishTotpRead(fake, () -> 59, () -> fail()));
        vault.publishTotpRead(success, () -> 59, () -> { });
    }

    @Test public void invalidationAfterReservationPreventsActualListAndCopyAndNeverRestoresReservation() throws Exception {
        for (boolean list : new boolean[] {false, true}) for (int invalidation = 0; invalidation < 7; invalidation++) {
            setup();
            AppSecrets.TotpReadContext context = context();
            VaultOperation operation = admit(list, context); read(list, operation);
            final int kind = invalidation;
            afterPublish = () -> {
                assertDetached();
                switch (kind) {
                    case 0: phase.cancel(); break;
                    case 1: context.cancel(); break;
                    case 2: vault.session.lock(); break;
                    case 3: vault.session.foreground(false); break;
                    case 4: clock.now = 900; break;
                    case 5: vault.attachSshValidator(loan -> { }); break;
                    case 6: vault.contentRevision++; break;
                    default: throw new AssertionError();
                }
            };
            String expected = kind <= 1 ? "VAULT_CANCELLED" : kind == 3 ? "VAULT_FOREGROUND_REQUIRED"
                    : kind == 4 ? "VAULT_EXPIRED" : "VAULT_CHANGED";
            rejects(expected, () -> vault.publishTotpRead(operation, () -> 59, () -> fail("Stale code disclosed")));
            afterPublish = null;
            rejects(expected, () -> vault.publishTotpRead(operation, () -> 59, () -> fail("Retried stale code")));
        }
    }

    @Test public void deliveryThrowableConsumesItsOnlyAttemptAndPhaseCancellationDoesNotWaitForActualEffect() throws Exception {
        VaultOperation failed = vault.beginTotpList(context()); read(true, failed);
        AtomicInteger effects = new AtomicInteger();
        try {
            vault.publishTotpRead(failed, () -> 59, () -> { effects.incrementAndGet(); throw new AssertionError("fixture transport"); });
            fail();
        } catch (AssertionError expected) { assertEquals("fixture transport", expected.getMessage()); }
        rejects("VAULT_OPERATION", () -> vault.publishTotpRead(failed, () -> 59, () -> effects.incrementAndGet()));
        assertEquals(1, effects.get());
        VaultOperation paused = vault.beginTotpList(context()); read(true, paused);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread effect = start(() -> vault.publishTotpRead(paused, () -> 59, () -> { entered.countDown(); await(release); }), failure);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch cancelled = new CountDownLatch(1);
        Thread canceller = start(() -> { phase.cancel(); cancelled.countDown(); }, failure);
        try { assertTrue("Actual disclosure holds original phase", cancelled.await(2, TimeUnit.SECONDS)); }
        finally { release.countDown(); effect.join(5000); canceller.join(5000); }
        assertNull(failure.get()); assertFalse(effect.isAlive()); assertFalse(canceller.isAlive());
    }

    @Test public void finalWallWindowIsSampledAtActualEffectForBothListAndCopy() throws Exception {
        for (boolean list : new boolean[] {false, true}) {
            for (long generated : new long[] {29, 30, 59, 60, 20000000000L}) {
                for (long delivered : new long[] {generated, generated + 30, Math.max(0, generated - 30)}) {
                    setup(false, false);
                    VaultOperation operation = admit(list, context());
                    if (list) vault.totpList(operation, generated); else vault.totpCode(operation, "totp", generated);
                    AtomicInteger attempts = new AtomicInteger();
                    if (generated / 30 == delivered / 30) {
                        vault.publishTotpRead(operation, () -> delivered, () -> attempts.incrementAndGet());
                        assertEquals(1, attempts.get());
                    } else {
                        rejects("TOTP_TIME_CHANGED", () -> vault.publishTotpRead(operation, () -> delivered, () -> attempts.incrementAndGet()));
                        assertEquals(0, attempts.get());
                        rejects("VAULT_OPERATION", () -> vault.publishTotpRead(operation, () -> generated, () -> fail()));
                    }
                }
            }
            setup(false, false);
            VaultOperation boundary = admit(list, context());
            if (list) vault.totpList(boundary, 29); else vault.totpCode(boundary, "totp", 29);
            long[] wall = {29};
            afterPublish = () -> wall[0] = 30;
            rejects("TOTP_TIME_CHANGED", () -> vault.publishTotpRead(boundary, () -> wall[0], () -> fail("Crossed window")));
        }
    }

    @Test public void finalWallClockSeamCannotBypassCancellationDeadlineEpochForegroundOrRevision() throws Exception {
        for (boolean list : new boolean[] {false, true}) for (int invalidation = 0; invalidation < 6; invalidation++) {
            setup();
            AppSecrets.TotpReadContext context = context();
            VaultOperation operation = admit(list, context); read(list, operation);
            final int kind = invalidation;
            String expected = kind <= 1 ? "VAULT_CANCELLED" : kind == 2 ? "VAULT_EXPIRED"
                    : kind == 4 ? "VAULT_FOREGROUND_REQUIRED" : "VAULT_CHANGED";
            AtomicInteger attempts = new AtomicInteger();
            rejects(expected, () -> vault.publishTotpRead(operation, () -> {
                assertFalse(Thread.holdsLock(phase)); assertFalse(Thread.holdsLock(vault));
                switch (kind) {
                    case 0: phase.cancel(); break;
                    case 1: context.cancel(); break;
                    case 2: clock.now = 900; break;
                    case 3: vault.session.lock(); break;
                    case 4: vault.session.foreground(false); break;
                    case 5: vault.contentRevision++; break;
                    default: throw new AssertionError();
                }
                return 59;
            }, () -> attempts.incrementAndGet()));
            assertEquals(0, attempts.get());
        }
    }

    @Test public void emptyListStillRejectsNegativeTimeAndInGenerationDiskReplacementIsRejected() throws Exception {
        setup(false, true);
        VaultOperation empty = vault.beginTotpList(context());
        rejects("PARAMETERS", () -> vault.totpList(empty, -1));
        rejects("VAULT_OPERATION", () -> vault.totpList(empty, 59));
        setup();
        VaultOperation changed = vault.beginTotpList(context());
        VaultEnvelope current = VaultEnvelope.parse(store.bytes);
        try (VaultManifest replacement = manifest(true, true)) {
            byte[] plain = replacement.encode();
            try { store.bytes = VaultEnvelope.encrypt(current.id, current.generation, current.salt, KEY, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        }
        rejects("VAULT_CHANGED", () -> vault.totpList(changed, 59));
        rejects("VAULT_OPERATION", () -> vault.totpList(changed, 59));
    }

    @Test public void backgroundExpiryNeverRunsBlockingRevocationUnderOriginalPhase() throws Exception {
        VaultOperation operation = vault.beginTotpList(vault.newTotpReadContext(vault.session.epoch(), 120000, origin()));
        read(true, operation);
        AtomicBoolean revoked = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        vault.session.attachRevocation(operation.epoch, new VaultSession.Revocation() {
            @Override public boolean active() { return true; }
            @Override public void revoke() {
                assertFalse("Lazy cleanup entered original phase", Thread.holdsLock(phase));
                revoked.set(true); entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("revoke timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }
        });
        vault.session.timeout(1); vault.session.foreground(false); clock.now = 1000;
        rejects("VAULT_FOREGROUND_REQUIRED", () -> vault.publishTotpRead(operation, () -> 59, () -> fail()));
        assertFalse("Receipt invoked lazy revocation", revoked.get());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread revoker = start(() -> vault.session.epoch(), failure);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch cancelled = new CountDownLatch(1);
        Thread canceller = start(() -> { phase.cancel(); cancelled.countDown(); }, failure);
        try { assertTrue("Blocking revoke acquired phase", cancelled.await(2, TimeUnit.SECONDS)); }
        finally { release.countDown(); revoker.join(5000); canceller.join(5000); }
        assertNull(failure.get()); assertTrue(revoked.get());
        assertFalse(revoker.isAlive()); assertFalse(canceller.isAlive());
    }
}
