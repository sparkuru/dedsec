package im.majo.ctos;

import java.lang.reflect.Method;
import java.util.ArrayDeque;

public final class CancelFailurePumpRepro {
    private static final class Scheduler implements SshTerminalSession.Scheduler {
        final ArrayDeque<Runnable> turns = new ArrayDeque<>();
        @Override public void later(Runnable turn, long delay) { turns.add(turn); }
        void next() { turns.remove().run(); }
    }
    private static final class Port implements SshTerminalSession.Port {
        int writes, steps;
        @Override public void begin(int columns, int rows) { }
        @Override public SshTerminalSession.Status status() {
            return new SshTerminalSession.Status(SshNativeSession.ShellPhase.RUNNING, 0, null);
        }
        @Override public void step() { steps++; }
        @Override public int write(byte[] bytes) { writes++; return bytes.length; }
        @Override public byte[] read(int maximum) { return new byte[0]; }
        @Override public boolean resize(int columns, int rows) { return true; }
        @Override public boolean publicationLive() { return true; }
        @Override public void cancel() { }
        @Override public void close() { }
    }
    public static void main(String[] args) throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            Scheduler scheduler = new Scheduler();
            Port port = new Port();
            SshSessionOwner.Cancellation gate = new SshSessionOwner.Cancellation();
            SshTerminalSession terminal = new SshTerminalSession(port, scheduler,
                    new SshTerminalSession.Sink() {
                        @Override public void data(byte[] bytes, Runnable consumed) { consumed.run(); }
                        @Override public void state(String state, Long exitStatus, String reason) { }
                    }, () -> { }, gate);
            terminal.start(80, 24);
            scheduler.next();
            terminal.write(new byte[] { 1 });
            int stepsBefore = port.steps;
            Method arm = Class.forName("im.majo.ctos.SshFreshVaultRealJniTest")
                    .getDeclaredMethod("armLegacyEffectInvalidationFailure");
            arm.setAccessible(true);
            arm.invoke(null);
            boolean failed = false;
            try { terminal.requestCancel(); }
            catch (LinkageError expected) { failed = true; }
            if (!failed || gate.getAsBoolean()) throw new AssertionError("unexpected cancellation state");
            scheduler.next();
            if (port.writes != 1 || port.steps != stepsBefore + 1)
                throw new AssertionError("bug did not reproduce");
            System.out.println("REPRODUCED: injected JNI begin failure left logical cancellation open; queued input reached port.write and pump called port.step");
            terminal.stop();
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }
}
