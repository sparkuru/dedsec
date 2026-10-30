package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;

public final class SshSessionOwnerTest {
    private static final class Factory implements SshSessionOwner.Factory {
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicReference<Thread> owner = new AtomicReference<>();
        Runnable cancellation;
        int closeFailures;
        CountDownLatch createEntered, allowCreate;
        @Override public SshNativeSession create(BooleanSupplier cancelled) throws Exception {
            owner.set(Thread.currentThread());
            creates.incrementAndGet();
            if (createEntered != null) {
                createEntered.countDown();
                assertTrue(allowCreate.await(5, TimeUnit.SECONDS));
            }
            throw new IOException("injected pre-auth failure");
        }
        @Override public void cancellationAction(Runnable action) { cancellation = action; }
        @Override public void close() throws Exception {
            Thread original = owner.get();
            if (original == null) owner.compareAndSet(null, Thread.currentThread());
            assertSame(owner.get(), Thread.currentThread());
            closes.incrementAndGet();
            if (closeFailures-- > 0) throw new IOException("injected cleanup retry");
        }
    }

    @Test public void failedCreateRetainsOriginalOwnerUntilEveryCloseRetryConfirms() throws Exception {
        Factory factory = new Factory();
        factory.closeFailures = 2;
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        owner.begin();
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(1, factory.creates.get());
        assertEquals(3, factory.closes.get());
        assertNotSame(Thread.currentThread(), factory.owner.get());
        assertTrue(owner.connected.isCompletedExceptionally());
    }

    @Test public void cancelBeforeServiceStartNeverCreatesANativeSession() throws Exception {
        Factory factory = new Factory();
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        factory.cancellation.run();
        owner.begin();
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(0, factory.creates.get());
        assertEquals(1, factory.closes.get());
        assertTrue(owner.connected.isCompletedExceptionally());
    }

    @Test public void failedServiceStartCleansPendingFactoryWithoutCreatingOrRestarting() throws Exception {
        Factory factory = new Factory();
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        owner.rejectStart();
        owner.begin();
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(0, factory.creates.get());
        assertEquals(1, factory.closes.get());
        assertFalse(owner.matches("stale-notification"));
        assertTrue(owner.matches(owner.id));
        assertTrue(owner.stop(owner.generation + 1).isCompletedExceptionally());
    }

    @Test public void lateFactoryCancellationCannotPublishLifecycleAfterOwnerRelease() throws Exception {
        Factory factory = new Factory();
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger notifications = new AtomicInteger();
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24,
                new SshTerminalState(), Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String state) { notifications.incrementAndGet(); }
                    @Override public void released() { released.countDown(); }
                });
        owner.begin();
        assertTrue(released.await(5, TimeUnit.SECONDS));
        int before = notifications.get();
        factory.cancellation.run();
        owner.stop(owner.generation);
        assertEquals(before, notifications.get());
        assertEquals(1, factory.creates.get());
        assertEquals(1, factory.closes.get());
    }

    @Test public void screenLockBeforeServiceStartFencesInputAndWaitsForCleanup() throws Exception {
        Factory factory = new Factory();
        factory.closeFailures = 2;
        SshTerminalState events = new SshTerminalState();
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released, events);
        assertFalse(owner.stopForScreenLock("stale-owner"));
        assertFalse(owner.screenLocked());
        assertTrue(owner.stopForScreenLock(owner.id));
        assertFalse("Duplicate lock must not republish or restart cleanup", owner.stopForScreenLock(owner.id));
        assertTrue(owner.write(owner.generation, new byte[] {1}).isCompletedExceptionally());
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("Pending authentication must be cancelled by lock"); }
        catch (java.util.concurrent.ExecutionException failure) {
            assertEquals("SSH_SCREEN_LOCKED", ((VaultException)failure.getCause()).code);
        }
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(0, factory.creates.get());
        assertEquals(3, factory.closes.get());
        assertEquals("closed", events.snapshot().get("state"));
        assertEquals("SSH_SCREEN_LOCKED", events.snapshot().get("reason"));
        assertFalse(events.snapshot().containsKey("reconnect"));
    }

    @Test public void screenLockDuringCreateRetainsOriginalFactoryUntilItReturns() throws Exception {
        Factory factory = new Factory();
        factory.createEntered = new CountDownLatch(1);
        factory.allowCreate = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        SshTerminalState events = new SshTerminalState();
        SshSessionOwner owner = create(factory, released, events);
        owner.begin();
        try {
            assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
            owner.stopForScreenLock(owner.id);
            assertEquals(0, factory.closes.get());
            assertEquals(1, released.getCount());
            assertTrue(owner.write(owner.generation, new byte[] {1}).isCompletedExceptionally());
        } finally { factory.allowCreate.countDown(); }
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(1, factory.creates.get());
        assertEquals(1, factory.closes.get());
        assertEquals("closed", events.snapshot().get("state"));
        assertEquals("SSH_SCREEN_LOCKED", events.snapshot().get("reason"));
    }

    @Test public void oldScreenLockCallbackCannotCancelAReplacementOwner() throws Exception {
        SshTerminalState events = new SshTerminalState();
        CountDownLatch oldReleased = new CountDownLatch(1);
        SshSessionOwner old = create(new Factory(), oldReleased, events);
        old.stopForScreenLock(old.id);
        assertTrue(oldReleased.await(5, TimeUnit.SECONDS));
        Factory nextFactory = new Factory();
        CountDownLatch nextReleased = new CountDownLatch(1);
        SshSessionOwner replacement = create(nextFactory, nextReleased, events);
        assertTrue(replacement.generation > old.generation);
        assertFalse(old.stopForScreenLock(old.id));
        assertFalse(replacement.stopForScreenLock(old.id));
        assertFalse(replacement.screenLocked());
        assertEquals(null, events.snapshot());
        replacement.begin();
        assertTrue(nextReleased.await(5, TimeUnit.SECONDS));
        assertEquals(1, nextFactory.creates.get());
        assertEquals(1, nextFactory.closes.get());
    }

    private static SshSessionOwner create(Factory factory, CountDownLatch released) {
        return create(factory, released, new SshTerminalState());
    }

    private static SshSessionOwner create(Factory factory, CountDownLatch released, SshTerminalState events) {
        return new SshSessionOwner(factory, "ssh", 80, 24, events, Runnable::run,
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String state) { }
                    @Override public void released() { released.countDown(); }
                });
    }
}
