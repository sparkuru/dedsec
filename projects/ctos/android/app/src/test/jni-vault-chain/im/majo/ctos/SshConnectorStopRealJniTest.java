package im.majo.ctos;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Host-only: real connector/owner and JNI effect guard, no Android lifecycle or network. */
public final class SshConnectorStopRealJniTest {
    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Object fixture(String name) throws Exception {
        Method method = SshFreshVaultRealJniTest.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static AppSecrets vault() throws Exception {
        AppSecrets result = new AppSecrets(new AppSecrets.Store() {
            @Override public byte[] read() { throw new AssertionError("unexpected store read"); }
            @Override public byte[] quickMaterial() { throw new AssertionError("unexpected store read"); }
            @Override public void commit(byte[] envelope, byte[] quick, AppSecrets.EffectGuard guard) {
                throw new AssertionError("unexpected store write");
            }
        }, () -> 1_000);
        result.attachSshValidator(manifest -> { });
        result.session.foreground(true);
        result.session.unlock(new byte[32]);
        return result;
    }

    private static void run(String mode) throws Exception {
        boolean admittedFirst = "admitted-unknown".equals(mode);
        boolean nativeFailure = !"drain".equals(mode);
        AppSecrets vault = vault();
        CountDownLatch guardEntered = new CountDownLatch(1), guardRelease = new CountDownLatch(1);
        AtomicBoolean guardArmed = new AtomicBoolean();
        AppSecrets.EffectGuard guard = () -> {
            if (!guardArmed.get()) return;
            guardEntered.countDown();
            try {
                if (!guardRelease.await(5, TimeUnit.SECONDS)) throw new AssertionError("guard barrier timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            throw new VaultException("SSH_CANCELLED", "fixture stop");
        };
        SshPasswordConnector connector = new SshPasswordConnector(vault,
                new SshPasswordProfile("example.invalid", 22, "user"),
                "test-secret".getBytes(StandardCharsets.UTF_8), false, true, 60_000,
                guard, null, () -> 1_000);
        byte[] held = (byte[])field(connector, "pendingSave");
        Object admittedPassword = field(connector, "password");
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1), released = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        SshSessionOwner.Cancellation cancellation = connector.trustCancellation();
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public SshNativeSession create(BooleanSupplier stopped) throws Exception {
                entered.countDown();
                if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("factory barrier timed out");
                try { return connector.create(stopped); }
                finally { returned.countDown(); }
            }
            @Override public boolean mayWriteTrust() { return true; }
            @Override public SshSessionOwner.Cancellation trustCancellation() { return cancellation; }
            @Override public void cancellationAction(Runnable action) { connector.cancellationAction(action); }
            @Override public void close() throws Exception { closes.incrementAndGet(); connector.close(); }
        };
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String state) { }
                    @Override public void released() { released.countDown(); }
                });
        ScheduledExecutorService executor = (ScheduledExecutorService)field(owner, "executor");
        AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
        Thread cancel = new Thread(() -> {
            try {
                if (nativeFailure) fixture("armLegacyEffectInvalidationFailure");
                connector.cancel();
            } catch (Throwable failure) { cancelFailure.set(failure); }
        });
        boolean effectHeld = false;
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            owner.begin();
            require(entered.await(5, TimeUnit.SECONDS), "owner did not admit the factory");
            if (admittedFirst) {
                guardArmed.set(true);
                proceed.countDown();
                require(guardEntered.await(5, TimeUnit.SECONDS) && connector.started(),
                        "connector did not win factory admission");
            }
            if (!nativeFailure) { fixture("acquireSharedTestEffect"); effectHeld = true; }
            cancel.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            if (nativeFailure) {
                cancel.join(5000);
                require(!cancel.isAlive() && cancelFailure.get() instanceof LinkageError
                                && "test effect invalidation failure".equals(cancelFailure.get().getMessage())
                                && cancellation.invalidationUnconfirmed(),
                        "injected JNI begin failure was not retained");
            } else {
                while (Boolean.TRUE.equals(fixture("effectAvailable")) && System.nanoTime() < deadline)
                    Thread.yield();
                require(!Boolean.TRUE.equals(fixture("effectAvailable")) && cancel.isAlive(),
                        "native invalidation did not drain the held effect");
            }
            require(cancellation.stopRequested(), "STOP was not published");
            if (admittedFirst) {
                require(field(connector, "pendingSave") == null && Arrays.equals(held, new byte[held.length])
                                && field(connector, "password") == admittedPassword,
                        "unknown cancellation did not wipe only the queued material");
                guardRelease.countDown();
            }
            proceed.countDown();
            require(returned.await(5, TimeUnit.SECONDS), "connector waited for native drain");
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            if (!admittedFirst) {
                require(!connector.started() && field(connector, "pendingSave") == null
                                && Arrays.equals(held, new byte[held.length]),
                        "STOP before connector admission did not retire its material");
            } else require(connector.started() && field(connector, "pendingSave") == null
                            && Arrays.equals(held, new byte[held.length])
                            && field(connector, "password") == admittedPassword,
                    "unconfirmed entered connector lost its original password owner");
            require(field(owner, "factory") == factory && closes.get() == 0 && released.getCount() == 1,
                    "owner was released before native cancellation confirmation");
            if (effectHeld) {
                fixture("releaseSharedTestEffect"); effectHeld = false;
                cancel.join(5000);
                require(!cancel.isAlive() && cancelFailure.get() == null, "native cancellation did not finish");
                require(released.await(5, TimeUnit.SECONDS) && closes.get() == 1,
                        "confirmed owner cleanup did not finish");
            }
            try { owner.connected.get(5, TimeUnit.SECONDS); throw new AssertionError("stopped factory connected"); }
            catch (ExecutionException expected) {
                require(expected.getCause() instanceof VaultException
                                && "SSH_CANCELLED".equals(((VaultException)expected.getCause()).code),
                        "wrong stopped factory result");
            }
            System.out.println("SSH_CONNECTOR_STOP_JNI mode=" + mode
                    + " queued-material=erased"
                    + " admitted-password=" + (admittedFirst ? "retained" : "not-admitted")
                    + " owner=" + (nativeFailure ? "retained" : "confirmed"));
        } finally {
            proceed.countDown();
            guardRelease.countDown();
            if (effectHeld) fixture("releaseSharedTestEffect");
            cancel.join(5000);
            executor.shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void lateAutomaticMaterial(String mode) throws Exception {
        boolean unknown = "material-unknown".equals(mode);
        AppSecrets vault = vault();
        SshPasswordConnector connector = new SshPasswordConnector(vault,
                new SshPasswordProfile("example.invalid", 22, "user"),
                "fixture-password".getBytes(StandardCharsets.UTF_8), false, false, 60_000,
                () -> { }, null, () -> 1_000);
        SshSessionOwner.Cancellation cancellation = connector.trustCancellation();
        byte[] candidate = "fixture-late-copy".getBytes(StandardCharsets.UTF_8);
        Method install = SshPasswordConnector.class.getDeclaredMethod("installAutomaticSave",
                SshPasswordMailbox.Purpose.class, byte[].class);
        install.setAccessible(true);
        AtomicReference<Throwable> stopFailure = new AtomicReference<>(), installFailure = new AtomicReference<>();
        Thread stop = new Thread(() -> {
            try {
                if (unknown) fixture("armLegacyEffectInvalidationFailure");
                cancellation.cancel();
            } catch (Throwable failure) { stopFailure.set(failure); }
        }, "fixture-stop");
        Thread installer = new Thread(() -> {
            try {
                install.invoke(connector, SshPasswordMailbox.Purpose.PASSWORD, candidate);
                installFailure.set(new AssertionError("STOP admitted late material"));
            } catch (InvocationTargetException failure) { installFailure.set(failure.getCause()); }
            catch (Throwable failure) { installFailure.set(failure); }
        }, "fixture-installer");
        boolean effectHeld = false;
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            if (!unknown) { fixture("acquireSharedTestEffect"); effectHeld = true; }
            stop.start();
            if (unknown) {
                stop.join(5000);
                require(!stop.isAlive() && stopFailure.get() instanceof LinkageError
                                && "test effect invalidation failure".equals(stopFailure.get().getMessage())
                                && cancellation.invalidationFailed(), "native invalidation failure was not injected");
            } else {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (Boolean.TRUE.equals(fixture("effectAvailable")) && System.nanoTime() < deadline)
                    Thread.yield();
                require(!Boolean.TRUE.equals(fixture("effectAvailable")) && stop.isAlive(),
                        "native invalidation did not drain the held effect");
            }
            require(cancellation.stopRequested(), "STOP was not published");
            installer.start();
            installer.join(2000);
            require(!installer.isAlive(), "late material waited on native invalidation");
            require(installFailure.get() instanceof VaultException
                            && "SSH_CANCELLED".equals(((VaultException)installFailure.get()).code)
                            && Arrays.equals(candidate, new byte[candidate.length])
                            && field(connector, "automaticPasswordSave") == null,
                    "late material was not rejected and erased by its installer");
            if (effectHeld) {
                fixture("releaseSharedTestEffect"); effectHeld = false;
                stop.join(5000);
                require(!stop.isAlive() && stopFailure.get() == null, "native cancellation did not complete");
                require(connector.retireIfUnstarted(), "fixture connector did not retire");
            }
            System.out.println("SSH_CONNECTOR_MATERIAL_JNI mode=" + mode
                    + " candidate=erased invalidation=" + (unknown ? "unknown" : "drained"));
        } finally {
            if (effectHeld) fixture("releaseSharedTestEffect");
            stop.join(5000);
            installer.join(5000);
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"drain".equals(args[0]) && !"unknown".equals(args[0])
                && !"admitted-unknown".equals(args[0]) && !"material-drain".equals(args[0])
                && !"material-unknown".equals(args[0]))
            throw new IllegalArgumentException("unknown connector fixture scenario");
        System.loadLibrary("ctos_ssh_files");
        if (args[0].startsWith("material-")) lateAutomaticMaterial(args[0]);
        else run(args[0]);
    }
}
