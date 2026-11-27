package im.majo.ctos;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
public final class StopEffectWindow {
    public static void main(String[] args) throws Exception {
        System.loadLibrary("ctos_ssh_files");
        SshNativeCallbackAdapter.enableJumpEffectFence();
        SshSessionOwner.Cancellation stop = new SshSessionOwner.Cancellation();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { stop.cancel(); }
            catch (Throwable failure) { error.set(failure); }
        }, "fixture-stop");
        Method acquire = SshFreshVaultRealJniTest.class.getDeclaredMethod("acquireSharedTestEffect");
        Method release = SshFreshVaultRealJniTest.class.getDeclaredMethod("releaseSharedTestEffect");
        acquire.setAccessible(true);
        release.setAccessible(true);
        long before = SshNativeCallbackAdapter.captureJumpEffectGeneration();
        try {
            synchronized (stop) {
                worker.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((!stop.stopRequested() || worker.getState() != Thread.State.BLOCKED)
                        && System.nanoTime() < deadline) Thread.yield();
                if (!stop.stopRequested() || worker.getState() != Thread.State.BLOCKED)
                    throw new AssertionError("failed to establish controlled STOP window");
                long during = SshNativeCallbackAdapter.captureJumpEffectGeneration();
                if (during != before) throw new AssertionError("generation changed before native invalidation");
                acquire.invoke(null);
                release.invoke(null);
                System.out.println("STOP_EFFECT_WINDOW stop=published native-permit=accepted scope=standalone-hook");
            }
            worker.join(5000);
            if (worker.isAlive() || error.get() != null) throw new AssertionError("stop did not finish", error.get());
        } finally {
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }
}
