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
    enum ContinuationMode { NONE, STRICT, AUTOMATIC }

    /** Freeze the allowed continuation separately from the user's automatic intent. */
    static final class ContinuationPolicy {
        final ContinuationMode mode;
        final boolean configured;
        private Boolean configuredCapability;

        ContinuationPolicy(ContinuationMode mode, boolean configured) {
            this.mode = java.util.Objects.requireNonNull(mode);
            this.configured = configured;
        }

        void acceptConfiguredCapability(boolean allowed) throws VaultException {
            if (mode == ContinuationMode.NONE) return;
            if (!configured) throw SshPasswordProfile.parameters();
            if (mode == ContinuationMode.STRICT && !allowed)
                throw new VaultException("SSH_MFA_POLICY", "This configuration does not allow the selected authentication sequence");
            if (mode == ContinuationMode.AUTOMATIC) {
                if (configuredCapability != null && configuredCapability != allowed) throw changed();
                configuredCapability = allowed;
            }
        }

        boolean enabled() {
            return mode == ContinuationMode.STRICT || mode == ContinuationMode.AUTOMATIC
                    && (!configured || Boolean.TRUE.equals(configuredCapability));
        }

        SshAuthenticationSelection select(SshAuthenticationSelection selection, SshRoleContext.Clock clock)
                throws VaultException {
            return enabled() ? selection.withKeyboardContinuation(clock) : selection;
        }
    }

    /** Preserve one effective policy across every protected configuration snapshot. */
    static final class ServerAlivePolicy {
        private boolean configured;
        private int interval;
        private int countMax = 3;

        void accept(SshConfigEvaluator.DirectPlan plan) throws VaultException {
            if (plan == null) throw SshPasswordProfile.parameters();
            if (configured && (interval != plan.serverAliveInterval || countMax != plan.serverAliveCountMax))
                throw changed();
            interval = plan.serverAliveInterval;
            countMax = plan.serverAliveCountMax;
            configured = true;
        }

        boolean configured() { return configured; }
        int interval() { return interval; }
        int countMax() { return countMax; }
    }

    /** Preserve the exact effective OpenSSH RequestTTY value across snapshots. */
    static final class TtyPolicy {
        private boolean configured;
        private int requestTty;

        void accept(SshConfigEvaluator.DirectPlan plan) throws VaultException {
            if (plan == null) throw SshPasswordProfile.parameters();
            if (configured && requestTty != plan.requestTty) throw changed();
            requestTty = plan.requestTty;
            configured = true;
        }

        boolean configured() { return configured; }
        boolean noPty() { return configured && requestTty == 1; }
    }

    /** Preserve ordered config methods across every protected connection snapshot. */
    static final class AuthenticationOrderPolicy {
        private boolean configured, known;
        private java.util.List<SshConfigEvaluator.AuthenticationMethod> order = java.util.Collections.emptyList();

        void accept(SshConfigEvaluator.DirectPlan plan) throws VaultException {
            if (plan == null) throw SshPasswordProfile.parameters();
            if (configured && (known != plan.authenticationOrderKnown
                    || !order.equals(plan.preferredAuthenticationOrder))) throw changed();
            known = plan.authenticationOrderKnown;
            order = plan.preferredAuthenticationOrder;
            configured = true;
        }

        boolean configured() { return configured; }
        boolean known() { return known; }
        java.util.List<SshConfigEvaluator.AuthenticationMethod> order() { return order; }
    }

    interface Events {
        void present(SshHostKeyReviewMailbox.Ticket ticket) throws VaultException;
        void dismiss(SshHostKeyReviewMailbox.Ticket ticket);
        default void present(SshKeyboardMailbox.Ticket ticket) throws VaultException { throw SshKeyboardChallenge.failure(); }
        default void dismiss(SshKeyboardMailbox.Ticket ticket) { }
        default void present(SshPasswordMailbox.Ticket ticket) throws VaultException { throw SshAuthenticationSelection.invalid(); }
        default void dismiss(SshPasswordMailbox.Ticket ticket) { }
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
    private SshTransientPassword password;
    private SshSavedPassword saved;
    private final boolean useSaved;
    private final boolean keyboardInteractive;
    private boolean serverDrivenAuthentication;
    private AppSecrets.SshEntryMutationContext saveContext;
    private volatile AppSecrets.BundleContext automaticSaveOrigin;
    private volatile boolean automaticSaveScheduled, automaticCredentialWritten;
    private ContinuationPolicy continuationPolicy = new ContinuationPolicy(ContinuationMode.NONE, false);
    private final ServerAlivePolicy serverAlivePolicy = new ServerAlivePolicy();
    private final TtyPolicy ttyPolicy = new TtyPolicy();
    private final AuthenticationOrderPolicy authenticationOrderPolicy = new AuthenticationOrderPolicy();
    private boolean configuredPassword, saveConfigPassword;
    private byte[] pendingConfigPassword;
    private final AppSecrets.BundleContext origin;
    private final String privateKeyId, configAlias, usernameOverride, expectedConfigBinding;
    private SshConnectionMaterial.Key selectedKey;
    private boolean useSavedKeyPassphrase, saveKeyPassphrase;
    private byte[] pendingKeyPassphrase;
    private SshTransientKey transientKey;
    private AppSecrets.SshEntryMutationContext keyPassphraseMutation;
    private long expectedKeyRevision;
    private String rootPath = SshPasswordProfile.CONFIG_PATH;
    private String hostsPath = SshPasswordProfile.HOSTS_PATH;
    private String hostsId = SshPasswordProfile.HOSTS_ID;
    private String hostsIdentity = SshPasswordProfile.HOSTS_IDENTITY;
    private String targetIdentity;
    private int strictHostKeyChecking = 3;
    private long transportDeadline;
    private boolean jumpTransportActive;
    private byte[] pendingSave;
    private byte[] automaticPasswordSave, automaticPhraseSave;
    private long expectedEpoch;
    private final long initialRevision;
    private volatile boolean started;
    private boolean retiredBeforeStart;
    private volatile boolean durableWrite;
    private final TrustCommitState trustCommit = new TrustCommitState();
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
    private volatile SshKeyboardMailbox keyboardMailbox;
    private volatile SshPasswordMailbox passwordMailbox;
    private SshHostKeyAuthority.Review approved;
    private SshHostKeyAuthority.ChangedReview approvedChanged;
    private volatile boolean reviewingChanged;
    private SshNativeSession session;
    private boolean returned;
    private SshReconnectTarget reconnectTarget;
    private volatile SshSharedPublicView sharedView;
    private java.util.List<JumpHop> jumpHops;

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile profile, byte[] bytes,
            long deadline, AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        this(vault, profile, bytes, false, false, deadline, sensitive, events);
    }

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile profile, byte[] bytes, boolean useSavedPassword,
            boolean savePassword, long deadline, AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        this(vault, profile, bytes, useSavedPassword, savePassword, deadline, sensitive, events,
                SystemClock::elapsedRealtime);
    }

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile profile, byte[] bytes, boolean useSavedPassword,
            boolean savePassword, long deadline, AppSecrets.EffectGuard sensitive, Events events,
            SshRoleContext.Clock requestClock) throws VaultException {
        this.vault = vault; this.profile = profile; this.deadline = deadline;
        this.sensitive = sensitive; this.events = events; clock = requestClock; requestStarted = clock.now();
        expectedEpoch = vault.session.epoch(); initialRevision = vault.contentRevision;
        tcp = new SshTcpConnector();
        saved = new SshSavedPassword(profile);
        privateKeyId = null; configAlias = ""; usernameOverride = ""; expectedConfigBinding = "";
        origin = vault.newBundleContext(deadline, this::check);
        useSaved = useSavedPassword || savePassword;
        keyboardInteractive = false;
        targetIdentity = useSaved ? saved.target : SshPasswordProfile.TARGET;
        transportDeadline = deadline;
        if (useSavedPassword && (savePassword || bytes.length != 0)) throw SshPasswordProfile.parameters();
        if (!useSavedPassword) SshSavedPassword.validateSecret(bytes);
        saveContext = savePassword ? vault.newPasswordMutationContext(expectedEpoch, deadline, this::check, profile, false) : null;
        try {
            pendingSave = savePassword ? bytes.clone() : null;
            password = useSaved ? null : new SshTransientPassword(vault, profile, bytes, deadline, this::check, clock);
            check();
        } catch (VaultException | RuntimeException | Error failure) {
            retireIfUnstarted();
            throw failure;
        }
    }

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile manual, String privateKeyId,
            String configAlias, String configBinding, String usernameOverride, long deadline,
            AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        this(vault, manual, privateKeyId, configAlias, configBinding, usernameOverride,
                new byte[0], false, false, deadline, sensitive, events);
    }

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile manual, String privateKeyId,
            String configAlias, String configBinding, String usernameOverride, byte[] passphrase,
            boolean useSavedPassphrase, boolean savePassphrase, long deadline,
            AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        this(vault, manual, privateKeyId, configAlias, configBinding, usernameOverride, passphrase,
                useSavedPassphrase, savePassphrase, deadline, sensitive, events, SystemClock::elapsedRealtime);
    }

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile manual, String privateKeyId,
            String configAlias, String configBinding, String usernameOverride, byte[] passphrase,
            boolean useSavedPassphrase, boolean savePassphrase, long deadline,
            AppSecrets.EffectGuard sensitive, Events events, SshRoleContext.Clock requestClock) throws VaultException {
        VaultManifest.identifier(privateKeyId);
        if (configAlias == null || usernameOverride == null || manual == null && configAlias.isEmpty())
            throw SshPasswordProfile.parameters();
        this.vault = vault; profile = manual; this.privateKeyId = privateKeyId;
        this.configAlias = configAlias; this.expectedConfigBinding = configBinding; this.usernameOverride = usernameOverride;
        this.deadline = deadline; this.sensitive = sensitive; this.events = events;
        clock = requestClock; requestStarted = clock.now(); expectedEpoch = vault.session.epoch();
        initialRevision = vault.contentRevision; tcp = new SshTcpConnector();
        origin = vault.newBundleContext(deadline, this::check);
        saved = null; useSaved = false; saveContext = null; password = null;
        keyboardInteractive = false;
        if (passphrase == null || useSavedPassphrase && (savePassphrase || passphrase.length != 0))
            throw SshPasswordProfile.parameters();
        if (passphrase.length != 0) SshSavedPassword.validateSecret(passphrase);
        pendingKeyPassphrase = passphrase.clone();
        useSavedKeyPassphrase = useSavedPassphrase; saveKeyPassphrase = savePassphrase;
        expectedKeyRevision = initialRevision;
        transportDeadline = deadline;
        try { check(); }
        catch (VaultException | RuntimeException | Error failure) { retireIfUnstarted(); throw failure; }
    }

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile manual, String configAlias,
            String configBinding, String usernameOverride, long deadline,
            AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        this(vault, manual, configAlias, configBinding, usernameOverride, deadline,
                sensitive, events, SystemClock::elapsedRealtime);
    }

    SshPasswordConnector(AppSecrets vault, SshPasswordProfile manual, String configAlias,
            String configBinding, String usernameOverride, long deadline,
            AppSecrets.EffectGuard sensitive, Events events, SshRoleContext.Clock requestClock) throws VaultException {
        if (configAlias == null || configBinding == null || usernameOverride == null
                || manual == null && configAlias.isEmpty() || manual != null && !configAlias.isEmpty())
            throw SshPasswordProfile.parameters();
        this.vault = vault; profile = manual; privateKeyId = null;
        this.configAlias = configAlias; expectedConfigBinding = configBinding; this.usernameOverride = usernameOverride;
        this.deadline = deadline; this.sensitive = sensitive; this.events = events;
        clock = requestClock; requestStarted = clock.now(); expectedEpoch = vault.session.epoch();
        initialRevision = vault.contentRevision; tcp = new SshTcpConnector();
        origin = vault.newBundleContext(deadline, this::check);
        saved = null; useSaved = false; saveContext = null; password = null; keyboardInteractive = true;
        transportDeadline = deadline;
        targetIdentity = "target:ctos-keyboard-" + UUID.randomUUID();
        try { check(); }
        catch (VaultException | RuntimeException | Error failure) { retireIfUnstarted(); throw failure; }
    }

    boolean started() { return started; }

    /** Retire only constructor-owned Java material if create has not entered. */
    synchronized boolean retireIfUnstarted() {
        if (started) return false;
        retiredBeforeStart = true;
        cancelled.set(true);
        wipePendingMaterialsLocked();
        if (password != null) { password.close(); password = null; }
        if (transientKey != null) { transientKey.close(); transientKey = null; }
        return true;
    }

    private void wipePendingMaterialsLocked() {
        VaultCodec.wipe(pendingSave); pendingSave = null;
        VaultCodec.wipe(pendingKeyPassphrase); pendingKeyPassphrase = null;
        VaultCodec.wipe(pendingConfigPassword); pendingConfigPassword = null;
        VaultCodec.wipe(automaticPasswordSave); automaticPasswordSave = null;
        VaultCodec.wipe(automaticPhraseSave); automaticPhraseSave = null;
    }

    void useServerDrivenAuthentication() throws VaultException {
        if (started || serverDrivenAuthentication || !keyboardInteractive && privateKeyId == null
                || continuationPolicy.mode != ContinuationMode.NONE) throw SshPasswordProfile.parameters();
        serverDrivenAuthentication = true;
    }
    void allowKeyboardContinuation(boolean enabled) throws VaultException {
        setContinuationMode(enabled ? ContinuationMode.STRICT : ContinuationMode.NONE);
    }

    void setContinuationMode(ContinuationMode mode) throws VaultException {
        if (mode == null || started || keyboardInteractive && mode != ContinuationMode.NONE)
            throw SshPasswordProfile.parameters();
        continuationPolicy = new ContinuationPolicy(mode, !configAlias.isEmpty());
    }

    SshPasswordConnector(AppSecrets vault, String configAlias, String configBinding, String usernameOverride,
            byte[] bytes, boolean useSavedPassword, boolean savePassword, long deadline,
            AppSecrets.EffectGuard sensitive, Events events) throws VaultException {
        this(vault, configAlias, configBinding, usernameOverride, bytes, useSavedPassword, savePassword,
                deadline, sensitive, events, SystemClock::elapsedRealtime);
    }

    SshPasswordConnector(AppSecrets vault, String configAlias, String configBinding, String usernameOverride,
            byte[] bytes, boolean useSavedPassword, boolean savePassword, long deadline,
            AppSecrets.EffectGuard sensitive, Events events, SshRoleContext.Clock requestClock) throws VaultException {
        if (configAlias == null || configAlias.isEmpty() || configBinding == null || usernameOverride == null
                || bytes == null || useSavedPassword && (savePassword || bytes.length != 0))
            throw SshPasswordProfile.parameters();
        if (!useSavedPassword) SshSavedPassword.validateSecret(bytes);
        this.vault = vault; privateKeyId = null; this.configAlias = configAlias;
        expectedConfigBinding = configBinding; this.usernameOverride = usernameOverride;
        this.deadline = deadline; this.sensitive = sensitive; this.events = events;
        clock = requestClock; requestStarted = clock.now(); expectedEpoch = vault.session.epoch();
        initialRevision = vault.contentRevision; tcp = new SshTcpConnector();
        origin = vault.newBundleContext(deadline, this::check);
        useSaved = useSavedPassword || savePassword; keyboardInteractive = false;
        configuredPassword = true; saveConfigPassword = savePassword;
        pendingConfigPassword = bytes.clone(); transportDeadline = deadline;
        try { check(); }
        catch (VaultException | RuntimeException | Error failure) { retireIfUnstarted(); throw failure; }
    }
    boolean durableWrite() { return durableWrite; }
    @Override public boolean tracksCredentialSave() { return automaticSaveScheduled; }
    @Override public boolean mayWriteTrust() { return true; }
    @Override public SshSessionOwner.Cancellation trustCancellation() { return trustCommit.cancellation; }
    boolean automaticCredentialWritten() { return automaticCredentialWritten; }
    /** Each provider has one receipt; the connection retains all unresolved receipts. */
    static final class TrustCommitState implements SshManagedKnownHostsProvider.TrustCommitObserver {
        private static final int UNWRITTEN = 0, PENDING = 1, CONFIRMED = 2, INVALID = 3;
        private static final int CHANGED = 1, NEW = 2;
        private static final class Ledger {
            final SshSessionOwner.Cancellation cancellation = new SshSessionOwner.Cancellation();
            final java.util.List<TrustCommitState> receipts = new java.util.ArrayList<>();
            Thread owner;
        }
        private final Ledger ledger;
        final SshSessionOwner.Cancellation cancellation;
        private final SshSessionOwner.Cancellation.TrustReceipt receipt;
        private final TrustCommitState previous;
        private final SshNativeSession.JumpAdmission admission;
        private volatile SshNativeRoleBootstrap.Issued issued;
        private SshManagedKnownHostsProvider boundProvider;
        private volatile int phase = UNWRITTEN;
        private int kind;
        private boolean childPrepared;

        TrustCommitState() {
            ledger = new Ledger();
            cancellation = ledger.cancellation;
            receipt = cancellation.trustReceipt(this);
            previous = null;
            admission = null;
            ledger.receipts.add(this);
        }

        private TrustCommitState(Ledger ledger, SshNativeRoleBootstrap.Issued issued,
                TrustCommitState previous, SshNativeSession.JumpAdmission admission) {
            this.ledger = ledger;
            cancellation = ledger.cancellation;
            receipt = cancellation.trustReceipt(this);
            this.issued = issued;
            this.previous = previous;
            this.admission = admission;
        }

        /** The direct connector binds the observer to its actual issued native role. */
        void bindIssued(SshNativeRoleBootstrap.Issued actual) throws VaultException {
            if (actual == null || actual.token <= 0 || actual.registry == null || actual.role == null)
                throw roleMismatch();
            actual.registry.requireOwned(actual.role);
            synchronized (ledger) {
                if (ledger.receipts.get(0) != this || ledger.receipts.size() != 1
                        || issued != null || actual.role.hop != 0 || phase != UNWRITTEN)
                    throw roleMismatch();
                ledger.owner = Thread.currentThread();
                issued = actual;
            }
        }

        /** Only an exactly bound provider can report this hop's commit and confirmation. */
        @Override public void requireRole(SshRoleContext actual) throws VaultException {
            SshNativeRoleBootstrap.Issued bound = issued;
            if (bound == null || bound.role != actual) throw roleMismatch();
            bound.registry.requireOwned(actual);
        }

        /** A role's one-shot receipt cannot be installed in a second provider. */
        @Override public void bindProvider(SshManagedKnownHostsProvider exact, SshRoleContext actual)
                throws VaultException {
            if (exact == null || !exact.hasCommitObserver(this)) throw roleMismatch();
            requireRole(actual);
            if (previous != null && !previous.providerFor(previous.issued).sharesVault(exact))
                throw roleMismatch();
            synchronized (this) {
                if (ledger.owner != Thread.currentThread() || issued == null || issued.role != actual
                        || boundProvider != null || phase != UNWRITTEN) throw roleMismatch();
                boundProvider = exact;
            }
        }

        /** Construct the next observer only for this owner's adjacent, same-view role. */
        TrustCommitState forNextHop(SshNativeRoleBootstrap.Issued next,
                SshRoleContext.PublicView view, SshNativeSession.JumpAdmission admission) throws VaultException {
            if (admission == null) throw roleMismatch();
            synchronized (ledger) { requireNextSlot(); }
            admission.requireTrustParent(this, issued);
            return issueNextHop(next, view, admission);
        }

        /** Old ledger tests have no parent provider; this cannot issue a production chain. */
        TrustCommitState forNextHopForFixture(SshNativeRoleBootstrap.Issued next,
                SshRoleContext.PublicView view, SshNativeSession.JumpAdmission admission) throws VaultException {
            if (admission == null) throw roleMismatch();
            admission.requireFixture();
            return issueNextHop(next, view, admission);
        }

        private TrustCommitState issueNextHop(SshNativeRoleBootstrap.Issued next,
                SshRoleContext.PublicView view, SshNativeSession.JumpAdmission admission) throws VaultException {
            SshNativeRoleBootstrap.Issued previous = issued;
            if (previous == null || next == null || view == null || admission == null || next.token <= 0
                    || next.registry != previous.registry || next.role == null
                    || previous.ownerUid != next.ownerUid || previous.token == next.token)
                throw roleMismatch();
            previous.registry.requireOwned(previous.role);
            next.registry.requireOwned(next.role);
            if (!previous.role.ownsPublicView(view) || !next.role.ownsPublicView(view)) throw roleMismatch();
            byte[] previousBinding = previous.role.connectionBinding(), nextBinding = next.role.connectionBinding();
            try {
                if (previous.role.program != SshRoleContext.Program.JUMP
                        || previous.role.identityVersion != SshRoleContext.IDENTITY_V2
                        || next.role.identityVersion != previous.role.identityVersion
                        || next.role.hop != previous.role.hop + 1
                        || next.role.ownerPid != previous.role.ownerPid
                        || next.role.deadline != previous.role.deadline
                        || !next.role.connectionId.equals(previous.role.connectionId)
                        || !next.role.chainIdentity.equals(previous.role.chainIdentity)
                        || !MessageDigest.isEqual(previousBinding, nextBinding)) throw roleMismatch();
            } finally { VaultCodec.wipe(previousBinding); VaultCodec.wipe(nextBinding); }
            synchronized (ledger) {
                requireNextSlot();
            }
            // The admission checks session/callback/view liveness; do not hold the ledger lock.
            admission.consume(previous, next, view);
            synchronized (ledger) {
                requireNextSlot();
                TrustCommitState result = new TrustCommitState(ledger, next, this, admission);
                ledger.receipts.add(result);
                return result;
            }
        }

        synchronized SshManagedKnownHostsProvider providerFor(SshNativeRoleBootstrap.Issued actual)
                throws VaultException {
            if (ledger.owner != Thread.currentThread() || actual == null || issued != actual
                    || boundProvider == null) throw roleMismatch();
            return boundProvider;
        }

        /** Checks assembly identity only; it does not authorize a trust write or a secret copy. */
        void requireChildAssembly(SshNativeSession.JumpAdmission actualAdmission,
                SshNativeCallbackAdapter parentCallback, SshNativeCallbackAdapter childCallback,
                SshManagedKnownHostsProvider.ChildTrustSnapshot source, String path) throws VaultException {
            synchronized (ledger) { requireAssemblySlot(actualAdmission); }
            SshNativeRoleBootstrap.Issued current = issued;
            SshNativeRoleBootstrap.Issued parentIssued = previous.issued;
            SshManagedKnownHostsProvider parentProvider = previous.providerFor(parentIssued);
            SshManagedKnownHostsProvider childProvider = providerFor(current);
            if (parentCallback == null || childCallback == null || source == null
                    || !parentCallback.ownsIssued(parentIssued) || !childCallback.ownsIssued(current)
                    || !parentCallback.ownsTrustProvider(parentProvider)
                    || !childCallback.ownsTrustProvider(childProvider)
                    || !parentProvider.sharesVault(childProvider)
                    || !childProvider.ownsChildSnapshot(source)) throw roleMismatch();
            source.requireFor(current.role, path);
            synchronized (ledger) { requireAssemblySlot(actualAdmission); }
        }

        void requireChildAssemblySlot(SshNativeSession.JumpAdmission actualAdmission) throws VaultException {
            synchronized (ledger) { requireAssemblySlot(actualAdmission); }
        }

        void claimChildAssembly(SshNativeSession.JumpAdmission actualAdmission,
                SshNativeCallbackAdapter parentCallback, SshNativeCallbackAdapter childCallback,
                SshManagedKnownHostsProvider.ChildTrustSnapshot source, String path) throws VaultException {
            requireChildAssembly(actualAdmission, parentCallback, childCallback, source, path);
            synchronized (ledger) {
                requireAssemblySlot(actualAdmission);
                childPrepared = true;
            }
        }

        /** Caller holds ledger. The receipt and admission are one-shot for this child. */
        private void requireAssemblySlot(SshNativeSession.JumpAdmission actualAdmission) throws VaultException {
            int count = ledger.receipts.size();
            if (ledger.owner != Thread.currentThread() || previous == null || admission == null
                    || admission != actualAdmission || previous.ledger != ledger || count < 2
                    || ledger.receipts.get(count - 1) != this || ledger.receipts.get(count - 2) != previous
                    || childPrepared) throw roleMismatch();
            if (publicationUncertain()) throw uncertain();
            if (cancellation.getAsBoolean()) throw new VaultException("SSH_CANCELLED", "SSH connection ended");
        }

        /** Caller holds ledger. Cancellation after token consumption ends this chain. */
        private void requireNextSlot() throws VaultException {
            if (ledger.owner != Thread.currentThread()
                    || ledger.receipts.get(ledger.receipts.size() - 1) != this
                    || ledger.receipts.size() >= SshRoleContext.MAX_HOPS) throw roleMismatch();
            if (publicationUncertain()) throw uncertain();
            if (cancellation.getAsBoolean()) throw new VaultException("SSH_CANCELLED", "SSH connection ended");
        }

        @Override public void persisted() { markWritten(CHANGED); }
        @Override public void newPersisted() { markWritten(NEW); }
        @Override public void proofPublished() throws VaultException { confirm(CHANGED); }
        @Override public void newProofPublished() throws VaultException { confirm(NEW); }

        private synchronized void markWritten(int writtenKind) {
            if (issued != null && ledger.owner != Thread.currentThread()) {
                phase = INVALID;
                return;
            }
            if (phase == UNWRITTEN) { kind = writtenKind; phase = PENDING; return; }
            // The provider may repeat the same notification on its uncertain-return path.
            if (phase == PENDING && kind == writtenKind) return;
            // Reporting a committed write must not throw past the provider's uncertainty cleanup.
            phase = INVALID;
        }

        private synchronized void confirm(int expectedKind) throws VaultException {
            if (issued != null && ledger.owner != Thread.currentThread()
                    || phase != PENDING || kind != expectedKind || !cancellation.confirmTrust(receipt, this))
                throw uncertain();
            phase = CONFIRMED;
        }

        boolean written() {
            synchronized (ledger) {
                for (TrustCommitState value : ledger.receipts) if (value.phase != UNWRITTEN) return true;
                return false;
            }
        }
        boolean publicationUncertain() {
            synchronized (ledger) {
                for (TrustCommitState value : ledger.receipts)
                    if (value.phase == PENDING || value.phase == INVALID) return true;
                return false;
            }
        }
        private static VaultException roleMismatch() {
            return new VaultException("SSH_ROLE_REQUIRED", "The trust receipt does not match this SSH role");
        }
        private static VaultException uncertain() {
            return new VaultException("VAULT_COMMIT_UNCERTAIN", "The host trust result was not confirmed");
        }
    }
    boolean trustPublicationUncertain() { return trustCommit.publicationUncertain(); }
    private boolean trackedWrite() { return durableWrite || trustCommit.written(); }

    /** Transfer the caller's receipt to the provider, or wipe it if binding fails. */
    static SshManagedKnownHostsProvider bindOwnedTrustReceipt(SshVaultAdapter adapter,
            SshManagedKnownHostsProvider.SnapshotReceipt receipt,
            SshManagedKnownHostsProvider.TrustCommitObserver observer) throws VaultException {
        try {
            return SshManagedKnownHostsProvider.bindForNativeHandoff(adapter, receipt, observer);
        } catch (VaultException | RuntimeException | Error failure) {
            if (receipt != null) {
                try { receipt.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
    @Override public SshReconnectTarget reconnectTarget() { return reconnectTarget; }
    boolean handoffLive() {
        return !cancelled.get() && session != null && session.shellOutputPublicationLive()
                && trustCommit.cancellation.admitOutput();
    }
    @Override public void cancellationAction(Runnable action) {
        cancellationAction = action;
        if (cancelled.get()) action.run();
    }
    void cancel() {
        try {
            try { trustCommit.cancellation.cancel(); }
            catch (RuntimeException | Error failure) {
                try { retireIfUnstarted(); }
                catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
                throw failure;
            }
            cancelled.set(true);
            SshSharedPublicView shared = sharedView;
            if (shared != null) shared.cancel();
            SshTransientPassword currentPassword;
            SshTransientKey currentKey;
            AppSecrets.BundleContext currentAutomaticSave;
            AppSecrets.SshEntryMutationContext currentPasswordMutation, currentKeyMutation;
            synchronized (this) {
                currentPassword = password; currentKey = transientKey;
                currentAutomaticSave = automaticSaveOrigin;
                currentPasswordMutation = saveContext; currentKeyMutation = keyPassphraseMutation;
            }
            vault.cancelBundleContext(origin);
            vault.cancelBundleContext(currentAutomaticSave);
            if (currentPassword != null) currentPassword.close();
            if (currentKey != null) currentKey.close();
            if (currentKeyMutation != null) currentKeyMutation.cancel();
            if (currentPasswordMutation != null) currentPasswordMutation.cancel();
            cleanupQueuedMaterialAndPrompts();
            Runnable action = cancellationAction;
            if (action != null) action.run();
        } catch (RuntimeException | Error failure) {
            try { cleanupQueuedMaterialAndPrompts(); }
            catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void cleanupQueuedMaterialAndPrompts() {
        synchronized (this) { wipePendingMaterialsLocked(); }
        cancelPromptMailboxes();
    }

    private void cancelPromptMailboxes() {
        SshHostKeyReviewMailbox current = mailbox;
        if (current != null) current.cancel();
        SshKeyboardMailbox keyboard = keyboardMailbox;
        if (keyboard != null) keyboard.cancel();
        SshPasswordMailbox dynamicPassword = passwordMailbox;
        if (dynamicPassword != null) dynamicPassword.cancel();
    }
    boolean decide(String id, String binding, boolean accept) {
        SshHostKeyReviewMailbox current = mailbox;
        SshHostKeyReviewMailbox.Ticket pending = ticket;
        return current != null && pending != null && pending.id.equals(id) && pending.binding.equals(binding)
                && current.resolve(pending, accept);
    }

    boolean hostKeyPending(SshHostKeyReviewMailbox.Ticket pending) throws VaultException {
        SshHostKeyReviewMailbox current = mailbox;
        return hostKeyPending(pending, current, this::check, this::checkTransport)
                && mailbox == current && ticket == pending;
    }

    static boolean hostKeyPending(SshHostKeyReviewMailbox.Ticket pending, SshHostKeyReviewMailbox current,
            AppSecrets.EffectGuard live, AppSecrets.EffectGuard transport) throws VaultException {
        if (pending == null) return false;
        if (pending.changed) transport.check(); else live.check();
        return current != null && current.pending(pending);
    }

    boolean respondKeyboard(String id, String binding, java.util.List<?> answers) throws VaultException {
        SshKeyboardMailbox current = keyboardMailbox;
        return current != null && current.respond(id, binding, answers);
    }

    boolean keyboardPending(SshKeyboardMailbox.Ticket pending) throws VaultException {
        check();
        SshKeyboardMailbox current = keyboardMailbox;
        return current != null && current.pending(pending);
    }

    boolean respondPassword(String id, String binding, byte[] password, SshPasswordMailbox.Choice choice)
            throws VaultException {
        SshPasswordMailbox current = passwordMailbox;
        return current != null && current.respond(id, binding, password, choice);
    }

    boolean passwordPending(SshPasswordMailbox.Ticket pending) throws VaultException {
        check();
        SshPasswordMailbox current = passwordMailbox;
        return current != null && current.pending(pending);
    }

    @Override public SshNativeSession create(BooleanSupplier hostCancelled) throws Exception {
        synchronized (this) {
            if (retiredBeforeStart) throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
            if (started) throw new VaultException("BUSY", "The SSH connection was already started");
            if (!trustCommit.cancellation.admitFactoryStart()) {
                retireIfUnstarted();
                throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
            }
            owner = Thread.currentThread(); started = true; this.hostCancelled = hostCancelled;
        }
        return SshPasswordManifestValidator.within(deadline, () -> {
            check();
            if (vault.contentRevision != initialRevision) throw changed();
            java.util.List<JumpHop> route = prepareJumpRoute();
            if (route != null) {
                try { return createJumpConnection(route); }
                catch (VaultException failure) {
                    throw jumpTrustDiagnostic(failure, () -> cancelled.get() || this.hostCancelled.getAsBoolean(),
                            trustCommit::publicationUncertain,
                            () -> checkJumpDiagnosticLive(jumpTransportActive, clock, transportDeadline, this::check),
                            this::reportableJumpTrustRefusal);
                }
            }
            if (configuredPassword) prepareConfigPassword();
            else if (keyboardInteractive && !configAlias.isEmpty()) prepareKeyboardConfig();
            else if (privateKeyId != null) prepareKey();
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
                if (reviewingChanged) checkTransport();
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
            }, trustCommit.cancellation);
            SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
                if (strictHostKeyChecking == 2) throw new VaultException("SSH_HOSTKEY_UNKNOWN", "This configuration requires an already trusted host");
                mailbox.requireApproved(review); check(); approved = review;
            }, review -> {
                if (strictHostKeyChecking == 2) throw new VaultException("SSH_HOST_CHANGED_POLICY", "This configuration requires an already trusted host");
                checkTransport();
                reviewingChanged = true;
                try { mailbox.requireChangedApproved(review, transportDeadline); checkTransport(); approvedChanged = review; }
                finally { reviewingChanged = false; }
            });
            SshVaultAdapter trusted = new SshVaultAdapter(vault, (connection, target) -> {
                check();
                if (approved == null || !connectionId.equals(connection) || !profileTarget(target)
                        || !issued.role.binding().equals(approved.roleBinding)) throw denied();
            }, this::requireApprovedProposal, proposal -> { throw denied(); }, (review, proposal) -> {
                checkTransport();
                if (approvedChanged != review || !proposal.matchesManagedObject(
                        VaultManifest.FileType.KNOWN_HOSTS, hostsId, hostsPath, hostsIdentity)) throw denied();
            });
            SshManagedKnownHostsProvider.SnapshotReceipt receipt = SshManagedKnownHostsProvider.snapshotFor(
                    trusted, issued.role, clock, rootPath, hostsPath, hostsId, hostsIdentity);
            trust = bindOwnedTrustReceipt(trusted, receipt, trustCommit);
            handoff = trust.nativeCandidateHandoff();
            SshCredentialBridge credential = new SshCredentialBridge(vault, authority, clock, password, transientKey);
            SshManagedProvider provider = new SshManagedProvider(credential, clock, trust, trust,
                    (b, r, proof, kind) -> { throw denied(); });
            callback = SshNativeCallbackAdapter.bind(issued, registry, authority, clock, provider, handoff,
                    trustCommit.cancellation);
            SshAuthenticationSelection selection;
            if (keyboardInteractive) selection = SshAuthenticationSelection.keyboardInteractive(issued.role, profile.username, clock);
            else if (selectedKey == null) selection = SshAuthenticationSelection.password(issued.role,
                    profile.username, useSaved ? saved.entryId : password.entryId, clock);
            else {
                byte[] blob = selectedKey.publicBlob();
                try { selection = SshAuthenticationSelection.privateKey(issued.role, profile.username,
                        selectedKey.path, selectedKey.identity, blob,
                        transientKey != null ? transientKey.entryId
                                : useSavedKeyPassphrase ? selectedKey.binding.passphraseEntryId : "", clock); }
                finally { VaultCodec.wipe(blob); }
            }
            java.util.List<SshConfigEvaluator.AuthenticationMethod> automaticOrder = serverDrivenAuthentication
                    ? automaticOrder() : java.util.Collections.emptyList();
            selection = serverDrivenAuthentication
                    ? selection.withAutomaticDiscovery(automaticMethodMask(automaticOrder, selectedKey != null), clock)
                    : continuationPolicy.select(selection, clock);
            if (password != null) password.bind(issued.role, selection);
            if (transientKey != null) transientKey.bind(issued.role, selection);
            callback.selectAuthentication(selection);
            SshSocketOwnership socket = tcp.connect(profile, transportDeadline, this::check);
            session = SshNativeSession.openManaged(callback, view, socket, hostsPath,
                    "candidate-" + UUID.randomUUID(), SshNativeSession.MAX_STEP_BUDGET);
            while (!keyExchangeStep(session, callback, this::checkTransport,
                    this::trackedWrite).complete) {
                if (clock.now() >= transportDeadline) throw new VaultException("SSH_TIMED_OUT", "SSH key exchange timed out");
                pause();
            }
            boolean trustCompletionPending = trustPublicationUncertain();
            if (trustCompletionPending) check();
            requireChangedCompletionDeadline(trustCompletionPending, clock, transportDeadline);
            callback.confirmChangedNativeCompletion();
            callback.confirmNewNativeCompletion();
            if (trustPublicationUncertain())
                throw new VaultException("VAULT_COMMIT_UNCERTAIN", "The host trust result was not confirmed");
            check(); session.beginUserauthService();
            while (!session.userauthServiceReady()) { session.step(); pause(); }
            check();
            boolean partial = false;
            if (serverDrivenAuthentication) {
                completeServerDrivenAuthentication(automaticOrder, selection);
            } else if (keyboardInteractive) {
                session.beginKeyboardInteractiveAuth();
                completeKeyboardAuthentication(selection);
            } else if (selectedKey == null) {
                beginFirstFactor(session, callback, false, this::check,
                        this::trackedWrite);
                SshNativeSession.PasswordAuthResult result;
                while ((result = session.passwordAuthResult()) == SshNativeSession.PasswordAuthResult.PENDING) {
                    session.step(); pause();
                }
                partial = firstFactorNeedsContinuation(result == SshNativeSession.PasswordAuthResult.SUCCESS,
                        result == SshNativeSession.PasswordAuthResult.PARTIAL, continuationPolicy.enabled());
            } else {
                beginFirstFactor(session, callback, true, this::check,
                        this::trackedWrite);
                SshNativeSession.PrivateKeyAuthResult result;
                while ((result = session.privateKeyAuthResult()) == SshNativeSession.PrivateKeyAuthResult.PENDING) {
                    session.step(); pause();
                }
                partial = firstFactorNeedsContinuation(result == SshNativeSession.PrivateKeyAuthResult.SUCCESS,
                        result == SshNativeSession.PrivateKeyAuthResult.PARTIAL, continuationPolicy.enabled());
            }
            if (partial && continuationPolicy.enabled()) {
                session.continueKeyboardInteractiveAuth();
                completeKeyboardAuthentication(selection);
            }
            check();
            if (!configAlias.isEmpty() && (!serverAlivePolicy.configured() || !ttyPolicy.configured()
                    || !authenticationOrderPolicy.configured())) throw changed();
            if (serverAlivePolicy.interval() != 0)
                session.configureServerAlive(serverAlivePolicy.interval(), serverAlivePolicy.countMax());
            if (ttyPolicy.noPty()) session.configureNoPty();
            reconnectTarget = new SshReconnectTarget(profile, privateKeyId, configAlias, keyboardInteractive,
                    continuationPolicy.mode == ContinuationMode.STRICT,
                    continuationPolicy.mode == ContinuationMode.AUTOMATIC, serverDrivenAuthentication);
            boolean hasAutomaticSave;
            synchronized (this) { hasAutomaticSave = automaticPasswordSave != null || automaticPhraseSave != null; }
            if (hasAutomaticSave) prepareAutomaticSave();
            session.retireAuthenticationWith(this::retireAuthentication);
            returned = true;
            return session;
        });
    }

    /** One frozen endpoint and the owners created for it, in travel order. */
    private static final class JumpHop {
        final SshConfigEvaluator.ProfilePlan plan;
        final SshPasswordProfile profile;
        final SshSavedPassword saved;
        final boolean savedPresent;
        final String hostsPath, hostsId, hostsIdentity;
        SshNativeRoleBootstrap.Unissued pending;
        SshNativeRoleBootstrap.Issued issued;
        TrustCommitState commit;
        SshManagedKnownHostsProvider.ChildTrustSnapshot source;
        SshManagedKnownHostsProvider trust;
        SshKnownHostsCandidateHandoff handoff;
        SshNativeCallbackAdapter callback;
        SshAuthenticationSelection selection;
        SshNativeSession.JumpChildOwner childOwner;
        SshNativeSession session;
        boolean rootTrustRefusal;

        JumpHop(SshConfigEvaluator.ProfilePlan plan, SshPasswordProfile profile,
                SshSavedPassword saved, boolean savedPresent, String hostsPath, VaultManifest.FileObject hosts) {
            this.plan = plan; this.profile = profile; this.saved = saved;
            this.savedPresent = savedPresent;
            this.hostsPath = hostsPath; hostsId = hosts.id; hostsIdentity = hosts.identity;
        }
    }

    /** Resolve the whole route before opening a socket or releasing any credential. */
    private java.util.List<JumpHop> prepareJumpRoute() throws Exception {
        if (configAlias.isEmpty()) return null;
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, this::check);
        try (SshConnectionMaterial.Snapshot snapshot = vault.sshLibrarySnapshot(context)) {
            if (snapshot.epoch != expectedEpoch || snapshot.revision != initialRevision
                    || !SshConnectionMaterial.configBinding(snapshot, configAlias).equals(expectedConfigBinding))
                throw changed();
            SshConfigEvaluator.ProfileRoute route = SshConnectionMaterial.profileRoute(snapshot.manifest,
                    SshConnectionMaterial.ROOT, configAlias, usernameOverride, deadline, this::check);
            if (route.entries.size() == 1) return null;
            if (route.entries.size() > 2)
                throw new VaultException("SSH_JUMP_CHAIN_UNSUPPORTED", "Multiple jump hosts are not available yet");
            // Admit only the restricted product route after its transport and
            // per-hop credential effects have been guarded.
            if (!jumpProductAvailable())
                throw new VaultException("SSH_JUMP_AUTH_UNSUPPORTED", "Jump authentication is not available yet");
            // The initial supported chain uses exact previously saved passwords.  Do not
            // silently substitute this for a user-selected key or an explicit method.
            if (!serverDrivenAuthentication || privateKeyId != null || configuredPassword)
                throw new VaultException("SSH_JUMP_AUTH_UNSUPPORTED", "This jump connection requires saved passwords");
            java.util.List<JumpHop> result = new java.util.ArrayList<>();
            for (int index = 0; index < route.entries.size(); index++) {
                SshConfigEvaluator.ProfilePlan plan = route.entries.get(index);
                requireJumpUser(plan.userRequired, index + 1 == route.entries.size());
                if (!plan.authenticationOrderKnown) throw changed();
                SshPasswordProfile endpoint = new SshPasswordProfile(plan.hostname, plan.port, plan.user);
                SshSavedPassword password = new SshSavedPassword(endpoint);
                String path = SshConnectionMaterial.singleTrustPath(snapshot.manifest, plan);
                VaultManifest.FileObject hosts = SshConnectionMaterial.file(snapshot.manifest, path);
                if (hosts == null) throw changed();
                result.add(new JumpHop(plan, endpoint, password, password.present(snapshot.manifest.accounts), path, hosts));
            }
            check();
            if (vault.contentRevision != initialRevision) throw changed();
            return result;
        } finally { vault.cancelBundleContext(context); }
    }

    private static boolean jumpProductAvailable() { return true; }

    static void requireJumpUser(boolean missing, boolean target) throws VaultException {
        if (missing) throw new VaultException(target ? "SSH_USER_REQUIRED" : "SSH_JUMP_USER_REQUIRED",
                "A jump connection requires a username for each host");
    }

    static VaultException jumpCancellationDiagnostic(VaultException failure, BooleanSupplier cancelled,
            BooleanSupplier trustUncertain) {
        if (failure == null || cancelled == null || trustUncertain == null)
            throw new IllegalArgumentException("A jump failure needs its lifecycle state");
        if ("VAULT_COMMIT_UNCERTAIN".equals(failure.code)) return failure;
        if (trustUncertain.getAsBoolean())
            return new VaultException("VAULT_COMMIT_UNCERTAIN", "The host trust result was not confirmed");
        if ("SSH_FILE_VIEW".equals(failure.code) && cancelled.getAsBoolean()) {
            if (trustUncertain.getAsBoolean())
                return new VaultException("VAULT_COMMIT_UNCERTAIN", "The host trust result was not confirmed");
            return new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
        }
        return failure;
    }

    static VaultException jumpTrustDiagnostic(VaultException failure, BooleanSupplier cancelled,
            BooleanSupplier trustUncertain, AppSecrets.EffectGuard live, BooleanSupplier exactRefusal)
            throws VaultException {
        if (live == null || exactRefusal == null) throw new IllegalArgumentException("Missing jump trust state");
        VaultException priority = jumpCancellationDiagnostic(failure, cancelled, trustUncertain);
        if (priority != failure || "VAULT_COMMIT_UNCERTAIN".equals(failure.code)) return priority;
        if (!"SSH_SESSION_CANCELLED".equals(failure.code) && !"SSH_CALLBACK_CANCELLED".equals(failure.code)
                && !"SSH_HOST_CHANGED_POLICY".equals(failure.code) && !"SSH_HOSTKEY_UNKNOWN".equals(failure.code)
                && !"SSH_HOST_KEY_CHANGED".equals(failure.code) && !"SSH_JUMP_EFFECT_GUARD".equals(failure.code))
            return failure;
        priority = jumpTrustLiveDiagnostic(failure, cancelled, trustUncertain, live);
        if (priority != failure) return priority;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean exact = exactRefusal.getAsBoolean();
            priority = jumpTrustLiveDiagnostic(failure, cancelled, trustUncertain, live);
            if (priority != failure) return priority;
            if (!exact) {
                if ("SSH_HOST_KEY_CHANGED".equals(failure.code))
                    return new VaultException("SSH_JUMP_HOST_TRUST_FAILED", "A jump host trust check failed");
                return failure;
            }
        }
        return new VaultException("SSH_JUMP_HOST_TRUST_REQUIRED", "A jump host needs trust review");
    }

    private static VaultException jumpTrustLiveDiagnostic(VaultException failure, BooleanSupplier cancelled,
            BooleanSupplier trustUncertain, AppSecrets.EffectGuard live) throws VaultException {
        VaultException priority = jumpCancellationDiagnostic(failure, cancelled, trustUncertain);
        if (priority != failure) return priority;
        if (cancelled.getAsBoolean()) {
            if (trustUncertain.getAsBoolean())
                return new VaultException("VAULT_COMMIT_UNCERTAIN", "The host trust result was not confirmed");
            return new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
        }
        try { live.check(); }
        catch (VaultException lifecycle) { throw jumpCancellationDiagnostic(lifecycle, cancelled, trustUncertain); }
        priority = jumpCancellationDiagnostic(failure, cancelled, trustUncertain);
        if (priority != failure) return priority;
        if (cancelled.getAsBoolean()) {
            if (trustUncertain.getAsBoolean())
                return new VaultException("VAULT_COMMIT_UNCERTAIN", "The host trust result was not confirmed");
            return new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
        }
        return failure;
    }

    private boolean reportableJumpTrustRefusal() {
        if (jumpHops == null) return false;
        for (JumpHop hop : jumpHops) {
            if (hop.issued == null || hop.callback == null || hop.session == null
                    || !hop.callback.ownsIssued(hop.issued) || !hop.session.ownsCallback(hop.callback)
                    || hop.callback.externallyCancelled()) continue;
            String code = hop.callback.lastFailureCode();
            if (hop.issued.role.hop == 0 && hop.rootTrustRefusal
                    && ("SSH_JUMP_EFFECT_GUARD".equals(code) || "SSH_HOSTKEY_UNKNOWN".equals(code)
                            || "SSH_HOST_CHANGED_POLICY".equals(code))) return true;
            if (hop.issued.role.hop > 0 && hop.callback.reportableJumpTrustRefusal()) return true;
        }
        return false;
    }

    private SshNativeSession createJumpConnection(java.util.List<JumpHop> hops) throws Exception {
        jumpHops = hops;
        rootPath = SshConnectionMaterial.ROOT;
        SshVaultAdapter files = new SshVaultAdapter(vault, (c, t) -> { throw denied(); });
        AppSecrets.SshPublicReadContext fileContext = files.fileContext(deadline, this::check);
        VaultOperation read = files.fileRequest(rootPath, connectionId, fileContext);
        try { view = files.openPublicFiles(read, rootPath, connectionId); }
        catch (VaultException | RuntimeException | Error failure) { files.cancel(read); throw failure; }
        check();
        sharedView = new SshSharedPublicView(view);
        registry = new SshRoleRegistry(clock);
        String viewId = "view-" + UUID.randomUUID();
        String chainIdentity = hops.get(hops.size() - 1).saved.target;
        byte[] binding = new byte[32];
        SecureRandom random = new SecureRandom();
        random.nextBytes(binding);
        try {
            for (int index = 0; index < hops.size(); index++) {
                check();
                JumpHop hop = hops.get(index);
                transportDeadline = jumpTransportDeadline(clock, deadline, hop.plan.connectTimeoutMillis, this::check);
                jumpTransportActive = true;
                checkTransport();
                byte[] nonce = new byte[32]; random.nextBytes(nonce);
                try {
                    SshRoleContext.Program program = index + 1 == hops.size()
                            ? SshRoleContext.Program.CLIENT : SshRoleContext.Program.JUMP;
                    SshRoleContext.Bootstrap bootstrap = new SshRoleContext.Bootstrap(program, Process.myPid(),
                            index, connectionId, hop.saved.target, hop.profile.host, hop.profile.port,
                            viewId, nonce, binding, deadline, chainIdentity);
                    hop.pending = SshNativeRoleBootstrap.prepare(bootstrap);
                    hop.pending.open();
                    hop.issued = hop.pending.issue(registry, view, clock);
                    hop.pending = null;
                } finally { VaultCodec.wipe(nonce); }
                JumpHop parent = index == 0 ? null : hops.get(index - 1);
                if (parent == null) {
                    hop.commit = trustCommit;
                    hop.commit.bindIssued(hop.issued);
                } else {
                    SshNativeSession.JumpAdmission admission = parent.session.issueJumpAdmission(
                            parent.issued, hop.issued, sharedView, view);
                    hop.commit = parent.commit.forNextHop(hop.issued, view, admission);
                }
                prepareJumpCallback(hop, parent != null);
                check();
                if (parent == null) {
                    SshSocketOwnership socket = tcp.connect(hop.profile, transportDeadline, this::check);
                    hop.session = SshNativeSession.openManaged(hop.callback, sharedView, socket,
                            hop.hostsPath, "candidate-" + UUID.randomUUID(), SshNativeSession.MAX_STEP_BUDGET);
                } else {
                    hop.childOwner = SshNativeSession.prepareJumpChildFresh(parent.session, hop.callback,
                            sharedView, hop.hostsPath, "candidate-" + UUID.randomUUID(),
                            SshNativeSession.MAX_STEP_BUDGET, hop.source, hop.commit);
                    beginJumpBeforeDeadline(clock, transportDeadline, this::check,
                            () -> parent.session.beginJump(hop.childOwner, transportDeadline));
                    while (true) {
                        checkTransport();
                        SshNativeSession.JumpStatus status = parent.session.jumpStatus();
                        if (status.phase == SshNativeSession.JumpPhase.OPEN && !status.localEof && !status.remoteEof) {
                            checkTransport();
                            break;
                        }
                        if (status.phase != SshNativeSession.JumpPhase.OPENING)
                            throw new VaultException("SSH_SESSION_JUMP", "The SSH jump channel failed");
                        stepJumpChain(hops, index - 1, true); pause(); checkTransport();
                    }
                    checkTransport(); hop.session = hop.childOwner.open();
                }
                session = hop.session;
                while (!stepJumpChain(hops, index, true).complete) { pause(); checkTransport(); }
                checkTransport();
                hop.callback.confirmChangedNativeCompletion();
                hop.callback.confirmNewNativeCompletion();
                if (trustCommit.publicationUncertain())
                    throw new VaultException("VAULT_COMMIT_UNCERTAIN", "The host trust result was not confirmed");
                checkTransport();
                jumpTransportActive = false;
                check(); hop.session.beginUserauthService();
                while (!hop.session.userauthServiceReady()) { stepJumpChain(hops, index); pause(); }
                check(); hop.session.beginAuthDiscovery();
                SshNativeSession.AuthDiscoveryStatus discovery;
                while ((discovery = hop.session.authDiscoveryResult()).result
                        == SshNativeSession.AuthDiscoveryResult.PENDING) {
                    stepJumpChain(hops, index); pause();
                }
                if (discovery.result != SshNativeSession.AuthDiscoveryResult.NONE_SUCCESS) {
                    if (discovery.result != SshNativeSession.AuthDiscoveryResult.METHODS
                            && discovery.result != SshNativeSession.AuthDiscoveryResult.PARTIAL)
                        throw new VaultException("SSH_AUTHENTICATION", "SSH authentication discovery failed");
                    requireJumpPasswordMethod(hop.plan.preferredAuthenticationOrder, discovery.methods);
                    if (!hop.savedPresent)
                        throw new VaultException("SSH_JUMP_PASSWORD_MISSING", "This jump needs a saved password");
                    Object stage = hop.session.selectedPasswordPromptStage();
                    requireSavedJumpPassword(hops, index, stage);
                    hop.session.beginSelectedSavedPassword(stage);
                    SshNativeSession.PasswordAuthResult result;
                    while ((result = hop.session.passwordAuthResult()) == SshNativeSession.PasswordAuthResult.PENDING) {
                        stepJumpChain(hops, index); pause();
                    }
                    firstFactorNeedsContinuation(result == SshNativeSession.PasswordAuthResult.SUCCESS,
                            result == SshNativeSession.PasswordAuthResult.PARTIAL, false);
                }
                if (index + 1 < hops.size() && hop.plan.serverAliveInterval != 0)
                    hop.session.configureServerAlive(hop.plan.serverAliveInterval, hop.plan.serverAliveCountMax);
            }
        } finally { VaultCodec.wipe(binding); }
        JumpHop target = hops.get(hops.size() - 1);
        profile = target.profile;
        serverAlivePolicy.accept(target.plan);
        ttyPolicy.accept(target.plan);
        if (serverAlivePolicy.interval() != 0)
            session.configureServerAlive(serverAlivePolicy.interval(), serverAlivePolicy.countMax());
        if (ttyPolicy.noPty()) session.configureNoPty();
        reconnectTarget = new SshReconnectTarget(profile, null, configAlias, keyboardInteractive,
                false, false, true);
        sharedView.seal();
        session.retireAuthenticationWith(this::retireJumpAuthentication);
        returned = true;
        return session;
    }

    private void prepareJumpCallback(JumpHop hop, boolean child) throws VaultException {
        // A fresh child trust source is not yet authorized to publish new or changed
        // host keys.  The whole chain therefore accepts only previously known keys.
        SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
            if (!child && review.belongsTo(hop.issued.role) && hop.handoff != null
                    && hop.handoff.provider() == hop.trust) { check(); hop.rootTrustRefusal = true; }
            if (hop.plan.strictHostKeyChecking == 2)
                throw new VaultException("SSH_HOSTKEY_UNKNOWN", "This configuration requires an already trusted host");
            throw new VaultException("SSH_JUMP_EFFECT_GUARD", "New jump host trust is not available");
        }, review -> {
            if (!child && review.belongsTo(hop.issued.role) && review.source.equals(hop.hostsPath)
                    && hop.handoff != null && hop.handoff.provider() == hop.trust) { check(); hop.rootTrustRefusal = true; }
            if (hop.plan.strictHostKeyChecking == 2)
                throw new VaultException("SSH_HOST_CHANGED_POLICY", "This configuration requires an already trusted host");
            throw new VaultException("SSH_JUMP_EFFECT_GUARD", "Changed jump host trust is not available");
        });
        SshVaultAdapter trusted = new SshVaultAdapter(vault, (connection, target) -> { throw denied(); });
        SshManagedKnownHostsProvider.SnapshotReceipt receipt;
        if (child) {
            hop.source = SshManagedKnownHostsProvider.snapshotForChild(trusted, hop.issued.role,
                    clock, view, rootPath, hop.hostsPath, hop.hostsId, hop.hostsIdentity);
            receipt = hop.source.receipt();
        } else receipt = SshManagedKnownHostsProvider.snapshotFor(trusted, hop.issued.role,
                clock, rootPath, hop.hostsPath, hop.hostsId, hop.hostsIdentity);
        hop.trust = bindOwnedTrustReceipt(trusted, receipt, hop.commit);
        hop.handoff = hop.trust.nativeCandidateHandoff();
        SshCredentialBridge credential = new SshCredentialBridge(vault, authority, clock);
        SshManagedProvider provider = new SshManagedProvider(credential, clock, hop.trust, hop.trust,
                (b, r, proof, kind) -> { throw denied(); });
        hop.callback = SshNativeCallbackAdapter.bind(hop.issued, registry, authority, clock, provider, hop.handoff,
                trustCommit.cancellation);
        hop.selection = SshAuthenticationSelection.password(hop.issued.role,
                hop.profile.username, hop.saved.entryId, clock);
        hop.callback.selectAuthentication(hop.selection);
    }

    static void requireJumpPasswordMethod(java.util.List<SshConfigEvaluator.AuthenticationMethod> order,
            int serverMethods) throws VaultException {
        if (order == null || serverMethods < 0 || (serverMethods & ~15) != 0) throw SshPasswordProfile.parameters();
        SshConfigEvaluator.AuthenticationMethod selected;
        try { selected = chooseAutomaticMethod(order, serverMethods, false); }
        catch (VaultException unavailable) {
            if (!"SSH_PUBLICKEY_REQUIRED".equals(unavailable.code)) throw unavailable;
            throw new VaultException("SSH_JUMP_AUTH_UNSUPPORTED", "This jump requires another authentication method");
        }
        if (selected != SshConfigEvaluator.AuthenticationMethod.PASSWORD)
            throw new VaultException("SSH_JUMP_AUTH_UNSUPPORTED", "This jump requires another authentication method");
    }

    private void requireSavedJumpPassword(java.util.List<JumpHop> hops, int index, Object stage) throws VaultException {
        JumpHop hop = hops.get(index);
        SshHostKeyAuthority.Proof host = hop.callback.hostProof();
        SshKexProof kex = hop.callback.kexProof();
        if (host == null || kex == null) throw denied();
        kex.requireMatches(hop.issued.role, host);
        SshPasswordMailbox.LiveCheck live = () -> {
            check();
            hop.callback.requireAuthenticationSelection(hop.selection);
            hop.session.requireSelectedPasswordPromptStageLive(stage);
            if (hop.callback.hostProof() != host || hop.callback.kexProof() != kex) throw denied();
            kex.requireMatches(hop.issued.role, host);
        };
        passwordMailbox = new SshPasswordMailbox(hop.issued.role, hop.selection, clock, live,
                new SshPasswordMailbox.Sink() {
                    @Override public void present(SshPasswordMailbox.Ticket pending) throws VaultException {
                        events.present(pending);
                    }
                    @Override public void dismiss(SshPasswordMailbox.Ticket pending) { events.dismiss(pending); }
                }, SshPasswordMailbox.Purpose.PASSWORD, trustCommit.cancellation);
        try (SshPasswordMailbox.Response response = passwordMailbox.await(() -> {
            live.check(); pollJumpPasswordPrompt(hops, index, stage);
        }, true, true)) {
            live.check();
            if (response.choice != SshPasswordMailbox.Choice.SAVED || response.bytes.length != 0)
                throw new VaultException("SSH_JUMP_AUTH_UNSUPPORTED", "This jump requires its saved password");
        } finally {
            passwordMailbox.close(); passwordMailbox = null;
        }
    }

    private void pollJumpPasswordPrompt(java.util.List<JumpHop> hops, int last, Object stage)
            throws VaultException {
        for (int index = 0; index < last; index++) {
            check();
            SshNativeSession ancestor = hops.get(index).session;
            if (index > 0) ancestor.pumpJumpRelay();
            SshNativeSession.JumpStatus status = ancestor.jumpStatus();
            if (status.phase != SshNativeSession.JumpPhase.OPEN || status.localEof || status.remoteEof)
                throw new VaultException("SSH_SESSION_JUMP", "The SSH jump channel failed");
            ancestor.step();
            if (index > 0) ancestor.pumpJumpRelay();
        }
        check();
        SshNativeSession active = hops.get(last).session;
        if (last > 0) active.pumpJumpRelay();
        active.pollSelectedPasswordPrompt(stage);
        if (last > 0) active.pumpJumpRelay();
    }

    /** Pump each authenticated ancestor and relay before and after the active hop. */
    private SshNativeSession.StepResult stepJumpChain(java.util.List<JumpHop> hops, int last)
            throws VaultException {
        return stepJumpChain(hops, last, false);
    }

    private SshNativeSession.StepResult stepJumpChain(java.util.List<JumpHop> hops, int last, boolean connecting)
            throws VaultException {
        SshNativeSession.StepResult result = null;
        for (int index = 0; index <= last; index++) {
            checkJumpPump(connecting);
            SshNativeSession current = hops.get(index).session;
            if (index > 0) { checkJumpPump(connecting); current.pumpJumpRelay(); }
            if (index < last) {
                SshNativeSession.JumpStatus status = current.jumpStatus();
                if (status.phase != SshNativeSession.JumpPhase.OPEN || status.localEof || status.remoteEof)
                    throw new VaultException("SSH_SESSION_JUMP", "The SSH jump channel failed");
            }
            checkJumpPump(connecting);
            result = keyExchangeStep(current, hops.get(index).callback,
                    connecting ? this::checkTransport : this::check, this::trackedWrite);
            if (index > 0) { checkJumpPump(connecting); current.pumpJumpRelay(); }
        }
        return result;
    }

    private void checkJumpPump(boolean connecting) throws VaultException {
        if (connecting) checkTransport(); else check();
    }

    private void retireJumpAuthentication() throws VaultException {
        if (Thread.currentThread() != owner || cancelled.get() || hostCancelled.getAsBoolean())
            throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
        vault.cancelBundleContext(origin);
        sensitive = null;
        events = null;
    }

    private void closeJumpConnection() throws Exception {
        // Keep every partially acquired owner until its close succeeds.
        for (int index = jumpHops.size() - 1; index >= 0; index--) {
            JumpHop hop = jumpHops.get(index);
            if (hop.childOwner != null) { hop.childOwner.close(); hop.childOwner = null; hop.session = null; }
            else if (hop.session != null && !returned) { hop.session.close(); hop.session = null; }
            // A failed root open may leave its cleanup-only session in the
            // socket owner, even though no session was returned to this hop.
            if (index == 0) tcp.close();
            if (hop.callback != null) { hop.callback.close(); hop.callback = null; }
            if (hop.handoff != null) { hop.handoff.close(); hop.handoff = null; }
            if (hop.trust != null) { hop.trust.close(); hop.trust = null; }
            if (hop.source != null) { hop.source.close(); hop.source = null; }
            if (hop.issued != null && hop.issued.role.active())
                SshNativeRoleBootstrap.release(hop.issued, registry, SshNativeRoleBootstrap.productionRelease());
            hop.issued = null;
            if (hop.pending != null) { hop.pending.closeUnissued(); hop.pending = null; }
        }
        session = null;
        tcp.close();
        if (sharedView != null) { sharedView.close(); sharedView = null; view = null; }
        else if (view != null) { view.close(); view = null; }
        if (registry != null) { registry.close(); registry = null; }
        vault.retryPasswordValidationCleanup();
        jumpHops = null;
    }

    static boolean firstFactorNeedsContinuation(boolean success, boolean partial,
            boolean continuationAllowed) throws VaultException {
        if (success) return false;
        if (partial) {
            if (continuationAllowed) return true;
            throw new VaultException("SSH_AUTH_PARTIAL", "The server requires another authentication method");
        }
        throw new VaultException("SSH_AUTHENTICATION", "SSH authentication failed");
    }

    static int automaticMethodMask(java.util.List<SshConfigEvaluator.AuthenticationMethod> order,
            boolean hasKey) throws VaultException {
        if (order == null) throw SshPasswordProfile.parameters();
        int mask = 0;
        for (SshConfigEvaluator.AuthenticationMethod method : order) {
            if (method == null) throw SshPasswordProfile.parameters();
            switch (method) {
                case PUBLIC_KEY: if (hasKey) mask |= SshNativeSession.AuthDiscoveryStatus.PUBLIC_KEY; break;
                case KEYBOARD_INTERACTIVE: mask |= SshNativeSession.AuthDiscoveryStatus.KEYBOARD_INTERACTIVE; break;
                case PASSWORD: mask |= SshNativeSession.AuthDiscoveryStatus.PASSWORD; break;
            }
        }
        return mask;
    }

    private java.util.List<SshConfigEvaluator.AuthenticationMethod> automaticOrder() throws VaultException {
        if (configAlias.isEmpty()) return java.util.Arrays.asList(SshConfigEvaluator.AuthenticationMethod.PUBLIC_KEY,
                SshConfigEvaluator.AuthenticationMethod.KEYBOARD_INTERACTIVE, SshConfigEvaluator.AuthenticationMethod.PASSWORD);
        if (!authenticationOrderPolicy.configured() || !authenticationOrderPolicy.known()) throw changed();
        return authenticationOrderPolicy.order();
    }

    private void completeServerDrivenAuthentication(java.util.List<SshConfigEvaluator.AuthenticationMethod> order,
            SshAuthenticationSelection selection)
            throws VaultException {
        session.beginAuthDiscovery();
        SshNativeSession.AuthDiscoveryStatus status;
        while ((status = session.authDiscoveryResult()).result == SshNativeSession.AuthDiscoveryResult.PENDING) {
            session.step(); pause();
        }
        if (status.result == SshNativeSession.AuthDiscoveryResult.NONE_SUCCESS) return;
        if (status.result != SshNativeSession.AuthDiscoveryResult.METHODS
                && status.result != SshNativeSession.AuthDiscoveryResult.PARTIAL)
            throw new VaultException("SSH_AUTHENTICATION", "SSH authentication discovery failed");
        java.util.List<SshConfigEvaluator.AuthenticationMethod> remaining = new java.util.ArrayList<>(order);
        int methods = status.methods;
        boolean initial = true;
        VaultException lastRejection = null;
        while (!remaining.isEmpty()) {
            SshConfigEvaluator.AuthenticationMethod method = nextAutomaticMethod(
                    remaining, methods, selectedKey != null, initial, lastRejection);
            remaining.remove(method);
            boolean partial = false;
            VaultException rejection = null;
            switch (method) {
                case KEYBOARD_INTERACTIVE:
                    if (!initial) session.continueKeyboardInteractiveAuth();
                    else if (selectedKey == null) session.beginKeyboardInteractiveAuth();
                    else session.beginDiscoveredKeyboard();
                    SshNativeSession.KeyboardInteractiveAuthResult keyboardResult = driveKeyboardAuthentication(selection);
                    if (keyboardResult == SshNativeSession.KeyboardInteractiveAuthResult.REJECTED)
                        rejection = new VaultException("SSH_KEYBOARD_AUTH", "The server rejected the authentication answers");
                    else partial = firstFactorNeedsContinuation(keyboardResult == SshNativeSession.KeyboardInteractiveAuthResult.SUCCESS,
                            keyboardResult == SshNativeSession.KeyboardInteractiveAuthResult.PARTIAL, true);
                    break;
                case PASSWORD:
                    SshNativeSession.PasswordAuthResult passwordResult = completeDiscoveredPassword(selection);
                    if (passwordResult == SshNativeSession.PasswordAuthResult.CHANGE_REQUIRED)
                        throw new VaultException("SSH_PASSWORD_CHANGE_REQUIRED", "The server requires a password change");
                    if (passwordResult == SshNativeSession.PasswordAuthResult.REJECTED)
                        rejection = new VaultException("SSH_AUTHENTICATION", "SSH authentication failed");
                    else partial = firstFactorNeedsContinuation(passwordResult == SshNativeSession.PasswordAuthResult.SUCCESS,
                            passwordResult == SshNativeSession.PasswordAuthResult.PARTIAL, true);
                    break;
                case PUBLIC_KEY:
                    completeDeferredKeyPhrase(selection);
                    try {
                        if (initial) beginFirstFactor(session, callback, true, this::check, this::trackedWrite);
                        else session.continuePrivateKeyAuth();
                    } catch (VaultException failure) {
                        VaultException diagnosed = proofFailureDiagnostic(session, callback, this::check,
                                this::trackedWrite, failure);
                        if ("SSH_SESSION_AUTH".equals(diagnosed.code) || "SSH_MFA_AUTH".equals(diagnosed.code))
                            throw new VaultException("SSH_KEY_UNAVAILABLE", "The selected private key could not be used");
                        throw diagnosed;
                    }
                    SshNativeSession.PrivateKeyAuthResult keyResult;
                    while ((keyResult = session.privateKeyAuthResult()) == SshNativeSession.PrivateKeyAuthResult.PENDING) {
                        session.step(); pause();
                    }
                    if (keyResult == SshNativeSession.PrivateKeyAuthResult.REJECTED) {
                        synchronized (this) {
                            VaultCodec.wipe(automaticPhraseSave); automaticPhraseSave = null;
                        }
                        rejection = new VaultException("SSH_KEY_REJECTED", "The selected private key was rejected");
                    } else partial = firstFactorNeedsContinuation(keyResult == SshNativeSession.PrivateKeyAuthResult.SUCCESS,
                            keyResult == SshNativeSession.PrivateKeyAuthResult.PARTIAL, true);
                    break;
                default: throw SshPasswordProfile.parameters();
            }
            if (rejection != null) {
                lastRejection = rejection;
                if (!remaining.isEmpty()) methods = session.authContinuationMethods();
                initial = false;
                continue;
            }
            lastRejection = null;
            if (!partial) return;
            methods = session.authContinuationMethods();
            initial = false;
        }
        if (lastRejection != null) throw lastRejection;
        throw new VaultException("SSH_AUTH_PARTIAL", "The server requires another authentication method");
    }

    static SshConfigEvaluator.AuthenticationMethod nextAutomaticMethod(
            java.util.List<SshConfigEvaluator.AuthenticationMethod> remaining, int serverMethods,
            boolean hasKey, boolean initial, VaultException lastRejection) throws VaultException {
        try { return chooseAutomaticMethod(remaining, serverMethods, hasKey); }
        catch (VaultException unavailable) {
            if (lastRejection != null) throw lastRejection;
            if (initial || "SSH_PUBLICKEY_REQUIRED".equals(unavailable.code)) throw unavailable;
            throw new VaultException("SSH_AUTH_PARTIAL", "The server requires another authentication method");
        }
    }

    static SshConfigEvaluator.AuthenticationMethod chooseAutomaticMethod(
            java.util.List<SshConfigEvaluator.AuthenticationMethod> order, int serverMethods,
            boolean hasKey) throws VaultException {
        int permitted = automaticMethodMask(order, hasKey);
        for (SshConfigEvaluator.AuthenticationMethod method : order) {
            int bit = method == SshConfigEvaluator.AuthenticationMethod.PUBLIC_KEY
                    ? SshNativeSession.AuthDiscoveryStatus.PUBLIC_KEY
                    : method == SshConfigEvaluator.AuthenticationMethod.KEYBOARD_INTERACTIVE
                            ? SshNativeSession.AuthDiscoveryStatus.KEYBOARD_INTERACTIVE
                            : SshNativeSession.AuthDiscoveryStatus.PASSWORD;
            if ((permitted & serverMethods & bit) != 0) return method;
        }
        if (!hasKey && order.contains(SshConfigEvaluator.AuthenticationMethod.PUBLIC_KEY)
                && (serverMethods & SshNativeSession.AuthDiscoveryStatus.PUBLIC_KEY) != 0)
            throw new VaultException("SSH_PUBLICKEY_REQUIRED", "The server requires a selected private key");
        throw new VaultException("SSH_AUTH_UNSUPPORTED", "The server offered no allowed authentication method");
    }

    static SshNativeSession.StepResult keyExchangeStep(SshNativeSession session,
            SshNativeCallbackAdapter callback, AppSecrets.EffectGuard transportGuard,
            BooleanSupplier trackedWrite) throws VaultException {
        try { return session.step(); }
        catch (VaultException failure) {
            throw proofFailureDiagnostic(session, callback, transportGuard, trackedWrite, failure);
        }
    }

    static void beginFirstFactor(SshNativeSession session, SshNativeCallbackAdapter callback,
            boolean privateKey, AppSecrets.EffectGuard authenticationGuard, BooleanSupplier trackedWrite)
            throws VaultException {
        try {
            if (privateKey) session.beginPrivateKeyAuth();
            else session.beginPasswordAuth();
        } catch (VaultException failure) {
            throw proofFailureDiagnostic(session, callback, authenticationGuard, trackedWrite, failure);
        }
    }

    private static VaultException proofFailureDiagnostic(SshNativeSession session,
            SshNativeCallbackAdapter callback, AppSecrets.EffectGuard guard,
            BooleanSupplier trackedWrite, VaultException failure) throws VaultException {
        // The bridge relies on cancellation to flag a write whose receipt becomes uncertain.
        if ("SSH_SESSION_CANCELLED".equals(failure.code)
                && session.ownsCallback(callback)
                && SshHostKeyAuthority.PROOF_INVALID.equals(callback.lastFailureCode())
                && !trackedWrite.getAsBoolean()) {
            checkProofFailureGuard(session, callback, guard, failure);
            if (!trackedWrite.getAsBoolean() && callback.reportableHostProofFailure()) {
                checkProofFailureGuard(session, callback, guard, failure);
                if (!trackedWrite.getAsBoolean() && callback.reportableHostProofFailure())
                    return new VaultException(SshHostKeyAuthority.PROOF_INVALID,
                            "The SSH host proof could not be verified");
            }
        }
        return failure;
    }

    private static void checkProofFailureGuard(SshNativeSession session, SshNativeCallbackAdapter callback,
            AppSecrets.EffectGuard guard, VaultException original) throws VaultException {
        try { guard.check(); }
        catch (VaultException failure) {
            if (!"VAULT_COMMIT_UNCERTAIN".equals(failure.code)
                    && (session.cancellationRequested() || callback.externallyCancelled())) throw original;
            throw failure;
        }
        if (session.cancellationRequested() || callback.externallyCancelled()) throw original;
    }

    private void completeKeyboardAuthentication(SshAuthenticationSelection selection) throws VaultException {
        SshNativeSession.KeyboardInteractiveAuthResult result = driveKeyboardAuthentication(selection);
        if (result != SshNativeSession.KeyboardInteractiveAuthResult.SUCCESS)
            throw new VaultException(continuationPolicy.enabled() ? "SSH_MFA_AUTH" : "SSH_KEYBOARD_AUTH",
                    "SSH keyboard-interactive authentication failed");
    }

    private SshNativeSession.KeyboardInteractiveAuthResult driveKeyboardAuthentication(
            SshAuthenticationSelection selection) throws VaultException {
        SshHostKeyAuthority.Proof actualHost = callback.hostProof();
        SshKexProof actualKex = callback.kexProof();
        Object stage = session.keyboardStage();
        if (actualHost == null || actualKex == null) throw denied();
        actualKex.requireMatches(issued.role, actualHost);
        keyboardMailbox = new SshKeyboardMailbox(issued.role, selection, clock, () -> {
            check(); callback.requireAuthenticationSelection(selection); session.requireKeyboardStageLive(stage);
            if (callback.hostProof() != actualHost || callback.kexProof() != actualKex) throw denied();
            actualKex.requireMatches(issued.role, actualHost);
        }, new SshKeyboardMailbox.Sink() {
            @Override public void present(SshKeyboardMailbox.Ticket pending) throws VaultException { events.present(pending); }
            @Override public void dismiss(SshKeyboardMailbox.Ticket pending) { events.dismiss(pending); }
        }, trustCommit.cancellation);
        SshNativeSession.KeyboardInteractiveAuthResult result;
        while ((result = session.keyboardInteractiveAuthResult()) == SshNativeSession.KeyboardInteractiveAuthResult.PENDING
                || result == SshNativeSession.KeyboardInteractiveAuthResult.CHALLENGE) {
            if (result == SshNativeSession.KeyboardInteractiveAuthResult.CHALLENGE) {
                SshKeyboardChallenge challenge = session.keyboardInteractiveChallenge();
                byte[] response = keyboardMailbox.await(challenge, () -> {
                    session.step();
                    if (session.keyboardInteractiveAuthResult() != SshNativeSession.KeyboardInteractiveAuthResult.CHALLENGE)
                        throw new VaultException("SSH_KEYBOARD_AUTH", "SSH keyboard-interactive authentication failed");
                });
                try { check(); session.respondKeyboardInteractive(challenge, response); }
                finally { VaultCodec.wipe(response); }
            } else { session.step(); pause(); }
        }
        keyboardMailbox.close(); keyboardMailbox = null;
        return result;
    }

    private SshNativeSession.PasswordAuthResult completeDiscoveredPassword(SshAuthenticationSelection selection)
            throws VaultException {
        SshHostKeyAuthority.Proof actualHost = callback.hostProof();
        SshKexProof actualKex = callback.kexProof();
        Object stage = session.passwordPromptStage();
        if (actualHost == null || actualKex == null) throw denied();
        actualKex.requireMatches(issued.role, actualHost);
        SshPasswordMailbox.LiveCheck live = () -> {
            check(); callback.requireAuthenticationSelection(selection); session.requirePasswordPromptStageLive(stage);
            if (callback.hostProof() != actualHost || callback.kexProof() != actualKex) throw denied();
            actualKex.requireMatches(issued.role, actualHost);
        };
        SshPasswordMailbox.Purpose purpose = SshPasswordMailbox.Purpose.PASSWORD;
        boolean savedAvailable = vault.automaticPromptCredential(selection, purpose, actualHost, actualKex,
                deadline, live::check, null);
        passwordMailbox = new SshPasswordMailbox(issued.role, selection, clock, live, new SshPasswordMailbox.Sink() {
            @Override public void present(SshPasswordMailbox.Ticket pending) throws VaultException { events.present(pending); }
            @Override public void dismiss(SshPasswordMailbox.Ticket pending) { events.dismiss(pending); }
        }, purpose, trustCommit.cancellation);
        try (SshPasswordMailbox.Response response = passwordMailbox.await(() -> {
            session.pollPasswordPrompt(stage);
            if ((session.passwordPromptMethods(stage) & SshNativeSession.AuthDiscoveryStatus.PASSWORD) == 0)
                throw new VaultException("SSH_AUTHENTICATION", "SSH password authentication is no longer available");
        }, savedAvailable)) {
            live.check(); session.pollPasswordPrompt(stage); live.check();
            byte[] secret = promptSecret(response, selection, purpose, actualHost, actualKex, live);
            try { submitDiscoveredPassword(session, stage, secret, live::check); }
            finally { if (secret != response.bytes) VaultCodec.wipe(secret); }
            if (response.choice != SshPasswordMailbox.Choice.SAVE) response.close();
            SshNativeSession.PasswordAuthResult result;
            while ((result = session.passwordAuthResult()) == SshNativeSession.PasswordAuthResult.PENDING) {
                session.step(); pause();
            }
            if (response.choice == SshPasswordMailbox.Choice.SAVE
                    && (result == SshNativeSession.PasswordAuthResult.SUCCESS
                            || result == SshNativeSession.PasswordAuthResult.PARTIAL))
                rememberAutomaticSave(purpose, response.bytes);
            return result;
        } finally {
            passwordMailbox.close(); passwordMailbox = null;
        }
    }

    private byte[] promptSecret(SshPasswordMailbox.Response response, SshAuthenticationSelection selection,
            SshPasswordMailbox.Purpose purpose, SshHostKeyAuthority.Proof host, SshKexProof kex,
            SshPasswordMailbox.LiveCheck live) throws VaultException {
        if (response.choice != SshPasswordMailbox.Choice.SAVED) return response.bytes;
        byte[][] captured = new byte[1][];
        boolean delivered = false;
        try {
            if (!vault.automaticPromptCredential(selection, purpose, host, kex,
                    deadline, live::check, bytes -> {
                        if (!trustCommit.cancellation.admitTerminalIo())
                            throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
                        captured[0] = bytes.clone();
                    })) {
                boolean key = purpose == SshPasswordMailbox.Purpose.KEY_PASSPHRASE;
                throw new VaultException(key ? "SSH_KEY_PASSPHRASE_MISSING" : "SSH_PASSWORD_MISSING",
                        "No saved credential matches this authentication attempt");
            }
            delivered = true;
            return captured[0];
        } finally { if (!delivered) VaultCodec.wipe(captured[0]); }
    }

    private void rememberAutomaticSave(SshPasswordMailbox.Purpose purpose, byte[] bytes) throws VaultException {
        check();
        installAutomaticSave(purpose, bytes.clone());
    }

    /** Order a material handoff against the same STOP that fences terminal I/O. */
    private void requireMaterialAdmission() throws VaultException {
        if (cancelled.get() || !trustCommit.cancellation.admitTerminalIo())
            throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
    }

    private void installAutomaticSave(SshPasswordMailbox.Purpose purpose, byte[] candidate) throws VaultException {
        boolean installed = false;
        try {
            synchronized (this) {
                requireMaterialAdmission();
                if (purpose == SshPasswordMailbox.Purpose.PASSWORD) {
                    if (automaticPasswordSave != null) throw changed();
                    automaticPasswordSave = candidate;
                } else {
                    if (automaticPhraseSave != null) throw changed();
                    automaticPhraseSave = candidate;
                }
                installed = true;
            }
        } finally { if (!installed) VaultCodec.wipe(candidate); }
    }

    private void installTransientPassword(SshTransientPassword candidate) throws VaultException {
        boolean installed = false;
        try {
            synchronized (this) {
                requireMaterialAdmission();
                if (password != null) throw changed();
                password = candidate;
                installed = true;
            }
        } finally { if (!installed) candidate.close(); }
    }

    private void installTransientKey(SshTransientKey candidate) throws VaultException {
        boolean installed = false;
        try {
            synchronized (this) {
                requireMaterialAdmission();
                if (transientKey != null) throw changed();
                transientKey = candidate;
                installed = true;
            }
        } finally { if (!installed) candidate.close(); }
    }

    private void installPasswordMutation(AppSecrets.SshEntryMutationContext candidate) throws VaultException {
        boolean installed = false;
        try {
            synchronized (this) {
                requireMaterialAdmission();
                if (saveContext != null) throw changed();
                saveContext = candidate;
                installed = true;
            }
        } finally { if (!installed) candidate.cancel(); }
    }

    private void installKeyMutation(AppSecrets.SshEntryMutationContext candidate) throws VaultException {
        boolean installed = false;
        try {
            synchronized (this) {
                requireMaterialAdmission();
                if (keyPassphraseMutation != null) throw changed();
                keyPassphraseMutation = candidate;
                installed = true;
            }
        } finally { if (!installed) candidate.cancel(); }
    }

    private void prepareAutomaticSave() throws VaultException {
        check(); callback.requireAuthenticationSelectionLive();
        SshPasswordProfile target = profile;
        SshConnectionMaterial.Key key = selectedKey;
        AppSecrets.EffectGuard request = sensitive;
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, request);
        try {
            synchronized (this) {
                requireMaterialAdmission();
                if (automaticSaveOrigin != null) throw changed();
                automaticSaveOrigin = context;
                automaticSaveScheduled = true;
            }
            long revision = vault.contentRevision;
            check(); callback.requireAuthenticationSelectionLive();
            vault.checkBundleContext(context);
            session.confirmShellWith(() -> saveAutomaticCredentials(context, revision, request, target, key));
        } catch (VaultException | RuntimeException | Error failure) {
            try {
                vault.cancelBundleContext(context);
                synchronized (this) { if (automaticSaveOrigin == context) automaticSaveOrigin = null; }
            } catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void saveAutomaticCredentials(AppSecrets.BundleContext context, long revision,
            AppSecrets.EffectGuard request, SshPasswordProfile target, SshConnectionMaterial.Key key)
            throws VaultException {
        byte[] passwordToSave = null, phraseToSave = null;
        try {
            synchronized (this) {
                if (cancelled.get() || !trustCommit.cancellation.admitTerminalIo()) {
                    VaultCodec.wipe(automaticPasswordSave); automaticPasswordSave = null;
                    VaultCodec.wipe(automaticPhraseSave); automaticPhraseSave = null;
                    throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
                }
                passwordToSave = automaticPasswordSave; automaticPasswordSave = null;
                phraseToSave = automaticPhraseSave; automaticPhraseSave = null;
            }
            final byte[] admittedPassword = passwordToSave, admittedPhrase = phraseToSave;
            AppSecrets.EffectGuard live = AppSecrets.boundGuard(request, () -> {
                if (cancelled.get() || hostCancelled.getAsBoolean())
                    throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
                requireTransportDeadline(clock.now(), deadline);
                vault.requirePasswordValidationOrigin(context);
                session.requireShellConfirmationLive();
            });
            SshPasswordManifestValidator.within(deadline, () -> {
                long epoch = context.epoch;
                long currentRevision = revision;
                if (admittedPassword != null) {
                    saveAutomaticCredential(live, epoch, currentRevision, target, null, admittedPassword);
                    epoch++;
                    currentRevision++;
                }
                if (admittedPhrase != null)
                    saveAutomaticCredential(live, epoch, currentRevision, target, key, admittedPhrase);
                return null;
            });
        } catch (VaultException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new VaultException("SSH_CREDENTIAL_SAVE", "Unable to save the SSH credential");
        } finally {
            VaultCodec.wipe(passwordToSave); VaultCodec.wipe(phraseToSave);
            vault.cancelBundleContext(context);
            synchronized (this) { if (automaticSaveOrigin == context) automaticSaveOrigin = null; }
        }
    }

    private void saveAutomaticCredential(AppSecrets.EffectGuard live, long epoch, long revision,
            SshPasswordProfile target, SshConnectionMaterial.Key key, byte[] secret) throws VaultException {
        AppSecrets.EffectGuard mutation = AppSecrets.boundGuard(live, () -> {
            vault.session.assertEpoch(epoch);
            if (vault.contentRevision != revision) throw changed();
        });
        mutation.check();
        AppSecrets.SshEntryMutationContext context = key == null
                ? vault.newPasswordMutationContext(epoch, deadline, mutation, target, false)
                : vault.newKeyPassphraseMutationContext(epoch, deadline, mutation, key, false);
        VaultOperation operation = null;
        try {
            if (key == null) {
                operation = vault.beginPasswordMutation(context, connectionId, true);
                vault.savePassword(operation, secret, mutation);
            } else {
                operation = vault.beginKeyPassphraseMutation(context, connectionId, true);
                vault.saveKeyPassphrase(operation, secret, mutation);
            }
            durableWrite = true;
            automaticCredentialWritten = true;
            live.check();
            vault.session.assertEpoch(epoch + 1);
            if (vault.contentRevision != revision + 1) throw changed();
        } finally { vault.cancelOperation(operation); context.cancel(); }
    }

    static void submitDiscoveredPassword(SshNativeSession session, Object stage, byte[] response,
            AppSecrets.EffectGuard guard) throws VaultException {
        guard.check(); session.pollPasswordPrompt(stage); guard.check(); session.beginDiscoveredPassword(stage, response);
    }

    private void completeDeferredKeyPhrase(SshAuthenticationSelection selection) throws VaultException {
        if (transientKey == null || !transientKey.needsPhrase()) return;
        SshHostKeyAuthority.Proof actualHost = callback.hostProof();
        SshKexProof actualKex = callback.kexProof();
        Object stage = session.privateKeyPromptStage();
        if (actualHost == null || actualKex == null) throw denied();
        actualKex.requireMatches(issued.role, actualHost);
        SshPasswordMailbox.LiveCheck live = () -> {
            check(); callback.requireAuthenticationSelection(selection); session.requirePrivateKeyPromptStageLive(stage);
            if (callback.hostProof() != actualHost || callback.kexProof() != actualKex) throw denied();
            actualKex.requireMatches(issued.role, actualHost);
        };
        SshPasswordMailbox.Purpose purpose = SshPasswordMailbox.Purpose.KEY_PASSPHRASE;
        boolean savedAvailable = vault.automaticPromptCredential(selection, purpose, actualHost, actualKex,
                deadline, live::check, null);
        passwordMailbox = new SshPasswordMailbox(issued.role, selection, clock, live, new SshPasswordMailbox.Sink() {
            @Override public void present(SshPasswordMailbox.Ticket pending) throws VaultException { events.present(pending); }
            @Override public void dismiss(SshPasswordMailbox.Ticket pending) { events.dismiss(pending); }
        }, purpose, trustCommit.cancellation);
        try (SshPasswordMailbox.Response response = passwordMailbox.await(() -> {
            session.pollPrivateKeyPrompt(stage);
            if ((session.privateKeyPromptMethods(stage) & SshNativeSession.AuthDiscoveryStatus.PUBLIC_KEY) == 0)
                throw new VaultException("SSH_AUTHENTICATION", "SSH public-key authentication is no longer available");
        }, savedAvailable)) {
            live.check(); session.pollPrivateKeyPrompt(stage); live.check();
            byte[] secret = promptSecret(response, selection, purpose, actualHost, actualKex, live);
            try {
                live.check();
                session.pollPrivateKeyPrompt(stage);
                live.check();
                transientKey.supplyPhrase(issued.role, actualHost, actualKex, live::check, secret);
                session.finishPrivateKeyPrompt(stage);
                if (response.choice == SshPasswordMailbox.Choice.SAVE)
                    rememberAutomaticSave(purpose, response.bytes);
            } finally { if (secret != response.bytes) VaultCodec.wipe(secret); }
        } finally {
            passwordMailbox.close(); passwordMailbox = null;
        }
    }

    private void requireChainPolicy(SshConfigEvaluator.DirectPlan plan, boolean key) throws VaultException {
        continuationPolicy.acceptConfiguredCapability(key ? plan.publickeyThenKeyboardAllowed : plan.passwordThenKeyboardAllowed);
    }

    private void prepareConfigPassword() throws Exception {
        byte[] bytes;
        synchronized (this) { bytes = pendingConfigPassword; pendingConfigPassword = null; }
        if (bytes == null) throw changed();
        long revision = initialRevision;
        try {
            prepareConfigPasswordSnapshot(revision, true);
            saved = new SshSavedPassword(profile);
            targetIdentity = useSaved ? saved.target : SshPasswordProfile.TARGET;
            if (saveConfigPassword) {
                AppSecrets.SshEntryMutationContext context =
                        vault.newPasswordMutationContext(expectedEpoch, deadline, this::check, profile, false);
                installPasswordMutation(context);
                VaultOperation operation = null;
                try {
                    operation = vault.beginPasswordMutation(context, connectionId, true);
                    vault.savePassword(operation, bytes, this::check); durableWrite = true;
                    expectedEpoch++; revision++;
                } finally { vault.cancelOperation(operation); context.cancel(); }
                check(); if (vault.contentRevision != revision) throw changed();
            }
            prepareConfigPasswordSnapshot(revision, !saveConfigPassword);
            if (!useSaved) installTransientPassword(
                    new SshTransientPassword(vault, profile, bytes, deadline, this::check, clock));
        } finally { VaultCodec.wipe(bytes); }
    }

    private void prepareConfigPasswordSnapshot(long revision, boolean originalBinding) throws Exception {
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, this::check);
        try (SshConnectionMaterial.Snapshot snapshot = vault.sshLibrarySnapshot(context)) {
            if (snapshot.epoch != expectedEpoch || snapshot.revision != revision
                    || originalBinding && !SshConnectionMaterial.configBinding(snapshot, configAlias).equals(expectedConfigBinding))
                throw changed();
            SshConfigEvaluator.DirectPlan plan = SshConnectionMaterial.profilePlan(snapshot.manifest,
                    SshConnectionMaterial.ROOT, configAlias, "", deadline, this::check);
            String userOverride = configPasswordUserOverride(plan, usernameOverride);
            if (plan.userRequired && !userOverride.isEmpty())
                plan = SshConnectionMaterial.profilePlan(snapshot.manifest, SshConnectionMaterial.ROOT,
                        configAlias, userOverride, deadline, this::check);
            if (plan.userRequired) throw new VaultException("SSH_USER_REQUIRED", "Enter an SSH username");
            if (!plan.passwordAuthentication)
                throw new VaultException("SSH_PASSWORD_POLICY", "This configuration disables password authentication");
            requireChainPolicy(plan, false);
            serverAlivePolicy.accept(plan);
            ttyPolicy.accept(plan);
            authenticationOrderPolicy.accept(plan);
            SshPasswordProfile actual = new SshPasswordProfile(plan.hostname, plan.port, plan.user);
            String trustPath = SshConnectionMaterial.singleTrustPath(snapshot.manifest, plan);
            if (profile != null && (!profile.same(actual) || !hostsPath.equals(trustPath))) throw changed();
            profile = actual; rootPath = SshConnectionMaterial.ROOT; hostsPath = trustPath;
            strictHostKeyChecking = plan.strictHostKeyChecking;
            transportDeadline = Math.min(deadline, requestStarted + plan.connectTimeoutMillis);
            VaultManifest.FileObject hosts = SshConnectionMaterial.file(snapshot.manifest, trustPath);
            hostsId = hosts.id; hostsIdentity = hosts.identity;
            if (useSaved && !saveConfigPassword && !new SshSavedPassword(profile).present(snapshot.manifest.accounts))
                throw new VaultException("SSH_PASSWORD_MISSING", "No saved password matches this connection");
        } finally { vault.cancelBundleContext(context); }
        check();
    }

    static String configPasswordUserOverride(SshConfigEvaluator.DirectPlan configured, String supplied) throws VaultException {
        if (configured == null || supplied == null || !configured.userRequired && !supplied.isEmpty())
            throw SshPasswordProfile.parameters();
        return supplied;
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
            trustCommit.bindIssued(issued);
        } finally { VaultCodec.wipe(nonce); VaultCodec.wipe(binding); }
    }

    private void prepareKey() throws Exception {
        prepareKeyPhrase();
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, this::check);
        try (SshConnectionMaterial.Snapshot snapshot = vault.sshLibrarySnapshot(context)) {
            if (snapshot.epoch != expectedEpoch || snapshot.revision != expectedKeyRevision) throw changed();
            selectedKey = SshConnectionMaterial.key(snapshot.manifest, privateKeyId);
            if (configAlias.isEmpty()) {
                rootPath = SshConnectionMaterial.MANUAL;
                hostsPath = SshConnectionMaterial.HOSTS;
            } else {
                rootPath = SshConnectionMaterial.ROOT;
                SshConfigEvaluator.DirectPlan plan = SshConnectionMaterial.profilePlan(snapshot.manifest, rootPath,
                        configAlias, usernameOverride, deadline, this::check);
                if (plan.userRequired) throw new VaultException("SSH_USER_REQUIRED", "Enter an SSH username");
                if (!plan.pubkeyAuthentication && !serverDrivenAuthentication)
                    throw new VaultException("SSH_KEY_SELECTION", "This SSH configuration disables public-key authentication");
                requireChainPolicy(plan, true);
                serverAlivePolicy.accept(plan);
                ttyPolicy.accept(plan);
                authenticationOrderPolicy.accept(plan);
                profile = new SshPasswordProfile(plan.hostname, plan.port, plan.user);
                strictHostKeyChecking = plan.strictHostKeyChecking;
                transportDeadline = Math.min(deadline, requestStarted + plan.connectTimeoutMillis);
                hostsPath = SshConnectionMaterial.singleTrustPath(snapshot.manifest, plan);
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
                    expectedKeyRevision++;
                    if (vault.contentRevision != expectedKeyRevision) throw changed();
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

    private void prepareKeyboardConfig() throws Exception {
        SshPasswordProfile selected = null;
        String selectedTrust = null;
        for (int pass = 0; pass < 2; pass++) {
            AppSecrets.BundleContext context = vault.newBundleContext(deadline, this::check);
            try (SshConnectionMaterial.Snapshot snapshot = vault.sshLibrarySnapshot(context)) {
                if (snapshot.epoch != expectedEpoch || snapshot.revision != initialRevision
                        || !SshConnectionMaterial.configBinding(snapshot, configAlias).equals(expectedConfigBinding)) throw changed();
                SshConfigEvaluator.DirectPlan plan = SshConnectionMaterial.profilePlan(snapshot.manifest, SshConnectionMaterial.ROOT,
                        configAlias, usernameOverride, deadline, this::check);
                if (plan.userRequired) throw new VaultException("SSH_USER_REQUIRED", "Enter an SSH username");
                if (!plan.keyboardInteractiveAuthentication && !serverDrivenAuthentication)
                    throw new VaultException("SSH_KEYBOARD_POLICY", "This SSH configuration does not allow keyboard-interactive authentication");
                serverAlivePolicy.accept(plan);
                ttyPolicy.accept(plan);
                authenticationOrderPolicy.accept(plan);
                SshPasswordProfile actual = new SshPasswordProfile(plan.hostname, plan.port, plan.user);
                String trustPath = SshConnectionMaterial.singleTrustPath(snapshot.manifest, plan);
                if (pass == 0) { selected = actual; selectedTrust = trustPath; }
                else if (!selected.same(actual) || !selectedTrust.equals(trustPath)) throw changed();
                profile = actual; rootPath = SshConnectionMaterial.ROOT; hostsPath = trustPath;
                strictHostKeyChecking = plan.strictHostKeyChecking;
                transportDeadline = Math.min(deadline, requestStarted + plan.connectTimeoutMillis);
                VaultManifest.FileObject hosts = SshConnectionMaterial.file(snapshot.manifest, trustPath);
                hostsId = hosts.id; hostsIdentity = hosts.identity;
            } finally { vault.cancelBundleContext(context); }
            check();
        }
    }

    private void prepareKeyPhrase() throws Exception {
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, this::check);
        byte[] phrase = null;
        try (SshConnectionMaterial.Snapshot snapshot = vault.sshLibrarySnapshot(context)) {
            if (snapshot.epoch != expectedEpoch || snapshot.revision != initialRevision) throw changed();
            if (!configAlias.isEmpty() && !SshConnectionMaterial.configBinding(snapshot, configAlias).equals(expectedConfigBinding))
                throw changed();
            SshConnectionMaterial.Key key = SshConnectionMaterial.key(snapshot.manifest, privateKeyId);
            SshPasswordProfile actualProfile = profile;
            if (!configAlias.isEmpty()) {
                SshConfigEvaluator.DirectPlan plan = SshConnectionMaterial.profilePlan(snapshot.manifest, SshConnectionMaterial.ROOT,
                        configAlias, usernameOverride, deadline, this::check);
                if (plan.userRequired) throw new VaultException("SSH_USER_REQUIRED", "Enter an SSH username");
                if (!plan.pubkeyAuthentication && !serverDrivenAuthentication)
                    throw new VaultException("SSH_KEY_SELECTION", "This SSH configuration disables public-key authentication");
                requireChainPolicy(plan, true);
                serverAlivePolicy.accept(plan);
                ttyPolicy.accept(plan);
                authenticationOrderPolicy.accept(plan);
                SshConnectionMaterial.singleTrustPath(snapshot.manifest, plan);
                actualProfile = new SshPasswordProfile(plan.hostname, plan.port, plan.user);
            }
            VaultManifest.FileObject privateFile = SshConnectionMaterial.fileId(snapshot.manifest, key.binding.privateFileId);
            synchronized (this) {
                if (pendingKeyPassphrase == null) throw changed();
                phrase = pendingKeyPassphrase; pendingKeyPassphrase = null;
            }
            if (!key.encrypted) {
                if (phrase.length != 0 || useSavedKeyPassphrase || saveKeyPassphrase) throw SshPasswordProfile.parameters();
                return;
            }
            if (serverDrivenAuthentication && phrase.length == 0 && !useSavedKeyPassphrase && !saveKeyPassphrase) {
                installTransientKey(SshTransientKey.deferred(vault, actualProfile, key, privateFile.content,
                        deadline, this::check, clock, trustCommit.cancellation));
                return;
            }
            if (useSavedKeyPassphrase) {
                VaultManifest.Entry stored = SshKeyPassphrase.entry(snapshot.manifest, key.binding);
                if (stored == null) throw new VaultException("SSH_KEY_PASSPHRASE_MISSING", "No saved passphrase matches this private key");
                VaultCodec.wipe(phrase); phrase = stored.secret.clone();
            }
            if (phrase.length == 0) throw new VaultException("SSH_KEY_PASSPHRASE_REQUIRED", "Enter the private-key passphrase");
            SshKeyMaterial.Result derived = SshKeyMaterial.inspect(privateFile.content, phrase, deadline);
            if (derived.status != SshKeyMaterial.Status.PARSED)
                throw new VaultException(derived.status == SshKeyMaterial.Status.UNSUPPORTED ? "SSH_KEY_UNSUPPORTED"
                        : derived.status == SshKeyMaterial.Status.BAD_PASSPHRASE ? "SSH_KEY_BAD_PASSPHRASE" : "SSH_KEY_INVALID",
                        "The private key could not be unlocked");
            byte[] expected = key.publicBlob(), actual = derived.publicBlob();
            try { if (!derived.encrypted || !MessageDigest.isEqual(expected, actual)) throw changed(); }
            finally { VaultCodec.wipe(expected); VaultCodec.wipe(actual); }
            check();
            if (saveKeyPassphrase) {
                AppSecrets.SshEntryMutationContext mutation =
                        vault.newKeyPassphraseMutationContext(expectedEpoch, deadline, this::check, key, false);
                installKeyMutation(mutation);
                VaultOperation operation = null;
                try {
                    operation = vault.beginKeyPassphraseMutation(mutation, connectionId, true);
                    vault.saveKeyPassphrase(operation, phrase, this::check); durableWrite = true;
                    expectedEpoch++; expectedKeyRevision++;
                } finally { vault.cancelOperation(operation); mutation.cancel(); }
                check(); if (vault.contentRevision != expectedKeyRevision) throw changed();
                useSavedKeyPassphrase = true;
            } else if (!useSavedKeyPassphrase) {
                installTransientKey(new SshTransientKey(vault, actualProfile, key, privateFile.content, phrase,
                        deadline, this::check, clock));
            }
        } finally { VaultCodec.wipe(phrase); vault.cancelBundleContext(context); }
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
        if (jumpHops != null && vault.contentRevision != initialRevision) throw changed();
        if (view == null) vault.session.assertEpoch(expectedEpoch);
        else if (!view.active()) throw changed();
    }
    private void checkTransport() throws VaultException {
        check();
        requireTransportDeadline(clock.now(), transportDeadline);
    }
    static void checkJumpDiagnosticLive(boolean connecting, SshRoleContext.Clock clock, long hopDeadline,
            AppSecrets.EffectGuard guard) throws VaultException {
        if (clock == null || guard == null) throw SshPasswordProfile.parameters();
        guard.check();
        if (connecting) {
            long now = clock.now();
            guard.check();
            requireTransportDeadline(now, hopDeadline);
        }
    }
    static long jumpTransportDeadline(SshRoleContext.Clock clock, long originalDeadline, int timeoutMillis,
            AppSecrets.EffectGuard guard) throws VaultException {
        if (clock == null || guard == null || timeoutMillis <= 0) throw SshPasswordProfile.parameters();
        guard.check();
        long now = clock.now();
        guard.check();
        requireTransportDeadline(now, originalDeadline);
        long hopDeadline = now > Long.MAX_VALUE - timeoutMillis ? Long.MAX_VALUE : now + timeoutMillis;
        guard.check();
        return Math.min(originalDeadline, hopDeadline);
    }
    static void beginJumpBeforeDeadline(SshRoleContext.Clock clock, long hopDeadline,
            AppSecrets.EffectGuard guard, AppSecrets.EffectGuard begin) throws VaultException {
        if (clock == null || guard == null || begin == null) throw SshPasswordProfile.parameters();
        guard.check();
        long now = clock.now();
        guard.check();
        now = Math.max(now, clock.now());
        guard.check();
        requireTransportDeadline(now, hopDeadline);
        begin.check();
    }
    static void requireTransportDeadline(long now, long limit) throws VaultException {
        if (now <= 0 || now >= limit) throw new VaultException("SSH_TIMED_OUT", "SSH key exchange timed out");
    }
    static void requireChangedCompletionDeadline(boolean pending, SshRoleContext.Clock clock, long limit)
            throws VaultException {
        if (pending) requireTransportDeadline(clock.now(), limit);
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
        if (keyboardMailbox != null) { keyboardMailbox.close(); keyboardMailbox = null; }
        if (passwordMailbox != null) { passwordMailbox.close(); passwordMailbox = null; }
        if (transientKey != null) { transientKey.close(); transientKey = null; }
        if (keyPassphraseMutation != null) keyPassphraseMutation.cancel();
        if (saveContext != null) saveContext.cancel();
        synchronized (this) {
            VaultCodec.wipe(pendingSave); pendingSave = null;
            VaultCodec.wipe(pendingKeyPassphrase); pendingKeyPassphrase = null;
            VaultCodec.wipe(pendingConfigPassword); pendingConfigPassword = null;
        }
        if (handoff != null) { handoff.close(); handoff = null; }
        if (trust != null) { trust.close(); trust = null; }
        if (view != null) { view.close(); view = null; }
        mailbox = null;
        ticket = null;
        approved = null;
        approvedChanged = null;
        selectedKey = null;
        sensitive = null;
        events = null;
    }

    @Override public void close() throws Exception {
        cancel();
        if (!started) return;
        if (Thread.currentThread() != owner) throw new VaultException("SSH_SESSION_THREAD", "SSH cleanup requires its owner");
        if (jumpHops != null) { closeJumpConnection(); return; }
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
