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
    private SshPasswordProfile profile;
    private AppSecrets.EffectGuard sensitive;
    private Events events;
    private final SshRoleContext.Clock clock;
    private final long deadline;
    private final long requestStarted;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final String connectionId = "password-" + UUID.randomUUID();
    private final SshTcpConnector tcp;
    private final SshTransientPassword password;
    private final SshSavedPassword saved;
    private final boolean useSaved;
    private final AppSecrets.SshEntryMutationContext saveContext;
    private final AppSecrets.BundleContext origin;
    private final String privateKeyId, configAlias, usernameOverride, expectedConfigBinding;
    private SshConnectionMaterial.Key selectedKey;
    private String rootPath = SshPasswordProfile.CONFIG_PATH;
    private String hostsPath = SshPasswordProfile.HOSTS_PATH;
    private String hostsId = SshPasswordProfile.HOSTS_ID;
    private String hostsIdentity = SshPasswordProfile.HOSTS_IDENTITY;
    private String targetIdentity;
    private int strictHostKeyChecking = 3;
    private long transportDeadline;
    private byte[] pendingSave;
    private long expectedEpoch;
    private final long initialRevision;
    private volatile boolean started;
    private volatile boolean durableWrite;
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
    private SshReconnectTarget reconnectTarget;

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile profile, byte[] bytes,
            long deadline, AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        this(vault, profile, bytes, false, false, deadline, sensitive, events);
    }

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile profile, byte[] bytes, boolean useSavedPassword,
            boolean savePassword, long deadline, AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        this.vault = vault; this.profile = profile; this.deadline = deadline;
        this.sensitive = sensitive; this.events = events; clock = SystemClock::elapsedRealtime; requestStarted = clock.now();
        expectedEpoch = vault.session.epoch(); initialRevision = vault.contentRevision;
        tcp = new SshTcpConnector();
        saved = new SshSavedPassword(profile);
        privateKeyId = null; configAlias = ""; usernameOverride = ""; expectedConfigBinding = "";
        origin = vault.newBundleContext(deadline, this::check);
        useSaved = useSavedPassword || savePassword;
        targetIdentity = useSaved ? saved.target : SshPasswordProfile.TARGET;
        transportDeadline = deadline;
        if (useSavedPassword && (savePassword || bytes.length != 0)) throw SshPasswordProfile.parameters();
        if (!useSavedPassword) SshSavedPassword.validateSecret(bytes);
        saveContext = savePassword ? vault.newPasswordMutationContext(expectedEpoch, deadline, this::check, profile, false) : null;
        pendingSave = savePassword ? bytes.clone() : null;
        password = useSaved ? null : new SshTransientPassword(vault, profile, bytes, deadline, this::check, clock);
        try { check(); }
        catch (VaultException | RuntimeException | Error failure) { cancel(); throw failure; }
    }

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile manual, String privateKeyId,
            String configAlias, String configBinding, String usernameOverride, long deadline,
            AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        VaultManifest.identifier(privateKeyId);
        if (configAlias == null || usernameOverride == null || manual == null && configAlias.isEmpty())
            throw SshPasswordProfile.parameters();
        this.vault = vault; profile = manual; this.privateKeyId = privateKeyId;
        this.configAlias = configAlias; this.expectedConfigBinding = configBinding; this.usernameOverride = usernameOverride;
        this.deadline = deadline; this.sensitive = sensitive; this.events = events;
        clock = SystemClock::elapsedRealtime; requestStarted = clock.now(); expectedEpoch = vault.session.epoch();
        initialRevision = vault.contentRevision; tcp = new SshTcpConnector();
        origin = vault.newBundleContext(deadline, this::check);
        saved = null; useSaved = false; saveContext = null; password = null;
        transportDeadline = deadline;
        try { check(); } catch (VaultException | RuntimeException | Error failure) { cancel(); throw failure; }
    }

    boolean started() { return started; }
    boolean durableWrite() { return durableWrite; }
    @Override public SshReconnectTarget reconnectTarget() { return reconnectTarget; }
    boolean handoffLive() {
        return !cancelled.get() && session != null && session.shellOutputPublicationLive();
    }
    @Override public void cancellationAction(Runnable action) {
        cancellationAction = action;
        if (cancelled.get()) action.run();
    }
    void cancel() {
        cancelled.set(true);
        vault.cancelBundleContext(origin);
        if (password != null) password.close();
        if (saveContext != null) saveContext.cancel();
        synchronized (this) { VaultCodec.wipe(pendingSave); pendingSave = null; }
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
            if (privateKeyId != null) prepareKey();
            else {
            long expectedRevision = initialRevision;
            if (saveContext != null) {
                byte[] saving;
                synchronized (this) { saving = pendingSave; pendingSave = null; }
                if (saving == null) throw changed();
                VaultOperation operation = null;
                try {
                    operation = vault.beginPasswordMutation(saveContext, connectionId, true);
                    vault.savePassword(operation, saving, this::check);
                    durableWrite = true;
                    expectedEpoch++; expectedRevision++;
                } finally {
                    VaultCodec.wipe(saving);
                    vault.cancelOperation(operation);
                    saveContext.cancel();
                }
                check();
                if (vault.contentRevision != expectedRevision) throw changed();
            }
            AppSecrets.BundleContext context = vault.newBundleContext(deadline, this::check);
            try (SshPasswordProfile.Snapshot snapshot = vault.passwordProfile(context)) {
                if (snapshot.epoch != expectedEpoch || snapshot.revision != expectedRevision) throw changed();
                if (useSaved && !saved.present(snapshot.accounts))
                    throw new VaultException("SSH_PASSWORD_MISSING", "No saved password matches this connection");
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
                        expectedRevision++;
                        check();
                        if (vault.contentRevision != expectedRevision) throw changed();
                    }
                }
            } finally { vault.cancelBundleContext(context); }
            }
            check();
            SshVaultAdapter files = new SshVaultAdapter(vault, (c, t) -> { throw denied(); });
            AppSecrets.SshPublicReadContext fileContext = files.fileContext(deadline, this::check);
            VaultOperation read = files.fileRequest(rootPath, connectionId, fileContext);
            try { view = files.openPublicFiles(read, rootPath, connectionId); }
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
                if (strictHostKeyChecking == 2) throw new VaultException("SSH_HOSTKEY_UNKNOWN", "This configuration requires an already trusted host");
                mailbox.requireApproved(review); check(); approved = review;
            });
            SshVaultAdapter trusted = new SshVaultAdapter(vault, (connection, target) -> {
                check();
                if (approved == null || !connectionId.equals(connection) || !profileTarget(target)
                        || !issued.role.binding().equals(approved.roleBinding)) throw denied();
            }, this::requireApprovedProposal);
            SshManagedKnownHostsProvider.SnapshotReceipt receipt = SshManagedKnownHostsProvider.snapshotFor(
                    trusted, issued.role, clock, rootPath, hostsPath, hostsId, hostsIdentity);
            trust = SshManagedKnownHostsProvider.bindForNativeHandoff(trusted, receipt);
            handoff = trust.nativeCandidateHandoff();
            SshCredentialBridge credential = new SshCredentialBridge(vault, authority, clock, password);
            SshManagedProvider provider = new SshManagedProvider(credential, clock, trust, trust,
                    (b, r, proof, kind) -> { throw denied(); });
            callback = SshNativeCallbackAdapter.bind(issued, registry, authority, clock, provider, handoff);
            SshAuthenticationSelection selection;
            if (selectedKey == null) selection = SshAuthenticationSelection.password(issued.role,
                    profile.username, useSaved ? saved.entryId : password.entryId, clock);
            else {
                byte[] blob = selectedKey.publicBlob();
                try { selection = SshAuthenticationSelection.privateKey(issued.role, profile.username,
                        selectedKey.path, selectedKey.identity, blob, clock); }
                finally { VaultCodec.wipe(blob); }
            }
            if (password != null) password.bind(issued.role, selection);
            callback.selectAuthentication(selection);
            SshSocketOwnership socket = tcp.connect(profile, transportDeadline, this::check);
            session = SshNativeSession.openManaged(callback, view, socket, hostsPath,
                    "candidate-" + UUID.randomUUID(), SshNativeSession.MAX_STEP_BUDGET);
            while (!session.step().complete) {
                if (clock.now() >= transportDeadline) throw new VaultException("SSH_TIMED_OUT", "SSH key exchange timed out");
                pause();
            }
            check(); session.beginUserauthService();
            while (!session.userauthServiceReady()) { session.step(); pause(); }
            check();
            if (selectedKey == null) {
                session.beginPasswordAuth();
                SshNativeSession.PasswordAuthResult result;
                while ((result = session.passwordAuthResult()) == SshNativeSession.PasswordAuthResult.PENDING) {
                    session.step(); pause();
                }
                if (result != SshNativeSession.PasswordAuthResult.SUCCESS)
                    throw new VaultException("SSH_AUTHENTICATION", "SSH password authentication failed");
            } else {
                session.beginPrivateKeyAuth();
                SshNativeSession.PrivateKeyAuthResult result;
                while ((result = session.privateKeyAuthResult()) == SshNativeSession.PrivateKeyAuthResult.PENDING) {
                    session.step(); pause();
                }
                if (result != SshNativeSession.PrivateKeyAuthResult.SUCCESS)
                    throw new VaultException("SSH_AUTHENTICATION", "SSH private key authentication failed");
            }
            check();
            reconnectTarget = new SshReconnectTarget(profile, privateKeyId, configAlias);
            session.retireAuthenticationWith(this::retireAuthentication);
            returned = true;
            return session;
        });
    }

    private void issueRole() throws VaultException {
        byte[] nonce = new byte[32], binding = new byte[32];
        SecureRandom random = new SecureRandom(); random.nextBytes(nonce); random.nextBytes(binding);
        try {
            SshRoleContext.Bootstrap bootstrap = new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                    Process.myPid(), 0, connectionId, targetIdentity, profile.host, profile.port,
                    "view-" + UUID.randomUUID(), nonce, binding, deadline);
            pendingRole = SshNativeRoleBootstrap.prepare(bootstrap);
            pendingRole.open();
            issued = pendingRole.issue(registry, view, clock);
            pendingRole = null;
        } finally { VaultCodec.wipe(nonce); VaultCodec.wipe(binding); }
    }

    private void prepareKey() throws Exception {
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, this::check);
        try (SshConnectionMaterial.Snapshot snapshot = vault.sshLibrarySnapshot(context)) {
            if (snapshot.epoch != expectedEpoch || snapshot.revision != initialRevision) throw changed();
            if (!configAlias.isEmpty() && !SshConnectionMaterial.configBinding(snapshot, configAlias).equals(expectedConfigBinding))
                throw changed();
            selectedKey = SshConnectionMaterial.key(snapshot.manifest, privateKeyId);
            if (configAlias.isEmpty()) {
                rootPath = SshConnectionMaterial.MANUAL;
                hostsPath = SshConnectionMaterial.HOSTS;
            } else {
                rootPath = SshConnectionMaterial.ROOT;
                SshConfigEvaluator.DirectPlan plan = SshConnectionMaterial.plan(snapshot.manifest, rootPath,
                        configAlias, usernameOverride, deadline, this::check);
                if (plan.userRequired) throw new VaultException("SSH_USER_REQUIRED", "Enter an SSH username");
                if (!plan.pubkeyAuthentication || !plan.identityFiles.contains(selectedKey.path))
                    throw new VaultException("SSH_KEY_SELECTION", "Choose an identity allowed by this SSH configuration");
                profile = new SshPasswordProfile(plan.hostname, plan.port, plan.user);
                strictHostKeyChecking = plan.strictHostKeyChecking;
                transportDeadline = Math.min(deadline, requestStarted + plan.connectTimeoutMillis);
                hostsPath = null;
                for (String path : plan.userKnownHostsFiles) {
                    VaultManifest.FileObject candidate = SshConnectionMaterial.file(snapshot.manifest, path);
                    if (candidate == null) continue;
                    if (candidate.type != VaultManifest.FileType.KNOWN_HOSTS || hostsPath != null)
                        throw new VaultException("SSH_CONFIG_UNSUPPORTED", "This connection cannot consume multiple host trust sources");
                    hostsPath = path;
                }
                if (hostsPath == null) throw new VaultException("SSH_CONFIG_DEPENDENCY", "The managed host trust file is missing");
            }
            SshConnectionMaterial.KeyAccount account = new SshConnectionMaterial.KeyAccount(profile, selectedKey);
            targetIdentity = account.target;
            try (SshBundleProposal proposal = SshConnectionMaterial.connectionProposal(snapshot.manifest,
                    profile, selectedKey, configAlias.isEmpty())) {
                if (proposal != null) {
                    SshVaultAdapter adapter = new SshVaultAdapter(vault, (c, t) -> { throw denied(); },
                            p -> { throw denied(); }, exact -> { check(); if (exact != proposal) throw denied(); });
                    VaultOperation operation = adapter.bundleRequest(proposal, context);
                    try { adapter.commitBundle(operation, proposal, this::check); durableWrite = true; }
                    finally { adapter.cancel(operation); }
                    expectedEpoch++;
                    check();
                    if (vault.contentRevision != initialRevision + 1) throw changed();
                }
            }
            if (hostsPath.equals(SshConnectionMaterial.HOSTS)) {
                hostsId = SshConnectionMaterial.id("hosts", hostsPath);
                hostsIdentity = SshConnectionMaterial.id("hosts-owner", hostsPath);
            } else {
                VaultManifest.FileObject hosts = SshConnectionMaterial.file(snapshot.manifest, hostsPath);
                if (hosts == null) throw changed();
                hostsId = hosts.id; hostsIdentity = hosts.identity;
            }
        } finally { vault.cancelBundleContext(context); }
    }

    private void requireApprovedProposal(SshTrustProposal proposal) throws VaultException {
        check();
        if (approved == null || !proposal.matchesRole(issued.role)
                || !proposal.matchesManagedObject(VaultManifest.FileType.KNOWN_HOSTS, hostsId,
                        hostsPath, hostsIdentity)
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
        AppSecrets.EffectGuard guard = sensitive;
        if (guard == null) throw new VaultException("SSH_CANCELLED", "SSH authentication has finished");
        guard.check();
        vault.requirePasswordValidationOrigin(origin);
        if (clock.now() >= deadline) throw new VaultException("SSH_TIMED_OUT", "The SSH connection timed out");
        if (view == null) vault.session.assertEpoch(expectedEpoch);
        else if (!view.active()) throw changed();
    }
    private boolean profileTarget(String target) { return targetIdentity.equals(target); }
    private static VaultException changed() { return new VaultException("VAULT_CHANGED", "The vault changed; try again"); }
    private static VaultException denied() { return new VaultException("SSH_HOST_KEY_REVIEW", "The exact host approval is required"); }

    private void retireAuthentication() throws VaultException {
        if (Thread.currentThread() != owner) throw new VaultException("SSH_SESSION_THREAD", "SSH cleanup requires its owner");
        if (cancelled.get() || hostCancelled.getAsBoolean())
            throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
        vault.cancelBundleContext(origin);
        if (password != null) password.close();
        if (saveContext != null) saveContext.cancel();
        synchronized (this) { VaultCodec.wipe(pendingSave); pendingSave = null; }
        if (handoff != null) { handoff.close(); handoff = null; }
        if (trust != null) { trust.close(); trust = null; }
        if (view != null) { view.close(); view = null; }
        mailbox = null;
        ticket = null;
        approved = null;
        selectedKey = null;
        sensitive = null;
        events = null;
    }

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
