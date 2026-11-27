package im.majo.ctos;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** The connector remains the owner of work admitted before Stop. */
public final class SshStartedMaterialAdmissionTest {
    private static final long NOW = 1_000, DEADLINE = 60_000;
    private static final SshRoleContext.Clock CLOCK = () -> NOW;
    private static final byte[] SECRET = "fixture-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "fixture-key-body".getBytes(StandardCharsets.UTF_8);

    private static SshPasswordProfile profile() throws VaultException {
        return new SshPasswordProfile("example.invalid", 22, "user");
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { method.invoke(target, args); }
        catch (InvocationTargetException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof Exception) throw (Exception)failure;
            throw (Error)failure;
        }
    }

    private static AppSecrets vault() throws Exception {
        AppSecrets vault = new AppSecrets(new AppSecrets.Store() {
            @Override public byte[] read() { throw new AssertionError("unexpected store read"); }
            @Override public byte[] quickMaterial() { throw new AssertionError("unexpected store read"); }
            @Override public void commit(byte[] envelope, byte[] quick, AppSecrets.EffectGuard guard) {
                throw new AssertionError("unexpected store write");
            }
        }, () -> NOW);
        vault.attachSshValidator(manifest -> { });
        vault.session.foreground(true);
        vault.session.unlock(new byte[32]);
        return vault;
    }

    private static SshConnectionMaterial.Key key() throws Exception {
        byte[] name = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        byte[] publicBlob = ByteBuffer.allocate(4 + name.length + 4 + 32)
                .putInt(name.length).put(name).putInt(32).put(new byte[32]).array();
        String identity = "ctos-key-" + SshConnectionMaterial.hex(VaultCodec.digest(publicBlob));
        byte[] publicLine = ("ssh-ed25519 " + Base64.getEncoder().encodeToString(publicBlob) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] magic = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);
        byte[] cipher = "aes256-ctr".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = ByteBuffer.allocate(magic.length + 4 + cipher.length)
                .put(magic).putInt(cipher.length).put(cipher).array();
        byte[] encrypted = ("-----BEGIN OPENSSH PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(raw)
                + "\n-----END OPENSSH PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);
        String path = ".ssh/id_fixture";
        String privateId = SshConnectionMaterial.id("private", path);
        String publicId = SshConnectionMaterial.id("public", path);
        VaultManifest.FileObject privateFile = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                privateId, path, identity, encrypted, List.of());
        VaultManifest.FileObject publicFile = new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                publicId, path + ".pub", identity, publicLine, List.of());
        VaultSshMetadata.KeyBinding binding = new VaultSshMetadata.KeyBinding(
                SshConnectionMaterial.bindingId(path, identity), privateId, publicId, List.of(), null);
        try (VaultManifest manifest = new VaultManifest(List.of(), List.of(privateFile, publicFile),
                List.of(binding), List.of())) {
            return new SshConnectionMaterial.Key(manifest, binding);
        }
    }

    private static final class Fixture {
        final AppSecrets vault;
        final SshPasswordProfile profile;
        final AtomicBoolean failCreate = new AtomicBoolean();
        final SshPasswordConnector connector;
        Fixture() throws Exception {
            vault = vault();
            profile = profile();
            connector = new SshPasswordConnector(vault, profile, "", "", "", DEADLINE, () -> {
                if (failCreate.get()) throw new VaultException("FIXTURE_STOP", "stop before network");
            }, null, CLOCK);
        }
        void enterCreate() throws Exception {
            failCreate.set(true);
            try { connector.create(() -> false); fail("fixture must stop before network"); }
            catch (VaultException expected) { assertEquals("FIXTURE_STOP", expected.code); }
            finally { failCreate.set(false); }
            assertTrue(connector.started());
        }
    }

    private static final class Candidate {
        final String name;
        final Object material;
        final byte[] secret, digest;
        Candidate(Fixture fixture, int kind) throws Exception {
            if (kind == 0) {
                name = "password";
                material = new SshTransientPassword(fixture.vault, fixture.profile, SECRET, DEADLINE, () -> { }, CLOCK);
                secret = (byte[])field(material, "bytes");
                digest = null;
            } else if (kind == 1 || kind == 2) {
                name = "transientKey";
                material = kind == 1 ? new SshTransientKey(fixture.vault, fixture.profile, key(), BODY, SECRET,
                        DEADLINE, () -> { }, CLOCK)
                        : SshTransientKey.deferred(fixture.vault, fixture.profile, key(), BODY, DEADLINE, () -> { }, CLOCK,
                                fixture.connector.trustCancellation());
                secret = (byte[])field(material, "phrase");
                digest = (byte[])field(material, "bodyDigest");
            } else {
                name = kind == 3 ? "automaticPasswordSave" : "automaticPhraseSave";
                material = SECRET.clone();
                secret = (byte[])material;
                digest = null;
            }
        }
        void install(SshPasswordConnector connector, int kind) throws Exception {
            if (kind == 0) invoke(connector, "installTransientPassword",
                    new Class<?>[] {SshTransientPassword.class}, material);
            else if (kind == 1 || kind == 2) invoke(connector, "installTransientKey",
                    new Class<?>[] {SshTransientKey.class}, material);
            else invoke(connector, "installAutomaticSave", new Class<?>[] {SshPasswordMailbox.Purpose.class, byte[].class},
                    kind == 3 ? SshPasswordMailbox.Purpose.PASSWORD : SshPasswordMailbox.Purpose.KEY_PASSPHRASE,
                    material);
        }
        void assertIntact() {
            if (secret != null) assertArrayEquals(SECRET, secret);
            if (digest != null) assertArrayEquals(VaultCodec.digest(BODY), digest);
        }
        void assertWiped() {
            if (secret != null) assertArrayEquals(new byte[secret.length], secret);
            if (digest != null) assertArrayEquals(new byte[digest.length], digest);
        }
    }

    private static void awaitBlockedOn(Thread thread, Object monitor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
            Thread.yield();
        assertEquals("worker did not reach the installation monitor", Thread.State.BLOCKED, thread.getState());
        Class<?> management = Class.forName("java.lang.management.ManagementFactory");
        Object bean = management.getMethod("getThreadMXBean").invoke(null);
        Object info = Class.forName("java.lang.management.ThreadMXBean")
                .getMethod("getThreadInfo", long.class).invoke(bean, thread.getId());
        Object lock = Class.forName("java.lang.management.ThreadInfo").getMethod("getLockInfo").invoke(info);
        assertNotNull(lock);
        int identity = (Integer)Class.forName("java.lang.management.LockInfo")
                .getMethod("getIdentityHashCode").invoke(lock);
        assertEquals(System.identityHashCode(monitor), identity);
    }

    @Test public void stopBeforeInstallationDeniesAndWipesEveryCandidateOnItsCreator() throws Exception {
        for (int kind = 0; kind < 5; kind++) {
            Fixture fixture = new Fixture();
            CountDownLatch ready = new CountDownLatch(1), proceed = new CountDownLatch(1);
            AtomicReference<Candidate> candidate = new AtomicReference<>();
            AtomicReference<Throwable> result = new AtomicReference<>();
            final int selected = kind;
            Thread owner = new Thread(() -> {
                try {
                    fixture.enterCreate();
                    candidate.set(new Candidate(fixture, selected));
                    ready.countDown();
                    assertTrue(proceed.await(5, TimeUnit.SECONDS));
                    candidate.get().install(fixture.connector, selected);
                    result.set(new AssertionError("stopped material was installed"));
                } catch (Throwable failure) { result.set(failure); }
                finally { ready.countDown(); }
            });
            owner.start();
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                assertNotNull(candidate.get());
                synchronized (fixture.connector) {
                    proceed.countDown();
                    awaitBlockedOn(owner, fixture.connector);
                    fixture.connector.trustCancellation().cancel();
                }
            } finally { proceed.countDown(); owner.join(5000); }
            assertFalse(owner.isAlive());
            assertTrue(String.valueOf(result.get()), result.get() instanceof VaultException);
            assertEquals("SSH_CANCELLED", ((VaultException)result.get()).code);
            assertNull(field(fixture.connector, candidate.get().name));
            candidate.get().assertWiped();
            assertSame(owner, field(fixture.connector, "owner"));
        }
    }

    @Test public void admittedMaterialRemainsWithItsOwnerUntilThatOwnerRetiresIt() throws Exception {
        for (int kind = 0; kind < 5; kind++) {
            Fixture fixture = new Fixture();
            CountDownLatch installed = new CountDownLatch(1), retire = new CountDownLatch(1);
            AtomicReference<Candidate> candidate = new AtomicReference<>();
            AtomicReference<Throwable> result = new AtomicReference<>();
            final int selected = kind;
            Thread owner = new Thread(() -> {
                try {
                    fixture.enterCreate();
                    candidate.set(new Candidate(fixture, selected));
                    candidate.get().install(fixture.connector, selected);
                    installed.countDown();
                    assertTrue(retire.await(5, TimeUnit.SECONDS));
                    fixture.connector.cancel();
                } catch (Throwable failure) { result.set(failure); }
                finally { installed.countDown(); }
            });
            owner.start();
            try {
                assertTrue(installed.await(5, TimeUnit.SECONDS));
                assertNull(result.get());
                assertNotNull(candidate.get());
                assertSame(candidate.get().material, field(fixture.connector, candidate.get().name));
                fixture.connector.trustCancellation().cancel();
                candidate.get().assertIntact();
                assertSame(owner, field(fixture.connector, "owner"));
            } finally { retire.countDown(); owner.join(5000); }
            assertFalse(owner.isAlive());
            assertNull(result.get());
            candidate.get().assertWiped();
        }
    }

    @Test public void pendingInvalidationDoesNotBlockRejectedMaterialInstallation() throws Exception {
        Fixture fixture = new Fixture();
        SshSessionOwner.Cancellation cancellation = fixture.connector.trustCancellation();
        CountDownLatch ready = new CountDownLatch(1), proceed = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicReference<Candidate> candidate = new AtomicReference<>();
        AtomicReference<Throwable> result = new AtomicReference<>(), cancelFailure = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try {
                fixture.enterCreate();
                candidate.set(new Candidate(fixture, 0));
                ready.countDown();
                assertTrue(proceed.await(5, TimeUnit.SECONDS));
                candidate.get().install(fixture.connector, 0);
                result.set(new AssertionError("stopped material was installed"));
            } catch (Throwable failure) { result.set(failure); }
            finally { ready.countDown(); returned.countDown(); }
        });
        Thread stop = new Thread(() -> {
            try { cancellation.cancel(); } catch (Throwable failure) { cancelFailure.set(failure); }
        });
        owner.start();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNotNull(candidate.get());
            synchronized (cancellation) {
                stop.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!cancellation.stopRequested() && System.nanoTime() < deadline) Thread.yield();
                assertTrue(cancellation.stopRequested());
                assertTrue(cancellation.invalidationPending());
                proceed.countDown();
                assertTrue("material admission blocked on native invalidation", returned.await(5, TimeUnit.SECONDS));
                candidate.get().assertWiped();
                assertNull(field(fixture.connector, "password"));
            }
        } finally { proceed.countDown(); owner.join(5000); stop.join(5000); }
        assertFalse(owner.isAlive());
        assertFalse(stop.isAlive());
        assertNull(cancelFailure.get());
        assertTrue(result.get() instanceof VaultException);
        assertEquals("SSH_CANCELLED", ((VaultException)result.get()).code);
        assertSame(owner, field(fixture.connector, "owner"));
    }

    @Test public void unconfirmedInvalidationRejectsMaterialWithoutRetryingNativeCancellation() throws Exception {
        Fixture fixture = new Fixture();
        SshSessionOwner.Cancellation cancellation = fixture.connector.trustCancellation();
        CountDownLatch ready = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicReference<Candidate> candidate = new AtomicReference<>();
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try {
                fixture.enterCreate();
                candidate.set(new Candidate(fixture, 1));
                ready.countDown();
                assertTrue(proceed.await(5, TimeUnit.SECONDS));
                candidate.get().install(fixture.connector, 1);
                result.set(new AssertionError("stopped material was installed"));
            } catch (Throwable failure) { result.set(failure); }
            finally { ready.countDown(); }
        });
        Field enabled = SshNativeCallbackAdapter.class.getDeclaredField("jumpEffectFenceEnabled");
        enabled.setAccessible(true);
        boolean previous = enabled.getBoolean(null);
        owner.start();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNotNull(candidate.get());
            enabled.setBoolean(null, true);
            try { cancellation.cancel(); fail("native invalidation unexpectedly succeeded"); }
            catch (LinkageError expected) { /* JNI is unavailable in this host-only fixture. */ }
            assertTrue(cancellation.invalidationUnconfirmed());
            assertTrue(cancellation.invalidationFailed());
            proceed.countDown();
            owner.join(5000);
            assertFalse(owner.isAlive());
            assertTrue(String.valueOf(result.get()), result.get() instanceof VaultException);
            assertEquals("SSH_CANCELLED", ((VaultException)result.get()).code);
            assertNull(field(fixture.connector, "transientKey"));
            candidate.get().assertWiped();
            assertSame(owner, field(fixture.connector, "owner"));
            assertTrue(cancellation.invalidationUnconfirmed());
        } finally {
            proceed.countDown();
            owner.join(5000);
            enabled.setBoolean(null, previous);
        }
    }

    @Test public void automaticSaveEntryRechecksStopAtTheExactInstallationMonitor() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch ready = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        byte[] source = SECRET.clone();
        Thread owner = new Thread(() -> {
            try {
                fixture.enterCreate();
                ready.countDown();
                assertTrue(proceed.await(5, TimeUnit.SECONDS));
                invoke(fixture.connector, "rememberAutomaticSave",
                        new Class<?>[] {SshPasswordMailbox.Purpose.class, byte[].class},
                        SshPasswordMailbox.Purpose.PASSWORD, source);
                result.set(new AssertionError("stopped material was installed"));
            } catch (Throwable failure) { result.set(failure); }
            finally { ready.countDown(); }
        });
        owner.start();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            synchronized (fixture.connector) {
                proceed.countDown();
                awaitBlockedOn(owner, fixture.connector);
                fixture.connector.trustCancellation().cancel();
            }
        } finally { proceed.countDown(); owner.join(5000); }
        assertFalse(owner.isAlive());
        assertTrue(String.valueOf(result.get()), result.get() instanceof VaultException);
        assertEquals("SSH_CANCELLED", ((VaultException)result.get()).code);
        assertNull(field(fixture.connector, "automaticPasswordSave"));
        assertArrayEquals(SECRET, source);
        assertSame(owner, field(fixture.connector, "owner"));
    }

    @Test public void automaticSaveKeepsOneOwnedCopyAndRejectsReplacement() throws Exception {
        for (boolean password : new boolean[] {true, false}) {
            Fixture fixture = new Fixture();
            byte[] source = SECRET.clone();
            AtomicReference<byte[]> copy = new AtomicReference<>();
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread owner = new Thread(() -> {
                try {
                    fixture.enterCreate();
                    SshPasswordMailbox.Purpose purpose = password ? SshPasswordMailbox.Purpose.PASSWORD
                            : SshPasswordMailbox.Purpose.KEY_PASSPHRASE;
                    String name = password ? "automaticPasswordSave" : "automaticPhraseSave";
                    invoke(fixture.connector, "rememberAutomaticSave",
                            new Class<?>[] {SshPasswordMailbox.Purpose.class, byte[].class}, purpose, source);
                    copy.set((byte[])field(fixture.connector, name));
                    assertNotSame(source, copy.get());
                    assertArrayEquals(SECRET, copy.get());
                    try {
                        invoke(fixture.connector, "rememberAutomaticSave",
                                new Class<?>[] {SshPasswordMailbox.Purpose.class, byte[].class}, purpose, source);
                        fail("a second save replaced the original");
                    } catch (VaultException expected) { assertEquals("VAULT_CHANGED", expected.code); }
                    assertSame(copy.get(), field(fixture.connector, name));
                    assertArrayEquals(SECRET, copy.get());
                    fixture.connector.cancel();
                } catch (Throwable failure) { result.set(failure); }
            });
            owner.start(); owner.join(5000);
            assertFalse(owner.isAlive());
            assertNull(result.get());
            assertArrayEquals(SECRET, source);
            assertNotNull(copy.get());
            assertArrayEquals(new byte[copy.get().length], copy.get());
        }
    }

    private static byte[] nonce() {
        byte[] nonce = new byte[32]; nonce[0] = 1; return nonce;
    }

    private static void exerciseDeferredPhrase(int mode) throws Exception {
        Fixture fixture = new Fixture();
        SshSessionOwner.Cancellation cancellation = fixture.connector.trustCancellation();
        CountDownLatch ready = new CountDownLatch(1), proceed = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<SshTransientKey> deferred = new AtomicReference<>();
        AtomicReference<byte[]> digest = new AtomicReference<>(), phrase = new AtomicReference<>();
        AtomicReference<Throwable> result = new AtomicReference<>(), stopFailure = new AtomicReference<>();
        AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            SshRoleContext role = null;
            try {
                fixture.enterCreate();
                SshConnectionMaterial.Key key = key();
                SshConnectionMaterial.KeyAccount account = new SshConnectionMaterial.KeyAccount(fixture.profile, key);
                role = SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT, 42, 0,
                        "key-connection", account.target, fixture.profile.host, fixture.profile.port,
                        "key-view", nonce(), nonce(), 31_000), () -> true, CLOCK);
                SshTransientKey actual = SshTransientKey.deferred(fixture.vault, fixture.profile, key, BODY,
                        DEADLINE, () -> {
                            if (deferred.get() != null) assertFalse(Thread.holdsLock(deferred.get()));
                        }, CLOCK, cancellation);
                deferred.set(actual);
                invoke(fixture.connector, "installTransientKey", new Class<?>[] {SshTransientKey.class}, actual);
                digest.set((byte[])field(actual, "bodyDigest"));
                byte[] blob = key.publicBlob();
                SshAuthenticationSelection selected;
                try {
                    selected = SshAuthenticationSelection.privateKey(role, fixture.profile.username,
                            key.path, key.identity, blob, actual.entryId, CLOCK).withAutomaticDiscovery(7, CLOCK);
                } finally { VaultCodec.wipe(blob); }
                actual.bind(role, selected);
                SshHostKeyAuthority authority = new SshHostKeyAuthority();
                SshHostKeyAuthority.Proof host = authority.requireVerified(role,
                        SshHostKeyAuthority.Evidence.fromNative(role, SshHostKeyAuthority.Classification.KNOWN,
                                "ssh-ed25519", "SHA256:" + "A".repeat(43), new byte[32], new byte[32]), null);
                SshKexProof kex = SshKexProof.fromNative(role, host, 1, nonce());
                if (mode == 4) {
                    byte[] localCandidate = SECRET.clone();
                    phrase.set(localCandidate);
                    ready.countDown();
                    assertTrue(proceed.await(5, TimeUnit.SECONDS));
                    invoke(actual, "installDeferredPhrase",
                            new Class<?>[] {SshAuthenticationSelection.class, byte[].class}, selected, localCandidate);
                } else {
                    actual.supplyPhrase(role, host, kex, () -> {
                        assertFalse("external guard called while key monitor held", Thread.holdsLock(actual));
                        ready.countDown();
                        try {
                            if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("phrase barrier timed out");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                    }, SECRET);
                    phrase.set((byte[])field(actual, "phrase"));
                }
                settled.countDown();
            } catch (Throwable failure) { result.set(failure); }
            finally {
                ready.countDown(); settled.countDown();
                try {
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                    if (deferred.get() != null) deferred.get().close();
                    if (role != null) role.close();
                } catch (Throwable failure) { cleanupFailure.set(failure); }
            }
        });
        Thread stop = new Thread(() -> {
            try { cancellation.cancel(); } catch (Throwable failure) { stopFailure.set(failure); }
        });
        Field enabled = SshNativeCallbackAdapter.class.getDeclaredField("jumpEffectFenceEnabled");
        enabled.setAccessible(true);
        boolean previous = enabled.getBoolean(null);
        owner.start();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNotNull(String.valueOf(result.get()), deferred.get());
            assertNull(result.get());
            assertSame(cancellation, field(deferred.get(), "phraseCancellation"));
            assertSame(deferred.get(), field(fixture.connector, "transientKey"));
            if (mode == 0 || mode == 4 || mode == 5) {
                synchronized (deferred.get()) {
                    proceed.countDown();
                    awaitBlockedOn(owner, deferred.get());
                    if (mode == 5) deferred.get().close();
                    else cancellation.cancel();
                }
                assertTrue(settled.await(5, TimeUnit.SECONDS));
            } else if (mode == 1 || mode == 6) {
                proceed.countDown();
                assertTrue(settled.await(5, TimeUnit.SECONDS));
                assertNull(result.get());
                assertArrayEquals(SECRET, phrase.get());
                if (mode == 6) {
                    deferred.get().close();
                    assertArrayEquals(new byte[phrase.get().length], phrase.get());
                } else {
                    cancellation.cancel();
                    assertArrayEquals(SECRET, phrase.get());
                }
            } else if (mode == 2) {
                synchronized (cancellation) {
                    stop.start();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!cancellation.stopRequested() && System.nanoTime() < deadline) Thread.yield();
                    assertTrue(cancellation.stopRequested());
                    assertTrue(cancellation.invalidationPending());
                    proceed.countDown();
                    assertTrue("phrase admission blocked on native invalidation", settled.await(5, TimeUnit.SECONDS));
                }
            } else {
                enabled.setBoolean(null, true);
                try { cancellation.cancel(); fail("native invalidation unexpectedly succeeded"); }
                catch (LinkageError expected) { /* JNI is unavailable in this host-only fixture. */ }
                assertTrue(cancellation.invalidationUnconfirmed());
                proceed.countDown();
                assertTrue(settled.await(5, TimeUnit.SECONDS));
            }
            if (mode != 1 && mode != 6) {
                assertTrue(String.valueOf(result.get()), result.get() instanceof VaultException);
                assertEquals(mode == 5 ? "SSH_CREDENTIAL_PROVIDER" : "SSH_CANCELLED",
                        ((VaultException)result.get()).code);
                assertNull(field(deferred.get(), "phrase"));
                if (mode == 4) assertArrayEquals(new byte[SECRET.length], phrase.get());
                else assertArrayEquals(new byte[digest.get().length], digest.get());
            }
            assertSame(owner, field(fixture.connector, "owner"));
            assertSame(deferred.get(), field(fixture.connector, "transientKey"));
        } finally {
            proceed.countDown(); enabled.setBoolean(null, previous); finish.countDown();
            owner.join(5000); if (stop.isAlive()) stop.join(5000);
        }
        assertFalse(owner.isAlive());
        assertNull(stopFailure.get());
        assertNull(cleanupFailure.get());
        if (mode == 1 || mode == 6) {
            assertNull(result.get());
            assertArrayEquals(new byte[phrase.get().length], phrase.get());
        }
    }

    @Test public void deferredPhraseRejectsStopBeforeItsKeyMonitorAdmission() throws Exception {
        exerciseDeferredPhrase(0);
    }

    @Test public void deferredPhraseAdmittedBeforeStopRemainsOwnedUntilCleanup() throws Exception {
        exerciseDeferredPhrase(1);
    }

    @Test public void deferredPhraseDoesNotWaitForPendingNativeInvalidation() throws Exception {
        exerciseDeferredPhrase(2);
    }

    @Test public void deferredPhraseRejectsAnUnconfirmedNativeInvalidation() throws Exception {
        exerciseDeferredPhrase(3);
    }

    @Test public void rejectedDeferredPhraseCandidateIsWipedByItsInstallingThread() throws Exception {
        exerciseDeferredPhrase(4);
    }

    @Test public void deferredPhraseIsRejectedWhenCloseWinsItsInstallationMonitor() throws Exception {
        exerciseDeferredPhrase(5);
    }

    @Test public void admittedDeferredPhraseIsWipedWhenConcurrentCloseWinsAfterHandoff() throws Exception {
        exerciseDeferredPhrase(6);
    }

    @Test public void deferredKeyRequiresAnExplicitCancellationOwner() throws Exception {
        Fixture fixture = new Fixture();
        try {
            SshTransientKey.deferred(fixture.vault, fixture.profile, key(), BODY, DEADLINE, () -> { }, CLOCK, null);
            fail("missing cancellation owner accepted");
        } catch (VaultException expected) { assertEquals("SSH_CREDENTIAL_PROVIDER", expected.code); }
    }
}
