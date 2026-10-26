package im.majo.ctos;

import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Complete admission only for the two exact generated public objects and unrelated TOTP. */
final class SshPasswordManifestValidator implements AppSecrets.SshContentValidator {
    interface Clock { long now(); }
    interface Checks { void check(VaultManifest manifest, SshPasswordProfile profile, long deadline) throws IOException, VaultException; }
    interface Work<T> { T run() throws Exception; }
    private final Clock clock;
    private final Checks checks;
    private static final ThreadLocal<Long> ORIGINAL_DEADLINE = new ThreadLocal<>();

    SshPasswordManifestValidator() { this(SystemClock::elapsedRealtime, new NativeChecks()); }
    SshPasswordManifestValidator(Clock clock, Checks checks) { this.clock = clock; this.checks = checks; }

    void retryCleanup() throws VaultException {
        if (!(checks instanceof NativeChecks)) return;
        try { ((NativeChecks)checks).retryCleanup(); }
        catch (IOException | LinkageError | RuntimeException failure) {
            throw new VaultException("SSH_PROFILE_CLEANUP", "SSH validation cleanup is pending");
        }
    }

    static <T> T within(long deadline, Work<T> work) throws Exception {
        Long previous = ORIGINAL_DEADLINE.get();
        ORIGINAL_DEADLINE.set(previous == null ? deadline : Math.min(previous, deadline));
        try { return work.run(); }
        finally { if (previous == null) ORIGINAL_DEADLINE.remove(); else ORIGINAL_DEADLINE.set(previous); }
    }

    @Override public void validate(VaultManifest manifest) throws VaultException {
        SshPasswordProfile profile = SshPasswordProfile.checkShape(manifest);
        if (profile == null) return;
        Long original = ORIGINAL_DEADLINE.get();
        long deadline = Math.min(clock.now() + 10_000, original == null ? Long.MAX_VALUE : original);
        if (deadline <= clock.now()) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        try { checks.check(manifest, profile, deadline); }
        catch (IOException | LinkageError failure) {
            throw new VaultException("SSH_PROFILE_VALIDATION", "The SSH connection material could not be checked");
        }
        if (clock.now() >= deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
    }

    private static final class NativeChecks implements Checks {
        private final Map<Thread, SshConfigEvaluator> pending = new HashMap<>();

        @Override public void check(VaultManifest manifest, SshPasswordProfile profile, long deadline)
                throws IOException, VaultException {
            retryCleanup();
            SshManifestInspector.Report material = new SshManifestInspector().inspect(manifest, deadline,
                    new SshManifestInspector.Cancellation());
            int hosts = 0;
            for (SshManifestInspector.Entry entry : material.entries) {
                if (entry.type == VaultManifest.FileType.KNOWN_HOSTS) {
                    hosts++;
                    if (entry.status != SshManifestInspector.Status.PARSED) throw SshPasswordProfile.unsupported();
                } else if (entry.type != VaultManifest.FileType.CONFIG
                        || entry.status != SshManifestInspector.Status.CONFIG_DEFERRED)
                    throw SshPasswordProfile.unsupported();
            }
            if (hosts != 1 || material.entries.size() != 2) throw SshPasswordProfile.unsupported();
            String[] identity = SshNativeRoleBootstrap.systemIdentity();
            if (identity == null || identity.length != 2) throw SshPasswordProfile.unsupported();
            SshConfigEvaluator.Request request = new SshConfigEvaluator.Request(SshPasswordProfile.ALIAS,
                    "", "", identity[0], identity[1], "", "", 0, Process.myUid());
            byte[] source = detached(manifest);
            SshConfigEvaluator evaluator = null;
            try {
                evaluator = SshConfigEvaluator.prepareDetached(() -> {
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("SSH configuration expired");
                }, false);
                synchronized (pending) { pending.put(Thread.currentThread(), evaluator); }
                int root = manifest.files.get(0).type == VaultManifest.FileType.CONFIG ? 0 : 1;
                evaluator.loadDetached(source, root, deadline, request);
                evaluator.run();
                SshConfigEvaluator.Summary value = evaluator.summary();
                if (!SshPasswordProfile.ALIAS.equals(value.alias) || !profile.host.equals(value.hostname)
                        || !profile.username.equals(value.user) || profile.port != value.port || value.userRequired
                        || value.hasJump || value.hasRemoteCommand || value.localForwards != 0
                        || value.remoteForwards != 0) throw SshPasswordProfile.unsupported();
            } finally {
                VaultCodec.wipe(source);
                if (evaluator != null) retryCleanup();
            }
        }

        private void retryCleanup() throws IOException {
            Thread owner = Thread.currentThread();
            SshConfigEvaluator retained;
            synchronized (pending) { retained = pending.get(owner); }
            if (retained == null) return;
            retained.close();
            synchronized (pending) { if (pending.get(owner) == retained) pending.remove(owner); }
        }

        private static byte[] detached(VaultManifest manifest) {
            int size = 24;
            byte[][] paths = new byte[manifest.files.size()][];
            for (int i = 0; i < paths.length; i++) {
                VaultManifest.FileObject file = manifest.files.get(i);
                paths[i] = file.path.getBytes(StandardCharsets.UTF_8);
                size += 12 + paths[i].length + (file.type == VaultManifest.FileType.CONFIG ? file.content.length : 0);
            }
            byte[] output = new byte[size];
            try {
                ByteBuffer wire = ByteBuffer.wrap(output).putInt(0x43534449).putInt(1).putInt(paths.length)
                        .putInt(SshConfigEvaluator.MAX_OPENS).putInt(SshConfigEvaluator.MAX_READ_BYTES)
                        .putInt(SshConfigEvaluator.MAX_LINES);
                for (int i = 0; i < paths.length; i++) {
                    VaultManifest.FileObject file = manifest.files.get(i);
                    boolean config = file.type == VaultManifest.FileType.CONFIG;
                    wire.putInt(config ? 1 : 5).putInt(paths[i].length).put(paths[i]);
                    wire.putInt(config ? file.content.length : 0);
                    if (config) wire.put(file.content);
                }
                return output;
            } catch (Throwable failure) { VaultCodec.wipe(output); throw failure; }
            finally { for (byte[] path : paths) VaultCodec.wipe(path); }
        }
    }
}
