package im.majo.ctos;

import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Complete admission for the supported password/config/key graph in the shared manifest. */
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
        if (manifest.files.isEmpty()) return;
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
        private final Map<Thread, SshManifestConfigInspector> pendingConfigs = new HashMap<>();

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
                } else if (entry.type == VaultManifest.FileType.CONFIG) {
                    if (entry.status != SshManifestInspector.Status.CONFIG_DEFERRED) throw SshPasswordProfile.unsupported();
                } else if ((entry.type != VaultManifest.FileType.PRIVATE_KEY && entry.type != VaultManifest.FileType.PUBLIC_KEY)
                        || entry.status != SshManifestInspector.Status.PARSED) throw SshPasswordProfile.unsupported();
            }
            if (hosts == 0 || material.entries.size() != manifest.files.size()) throw SshPasswordProfile.unsupported();
            if (SshConnectionMaterial.extended(manifest)) {
                for (VaultSshMetadata.KeyBinding binding : manifest.keyBindings) {
                    VaultManifest.FileObject privateFile = SshConnectionMaterial.fileId(manifest, binding.privateFileId);
                    VaultManifest.FileObject publicFile = SshConnectionMaterial.fileId(manifest, binding.publicFileId);
                    SshKeyMaterial.Result derived = SshKeyMaterial.inspect(privateFile.content, deadline);
                    if (derived.status != SshKeyMaterial.Status.PARSED) throw SshConnectionMaterial.unsupported();
                    byte[] line = derived.publicLine(), blob = derived.publicBlob();
                    try {
                        if (!java.util.Arrays.equals(line, publicFile.content)
                                || !privateFile.identity.equals("ctos-key-" + SshConnectionMaterial.hex(VaultCodec.digest(blob))))
                            throw SshConnectionMaterial.unsupported();
                    } finally { VaultCodec.wipe(line); VaultCodec.wipe(blob); }
                }
                SshManifestConfigInspector configs = new SshManifestConfigInspector();
                synchronized (pendingConfigs) { pendingConfigs.put(Thread.currentThread(), configs); }
                try {
                    SshManifestConfigInspector.Report report = configs.inspectResourceAudit(manifest, deadline,
                            new SshManifestConfigInspector.Cancellation());
                    for (SshManifestConfigInspector.Root root : report.roots) if (root.path.startsWith(".ssh/")) {
                        java.util.List<String> expected = SshConnectionMaterial.references(manifest, root.object, root.resourceAudit);
                        VaultManifest.FileObject file = manifest.files.get(root.object - 1);
                        if (!new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(file.references)))
                            throw SshConnectionMaterial.unsupported();
                    }
                } finally { retryCleanup(); }
            }
            if (profile == null) return;
            String[] identity = SshNativeRoleBootstrap.systemIdentity();
            if (identity == null || identity.length != 2) throw SshPasswordProfile.unsupported();
            SshConfigEvaluator.Request request = new SshConfigEvaluator.Request(SshPasswordProfile.ALIAS,
                    "", "", identity[0], identity[1], "", "", 0, Process.myUid());
            byte[] source = SshConnectionMaterial.detached(manifest);
            SshConfigEvaluator evaluator = null;
            try {
                evaluator = SshConfigEvaluator.prepareDetached(() -> {
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("SSH configuration expired");
                }, false);
                synchronized (pending) { pending.put(Thread.currentThread(), evaluator); }
                int root = SshConnectionMaterial.index(manifest, SshPasswordProfile.CONFIG_PATH);
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
            SshManifestConfigInspector configs;
            synchronized (pendingConfigs) { configs = pendingConfigs.get(owner); }
            if (configs != null) {
                configs.close();
                synchronized (pendingConfigs) { pendingConfigs.remove(owner, configs); }
            }
            SshConfigEvaluator retained;
            synchronized (pending) { retained = pending.get(owner); }
            if (retained != null) {
                retained.close();
                synchronized (pending) { if (pending.get(owner) == retained) pending.remove(owner); }
            }
            SshConnectionMaterial.retryCleanup();
        }

    }
}
