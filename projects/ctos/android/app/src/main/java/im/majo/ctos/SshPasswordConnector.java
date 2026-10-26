package im.majo.ctos;

import android.os.Process;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Real managed connection assembly. Every native resource is created and closed on one owner. */
final class SshPasswordConnector implements MainActivity.AuthenticatedSshFactory {
    interface Events {
        void present(SshHostKeyReviewMailbox.Ticket ticket) throws VaultException;
        void dismiss(SshHostKeyReviewMailbox.Ticket ticket);
    }
    private final AppSecrets vault;
    private final SshPasswordProfile profile;
    private final AppSecrets.EffectGuard sensitive;
    private final Events events;
    private final SshRoleContext.Clock clock;
    private final long deadline;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final String connectionId = "password-" + UUID.randomUUID();
    private final SshTcpConnector tcp;
    private final SshTransientPassword password;
    private long expectedEpoch;
    private final long initialRevision;
    private volatile boolean started;
    private volatile Runnable cancellationAction;
    private Thread owner;
    private BooleanSupplier hostCancelled = () -> false;
    private SshPublicFileLease view;
    private SshRoleRegistry registry;
    private SshNativeRoleBootstrap.Issued issued;
    private SshNativeRoleBootstrap.Unissued pendingRole;
    private SshManagedKnownHostsProvider trust;
    private SshKnownHostsCandidateHandoff handoff;
    private SshNativeCallbackAdapter callback;
    private volatile SshHostKeyReviewMailbox mailbox;
    private volatile SshHostKeyReviewMailbox.Ticket ticket;
    private SshHostKeyAuthority.Review approved;
    private SshNativeSession session;
    private boolean returned;

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile profile, byte[] bytes,
            long deadline, AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        this.vault = vault; this.profile = profile; this.deadline = deadline;
        this.sensitive = sensitive; this.events = events; clock = SystemClock::elapsedRealtime;
        expectedEpoch = vault.session.epoch(); initialRevision = vault.contentRevision;
        tcp = new SshTcpConnector();
        password = new SshTransientPassword(vault, profile, bytes, deadline, this::check, clock);
        try { check(); }
        catch (VaultException | RuntimeException | Error failure) { password.close(); throw failure; }
    }

    boolean started() { return started; }
    boolean handoffLive() {
        return !cancelled.get() && view != null && view.active() && session != null && !session.closed();
    }
    @Override public void cancellationAction(Runnable action) {
        cancellationAction = action;
        if (cancelled.get()) action.run();
    }
    void cancel() {
        cancelled.set(true);
        password.close();
        SshHostKeyReviewMailbox current = mailbox;
        if (current != null) current.cancel();
        Runnable action = cancellationAction;
        if (action != null) action.run();
    }
    boolean decide(String id, String binding, boolean accept) {
        SshHostKeyReviewMailbox current = mailbox;
        SshHostKeyReviewMailbox.Ticket pending = ticket;
        return current != null && pending != null && pending.id.equals(id) && pending.binding.equals(binding)
                && current.resolve(pending, accept);
    }

    @Override public SshNativeSession create(BooleanSupplier hostCancelled) throws Exception {
        if (started) throw new VaultException("BUSY", "The SSH connection was already started");
        owner = Thread.currentThread(); started = true; this.hostCancelled = hostCancelled;
        return SshPasswordManifestValidator.within(deadline, () -> {
            check();
            if (vault.contentRevision != initialRevision) throw changed();
            AppSecrets.BundleContext context = vault.newBundleContext(deadline, this::check);
            try (SshPasswordProfile.Snapshot snapshot = vault.passwordProfile(context)) {
                if (snapshot.epoch != expectedEpoch || snapshot.revision != initialRevision) throw changed();
                try (SshBundleProposal proposal = snapshot.changes(profile)) {
                    if (proposal != null) {
                        SshVaultAdapter bundles = new SshVaultAdapter(vault, (c, t) -> { throw denied(); },
                                p -> { throw denied(); }, exact -> {
                                    check(); if (exact != proposal) throw denied();
                                });
                        VaultOperation operation = bundles.bundleRequest(proposal, context);
                        try { bundles.commitBundle(operation, proposal, this::check); }
                        finally { bundles.cancel(operation); }
                        // Only this exact, confirmed bundle may continue across its own write.
                        expectedEpoch++;
                        check();
                        if (vault.contentRevision != initialRevision + 1) throw changed();
                    }
                }
            } finally { vault.cancelBundleContext(context); }
            check();
            SshVaultAdapter files = new SshVaultAdapter(vault, (c, t) -> { throw denied(); });
            AppSecrets.SshPublicReadContext fileContext = files.fileContext(deadline, this::check);
            VaultOperation read = files.fileRequest(SshPasswordProfile.CONFIG_PATH, connectionId, fileContext);
            try { view = files.openPublicFiles(read, SshPasswordProfile.CONFIG_PATH, connectionId); }
            catch (VaultException | RuntimeException | Error failure) { files.cancel(read); throw failure; }
            check();
            registry = new SshRoleRegistry(clock);
            issueRole();
            mailbox = new SshHostKeyReviewMailbox(issued.role, clock, () -> {
                check();
                if (callback == null) throw denied();
                callback.requireAuthenticationSelectionLive();
            }, new SshHostKeyReviewMailbox.Sink() {
                @Override public void present(SshHostKeyReviewMailbox.Ticket value) throws VaultException {
                    ticket = value; events.present(value);
                }
                @Override public void dismiss(SshHostKeyReviewMailbox.Ticket value) {
                    if (ticket == value) ticket = null;
                    events.dismiss(value);
                }
            });
            SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
                mailbox.requireApproved(review); check(); approved = review;
            });
            SshVaultAdapter trusted = new SshVaultAdapter(vault, (connection, target) -> {
                check();
                if (approved == null || !connectionId.equals(connection) || !profileTarget(target)
                        || !issued.role.binding().equals(approved.roleBinding)) throw denied();
            }, this::requireApprovedProposal);
            SshManagedKnownHostsProvider.SnapshotReceipt receipt = SshManagedKnownHostsProvider.snapshotFor(
                    trusted, issued.role, clock, SshPasswordProfile.CONFIG_PATH, SshPasswordProfile.HOSTS_PATH,
                    SshPasswordProfile.HOSTS_ID, SshPasswordProfile.HOSTS_IDENTITY);
            trust = SshManagedKnownHostsProvider.bindForNativeHandoff(trusted, receipt);
            handoff = trust.nativeCandidateHandoff();
            SshCredentialBridge credential = new SshCredentialBridge(vault, authority, clock, password);
            SshManagedProvider provider = new SshManagedProvider(credential, clock, trust, trust,
                    (b, r, proof, kind) -> { throw denied(); });
            callback = SshNativeCallbackAdapter.bind(issued, registry, authority, clock, provider, handoff);
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(issued.role,
                    profile.username, password.entryId, clock);
            password.bind(issued.role, selection);
            callback.selectAuthentication(selection);
            SshSocketOwnership socket = tcp.connect(profile, deadline, this::check);
            session = SshNativeSession.openManaged(callback, view, socket,
                    "candidate-" + UUID.randomUUID(), SshNativeSession.MAX_STEP_BUDGET);
            while (!session.step().complete) pause();
            check(); session.beginUserauthService();
            while (!session.userauthServiceReady()) { session.step(); pause(); }
            check(); session.beginPasswordAuth();
            SshNativeSession.PasswordAuthResult result;
            while ((result = session.passwordAuthResult()) == SshNativeSession.PasswordAuthResult.PENDING) {
                session.step(); pause();
            }
            if (result != SshNativeSession.PasswordAuthResult.SUCCESS)
                throw new VaultException("SSH_AUTHENTICATION", "SSH password authentication failed");
            check(); returned = true;
            return session;
        });
    }

    private void issueRole() throws VaultException {
        byte[] nonce = new byte[32], binding = new byte[32];
        SecureRandom random = new SecureRandom(); random.nextBytes(nonce); random.nextBytes(binding);
        try {
            SshRoleContext.Bootstrap bootstrap = new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                    Process.myPid(), 0, connectionId, SshPasswordProfile.TARGET, profile.host, profile.port,
                    "view-" + UUID.randomUUID(), nonce, binding, deadline);
            pendingRole = SshNativeRoleBootstrap.prepare(bootstrap);
            pendingRole.open();
            issued = pendingRole.issue(registry, view, clock);
            pendingRole = null;
        } finally { VaultCodec.wipe(nonce); VaultCodec.wipe(binding); }
    }

    private void requireApprovedProposal(SshTrustProposal proposal) throws VaultException {
        check();
        if (approved == null || !proposal.matchesRole(issued.role)
                || !proposal.matchesManagedObject(VaultManifest.FileType.KNOWN_HOSTS, SshPasswordProfile.HOSTS_ID,
                        SshPasswordProfile.HOSTS_PATH, SshPasswordProfile.HOSTS_IDENTITY)
                || !approved.fingerprint.equals(proposal.fingerprint) || !approved.keyType.equals(proposal.keyType))
            throw denied();
        byte[] base = approved.baseDigest(), replacement = approved.replacementDigest();
        byte[] actualBase = proposal.baseDigest(), content = proposal.newContent();
        byte[] actualReplacement = VaultCodec.digest(content);
        try {
            if (!MessageDigest.isEqual(base, actualBase) || !MessageDigest.isEqual(replacement, actualReplacement))
                throw denied();
        } finally {
            VaultCodec.wipe(base); VaultCodec.wipe(replacement);
            VaultCodec.wipe(actualBase); VaultCodec.wipe(actualReplacement);
            VaultCodec.wipe(content);
        }
    }

    private void pause() throws VaultException {
        check();
        try { Thread.sleep(2); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); cancel(); check(); }
    }
    private void check() throws VaultException {
        if (cancelled.get() || hostCancelled.getAsBoolean())
            throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
        sensitive.check();
        if (clock.now() >= deadline) throw new VaultException("SSH_TIMED_OUT", "The SSH connection timed out");
        if (view == null) vault.session.assertEpoch(expectedEpoch);
        else if (!view.active()) throw changed();
    }
    private static boolean profileTarget(String target) { return SshPasswordProfile.TARGET.equals(target); }
    private static VaultException changed() { return new VaultException("VAULT_CHANGED", "The vault changed; try again"); }
    private static VaultException denied() { return new VaultException("SSH_HOST_KEY_REVIEW", "The exact host approval is required"); }

    @Override public void close() throws Exception {
        cancel();
        if (!started) return;
        if (Thread.currentThread() != owner) throw new VaultException("SSH_SESSION_THREAD", "SSH cleanup requires its owner");
        // Keep fields until their close confirms success, so MainActivity can retry on this owner.
        if (session != null && !returned) { session.close(); session = null; }
        tcp.close();
        if (callback != null) { callback.close(); callback = null; }
        if (handoff != null) { handoff.close(); handoff = null; }
        if (trust != null) { trust.close(); trust = null; }
        if (issued != null && issued.role.active())
            SshNativeRoleBootstrap.release(issued, registry, SshNativeRoleBootstrap.productionRelease());
        issued = null;
        if (pendingRole != null) { pendingRole.closeUnissued(); pendingRole = null; }
        if (view != null) { view.close(); view = null; }
        if (registry != null) { registry.close(); registry = null; }
        vault.retryPasswordValidationCleanup();
    }
}
