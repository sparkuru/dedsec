package im.majo.ctos;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/* This isolated JVM receiver exercises the real JNI adapter and native SSH
 * chain. It does not implement the app's vault or trust writer. */
public final class SshNativeCallbackAdapter {
    private native boolean nativeCallbackAncestorProofsLive(long callback);
    private native boolean nativeSelectedEffectCopyBegin();
    private native void nativeSelectedEffectCopyEnd();
    private static native boolean fixtureSessionLockHeld();
    private static native void fixtureCancelParent();
    private static native void fixtureRevokeSharedView();
    private static native int fixtureRun(SshNativeCallbackAdapter receiver, int mode);
    private static native int fixtureNativeFreshCases();
    private static native int fixtureEffectActive();
    private static native void fixtureExceptionAfterReturn();
    private static native boolean fixtureCancelParentConcurrent();
    private static native boolean fixtureAwaitInvalidation();

    private long callback;
    private long parentCallback;
    private int hostCompleted;
    private int kexCompleted;
    private int credentialCompleted;
    private int mode;
    private byte[] returnedSecret;
    private SshNativeCallbackAdapter other;
    private Thread cancellationThread;
    private final AtomicBoolean concurrentCancellationSucceeded = new AtomicBoolean();

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private boolean onNativeEffectAdmission() { return true; }

    private void requireLiveProof() {
        require(fixtureSessionLockHeld());
        require(nativeCallbackAncestorProofsLive(callback));
        require(!other.nativeCallbackAncestorProofsLive(callback));
        require(!nativeCallbackAncestorProofsLive(parentCallback));
    }

    private static void writeField(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private byte[] onNativeHostEvidence(long role, int hop, int port, String connection,
            String target, String host, int classification, String key, String fingerprint,
            byte[] base, byte[] replacement) throws IOException, InterruptedException {
        require(hop == (mode >= 3 && mode <= 10 ? 2 : 1) && port == 22 && classification == 1);
        require(base.length == 32 && replacement.length == 32);
        requireLiveProof();
        AtomicBoolean wrongThreadAccepted = new AtomicBoolean(true);
        Thread wrongThread = new Thread(() ->
                wrongThreadAccepted.set(nativeCallbackAncestorProofsLive(callback)));
        wrongThread.setDaemon(true);
        wrongThread.start();
        wrongThread.join(5000);
        require(!wrongThread.isAlive() && !wrongThreadAccepted.get());
        if (mode == 1 || mode == 4) {
            fixtureCancelParent();
            require(!nativeCallbackAncestorProofsLive(callback));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x43534852);
        output.writeByte(1);
        output.writeByte(1);
        output.writeShort(0);
        output.writeLong(role);
        output.writeInt(hop);
        output.writeInt(port);
        output.writeInt(classification);
        output.writeInt(0);
        writeField(output, connection);
        writeField(output, target);
        writeField(output, host);
        writeField(output, key);
        writeField(output, fingerprint);
        writeField(output, "");
        output.write(base);
        output.write(replacement);
        output.flush();
        hostCompleted++;
        return bytes.toByteArray();
    }

    private boolean onNativeKexComplete(long cycle, byte[] digest) {
        require(cycle > 0 && digest.length == 32);
        requireLiveProof();
        kexCompleted++;
        return true;
    }

    private byte[] onNativeSelectedCredentialBytes(int kind, byte[] receipt, long cycle,
            byte[] digest) {
        require(kind == 1 && receipt.length == 32 && cycle > 0 && digest.length == 32);
        requireLiveProof();
        if (mode >= 11) {
            require(nativeSelectedEffectCopyBegin());
            require(fixtureEffectActive() == 1);
            returnedSecret = "selected-password".getBytes(StandardCharsets.US_ASCII);
            credentialCompleted++;
            if (mode == 12 || mode == 17) {
                other.nativeSelectedEffectCopyEnd();
                require(fixtureEffectActive() == 1);
            }
            if (mode == 13 || mode == 12) return returnedSecret;
            if (mode == 14) {
                Arrays.fill(returnedSecret, (byte) 0);
                throw new IllegalStateException("fixture exception while copying");
            }
            if (mode == 16) {
                cancellationThread = new Thread(() -> concurrentCancellationSucceeded.set(
                        fixtureCancelParentConcurrent()));
                cancellationThread.setDaemon(true);
                cancellationThread.start();
                require(fixtureAwaitInvalidation());
                require(fixtureEffectActive() == 1);
            }
            nativeSelectedEffectCopyEnd();
            require(fixtureEffectActive() == 0);
            if (mode == 15) fixtureExceptionAfterReturn();
            if (mode == 17) fixtureCancelParent();
            return returnedSecret;
        }
        if (mode == 2 || mode == 5) {
            fixtureCancelParent();
            require(!nativeCallbackAncestorProofsLive(callback));
        }
        if (mode == 9) {
            fixtureRevokeSharedView();
            require(!nativeCallbackAncestorProofsLive(callback));
        }
        returnedSecret = "selected-password".getBytes(StandardCharsets.US_ASCII);
        credentialCompleted++;
        return returnedSecret;
    }

    private boolean onNativeHostKey(int classification, String key, String fingerprint,
            byte[] base, byte[] replacement) { return false; }
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
    private boolean onNativeCredential(int kind) { return false; }
    private byte[] onNativeCredentialBytes(int kind) { return null; }

    public static void main(String[] args) {
        require(args.length == 1);
        System.load(args[0]);
        boolean freshOnly = "1".equals(System.getenv("CTOS_SSH_JNI_FRESH_ONLY"));
        boolean effectOnly = "1".equals(System.getenv("CTOS_SSH_JNI_EFFECT_ONLY"));
        require(!(freshOnly && effectOnly));
        if (!freshOnly && !effectOnly) {
            int freshChecks = fixtureNativeFreshCases();
            require(freshChecks > 0);
            System.out.println("SSH_NATIVE_FRESH_HOSTFILE checks=" + freshChecks
                    + " peer=host-synthetic callbacks=native-test");
        }
        for (int mode = effectOnly ? 11 : freshOnly ? 10 : 0; mode <= (freshOnly ? 10 : 17); mode++) {
            SshNativeCallbackAdapter receiver = new SshNativeCallbackAdapter();
            receiver.other = new SshNativeCallbackAdapter();
            receiver.mode = mode;
            int checks = fixtureRun(receiver, mode);
            if (receiver.cancellationThread != null) {
                try { receiver.cancellationThread.join(5000); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                require(!receiver.cancellationThread.isAlive() && receiver.concurrentCancellationSucceeded.get());
            }
            require(checks > 0 && receiver.hostCompleted == 1);
            boolean cancelledAtHost = mode == 1 || mode == 4;
            require(receiver.kexCompleted == (cancelledAtHost ? 0 : 1));
            require(receiver.credentialCompleted == (cancelledAtHost ? 0 : 1));
            if (!cancelledAtHost) {
                require(receiver.returnedSecret != null && receiver.returnedSecret.length > 0);
                for (byte value : receiver.returnedSecret) require(value == 0);
            }
            require(!receiver.nativeCallbackAncestorProofsLive(receiver.callback));
            System.out.println("SSH_CALLBACK_JNI_MULTIHOP mode=" + mode + " checks=" + checks
                    + " session-lock=held runtime=host-synthetic");
        }
    }
}
