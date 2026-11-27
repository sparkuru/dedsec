package im.majo.ctos;

import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real service admission and host test JNI with substituted Android APIs, in an isolated JVM. */
public final class SshServiceLockedAdmissionRealJniTest {
    private static final class Factory implements SshSessionOwner.Factory {
        final SshSessionOwner.Cancellation cancellation = new SshSessionOwner.Cancellation();
        final AtomicInteger creates = new AtomicInteger(), closes = new AtomicInteger();
        final CountDownLatch createEntered = new CountDownLatch(1), finishCreate = new CountDownLatch(1);
        boolean writesTrust;
        RuntimeException metadataFailure;

        @Override public SshSessionOwner.Cancellation trustCancellation() { return cancellation; }
        @Override public boolean mayWriteTrust() { return writesTrust; }
        @Override public boolean tracksCredentialSave() {
            if (metadataFailure != null) throw metadataFailure;
            return false;
        }
        @Override public SshNativeSession create(BooleanSupplier stopped) throws Exception {
            creates.incrementAndGet();
            if (writesTrust) {
                createEntered.countDown();
                require(finishCreate.await(5, TimeUnit.SECONDS), "fixture trust factory timed out");
                require(stopped.getAsBoolean(), "trust factory did not observe STOP");
                throw new VaultException("VAULT_COMMIT_UNCERTAIN", "fixture trust receipt");
            }
            throw new AssertionError("locked admission must not enter a factory");
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void armFailure() throws Exception {
        Method arm = SshFreshVaultRealJniTest.class.getDeclaredMethod("armLegacyEffectInvalidationFailure");
        arm.setAccessible(true);
        arm.invoke(null);
    }

    private static void requireInjectedFailure(LinkageError failure) {
        require("test effect invalidation failure".equals(failure.getMessage()), "unexpected JNI failure");
    }

    private static void expectLocked(java.util.concurrent.CompletableFuture<String> result) throws Exception {
        try { result.get(5, TimeUnit.SECONDS); throw new AssertionError("screen-locked admission succeeded"); }
        catch (ExecutionException failure) {
            require(failure.getCause() instanceof VaultException, "expected a vault error");
            require("SSH_SCREEN_LOCKED".equals(((VaultException)failure.getCause()).code), "wrong admission result");
        }
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "one fixture mode is required");
        String mode = args[0];
        require(mode.equals("no-owner") || mode.equals("normal") || mode.equals("unknown")
                || mode.equals("unrelated") || mode.equals("unknown-unrelated")
                || mode.equals("dispatch-unrelated") || mode.equals("unknown-dispatch")
                || mode.equals("owner-propagates") || mode.equals("presuppressed")
                || mode.equals("same-throwable")
                || mode.equals("inflight-unknown"), "invalid fixture mode");
        System.loadLibrary("ctos_ssh_files");
        Factory incoming = new Factory();
        Factory old = new Factory();
        BlockingQueue<Runnable> posted = new LinkedBlockingQueue<>();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicReference<Error> callbackFailure = new AtomicReference<>();
        IllegalStateException dispatchFailure = new IllegalStateException("fixture dispatcher failure");
        SshSessionOwner owner = null;
        old.writesTrust = mode.equals("inflight-unknown");
        Field currentOwner = field(SshSessionService.class, "owner");
        if (!mode.equals("no-owner")) {
            SshSessionOwner.Dispatcher dispatcher;
            if (mode.equals("dispatch-unrelated") || mode.equals("unknown-dispatch"))
                dispatcher = action -> { throw dispatchFailure; };
            else if (mode.equals("same-throwable"))
                dispatcher = action -> { dispatches.incrementAndGet(); throw callbackFailure.get(); };
            else dispatcher = action -> { dispatches.incrementAndGet(); posted.add(action); };
            owner = new SshSessionOwner(old, "ssh", 80, 24, new SshTerminalState(), dispatcher,
                    new SshSessionOwner.Lifecycle() {
                        @Override public void state(String state) { }
                        @Override public void released() { releases.incrementAndGet(); }
                    }, true);
            currentOwner.set(null, owner);
        }
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            if (old.writesTrust) {
                owner.begin();
                require(old.createEntered.await(5, TimeUnit.SECONDS), "trust factory was not admitted");
            }
            IllegalStateException unrelated = new IllegalStateException("fixture metadata failure");
            if (mode.equals("unrelated") || mode.equals("unknown-unrelated")) old.metadataFailure = unrelated;
            if (mode.equals("unknown") || mode.equals("unknown-dispatch") || mode.equals("owner-propagates")
                    || mode.equals("inflight-unknown") || mode.equals("presuppressed") || mode.equals("same-throwable"))
                armFailure();
            if (mode.equals("unknown-unrelated")) {
                armFailure();
                try { old.cancellation.cancel(); throw new AssertionError("invalidation unexpectedly succeeded"); }
                catch (LinkageError failure) { requireInjectedFailure(failure); }
            }
            if (mode.equals("presuppressed") || mode.equals("same-throwable")) {
                LinkageError nativeFailure;
                try { old.cancellation.cancel(); throw new AssertionError("invalidation unexpectedly succeeded"); }
                catch (LinkageError failure) { requireInjectedFailure(failure); nativeFailure = failure; }
                Throwable existing = new IllegalStateException("fixture prior native diagnostic");
                nativeFailure.addSuppressed(existing);
                callbackFailure.set(nativeFailure);
                Method handling = SshSessionOwner.class.getDeclaredMethod("cancellationFailed", Throwable.class, boolean.class);
                handling.setAccessible(true);
                try {
                    handling.invoke(owner, nativeFailure, true);
                    if (mode.equals("same-throwable")) throw new AssertionError("same callback failure was suppressed");
                } catch (InvocationTargetException failure) {
                    if (!mode.equals("same-throwable") || failure.getCause() != nativeFailure) throw failure;
                }
                require(dispatches.get() == 2, "publication and scheduling did not each run once");
                require(nativeFailure.getSuppressed().length == 1 && nativeFailure.getSuppressed()[0] == existing,
                        "preexisting native diagnostic changed");
            } else if (mode.equals("owner-propagates")) {
                try {
                    owner.stopForScreenLock(owner.id);
                    throw new AssertionError("ordinary owner entry suppressed invalidation failure");
                } catch (LinkageError failure) { requireInjectedFailure(failure); }
            } else if (mode.equals("unknown-dispatch")) {
                try {
                    SshSessionService.attach(new Context(), incoming, "ssh", 80, 24);
                    throw new AssertionError("dispatcher failure alongside unknown JNI was suppressed");
                } catch (LinkageError failure) {
                    requireInjectedFailure(failure);
                    require(failure.getSuppressed().length > 0 && failure.getSuppressed()[0] == dispatchFailure,
                            "dispatcher failure was not retained on the original JNI exception");
                    require(old.cancellation.invalidationFailed(), "unknown native invalidation was lost");
                }
            } else if (mode.equals("dispatch-unrelated")) {
                try {
                    SshSessionService.attach(new Context(), incoming, "ssh", 80, 24);
                    throw new AssertionError("dispatcher exception was suppressed");
                } catch (IllegalStateException failure) {
                    require(failure == dispatchFailure, "dispatcher exception was replaced");
                    require(!old.cancellation.invalidationFailed(), "confirmed invalidation was marked unknown");
                }
            } else if (old.metadataFailure != null) {
                try {
                    SshSessionService.attach(new Context(), incoming, "ssh", 80, 24);
                    throw new AssertionError("unrelated exception was suppressed");
                } catch (IllegalStateException failure) {
                    require(failure == unrelated, "unrelated exception was replaced");
                }
            } else expectLocked(SshSessionService.attach(new Context(), incoming, "ssh", 80, 24));
            require(incoming.creates.get() == 0 && incoming.closes.get() == 0,
                    "the incoming factory was adopted or closed");
            require(currentOwner.get(null) == owner && releases.get() == 0, "the original owner was replaced");
            if (owner != null && !mode.equals("presuppressed") && !mode.equals("same-throwable")) {
                require(owner.screenLocked() && old.creates.get() == (old.writesTrust ? 1 : 0) && old.closes.get() == 0,
                        "original owner failed its screen-lock fence");
            }
            if (mode.equals("unknown-unrelated")) {
                require(old.cancellation.invalidationFailed()
                                && "SSH_CANCELLED".equals(old.cancellation.stopCode()),
                        "the earlier unrelated unknown state lost its first stop");
            }
            if (mode.equals("normal")) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (releases.get() == 0 && System.nanoTime() < deadline) {
                    Runnable action = posted.poll(100, TimeUnit.MILLISECONDS);
                    if (action != null) action.run();
                }
                require(old.closes.get() == 1 && releases.get() == 1, "confirmed cleanup did not finish");
            }
            if (mode.equals("unknown") || mode.equals("inflight-unknown")) {
                require(old.cancellation.invalidationFailed() && old.cancellation.invalidationUnconfirmed(),
                        "unknown native invalidation was not retained");
                require("SSH_SCREEN_LOCKED".equals(old.cancellation.stopCode()), "first stop was not retained");
                // A second arm proves that the initial JNI fault was consumed on this thread.
                armFailure();
                if (old.writesTrust) {
                    require(!owner.connected.isDone(), "in-flight trust outcome was guessed");
                    old.finishCreate.countDown();
                    try { owner.connected.get(5, TimeUnit.SECONDS); throw new AssertionError("uncertain write succeeded"); }
                    catch (ExecutionException failure) {
                        require(failure.getCause() instanceof VaultException
                                && "VAULT_COMMIT_UNCERTAIN".equals(((VaultException)failure.getCause()).code),
                                "in-flight uncertain receipt was lost");
                    }
                } else {
                    java.util.concurrent.atomic.AtomicBoolean begun =
                            (java.util.concurrent.atomic.AtomicBoolean)field(SshSessionOwner.class, "begun").get(owner);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!begun.get() && System.nanoTime() < deadline) {
                        Runnable action = posted.poll(100, TimeUnit.MILLISECONDS);
                        if (action != null) action.run();
                    }
                    require(begun.get(), "old owner did not schedule the pending cleanup check");
                }
                ScheduledExecutorService executor =
                        (ScheduledExecutorService)field(SshSessionOwner.class, "executor").get(owner);
                executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
                require(currentOwner.get(null) == owner && old.closes.get() == 0 && releases.get() == 0,
                        "unknown cleanup released the old owner");
                try {
                    SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                    throw new AssertionError("calling thread consumed the rearmed fault");
                } catch (LinkageError failure) { requireInjectedFailure(failure); }
            }
            String entry = mode.equals("owner-propagates") ? "owner"
                    : mode.equals("presuppressed") || mode.equals("same-throwable") ? "owner-helper" : "service";
            String ownerState = mode.equals("no-owner") ? "absent" : mode.equals("normal") ? "closed-fixture" : "retained";
            System.out.println("SSH_SERVICE_LOCK_JNI mode=" + mode + " entry=" + entry
                    + " result=checked owner=" + ownerState);
        } finally {
            currentOwner.set(null, null);
            old.finishCreate.countDown();
            if (owner != null) ((ScheduledExecutorService)field(SshSessionOwner.class, "executor").get(owner)).shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }
}
