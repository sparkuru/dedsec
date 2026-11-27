package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
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
        final AtomicReference<Boolean> observedCancellation = new AtomicReference<>();
        Runnable cancellation;
        SshSessionOwner.Cancellation sharedCancellation;
        int closeFailures;
        boolean mayWriteTrust;
        volatile boolean tracksSave;
        boolean tracksSaveAfterCreateLatch;
        Exception createFailure;
        Error createError;
        CountDownLatch createEntered, allowCreate, closeEntered, allowClose;
        @Override public SshNativeSession create(BooleanSupplier cancelled) throws Exception {
            owner.set(Thread.currentThread());
            creates.incrementAndGet();
            if (createEntered != null) {
                createEntered.countDown();
                assertTrue(allowCreate.await(5, TimeUnit.SECONDS));
            }
            observedCancellation.set(cancelled.getAsBoolean());
            if (tracksSaveAfterCreateLatch) tracksSave = true;
            if (createFailure != null) throw createFailure;
            if (createError != null) throw createError;
            throw new IOException("injected pre-auth failure");
        }
        @Override public void cancellationAction(Runnable action) { cancellation = action; }
        @Override public SshSessionOwner.Cancellation trustCancellation() { return sharedCancellation; }
        @Override public boolean mayWriteTrust() { return mayWriteTrust; }
        @Override public boolean tracksCredentialSave() { return tracksSave; }
        @Override public void close() throws Exception {
            Thread original = owner.get();
            if (original == null) owner.compareAndSet(null, Thread.currentThread());
            assertSame(owner.get(), Thread.currentThread());
            closes.incrementAndGet();
            if (closeEntered != null) {
                closeEntered.countDown();
                assertTrue(allowClose.await(5, TimeUnit.SECONDS));
            }
            if (closeFailures-- > 0) throw new IOException("injected cleanup retry");
        }
    }

    @Test public void stopCodePreservesTheFirstPublishedReason() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Factory factory = new Factory();
            factory.sharedCancellation = new SshSessionOwner.Cancellation();
            CountDownLatch released = new CountDownLatch(1);
            SshSessionOwner owner = create(factory, released);
            assertNull(factory.sharedCancellation.stopCode());
            if (mode == 0) owner.requestStop();
            else if (mode == 1) assertTrue(owner.stopForScreenLock(owner.id));
            else owner.rejectStart();
            assertEquals(mode == 0 ? "SSH_CANCELLED" : mode == 1 ? "SSH_SCREEN_LOCKED" : "SSH_SERVICE_START",
                    factory.sharedCancellation.stopCode());
            assertTrue(released.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void deferredCancellationBindingRunsOnlyAfterOwnerPublication() throws Exception {
        AtomicReference<SshSessionOwner> published = new AtomicReference<>();
        AtomicInteger bindings = new AtomicInteger(), starts = new AtomicInteger(), closes = new AtomicInteger();
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public SshNativeSession create(BooleanSupplier cancelled) {
                throw new AssertionError("stopped factory entered");
            }
            @Override public void cancellationAction(Runnable action) {
                assertNotNull("owner must already be published", published.get());
                bindings.incrementAndGet();
                action.run();
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String state) { }
                    @Override public void released() { released.countDown(); }
                }, true);
        assertEquals(0, bindings.get());
        published.set(owner);
        CompletableFuture<String> future = owner.startPublished(starts::incrementAndGet);
        assertSame(owner.connected, future);
        assertEquals(1, bindings.get());
        assertEquals(0, starts.get());
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(1, closes.get());
        assertTrue(future.isCompletedExceptionally());
    }

    @Test public void publishedOwnerReturnsItsFutureWhileAnEnteredTrustFactoryFinishes() throws Exception {
        Factory factory = new Factory();
        factory.mayWriteTrust = true;
        factory.createEntered = new CountDownLatch(1);
        factory.allowCreate = new CountDownLatch(1);
        factory.createFailure = new VaultException("VAULT_COMMIT_UNCERTAIN", "fixture receipt");
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String state) { }
                    @Override public void released() { released.countDown(); }
                }, true);
        owner.begin();
        try {
            assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<String> future = owner.startPublished(() -> {
                throw new IllegalStateException("fixture service start failure");
            });
            assertSame(owner.connected, future);
            assertFalse("in-flight receipt must not be guessed", future.isDone());
        } finally { factory.allowCreate.countDown(); }
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("uncertain trust must fail"); }
        catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof VaultException);
            assertEquals("VAULT_COMMIT_UNCERTAIN", ((VaultException)expected.getCause()).code);
        }
        assertTrue(released.await(5, TimeUnit.SECONDS));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwFixtureFailure(Throwable failure) throws T {
        throw (T)failure;
    }

    private static SshNativeSession completedFixtureSession() throws Exception {
        java.lang.reflect.Constructor<SshNativeSession> constructor = SshNativeSession.class.getDeclaredConstructor(
                SshNativeCallbackAdapter.class, SshNativeSession.ViewBinding.class, SshNativeSession.NativeBinding.class,
                SshNativeCallbackAdapter.NativeSessionClaim.class, long.class, int.class, byte[].class, boolean.class);
        constructor.setAccessible(true);
        SshNativeSession session = constructor.newInstance(null, null, null, null, 0L, 0, null, false);
        java.lang.reflect.Field destroyed = SshNativeSession.class.getDeclaredField("destroyed");
        destroyed.setAccessible(true);
        destroyed.setBoolean(session, true);
        return session;
    }

    @Test public void stopWaitsForFactoryHandoffFailureToSettle() throws Exception {
        for (boolean screenLock : new boolean[] {false, true}) {
            CountDownLatch inHandoff = new CountDownLatch(1), finishHandoff = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
                @Override public boolean mayWriteTrust() { return true; }
                @Override public SshNativeSession create(BooleanSupplier cancelled) throws Exception {
                    return completedFixtureSession();
                }
                @Override public SshReconnectTarget reconnectTarget() {
                    inHandoff.countDown();
                    try { assertTrue(finishHandoff.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                    // Inject an uncertain receipt at the post-create settlement boundary.
                    SshSessionOwnerTest.<RuntimeException>throwFixtureFailure(
                            new VaultException("VAULT_COMMIT_UNCERTAIN", "fixture receipt"));
                    throw new AssertionError("unreachable");
                }
                @Override public void close() { }
            };
            SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                    Runnable::run, new SshSessionOwner.Lifecycle() {
                        @Override public void state(String state) { }
                        @Override public void released() { released.countDown(); }
                    });
            owner.begin();
            try {
                assertTrue(inHandoff.await(5, TimeUnit.SECONDS));
                if (screenLock) assertTrue(owner.stopForScreenLock(owner.id));
                else owner.rejectStart();
                assertFalse("handoff receipt has not settled", owner.connected.isDone());
            } finally { finishHandoff.countDown(); }
            try { owner.connected.get(5, TimeUnit.SECONDS); fail("uncertain receipt must fail"); }
            catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof VaultException);
                assertEquals("VAULT_COMMIT_UNCERTAIN", ((VaultException)expected.getCause()).code);
            }
            assertTrue(released.await(5, TimeUnit.SECONDS));
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
        assertEquals(Boolean.FALSE, factory.observedCancellation.get());
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

    @Test public void factorySeesStopBeforeItExitsAndReleasesOnItsOwner() throws Exception {
        Factory factory = new Factory();
        factory.createEntered = new CountDownLatch(1);
        factory.allowCreate = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        owner.begin();
        try {
            assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
            owner.requestStop();
        } finally { factory.allowCreate.countDown(); }
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, factory.observedCancellation.get());
        assertEquals(1, factory.closes.get());
    }

    @Test public void duplicatePendingStillProcessesTerminalConnectionResult() throws Exception {
        for (boolean tracksSave : new boolean[] {false, true}) {
            Factory factory = new Factory();
            factory.tracksSave = tracksSave;
            AtomicInteger pendingEvents = new AtomicInteger();
            SshTerminalState events = new SshTerminalState();
            events.subscribe(event -> {
                if ("SSH_TERMINAL_CLEANUP_PENDING".equals(event.get("reason"))) pendingEvents.incrementAndGet();
            });
            SshSessionOwner owner = create(factory, new CountDownLatch(1), events);
            java.lang.reflect.Method dispatch = SshSessionOwner.class.getDeclaredMethod(
                    "dispatchState", String.class, Long.class, String.class);
            java.lang.reflect.Method terminal = SshSessionOwner.class.getDeclaredMethod(
                    "terminalState", String.class, Long.class, String.class);
            dispatch.setAccessible(true);
            terminal.setAccessible(true);
            dispatch.invoke(owner, "failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
            assertFalse(owner.connected.isDone());
            terminal.invoke(owner, "failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
            assertEquals(1, pendingEvents.get());
            assertEquals("SSH_TERMINAL_CLEANUP_PENDING", events.snapshot().get("reason"));
            if (tracksSave) assertFalse(owner.connected.isDone());
            else {
                try { owner.connected.get(5, TimeUnit.SECONDS); fail("pending terminal must fail its connection"); }
                catch (java.util.concurrent.ExecutionException failure) {
                    assertEquals("SSH_TERMINAL_START", ((VaultException)failure.getCause()).code);
                }
            }
        }
    }

    @Test public void terminalConnectionResultSurvivesDispatcherFailure() throws Exception {
        Factory factory = new Factory();
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24,
                new SshTerminalState(), action -> { throw new IllegalStateException("dispatcher failed"); },
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { }
                });
        java.lang.reflect.Method terminal = SshSessionOwner.class.getDeclaredMethod(
                "terminalState", String.class, Long.class, String.class);
        terminal.setAccessible(true);
        try {
            terminal.invoke(owner, "failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
            fail("dispatcher failure must remain observable");
        } catch (java.lang.reflect.InvocationTargetException failure) {
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("pending terminal must fail its connection"); }
        catch (java.util.concurrent.ExecutionException failure) {
            assertEquals("SSH_TERMINAL_START", ((VaultException)failure.getCause()).code);
        }
    }

    @Test public void queuedFailureCannotSupersedeCleanupPending() throws Exception {
        Factory factory = new Factory();
        java.util.ArrayDeque<Runnable> posted = new java.util.ArrayDeque<>();
        SshTerminalState events = new SshTerminalState();
        AtomicInteger pendingEvents = new AtomicInteger();
        events.subscribe(event -> {
            if ("SSH_TERMINAL_CLEANUP_PENDING".equals(event.get("reason"))) pendingEvents.incrementAndGet();
        });
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, events, posted::add,
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { }
                });
        java.lang.reflect.Method dispatch = SshSessionOwner.class.getDeclaredMethod(
                "dispatchState", String.class, Long.class, String.class);
        dispatch.setAccessible(true);
        dispatch.invoke(owner, "failed", null, "SSH_TERMINAL_START");
        dispatch.invoke(owner, "failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
        while (!posted.isEmpty()) posted.remove().run();
        dispatch.invoke(owner, "failed", null, "SSH_TERMINAL_START");
        dispatch.invoke(owner, "failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
        while (!posted.isEmpty()) posted.remove().run();
        assertEquals(1, pendingEvents.get());
        assertEquals("SSH_TERMINAL_CLEANUP_PENDING", events.snapshot().get("reason"));
    }

    @Test public void screenLockAndCleanupPendingKeepPendingInEitherFifoOrder() throws Exception {
        for (boolean pendingFirst : new boolean[] {true, false}) {
            Factory factory = new Factory();
            factory.createEntered = new CountDownLatch(1);
            factory.allowCreate = new CountDownLatch(1);
            java.util.ArrayDeque<Runnable> posted = new java.util.ArrayDeque<>();
            SshTerminalState events = new SshTerminalState();
            AtomicInteger pendingEvents = new AtomicInteger();
            events.subscribe(event -> {
                if ("SSH_TERMINAL_CLEANUP_PENDING".equals(event.get("reason"))) pendingEvents.incrementAndGet();
            });
            SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, events, posted::add,
                    new SshSessionOwner.Lifecycle() {
                        @Override public void state(String ignored) { }
                        @Override public void released() { }
                    });
            java.lang.reflect.Method dispatch = SshSessionOwner.class.getDeclaredMethod(
                    "dispatchState", String.class, Long.class, String.class);
            dispatch.setAccessible(true);
            owner.begin();
            try {
                assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
                if (pendingFirst) dispatch.invoke(owner, "failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
                assertTrue(owner.stopForScreenLock(owner.id));
                assertTrue(owner.screenLocked());
                if (!pendingFirst) {
                    posted.remove().run();
                    assertEquals("SSH_SCREEN_LOCKED", events.snapshot().get("reason"));
                    dispatch.invoke(owner, "failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
                }
                while (!posted.isEmpty()) posted.remove().run();
                assertEquals(1, pendingEvents.get());
                assertEquals("SSH_TERMINAL_CLEANUP_PENDING", events.snapshot().get("reason"));
            } finally { factory.allowCreate.countDown(); }
        }
    }

    @Test public void runningConnectionHandoffIsOrderedAgainstStop() throws Exception {
        for (boolean stopBeforeHandoff : new boolean[] {true, false}) {
            Factory factory = new Factory();
            factory.tracksSave = true;
            factory.sharedCancellation = new SshSessionOwner.Cancellation();
            CountDownLatch boundary = new CountDownLatch(1), resume = new CountDownLatch(1);
            AtomicInteger runningEvents = new AtomicInteger();
            SshTerminalState events = new SshTerminalState();
            events.subscribe(event -> { if ("running".equals(event.get("state"))) runningEvents.incrementAndGet(); });
            SshSessionOwner.Dispatcher dispatcher = action -> {
                if (!stopBeforeHandoff) {
                    boundary.countDown();
                    try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                }
                action.run();
            };
            SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, events, dispatcher,
                    new SshSessionOwner.Lifecycle() {
                        @Override public void state(String ignored) { }
                        @Override public void released() { }
                    });
            java.lang.reflect.Method terminalState = SshSessionOwner.class.getDeclaredMethod(
                    "terminalState", String.class, Long.class, String.class);
            terminalState.setAccessible(true);
            java.util.concurrent.BlockingQueue<Runnable> turns = new java.util.concurrent.LinkedBlockingQueue<>();
            SshTerminalSession.Port port = new SshTerminalSession.Port() {
                int closes;
                @Override public void begin(int columns, int rows) { }
                @Override public SshTerminalSession.Status status() {
                    return new SshTerminalSession.Status(SshNativeSession.ShellPhase.RUNNING, 0, null);
                }
                @Override public void step() { }
                @Override public int write(byte[] bytes) { return bytes.length; }
                @Override public byte[] read(int maximum) { return new byte[0]; }
                @Override public boolean resize(int columns, int rows) { return true; }
                @Override public boolean publicationLive() { return true; }
                @Override public void cancel() { }
                @Override public void close() throws IOException {
                    if (stopBeforeHandoff && closes++ == 0) throw new IOException("synthetic retryable close");
                }
            };
            SshTerminalSession terminal = new SshTerminalSession(port, (turn, delay) -> turns.add(turn),
                    new SshTerminalSession.Sink() {
                        @Override public void data(byte[] bytes, Runnable consumed) { consumed.run(); }
                        @Override public void state(String value, Long exit, String reason) {
                            try {
                                if (stopBeforeHandoff) {
                                    boundary.countDown();
                                    assertTrue(resume.await(5, TimeUnit.SECONDS));
                                }
                                terminalState.invoke(owner, value, exit, reason);
                            } catch (Exception failure) { throw new AssertionError(failure); }
                        }
                    }, () -> { }, factory.sharedCancellation);
            terminal.start(80, 24);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { turns.remove().run(); }
                catch (Throwable error) { failure.set(error); }
            });
            worker.start();
            try {
                assertTrue(boundary.await(5, TimeUnit.SECONDS));
                factory.sharedCancellation.cancel();
            } finally { resume.countDown(); }
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            if (stopBeforeHandoff) {
                assertFalse(owner.connected.isDone());
                terminal.requestCancel();
                turns.remove().run();
                assertFalse("retryable close must keep the connection pending", owner.connected.isDone());
                turns.remove().run();
                try { owner.connected.get(5, TimeUnit.SECONDS); fail("stopped running handoff must fail"); }
                catch (java.util.concurrent.ExecutionException expected) {
                    assertEquals("SSH_CANCELLED", ((VaultException)expected.getCause()).code);
                }
                assertEquals(0, runningEvents.get());
            } else {
                assertEquals("ssh", owner.connected.get(5, TimeUnit.SECONDS));
                assertEquals(1, runningEvents.get());
            }
        }
    }

    @Test public void admittedRunningDeliveryCannotRestoreLifecycleAfterPending() throws Exception {
        Factory factory = new Factory();
        factory.sharedCancellation = new SshSessionOwner.Cancellation();
        CountDownLatch firstPostEntered = new CountDownLatch(1), allowFirstPost = new CountDownLatch(1);
        java.util.concurrent.BlockingQueue<Runnable> posted = new java.util.concurrent.LinkedBlockingQueue<>();
        AtomicInteger posts = new AtomicInteger();
        java.util.List<String> lifecycle = new java.util.ArrayList<>();
        SshTerminalState events = new SshTerminalState();
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, events, action -> {
            if (posts.getAndIncrement() == 0) {
                firstPostEntered.countDown();
                try { assertTrue(allowFirstPost.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            }
            posted.add(action);
        }, new SshSessionOwner.Lifecycle() {
            @Override public void state(String value) { lifecycle.add(value); }
            @Override public void released() { }
        });
        java.lang.reflect.Method terminal = SshSessionOwner.class.getDeclaredMethod(
                "terminalState", String.class, Long.class, String.class);
        java.lang.reflect.Method dispatch = SshSessionOwner.class.getDeclaredMethod(
                "dispatchState", String.class, Long.class, String.class);
        terminal.setAccessible(true);
        dispatch.setAccessible(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread running = new Thread(() -> {
            try { terminal.invoke(owner, "running", null, null); }
            catch (Throwable error) { failure.set(error); }
        });
        running.start();
        try {
            assertTrue(firstPostEntered.await(5, TimeUnit.SECONDS));
            factory.sharedCancellation.cancel();
            dispatch.invoke(owner, "failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
        } finally { allowFirstPost.countDown(); }
        running.join(5000);
        assertFalse(running.isAlive());
        assertNull(failure.get());
        assertEquals("ssh", owner.connected.get(5, TimeUnit.SECONDS));
        Runnable action;
        while ((action = posted.poll()) != null) action.run();
        assertEquals(java.util.List.of("failed"), lifecycle);
        assertEquals("SSH_TERMINAL_CLEANUP_PENDING", events.snapshot().get("reason"));
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

    @Test public void screenLockDuringPossibleTrustWriteWaitsAndKeepsOrdinaryScreenReason() throws Exception {
        Factory factory = new Factory();
        factory.mayWriteTrust = true;
        factory.createEntered = new CountDownLatch(1);
        factory.allowCreate = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        SshTerminalState events = new SshTerminalState();
        SshSessionOwner owner = create(factory, released, events);
        owner.begin();
        try {
            assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
            assertTrue(owner.stopForScreenLock(owner.id));
            assertEquals("SSH_SCREEN_LOCKED", events.snapshot().get("reason"));
            assertFalse(owner.connected.isDone());
            assertTrue(owner.write(owner.generation, new byte[] {1}).isCompletedExceptionally());
        } finally { factory.allowCreate.countDown(); }
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("screen lock must end the connection"); }
        catch (java.util.concurrent.ExecutionException failure) {
            assertEquals("SSH_SCREEN_LOCKED", ((VaultException)failure.getCause()).code);
        }
        assertTrue(released.await(5, TimeUnit.SECONDS));
    }

    @Test public void screenLockBeforeTrustFactoryAdmissionPreventsItsCreate() throws Exception {
        Factory factory = new Factory();
        factory.mayWriteTrust = true;
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        assertTrue(owner.stopForScreenLock(owner.id));
        owner.begin();
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("screen lock must end the connection"); }
        catch (java.util.concurrent.ExecutionException failure) {
            assertEquals("SSH_SCREEN_LOCKED", ((VaultException)failure.getCause()).code);
        }
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(0, factory.creates.get());
    }

    @Test public void screenLockDuringCredentialSaveKeepsItsOriginalFailure() throws Exception {
        Factory factory = new Factory();
        factory.mayWriteTrust = true;
        factory.tracksSave = true;
        factory.createEntered = new CountDownLatch(1);
        factory.allowCreate = new CountDownLatch(1);
        factory.createFailure = new VaultException("VAULT_IO", "injected credential save failure");
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        owner.begin();
        try {
            assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
            assertTrue(owner.stopForScreenLock(owner.id));
            assertFalse(owner.connected.isDone());
        } finally { factory.allowCreate.countDown(); }
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("the save failure must be retained"); }
        catch (java.util.concurrent.ExecutionException failure) {
            assertEquals("VAULT_IO", ((VaultException)failure.getCause()).code);
        }
        assertTrue(released.await(5, TimeUnit.SECONDS));
    }

    @Test public void screenLockUsesCredentialSaveSnapshotFromTheLockEvent() throws Exception {
        Factory factory = new Factory();
        factory.mayWriteTrust = true;
        factory.tracksSaveAfterCreateLatch = true;
        factory.createEntered = new CountDownLatch(1);
        factory.allowCreate = new CountDownLatch(1);
        factory.createFailure = new VaultException("VAULT_IO", "injected save failure after screen lock");
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        owner.begin();
        try {
            assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
            assertFalse(factory.tracksSave);
            assertTrue(owner.stopForScreenLock(owner.id));
            assertFalse(owner.connected.isDone());
        } finally { factory.allowCreate.countDown(); }
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("screen lock must end the connection"); }
        catch (java.util.concurrent.ExecutionException failure) {
            assertEquals("SSH_SCREEN_LOCKED", ((VaultException)failure.getCause()).code);
        }
        assertTrue(factory.tracksSave);
        assertTrue(released.await(5, TimeUnit.SECONDS));
    }

    @Test public void firstDeferredScreenOrServiceResultWins() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Factory factory = new Factory();
            factory.mayWriteTrust = true;
            factory.tracksSave = mode == 2;
            factory.createEntered = new CountDownLatch(1);
            factory.allowCreate = new CountDownLatch(1);
            factory.createFailure = new VaultException("VAULT_IO", "injected factory failure");
            CountDownLatch released = new CountDownLatch(1);
            SshSessionOwner owner = create(factory, released);
            owner.begin();
            try {
                assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
                if (mode == 0) {
                    owner.rejectStart();
                    assertTrue(owner.stopForScreenLock(owner.id));
                } else {
                    assertTrue(owner.stopForScreenLock(owner.id));
                    owner.rejectStart();
                }
                assertFalse(owner.connected.isDone());
            } finally { factory.allowCreate.countDown(); }
            try { owner.connected.get(5, TimeUnit.SECONDS); fail("cancelled factory must not connect"); }
            catch (java.util.concurrent.ExecutionException failure) {
                assertEquals(mode == 1 ? "SSH_SCREEN_LOCKED" : "SSH_SERVICE_START",
                        ((VaultException)failure.getCause()).code);
            }
            assertTrue(released.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void screenLockDuringTrustWriteNeverOverridesUncertainFailure() throws Exception {
        Factory factory = new Factory();
        factory.mayWriteTrust = true;
        factory.createEntered = new CountDownLatch(1);
        factory.allowCreate = new CountDownLatch(1);
        factory.createFailure = new VaultException("VAULT_COMMIT_UNCERTAIN", "injected uncertain trust write");
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        owner.begin();
        try {
            assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
            assertTrue(owner.stopForScreenLock(owner.id));
            assertFalse(owner.connected.isDone());
        } finally { factory.allowCreate.countDown(); }
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("the uncertain write must be retained"); }
        catch (java.util.concurrent.ExecutionException failure) {
            assertEquals("VAULT_COMMIT_UNCERTAIN", ((VaultException)failure.getCause()).code);
        }
        assertTrue(released.await(5, TimeUnit.SECONDS));
    }

    @Test public void deferredFirstResultSurvivesFactoryLinkageErrorAndCleanup() throws Exception {
        for (boolean serviceFirst : new boolean[] {false, true}) {
            Factory factory = new Factory();
            factory.mayWriteTrust = true;
            factory.createEntered = new CountDownLatch(1);
            factory.allowCreate = new CountDownLatch(1);
            factory.createError = new LinkageError("injected native linkage error");
            CountDownLatch released = new CountDownLatch(1);
            SshSessionOwner owner = create(factory, released);
            owner.begin();
            try {
                assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
                if (serviceFirst) {
                    owner.rejectStart();
                    assertTrue(owner.stopForScreenLock(owner.id));
                } else {
                    assertTrue(owner.stopForScreenLock(owner.id));
                    owner.rejectStart();
                }
                assertFalse(owner.connected.isDone());
            } finally { factory.allowCreate.countDown(); }
            try { owner.connected.get(5, TimeUnit.SECONDS); fail("failed factory must not connect"); }
            catch (java.util.concurrent.ExecutionException failure) {
                assertEquals(serviceFirst ? "SSH_SERVICE_START" : "SSH_SCREEN_LOCKED",
                        ((VaultException)failure.getCause()).code);
            }
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertEquals(1, factory.closes.get());
        }
    }

    @Test public void rejectedServiceDuringPossibleTrustWriteWaitsForFactoryExit() throws Exception {
        Factory factory = new Factory();
        factory.mayWriteTrust = true;
        factory.createEntered = new CountDownLatch(1);
        factory.allowCreate = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        owner.begin();
        try {
            assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
            owner.rejectStart();
            assertFalse(owner.connected.isDone());
        } finally { factory.allowCreate.countDown(); }
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("rejected service must not connect"); }
        catch (java.util.concurrent.ExecutionException failure) {
            assertEquals("SSH_SERVICE_START", ((VaultException)failure.getCause()).code);
        }
        assertTrue(released.await(5, TimeUnit.SECONDS));
    }

    @Test public void proofFailurePublicationRespectsEarlierCancellationAndScreenLock() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Factory factory = new Factory();
            factory.createEntered = new CountDownLatch(1);
            factory.allowCreate = new CountDownLatch(1);
            factory.createFailure = new VaultException(SshHostKeyAuthority.PROOF_INVALID, "synthetic proof failure");
            CountDownLatch released = new CountDownLatch(1);
            SshSessionOwner owner = create(factory, released);
            owner.begin();
            try {
                assertTrue(factory.createEntered.await(5, TimeUnit.SECONDS));
                if (mode == 1) factory.cancellation.run();
                if (mode == 2) assertTrue(owner.stopForScreenLock(owner.id));
            } finally { factory.allowCreate.countDown(); }
            try { owner.connected.get(5, TimeUnit.SECONDS); fail("The connection must fail"); }
            catch (java.util.concurrent.ExecutionException failure) {
                String expected = mode == 0 ? SshHostKeyAuthority.PROOF_INVALID
                        : mode == 1 ? "SSH_CANCELLED" : "SSH_SCREEN_LOCKED";
                assertEquals(expected, ((VaultException)failure.getCause()).code);
            }
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertEquals(1, factory.creates.get());
            assertEquals(1, factory.closes.get());
        }
    }

    @Test public void cancellationAfterProofFailurePublicationDoesNotRewriteTheReceipt() throws Exception {
        Factory factory = new Factory();
        factory.closeEntered = new CountDownLatch(1);
        factory.allowClose = new CountDownLatch(1);
        factory.createFailure = new VaultException(SshHostKeyAuthority.PROOF_INVALID, "synthetic proof failure");
        CountDownLatch released = new CountDownLatch(1);
        SshSessionOwner owner = create(factory, released);
        owner.begin();
        try {
            assertTrue(factory.closeEntered.await(5, TimeUnit.SECONDS));
            factory.cancellation.run();
            try { owner.connected.get(5, TimeUnit.SECONDS); fail("The connection must fail"); }
            catch (java.util.concurrent.ExecutionException failure) {
                assertEquals(SshHostKeyAuthority.PROOF_INVALID, ((VaultException)failure.getCause()).code);
            }
        } finally { factory.allowClose.countDown(); }
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(1, factory.closes.get());
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
