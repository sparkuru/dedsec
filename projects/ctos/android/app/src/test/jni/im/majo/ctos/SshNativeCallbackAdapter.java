package im.majo.ctos;

import java.util.concurrent.atomic.AtomicBoolean;

/* Isolated host-JVM target with the same JNI receiver name as the app. The
 * ancestor checker is a controlled native fixture, not an SSH client. */
public final class SshNativeCallbackAdapter {
    private native boolean nativeCallbackAncestorProofsLive(long callback);
    private static native long fixtureOpen(SshNativeCallbackAdapter target);
    private static native boolean fixtureDispatch(long callback);
    private static native boolean fixtureAdmit(long callback);
    private static native void fixtureMode(long callback, int mode);
    private static native int fixtureChecks(long callback);
    private static native void fixtureClose(long callback);

    private long callback;
    private SshNativeCallbackAdapter other;
    private int scenario;
    private int completedCallbacks;
    private int admissionMode;
    private int admissions;

    private static void require(boolean value) {
        if (!value) throw new AssertionError();
    }

    private boolean onNativeEffectAdmission() {
        admissions++;
        if (admissionMode == 2) throw new IllegalStateException("fixture admission failure");
        return admissionMode == 0;
    }

    private boolean onNativeHostKey(int classification, String key, String fingerprint,
            byte[] base, byte[] replacement) throws InterruptedException {
        if (scenario == 1) {
            require(!nativeCallbackAncestorProofsLive(callback));
            require(!other.nativeCallbackAncestorProofsLive(other.callback));
            completedCallbacks++;
            return true;
        }
        if (scenario == 2) {
            fixtureMode(callback, 2);
            require(!nativeCallbackAncestorProofsLive(callback));
            completedCallbacks++;
            return true;
        }
        require(nativeCallbackAncestorProofsLive(callback));
        require(!other.nativeCallbackAncestorProofsLive(callback));
        require(!nativeCallbackAncestorProofsLive(other.callback));
        require(!nativeCallbackAncestorProofsLive(0));
        require(!nativeCallbackAncestorProofsLive(-1));
        int before = fixtureChecks(callback);
        AtomicBoolean foreignAccepted = new AtomicBoolean(true);
        Thread foreign = new Thread(() -> foreignAccepted.set(nativeCallbackAncestorProofsLive(callback)));
        foreign.setDaemon(true);
        foreign.start();
        foreign.join(5000);
        require(!foreign.isAlive() && !foreignAccepted.get() && fixtureChecks(callback) == before);
        require(fixtureDispatch(other.callback));
        require(nativeCallbackAncestorProofsLive(callback));
        fixtureMode(callback, 1);
        require(!nativeCallbackAncestorProofsLive(callback));
        fixtureMode(callback, 0);
        require(nativeCallbackAncestorProofsLive(callback));
        completedCallbacks++;
        return true;
    }

    private byte[] onNativeHostEvidence(long role, int hop, int port, String connection,
            String target, String host, int classification, String key, String fingerprint,
            byte[] base, byte[] replacement) { return null; }
    private boolean onNativeKnownHostsCandidate(long role, int hop, int port, String connection,
            String target, String host, int classification, String key, String fingerprint,
            byte[] base, byte[] replacement, String candidate, byte[] body) { return false; }
    private boolean onNativeChangedHostCandidate(long role, int hop, int port, String connection,
            String target, String host, int classification, String key, String fingerprint,
            byte[] base, byte[] replacement, String candidate, byte[] body, byte[] old) { return false; }
    private boolean onNativeChangedHostReview(long role, int hop, int port, String connection,
            String target, String host, int classification, String key, String fingerprint,
            byte[] base, byte[] replacement) { return false; }
    private void abortNativeKnownHostsCandidate() { }
    private boolean onNativeKexComplete(long cycle, byte[] digest) { return false; }
    private boolean onNativeCredential(int kind) { return false; }
    private byte[] onNativeCredentialBytes(int kind) { return null; }
    private byte[] onNativeSelectedCredentialBytes(int kind, byte[] receipt, long cycle,
            byte[] digest) { return null; }

    public static void main(String[] args) {
        require(args.length == 1);
        System.load(args[0]);
        SshNativeCallbackAdapter first = new SshNativeCallbackAdapter();
        SshNativeCallbackAdapter second = new SshNativeCallbackAdapter();
        first.other = second;
        second.other = first;
        second.scenario = 1;
        first.callback = fixtureOpen(first);
        second.callback = fixtureOpen(second);
        require(fixtureAdmit(first.callback) && fixtureAdmit(second.callback));
        first.admissionMode = 1;
        require(!fixtureAdmit(first.callback) && fixtureAdmit(second.callback));
        first.admissionMode = 2;
        require(!fixtureAdmit(first.callback));
        first.admissionMode = 0;
        require(fixtureAdmit(first.callback));
        require(first.admissions == 4 && second.admissions == 2);
        require(!first.nativeCallbackAncestorProofsLive(first.callback));
        require(fixtureDispatch(first.callback));
        require(first.completedCallbacks == 1 && second.completedCallbacks == 1);
        require(fixtureChecks(first.callback) == 4 && fixtureChecks(second.callback) == 0);
        require(!first.nativeCallbackAncestorProofsLive(first.callback));
        first.scenario = 2;
        require(!fixtureDispatch(first.callback));
        require(first.completedCallbacks == 2 && fixtureChecks(first.callback) == 5);
        fixtureClose(first.callback);
        require(!first.nativeCallbackAncestorProofsLive(first.callback));
        fixtureClose(second.callback);
        System.out.println("SSH_CALLBACK_JNI_SCOPE host-jvm=pass admission=target-and-fail-closed ancestor-checker=fixture");
    }
}
