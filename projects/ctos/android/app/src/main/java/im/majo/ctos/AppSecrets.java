package im.majo.ctos;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.ByteBuffer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.UUID;

/** One vault owner. Business adapters receive purpose-limited operations, never a channel key API. */
final class AppSecrets {
    interface Store {
        byte[] read() throws IOException;
        void commit(byte[] envelope, byte[] quickMaterial, EffectGuard guard) throws IOException, VaultException;
        byte[] quickMaterial() throws IOException;
    }
    interface EffectGuard {
        void check() throws VaultException;
        default void beforeCommit() throws VaultException { check(); }
        default void publish(EffectGuard publication) throws VaultException { check(); publication.check(); }
    }
    static EffectGuard boundGuard(EffectGuard parent, EffectGuard condition) {
        return new EffectGuard() {
            @Override public void check() throws VaultException { parent.check(); condition.check(); }
            @Override public void beforeCommit() throws VaultException {
                check();
                parent.beforeCommit();
                condition.check();
            }
            @Override public void publish(EffectGuard publication) throws VaultException {
                parent.publish(() -> { condition.check(); publication.check(); });
            }
        };
    }
    interface SshContentValidator { void validate(VaultManifest manifest) throws VaultException; }
    /** Inner role/view fence, entered only after publication/session/operation locks. */
    interface SshRoleGuard extends EffectGuard {
        void withRole(EffectGuard publication) throws VaultException;
    }
    private static void withSshRole(EffectGuard guard, EffectGuard publication) throws VaultException {
        if (guard instanceof SshRoleGuard) ((SshRoleGuard) guard).withRole(publication);
        else publication.check();
    }
    interface SshRestoreReviewer {
        SshRestoreReview review(VaultManifest source, VaultManifest authenticatedTarget) throws VaultException;
    }
    interface ExactRestoreApproval {
        // Must be a bounded metadata/receipt check, not a parser, disk operation or system UI call.
        void requireApproved(SshRestoreReview review, RestoreConsent consent) throws VaultException;
    }
    private static final class ValidatorRegistration {
        final SshContentValidator validator;
        ValidatorRegistration(SshContentValidator validator) { this.validator = validator; }
    }
    /** Original native request authority, created before queueing or exact approval. No secrets. */
    static final class BundleContext {
        private final AppSecrets owner;
        final long epoch, deadline;
        private final EffectGuard guard;
        private final ValidatorRegistration validator;
        private volatile String snapshotBinding;
        private volatile boolean cancelled;
        private boolean admitted;
        private Object proposalIdentity;

        private BundleContext(AppSecrets owner, long epoch, long deadline, EffectGuard guard,
                ValidatorRegistration validator) {
            this.owner = owner; this.epoch = epoch; this.deadline = deadline;
            this.guard = guard; this.validator = validator;
        }

        void cancel() { synchronized (owner.session) { cancelled = true; } }
    }

    private static final class BundleWork implements AutoCloseable {
        final BundleContext context;
        byte[] ciphertext, key;
        BundleWork(BundleContext context, byte[] ciphertext, byte[] key) {
            this.context = context; this.ciphertext = ciphertext; this.key = key;
        }
        @Override public void close() { VaultCodec.wipe(key); VaultCodec.wipe(ciphertext); }
    }
    /** Original metadata-only authority. Bridge creates this before its worker queue. */
    static final class TotpReadContext {
        private final AppSecrets owner;
        private final long epoch, deadline;
        private final EffectGuard guard;
        private final ValidatorRegistration validator;
        private volatile boolean cancelled;
        private boolean admitted;
        private VaultOperation issued;

        private TotpReadContext(AppSecrets owner, long epoch, long deadline, EffectGuard guard,
                ValidatorRegistration validator) {
            this.owner = owner; this.epoch = epoch; this.deadline = deadline;
            this.guard = guard; this.validator = validator;
        }

        void cancel() {
            VaultOperation retired;
            synchronized (owner.session) {
                cancelled = true;
                // Issuance and cancellation share this fence, closing cleanup-before-assignment.
                retired = issued;
            }
            owner.cancelOperation(retired);
        }
    }

    /** Opaque native receipt attached to the exact operation; never reconstructed from scalars. */
    static final class TotpReadState {
        private final TotpReadContext context;
        private final long revision;
        private final boolean mixed;
        private boolean completed, reserved;
        private long window;

        private TotpReadState(TotpReadContext context, long revision, boolean mixed) {
            this.context = context; this.revision = revision; this.mixed = mixed;
        }
    }

    private static final class TotpReadWork implements AutoCloseable {
        final TotpReadContext context;
        final VaultOperation operation;
        final long revision;
        byte[] ciphertext, key;
        boolean mixed;
        TotpReadWork(TotpReadContext context, VaultOperation operation, long revision) {
            this.context = context; this.operation = operation; this.revision = revision;
        }
        @Override public void close() { VaultCodec.wipe(key); VaultCodec.wipe(ciphertext); }
    }

    /** Original management request metadata shared by bounded, separately admitted ticket children. */
    static final class TotpMutationOrigin {
        final AppSecrets owner;
        final long epoch, deadline;
        final ValidatorRegistration validator;
        final EffectGuard guard;
        final List<TotpMutationContext> contexts = new ArrayList<>();
        volatile boolean cancelled, handedOff;
        volatile boolean pinned;
        boolean mixed;
        volatile long revision;
        byte[] binding;
        Object ticket;
        List<VaultOperation> candidates;
        TotpMutationOrigin(AppSecrets owner, long epoch, long deadline, ValidatorRegistration validator, EffectGuard guard) {
            this.owner = owner; this.epoch = epoch; this.deadline = deadline; this.validator = validator; this.guard = guard;
        }
        TotpMutationContext context() throws VaultException {
            synchronized (owner.session) {
                if (cancelled || handedOff || contexts.size() >= 17) throw denied();
                TotpMutationContext context = new TotpMutationContext(this); contexts.add(context); return context;
            }
        }
        void check() throws VaultException { if (!handedOff) guard.check(); }
        void cancel() {
            List<TotpMutationContext> retired;
            synchronized (owner.session) { cancelled = true; retired = new ArrayList<>(contexts); }
            for (TotpMutationContext context : retired) context.cancel();
        }
    }
    static final class TotpMutationContext {
        final TotpMutationOrigin origin;
        volatile boolean cancelled;
        boolean admitted;
        VaultOperation issued;
        TotpMutationContext(TotpMutationOrigin origin) { this.origin = origin; }
        void cancel() {
            VaultOperation retired;
            synchronized (origin.owner.session) { cancelled = true; retired = issued; }
            origin.owner.cancelOperation(retired);
        }
    }
    static final class TotpMutationState {
        final TotpMutationContext context;
        final long revision;
        final boolean mixed;
        TotpMutationState(TotpMutationContext context, long revision, boolean mixed) {
            this.context = context; this.revision = revision; this.mixed = mixed;
        }
    }
    private static final class TotpMutationWork implements AutoCloseable {
        final TotpMutationContext context;
        final VaultOperation operation;
        final long revision;
        boolean mixed;
        byte[] key, ciphertext;
        TotpMutationWork(TotpMutationContext context, VaultOperation operation, long revision) {
            this.context = context; this.operation = operation; this.revision = revision;
        }
        @Override public void close() { VaultCodec.wipe(key); VaultCodec.wipe(ciphertext); }
    }
    private TotpMutationWork totpMutationWork;

    /** Trust authority fixed before external review; the proposal body remains caller-owned. */
    static final class SshTrustContext {
        private final AppSecrets owner;
        private final long epoch, deadline;
        private final EffectGuard guard;
        private final ValidatorRegistration validator;
        private final SshTrustProposal proposal;
        private volatile boolean cancelled;
        private boolean admitted;
        private VaultOperation issued;
        private SshTrustContext(AppSecrets owner, long epoch, long deadline, EffectGuard guard,
                ValidatorRegistration validator, SshTrustProposal proposal) {
            this.owner = owner; this.epoch = epoch; this.deadline = deadline;
            this.guard = guard; this.validator = validator; this.proposal = proposal;
        }
        void cancel() {
            VaultOperation retired;
            synchronized (owner.session) { cancelled = true; retired = issued; }
            owner.cancelOperation(retired);
        }
    }

    static final class SshTrustState {
        private final SshTrustContext context;
        private final EffectGuard authorization;
        private final long revision;
        private SshTrustState(SshTrustContext context, EffectGuard authorization, long revision) {
            this.context = context; this.authorization = authorization; this.revision = revision;
        }
    }

    private static final class SshTrustWork implements AutoCloseable {
        final SshTrustContext context;
        final VaultOperation operation;
        final EffectGuard authorization;
        final long revision;
        byte[] ciphertext, key, replacement, baseDigest;
        VaultSession.Revocation continuation;
        SshRoleContext role;
        SshTrustWork(SshTrustContext context, VaultOperation operation, EffectGuard authorization, long revision) {
            this.context = context; this.operation = operation;
            this.authorization = authorization; this.revision = revision;
        }
        @Override public void close() {
            VaultCodec.wipe(key); VaultCodec.wipe(ciphertext);
            VaultCodec.wipe(replacement); VaultCodec.wipe(baseDigest);
        }
    }

    private SshTrustWork sshTrustWork;

    /** Original save/delete metadata. The effect caller still owns the durable claim. */
    static final class SshEntryMutationContext {
        private final AppSecrets owner;
        private final long epoch, deadline;
        private final EffectGuard guard;
        private final ValidatorRegistration validator;
        private final SshSavedPassword passwordAccount;
        private final boolean forgetAllPasswords;
        private volatile boolean cancelled;
        private boolean admitted;
        private VaultOperation issued;
        private SshEntryMutationContext(AppSecrets owner, long epoch, long deadline, EffectGuard guard,
                ValidatorRegistration validator) {
            this(owner, epoch, deadline, guard, validator, null, false);
        }
        private SshEntryMutationContext(AppSecrets owner, long epoch, long deadline, EffectGuard guard,
                ValidatorRegistration validator, SshSavedPassword passwordAccount, boolean forgetAllPasswords) {
            this.owner = owner; this.epoch = epoch; this.deadline = deadline;
            this.guard = guard; this.validator = validator;
            this.passwordAccount = passwordAccount; this.forgetAllPasswords = forgetAllPasswords;
        }
        void cancel() {
            VaultOperation retired;
            synchronized (owner.session) { cancelled = true; retired = issued; }
            owner.cancelOperation(retired);
        }
    }

    static final class SshEntryMutationState {
        private final SshEntryMutationContext context;
        private final EffectGuard authorization;
        private final long revision;
        private SshEntryMutationState(SshEntryMutationContext context, EffectGuard authorization, long revision) {
            this.context = context; this.authorization = authorization; this.revision = revision;
        }
    }

    private static final class SshEntryMutationWork implements AutoCloseable {
        final SshEntryMutationContext context;
        final VaultOperation operation;
        final EffectGuard authorization;
        final long revision;
        byte[] ciphertext, key;
        SshEntryMutationWork(SshEntryMutationContext context, VaultOperation operation,
                EffectGuard authorization, long revision) {
            this.context = context; this.operation = operation;
            this.authorization = authorization; this.revision = revision;
        }
        @Override public void close() { VaultCodec.wipe(key); VaultCodec.wipe(ciphertext); }
    }

    private SshEntryMutationWork sshEntryMutationWork;

    /** Original typed-secret authority; never contains a secret or a detached source. */
    static final class SshSecretReadContext {
        private final AppSecrets owner;
        private final long epoch, deadline;
        private final EffectGuard guard;
        private final ValidatorRegistration validator;
        private final SshAuthenticationSelection passwordSelection;
        private volatile boolean cancelled;
        private boolean admitted;
        private VaultOperation issued;
        private SshSecretReadContext(AppSecrets owner, long epoch, long deadline, EffectGuard guard,
                ValidatorRegistration validator) {
            this(owner, epoch, deadline, guard, validator, null);
        }
        private SshSecretReadContext(AppSecrets owner, long epoch, long deadline, EffectGuard guard,
                ValidatorRegistration validator, SshAuthenticationSelection passwordSelection) {
            this.owner = owner; this.epoch = epoch; this.deadline = deadline;
            this.guard = guard; this.validator = validator;
            this.passwordSelection = passwordSelection;
        }
        void cancel() {
            VaultOperation retired;
            synchronized (owner.session) { cancelled = true; retired = issued; }
            owner.cancelOperation(retired);
        }
    }

    static final class SshSecretReadState {
        private final SshSecretReadContext context;
        private final long revision;
        private final VaultManifest.EntryType expectedType;
        private boolean delivered;
        private SshSecretReadState(SshSecretReadContext context, long revision, VaultManifest.EntryType expectedType) {
            this.context = context; this.revision = revision; this.expectedType = expectedType;
        }
    }

    private static final class SshSecretReadWork implements AutoCloseable {
        final SshSecretReadContext context;
        final VaultOperation operation;
        final long revision;
        byte[] ciphertext, key;
        SshSecretReadWork(SshSecretReadContext context, VaultOperation operation, long revision) {
            this.context = context; this.operation = operation; this.revision = revision;
        }
        @Override public void close() { VaultCodec.wipe(key); VaultCodec.wipe(ciphertext); }
    }

    private SshSecretReadWork sshSecretReadWork;

    /** Original public-file request authority. Construction reads metadata only. */
    static final class SshPublicReadContext {
        private final AppSecrets owner;
        private final long epoch, deadline;
        private final EffectGuard guard;
        private final ValidatorRegistration validator;
        private volatile boolean cancelled;
        private boolean admitted;
        private VaultOperation issued;
        private SshPublicReadContext(AppSecrets owner, long epoch, long deadline, EffectGuard guard,
                ValidatorRegistration validator) {
            this.owner = owner; this.epoch = epoch; this.deadline = deadline;
            this.guard = guard; this.validator = validator;
        }
        void cancel() {
            VaultOperation retired;
            synchronized (owner.session) { cancelled = true; retired = issued; }
            owner.cancelOperation(retired);
        }
    }

    static final class SshPublicReadState {
        private final SshPublicReadContext context;
        private final long revision;
        private boolean delivered;
        private SshPublicReadState(SshPublicReadContext context, long revision) {
            this.context = context; this.revision = revision;
        }
    }

    private static final class SshPublicReadWork implements AutoCloseable {
        final SshPublicReadContext context;
        final VaultOperation operation;
        final long revision;
        byte[] ciphertext, key;
        SshPublicReadWork(SshPublicReadContext context, VaultOperation operation, long revision) {
            this.context = context; this.operation = operation; this.revision = revision;
        }
        @Override public void close() { VaultCodec.wipe(key); VaultCodec.wipe(ciphertext); }
    }

    /** Owned preparation for encode/create outside locks, then one exact final attach. */
    static final class SshPublicReadPreparation implements AutoCloseable {
        private final AppSecrets owner;
        private final SshPublicReadWork work;
        private final Verified verified;
        final SshFileSnapshot snapshot;
        private final boolean nativeLease;
        private volatile boolean closed;
        private volatile boolean attachAttempted;
        private SshPublicReadPreparation(AppSecrets owner, SshPublicReadWork work, Verified verified,
                SshFileSnapshot snapshot, boolean nativeLease) {
            this.owner = owner; this.work = work; this.verified = verified; this.snapshot = snapshot;
            this.nativeLease = nativeLease;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { verified.close(); } finally { owner.finishSshPublicRead(work); }
        }
    }
    private static final class PreviewWork {
        final String requestId;
        final long epoch, deadline;
        final ValidatorRegistration validator;
        final RestoreProvider provider;
        volatile boolean cancelled;
        PreviewWork(String requestId, long epoch, long deadline,
                ValidatorRegistration validator, RestoreProvider provider) {
            this.requestId = requestId; this.epoch = epoch; this.deadline = deadline;
            this.validator = validator; this.provider = provider;
        }
    }
    private static final class RestoreProvider {
        final String id;
        final String version;
        final long revision;
        final SshRestoreReviewer reviewer;
        final ExactRestoreApproval approval;
        RestoreProvider(String id, String version, long revision, SshRestoreReviewer reviewer, ExactRestoreApproval approval) {
            this.id = id; this.version = version; this.revision = revision; this.reviewer = reviewer; this.approval = approval;
        }
    }
    static final class RestoreConsent {
        final String previewId;
        final String sourceCipherDigest;
        final String targetCipherDigest;
        final String reviewBinding;
        final String providerId;
        final String providerVersion;
        final long providerRevision;
        final long epoch;
        final long deadline;
        private RestoreConsent(ImportPreview preview) {
            previewId = preview.id;
            sourceCipherDigest = SshRestoreReview.hex(preview.sourceBinding);
            targetCipherDigest = preview.targetBinding == null ? "" : SshRestoreReview.hex(preview.targetBinding);
            reviewBinding = preview.reviewBinding;
            providerId = preview.provider.id; providerVersion = preview.provider.version; providerRevision = preview.provider.revision;
            epoch = preview.epoch; deadline = preview.deadline;
        }
    }

    static final class Verified implements AutoCloseable {
        final VaultEnvelope envelope;
        final byte[] key;
        final byte[] plaintext;
        final VaultManifest manifest;

        Verified(VaultEnvelope envelope, byte[] key, byte[] plaintext, VaultManifest manifest) {
            this.envelope = envelope;
            this.key = key;
            this.plaintext = plaintext;
            this.manifest = manifest;
        }

        @Override public void close() {
            VaultCodec.wipe(key);
            VaultCodec.wipe(plaintext);
            manifest.close();
        }
    }

    static final class ImportPreview implements AutoCloseable {
        final String id = UUID.randomUUID().toString();
        final Verified source;
        final byte[] targetBinding;
        private final byte[] sourceBinding;
        private final byte[] sourcePlaintextBinding;
        private final RestoreProvider provider;
        private final ValidatorRegistration validator;
        private final SshRestoreReview review;
        private volatile boolean valid = true;
        private boolean deliveryClaimed;
        final String reviewBinding;
        private RestoreConsent consent;
        final long epoch;
        volatile long deadline;
        final String requestId;

        ImportPreview(Verified source, byte[] targetBinding, long epoch, String requestId,
                RestoreProvider provider, ValidatorRegistration validator, SshRestoreReview review) {
            this.source = source;
            sourceBinding = source.envelope.binding();
            sourcePlaintextBinding = VaultCodec.digest(source.plaintext);
            this.targetBinding = targetBinding == null ? null : targetBinding.clone();
            this.provider = provider;
            this.validator = validator;
            this.review = review;
            reviewBinding = review == null ? "" : review.binding;
            this.epoch = epoch;
            this.requestId = requestId;
        }

        Map<String, Object> reviewMap() throws VaultException { return review == null ? null : review.toMap(); }
        @Override public void close() { source.close(); if (review != null) review.close(); consent = null; }
    }

    private final Store store;
    final Object publicationLock = new Object();
    volatile long contentRevision;
    final VaultSession session;
    private final VaultSession.Clock clock;
    private volatile ImportPreview preview;
    private volatile PreviewWork previewWork;
    private BundleWork bundleWork;
    private TotpReadWork totpReadWork;
    private SshPublicReadWork sshPublicReadWork;
    private volatile ValidatorRegistration validatorRegistration = new ValidatorRegistration(null);
    private volatile RestoreProvider restoreProvider;
    private long restoreRevision;
    // Cancellation is intentionally allowed while a commit holds this object's
    // monitor and is blocked in Store.commit.  A concurrent map lets the
    // cancellation path revoke the operation without waiting for that commit.
    private final Map<String, VaultOperation> operations = new ConcurrentHashMap<>();

    AppSecrets(Store store, VaultSession.Clock clock) {
        this.store = store;
        this.clock = clock;
        session = new VaultSession(clock);
    }

    synchronized void attachSshValidator(SshContentValidator validator) {
        synchronized (session) {
            validatorRegistration = new ValidatorRegistration(validator);
            if (preview != null && preview.review != null) preview.valid = false;
        }
        if (preview != null && !preview.valid) cancelPreview();
    }

    /** Retry only this thread's retained native validator resources, without replacing registration. */
    void retryPasswordValidationCleanup() throws VaultException {
        SshContentValidator validator = validatorRegistration.validator;
        if (validator instanceof SshPasswordManifestValidator)
            ((SshPasswordManifestValidator)validator).retryCleanup();
    }

    /** Public-only snapshot for the app-generated profile before its exact bundle is built. */
    SshPasswordProfile.Snapshot passwordProfile(BundleContext context) throws VaultException {
        byte[] key = null, ciphertext = null, plain = null;
        SshPasswordProfile.Snapshot result = null;
        long revision;
        try {
            synchronized (this) {
                checkBundleContext(context);
                revision = contentRevision;
                key = session.key();
                try { ciphertext = store.read(); }
                catch (IOException failure) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
                checkBundleContext(context);
                if (ciphertext == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
            }
            VaultEnvelope envelope = VaultEnvelope.parse(ciphertext);
            plain = envelope.decrypt(key);
            checkBundleContext(context);
            try (VaultManifest manifest = VaultManifest.decode(plain); VaultManifest loan = VaultManifest.decode(plain)) {
                context.validator.validator.validate(loan);
                requireUnchangedLoan(loan, plain);
                checkBundleContext(context);
                result = new SshPasswordProfile.Snapshot(manifest, context.epoch, revision);
            }
            synchronized (this) {
                checkBundleContext(context);
                unchanged(envelope.binding());
                checkBundleContext(context);
                if (contentRevision != revision) throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            }
            SshPasswordProfile.Snapshot published = result;
            context.guard.publish(() -> {
                synchronized (publicationLock) { synchronized (session) {
                    checkBundleMetadata(context);
                    if (contentRevision != published.revision)
                        throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
                } }
            });
            result = null;
            return published;
        } finally {
            if (result != null) result.close();
            VaultCodec.wipe(key); VaultCodec.wipe(ciphertext); VaultCodec.wipe(plain);
        }
    }

    /** Host-only owned source for SSH library inspection/import; callers close every loan. */
    SshConnectionMaterial.Snapshot sshLibrarySnapshot(BundleContext context) throws VaultException {
        byte[] key = null, ciphertext = null, plain = null;
        SshConnectionMaterial.Snapshot result = null;
        long revision;
        try {
            synchronized (this) {
                checkBundleContext(context); revision = contentRevision; key = session.key();
                try { ciphertext = store.read(); }
                catch (IOException failure) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
                checkBundleContext(context);
                if (ciphertext == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
            }
            VaultEnvelope envelope = VaultEnvelope.parse(ciphertext);
            String sourceBinding = SshConnectionMaterial.hex(VaultCodec.digest(ciphertext));
            if (context.snapshotBinding != null && !context.snapshotBinding.equals(sourceBinding))
                throw new VaultException("VAULT_CHANGED", "The vault changed; review again");
            plain = envelope.decrypt(key);
            checkBundleContext(context);
            try (VaultManifest manifest = VaultManifest.decode(plain); VaultManifest loan = VaultManifest.decode(plain)) {
                if (hasSsh(manifest)) {
                    context.validator.validator.validate(loan);
                    requireUnchangedLoan(loan, plain);
                }
                checkBundleContext(context);
                result = new SshConnectionMaterial.Snapshot(manifest, context.epoch, revision, sourceBinding);
            }
            synchronized (this) {
                checkBundleContext(context); unchanged(envelope.binding()); checkBundleContext(context);
                if (contentRevision != revision) throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            }
            SshConnectionMaterial.Snapshot published = result;
            context.guard.publish(() -> {
                synchronized (publicationLock) { synchronized (session) {
                    checkBundleMetadata(context);
                    if (context.snapshotBinding == null) context.snapshotBinding = sourceBinding;
                    else if (!context.snapshotBinding.equals(sourceBinding))
                        throw new VaultException("VAULT_CHANGED", "The vault changed; review again");
                    if (contentRevision != published.revision) throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
                } }
            });
            result = null;
            return published;
        } finally {
            if (result != null) result.close();
            VaultCodec.wipe(key); VaultCodec.wipe(ciphertext); VaultCodec.wipe(plain);
        }
    }

    void validateSshCandidate(BundleContext context, VaultManifest candidate) throws VaultException {
        checkBundleContext(context);
        byte[] canonical = candidate.encode();
        try (VaultManifest loan = VaultManifest.decode(canonical)) {
            context.validator.validator.validate(loan);
            requireUnchangedLoan(loan, canonical);
            checkBundleContext(context);
        } finally { VaultCodec.wipe(canonical); }
    }

    void inheritSshEditContext(BundleContext original, BundleContext child) throws VaultException {
        checkBundleContext(original); checkBundleContext(child);
        synchronized (publicationLock) { synchronized (session) {
            checkBundleMetadata(original); checkBundleMetadata(child);
            if (original.epoch != child.epoch || original.validator != child.validator
                    || child.deadline > original.deadline || original.snapshotBinding == null
                    || child.snapshotBinding != null) throw denied();
            child.snapshotBinding = original.snapshotBinding;
        } }
    }

    /** One bounded synchronous copy only; no provider/native work under the session fence. */
    void publishTransientSshPassword(SshRoleContext role, EffectGuard guard, EffectGuard copy)
            throws VaultException {
        guard.publish(() -> {
            synchronized (publicationLock) { synchronized (session) {
                foreground();
                long epoch = session.epoch();
                if (!session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
                VaultSession.Revocation view = session.attachedRevocation(epoch);
                if (!(view instanceof SshRoleContext.PublicView) || !view.active()
                        || !role.ownsPublicView((SshRoleContext.PublicView) view))
                    throw new VaultException("SSH_FILE_VIEW", "The SSH file view was revoked");
                role.publishBound(clock::now, () -> { guard.check(); copy.check(); });
            } }
        });
    }

    synchronized void attachSshRestoreReviewer(String id, String version, SshRestoreReviewer reviewer,
            ExactRestoreApproval approval) throws VaultException {
        if (id == null || version == null || !id.matches("[A-Za-z0-9_.-]{1,128}")
                || !version.matches("[A-Za-z0-9_.+-]{1,128}")) throw VaultException.invalid();
        restoreRevision++;
        synchronized (session) {
            restoreProvider = reviewer == null ? null : new RestoreProvider(id, version, restoreRevision, reviewer,
                    approval == null ? (review, consent) -> {
                        throw new VaultException("VAULT_TRUST_REVIEW", "Restore approval is not available");
                    } : approval);
            if (preview != null) preview.valid = false;
        }
        cancelPreview();
    }

    private static boolean hasSsh(VaultManifest manifest) {
        return !manifest.files.isEmpty() || manifest.entries.stream().anyMatch(e -> e.type != VaultManifest.EntryType.TOTP);
    }

    private void requireSshValidator() throws VaultException {
        if (validatorRegistration.validator == null) throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH file validation is not available yet");
    }

    synchronized VaultEnvelope current() throws VaultException {
        try {
            byte[] bytes = store.read();
            return bytes == null ? null : VaultEnvelope.parse(bytes);
        } catch (IOException error) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
    }

    synchronized byte[] quickMaterial() throws VaultException {
        try { return store.quickMaterial(); }
        catch (IOException error) { throw new VaultException("VAULT_IO", "Unable to read shortcut material"); }
    }

    void create(char[] password, char[] confirmation, EffectGuard guard) throws VaultException {
        long epoch = session.epoch();
        if (!Arrays.equals(password, confirmation)) throw new VaultException("PARAMETERS", "Passwords do not match");
        byte[] salt = VaultEnvelope.random(16);
        byte[] key = VaultEnvelope.derive(password, salt);
        byte[] plaintext = VaultManifest.empty().encode();
        try {
            VaultEnvelope envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plaintext);
            verifyReadback(envelope, key, plaintext);
            synchronized (this) {
                guard.check();
                foreground();
                session.assertEpoch(epoch);
                if (current() != null) throw new VaultException("VAULT_EXISTS", "A vault already exists");
                commit(envelope, null, boundGuard(guard, () -> session.assertEpoch(epoch)));
                session.unlockIfEpoch(epoch, key);
            }
        } finally { VaultCodec.wipe(key); VaultCodec.wipe(plaintext); }
    }

    void unlock(char[] password, EffectGuard guard) throws VaultException {
        VaultEnvelope target;
        long epoch = session.epoch();
        synchronized (this) { target = requireCurrent(); }
        try (Verified verified = verify(target.encode(), password)) {
            synchronized (this) {
                guard.check();
                foreground();
                session.assertEpoch(epoch);
                unchanged(target.binding());
                if (!session.unlockIfEpoch(epoch, verified.key)) throw new VaultException("VAULT_CHANGED", "The vault context changed; unlock again");
            }
        }
    }

    byte[] export(char[] password, EffectGuard guard) throws VaultException {
        VaultEnvelope target;
        long epoch = session.epoch();
        synchronized (this) { target = requireCurrent(); }
        try (Verified ignored = verify(target.encode(), password)) {
            synchronized (this) {
                guard.check();
                foreground();
                session.assertEpoch(epoch);
                unchanged(target.binding());
                return target.encode();
            }
        }
    }

    void changePassword(char[] password, char[] replacement, char[] confirmation,
            EffectGuard guard) throws VaultException {
        if (!Arrays.equals(replacement, confirmation)) throw new VaultException("PARAMETERS", "Passwords do not match");
        VaultEnvelope target;
        long epoch = session.epoch();
        synchronized (this) { target = requireCurrent(); }
        try (Verified verified = verify(target.encode(), password)) {
            byte[] salt = VaultEnvelope.random(16);
            byte[] key = VaultEnvelope.derive(replacement, salt);
            byte[] encoded = null;
            try {
                if (target.generation == Long.MAX_VALUE) throw VaultException.invalid();
                encoded = verified.manifest.encode();
                VaultEnvelope next = VaultEnvelope.encrypt(target.id, target.generation + 1, salt,
                        key, encoded);
                verifyReadback(next, key, encoded);
                synchronized (this) {
                    guard.check();
                    foreground();
                    session.assertEpoch(epoch);
                    unchanged(target.binding());
                    commit(next, null, boundGuard(guard, () -> session.assertEpoch(epoch)));
                    lock();
                }
            } finally { VaultCodec.wipe(encoded); VaultCodec.wipe(key); }
        }
    }

    ImportPreview preview(byte[] source, char[] sourcePassword, char[] targetPassword,
            String requestId, EffectGuard guard) throws VaultException {
        return preview(source, sourcePassword, targetPassword, requestId, clock.now() + 120000, guard);
    }

    ImportPreview preview(byte[] source, char[] sourcePassword, char[] targetPassword,
            String requestId, long deadline, EffectGuard guard) throws VaultException {
        PreviewWork work;
        VaultEnvelope target;
        synchronized (this) {
            if (previewWork != null) throw new VaultException("BUSY", "A restore preview is already being checked");
            guard.check(); foreground();
            work = new PreviewWork(requestId, session.epoch(), deadline, validatorRegistration, restoreProvider);
            previewWork = work;
            try { checkPreviewWork(work, guard); target = current(); checkPreviewWork(work, guard); }
            catch (Throwable failure) { previewWork = null; throw failure; }
        }
        Verified verified = null;
        Verified verifiedTarget = null;
        SshRestoreReview review = null;
        ImportPreview candidate = null;
        ImportPreview retired = null;
        boolean held = false;
        try {
            checkPreviewWork(work, guard);
            verified = verify(source, sourcePassword);
            checkPreviewWork(work, guard);
            SshRestoreReview.requireReviewedMetadata(verified.manifest);
            validatePreviewLoan(work, guard, verified);
            if (target != null) {
                checkPreviewWork(work, guard);
                if (targetPassword == null) throw new VaultException("PARAMETERS", "Enter the current vault password");
                verifiedTarget = verify(target.encode(), targetPassword);
                checkPreviewWork(work, guard);
                SshRestoreReview.requireReviewedMetadata(verifiedTarget.manifest);
                validatePreviewLoan(work, guard, verifiedTarget);
            }
            byte[] binding = target == null ? null : target.binding();
            boolean ssh = hasSsh(verified.manifest) || verifiedTarget != null && hasSsh(verifiedTarget.manifest);
            if (ssh) {
                if (work.validator.validator == null) throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH backup validation is not available yet");
                RestoreProvider provider = work.provider;
                if (provider == null) throw new VaultException("VAULT_SSH_REVIEW_REQUIRED", "SSH restore review is not available yet");
                checkPreviewWork(work, guard);
                try (VaultManifest sourceView = VaultManifest.decode(verified.plaintext);
                        VaultManifest targetView = verifiedTarget == null ? null : VaultManifest.decode(verifiedTarget.plaintext)) {
                    checkPreviewWork(work, guard);
                    try (SshRestoreReview supplied = provider.reviewer.review(sourceView, targetView)) {
                        checkPreviewWork(work, guard);
                        requireUnchangedLoan(sourceView, verified.plaintext);
                        if (targetView != null) requireUnchangedLoan(targetView, verifiedTarget.plaintext);
                        if (supplied == null) throw VaultException.invalid();
                        try (VaultManifest canonicalSource = VaultManifest.decode(verified.plaintext);
                                VaultManifest canonicalTarget = verifiedTarget == null ? null : VaultManifest.decode(verifiedTarget.plaintext)) {
                            checkPreviewWork(work, guard);
                            review = supplied.bind(canonicalSource, canonicalTarget,
                                    provider.id, provider.version, provider.revision, verified.envelope.binding(), binding);
                            checkPreviewWork(work, guard);
                        }
                    }
                }
            }
            checkPreviewWork(work, guard);
            candidate = new ImportPreview(verified, binding, work.epoch, requestId,
                    ssh ? work.provider : null, ssh ? work.validator : null, review);
            ImportPreview publish = candidate;
            synchronized (this) {
                checkPreviewWork(work, guard);
                unchanged(binding);
                // Store seams can re-enter and revoke the registration or original request.
                checkPreviewWork(work, guard);
                retired = preview;
                guard.publish(() -> {
                    synchronized (session) {
                        checkPreviewMetadata(work);
                        publish.deadline = clock.now() + 120000;
                        preview = publish;
                    }
                });
                held = true;
            }
            return candidate;
        } finally {
            try {
                if (retired != null && held) retired.close();
                if (verifiedTarget != null) verifiedTarget.close();
                if (!held) {
                    if (candidate != null) candidate.close();
                    else { if (verified != null) verified.close(); if (review != null) review.close(); }
                }
            } finally {
                synchronized (this) { if (previewWork == work) previewWork = null; }
            }
        }
    }

    private void checkPreviewWork(PreviewWork work, EffectGuard guard) throws VaultException {
        guard.check();
        synchronized (session) { checkPreviewMetadata(work); }
    }

    private void checkPreviewMetadata(PreviewWork work) throws VaultException {
        if (work.cancelled) throw new VaultException("VAULT_CANCELLED", "Operation was cancelled");
        if (clock.now() >= work.deadline) throw new VaultException("VAULT_EXPIRED", "Preview request expired");
        foreground(); session.assertEpoch(work.epoch);
        if (previewWork != work || validatorRegistration != work.validator || restoreProvider != work.provider)
            throw new VaultException("VAULT_CHANGED", "Restore validation context changed; review again");
    }

    private void validatePreviewLoan(PreviewWork work, EffectGuard guard, Verified verified) throws VaultException {
        checkPreviewWork(work, guard);
        if (!hasSsh(verified.manifest)) return;
        if (work.validator.validator == null) throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH backup validation is not available yet");
        try (VaultManifest loan = VaultManifest.decode(verified.plaintext)) {
            checkPreviewWork(work, guard);
            work.validator.validator.validate(loan);
            checkPreviewWork(work, guard);
            requireUnchangedLoan(loan, verified.plaintext);
            checkPreviewWork(work, guard);
        }
    }

    private static void requireUnchangedLoan(VaultManifest loan, byte[] authenticated) throws VaultException {
        byte[] encoded = loan.encode();
        byte[] expected = null;
        try {
            if (MessageDigest.isEqual(encoded, authenticated)) return;
            try (VaultManifest original = VaultManifest.decode(authenticated)) { expected = original.encode(); }
            if (!MessageDigest.isEqual(encoded, expected)) throw VaultException.invalid();
        } finally { VaultCodec.wipe(expected); VaultCodec.wipe(encoded); }
    }

    /** Claim delivery once using metadata only; result callbacks run outside the fence.
     * A cancellation after the claim still revokes commit, but cannot recall the claimed summary. */
    void deliverPreview(ImportPreview pending, EffectGuard owner, EffectGuard delivery) throws VaultException {
        owner.publish(() -> {
            synchronized (session) {
                foreground(); session.assertEpoch(pending.epoch);
                if (pending.deliveryClaimed || !pending.valid || preview != pending || clock.now() >= pending.deadline)
                    throw new VaultException("VAULT_EXPIRED", "Preview expired");
                checkPreviewRegistration(pending);
                pending.deliveryClaimed = true;
            }
        });
        delivery.check();
    }

    private void checkPreviewRegistration(ImportPreview pending) throws VaultException {
        if (pending.review != null && (pending.validator != validatorRegistration || pending.provider != restoreProvider))
            throw new VaultException("VAULT_CHANGED", "Restore validation context changed; review again");
    }

    synchronized void importCommit(String previewId, boolean confirmTrust, EffectGuard guard)
            throws VaultException {
        importCommit(previewId, null, confirmTrust, guard);
    }

    synchronized void importCommit(String previewId, String acceptedReviewBinding, boolean confirmTrust, EffectGuard guard)
            throws VaultException {
        ImportPreview pending = preview;
        if (pending == null || !pending.valid || !pending.id.equals(previewId)) throw new VaultException("VAULT_EXPIRED", "Preview expired");
        try {
            guard.check();
            foreground();
            session.assertEpoch(pending.epoch);
            unchanged(pending.targetBinding);
            if (clock.now() >= pending.deadline) throw new VaultException("VAULT_EXPIRED", "Preview expired");
            checkImportSource(pending);
            checkPreviewRegistration(pending);
            if (pending.review != null) {
                if (!confirmTrust || !pending.reviewBinding.equals(acceptedReviewBinding)) {
                    throw new VaultException("VAULT_TRUST_REVIEW", "Review and approve this exact restore projection");
                }
                pending.consent = new RestoreConsent(pending);
                checkRestoreApproval(pending);
            } else if (acceptedReviewBinding != null && !acceptedReviewBinding.isEmpty()) {
                throw new VaultException("VAULT_TRUST_REVIEW", "Restore review binding does not match");
            }
            byte[] encoded = null;
            try {
                try (VaultManifest source = VaultManifest.decode(pending.source.plaintext)) { encoded = source.encode(); }
                // A fresh local ID makes source/target generation collisions impossible; salt/key remain portable.
                VaultEnvelope next = VaultEnvelope.encrypt(UUID.randomUUID(), 1, pending.source.envelope.salt,
                        pending.source.key, encoded);
                verifyReadback(next, pending.source.key, encoded);
                guard.check();
                commit(next, null, boundGuard(guard, () -> {
                    unchanged(pending.targetBinding);
                    guard.check();
                    session.assertEpoch(pending.epoch);
                    foreground();
                    if (!pending.valid || preview != pending || clock.now() >= pending.deadline) throw new VaultException("VAULT_EXPIRED", "Preview expired");
                    checkImportSource(pending);
                    checkPreviewRegistration(pending);
                    if (pending.review != null) checkRestoreApproval(pending);
                }));
            } finally { VaultCodec.wipe(encoded); }
            lock();
        } finally { cancelPreview(); }
    }

    private void checkRestoreApproval(ImportPreview pending) throws VaultException {
        checkPreviewRegistration(pending);
        requireSshValidator();
        pending.review.requireOpen();
        if (pending.provider != restoreProvider || pending.consent == null
                || !pending.review.binding.equals(pending.consent.reviewBinding)) {
            throw new VaultException("VAULT_CHANGED", "Restore reviewer or consent changed; review again");
        }
        pending.provider.approval.requireApproved(pending.review, pending.consent);
        pending.review.requireOpen();
        session.assertEpoch(pending.epoch);
        foreground();
        if (!pending.valid || preview != pending || clock.now() >= pending.deadline) throw new VaultException("VAULT_EXPIRED", "Preview expired");
        checkPreviewRegistration(pending);
        if (pending.provider != restoreProvider || pending.consent == null) {
            throw new VaultException("VAULT_CHANGED", "Restore reviewer or consent changed; review again");
        }
    }

    private void checkImportSource(ImportPreview pending) throws VaultException {
        if (!MessageDigest.isEqual(pending.sourceBinding, pending.source.envelope.binding())
                || !MessageDigest.isEqual(pending.sourcePlaintextBinding, VaultCodec.digest(pending.source.plaintext))) {
            throw new VaultException("VAULT_CHANGED", "The authenticated source changed; review again");
        }
    }

    void disableQuick(char[] password, EffectGuard guard) throws VaultException {
        VaultEnvelope target;
        byte[] quick;
        long epoch = session.epoch();
        synchronized (this) { target = requireCurrent(); quick = quickMaterial(); }
        try (Verified ignored = verify(target.encode(), password)) {
            synchronized (this) {
                guard.check();
                foreground();
                session.assertEpoch(epoch);
                unchanged(target.binding());
                if (!Arrays.equals(quick, quickMaterial())) throw new VaultException("VAULT_CHANGED", "Shortcut material changed");
                // Disabled state is committed with the complete vault before Keystore cleanup.
                commit(target, null, boundGuard(guard, () -> session.assertEpoch(epoch)));
                lock();
            }
        }
    }

    synchronized void setQuick(VaultEnvelope target, byte[] wrapper, long epoch, EffectGuard guard)
            throws VaultException {
        guard.check();
        foreground();
        session.assertEpoch(epoch);
        unchanged(target.binding());
        commit(target, wrapper, boundGuard(guard, () -> session.assertEpoch(epoch)));
    }

    synchronized void quickUnlock(VaultEnvelope target, byte[] key, long epoch, EffectGuard guard)
            throws VaultException {
        byte[] plaintext = target.decrypt(key);
        try (VaultManifest ignored = VaultManifest.decode(plaintext)) {
            guard.check();
            foreground();
            session.assertEpoch(epoch);
            unchanged(target.binding());
            if (!session.unlockIfEpoch(epoch, key)) throw new VaultException("VAULT_CHANGED", "The vault context changed; unlock again");
        } finally { VaultCodec.wipe(plaintext); }
    }

    synchronized void lock() { session.lock(); cancelPreview(); operations.clear(); }
    synchronized void cleanupInvalidatedContexts() {
        long currentEpoch = session.epoch();
        operations.values().removeIf(operation -> operation.epoch != currentEpoch);
        if (preview != null && (preview.epoch != currentEpoch || clock.now() >= preview.deadline)) cancelPreview();
    }

    VaultOperation beginTotp(String entryId) throws VaultException {
        TotpReadContext context = newTotpReadContext();
        return beginTotp(entryId, context);
    }

    VaultOperation beginTotp(String entryId, TotpReadContext context) throws VaultException {
        return beginTotpRead(context, VaultOperation.Purpose.TOTP_CODE, entryId);
    }

    VaultOperation beginTotpList() throws VaultException {
        TotpReadContext context = newTotpReadContext();
        return beginTotpList(context);
    }

    VaultOperation beginTotpList(TotpReadContext context) throws VaultException {
        return beginTotpRead(context, VaultOperation.Purpose.TOTP_LIST, "totp-list");
    }

    Map<String, Object> totpList(VaultOperation operation, long unixSeconds) throws VaultException {
        TotpReadWork work = captureTotpRead(null, operation, VaultOperation.Purpose.TOTP_LIST, "totp-list");
        try (Verified verified = openTotpRead(work)) {
            if (unixSeconds < 0) throw new VaultException("PARAMETERS", "Invalid code time");
            List<Map<String, Object>> entries = new ArrayList<>();
            for (VaultManifest.Entry entry : verified.manifest.entries) {
                if (entry.type == VaultManifest.EntryType.TOTP) entries.add(VaultCollections.map(
                        "entryId", entry.id, "identity", entry.identity, "label", entry.label,
                        "code", generateTotp(entry.secret, unixSeconds)));
            }
            completeTotpRead(work, unixSeconds);
            return VaultCollections.map("entries", entries, "unixSeconds", unixSeconds);
        } finally { finishTotpRead(work); }
    }
    // Legacy TOTP_SAVE preview delivery. Read delivery requires its opaque native receipt below.
    void publishTotp(long epoch, long revision, long deadline, EffectGuard owner, EffectGuard publish) throws VaultException {
        synchronized (publicationLock) { synchronized (session) {
            owner.check(); session.assertEpoch(epoch);
            if (!session.foreground() || !session.unlocked() || contentRevision != revision || clock.now() >= deadline)
                throw new VaultException("VAULT_CHANGED", "The TOTP context changed or expired");
            publish.check();
        } }
    }

    private TotpReadContext newTotpReadContext() throws VaultException {
        long deadline = clock.now() + 120000;
        foreground();
        return newTotpReadContext(session.epoch(), deadline, () -> { });
    }

    TotpReadContext newTotpReadContext(long epoch, long deadline, EffectGuard guard) throws VaultException {
        if (guard == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        return new TotpReadContext(this, epoch, deadline, guard, validatorRegistration);
    }

    private void checkTotpReadMetadata(TotpReadContext context, boolean mixed) throws VaultException {
        if (context.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        if (clock.now() >= context.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        // Background must precede lazy session getters, including at phase publication.
        foreground();
        session.assertEpoch(context.epoch);
        if (!session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        if (mixed) {
            if (validatorRegistration != context.validator)
                throw new VaultException("VAULT_CHANGED", "SSH validation context changed; try again");
            if (context.validator.validator == null)
                throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH backup validation is not available yet");
        }
    }

    private void checkTotpRead(TotpReadWork work) throws VaultException {
        if (work.operation != null && work.operation.cancelled)
            throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        work.context.guard.check();
        synchronized (session) { checkTotpReadMetadata(work.context, work.mixed); }
        if (contentRevision != work.revision)
            throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
    }

    private TotpReadWork captureTotpRead(TotpReadContext context, VaultOperation operation,
            VaultOperation.Purpose purpose, String resource) throws VaultException {
        TotpReadWork captured = null;
        try {
            synchronized (this) {
                // Check exact capability before context errors so replays keep their original priority.
                if (operation != null) {
                    if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                    TotpReadState state = operation.totpReadState();
                    if (operations.get(operation.requestId) != operation || operation.consumed || state == null
                            || operation.consumer != VaultOperation.Consumer.TOTP || operation.purpose != purpose
                            || !operation.resource.equals(resource) || !operation.target.isEmpty()
                            || !operation.connectionId.isEmpty() || state.context.owner != this
                            || state.context.issued != operation) throw denied();
                    context = state.context;
                }
                if (context == null || context.owner != this || (operation == null && context.admitted)) throw denied();
                if (operation == null) context.admitted = true;
                if (totpReadWork != null) throw new VaultException("BUSY", "A TOTP read is already in progress");
                if (operation != null) {
                    operation.consumed = true;
                    operations.remove(operation.requestId, operation);
                }
                captured = new TotpReadWork(context, operation, contentRevision);
                if (operation != null) {
                    captured.mixed = operation.totpReadState().mixed;
                    if (operation.totpReadState().revision != captured.revision)
                        throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
                }
                totpReadWork = captured;
                checkTotpRead(captured);
                captured.key = session.key();
                captured.ciphertext = store.read();
                checkTotpRead(captured);
                if (captured.ciphertext == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
                return captured;
            }
        } catch (IOException failure) {
            if (captured != null) finishTotpRead(captured);
            throw new VaultException("VAULT_IO", "Unable to read the vault");
        } catch (Throwable failure) {
            if (captured != null) finishTotpRead(captured);
            throw failure;
        }
    }

    private Verified openTotpRead(TotpReadWork work) throws VaultException {
        byte[] key = null, plain = null;
        VaultManifest manifest = null;
        try {
            checkTotpRead(work);
            VaultEnvelope envelope = VaultEnvelope.parse(work.ciphertext);
            if (work.operation != null && (!work.operation.vaultId.equals(envelope.id)
                    || work.operation.generation != envelope.generation
                    || !MessageDigest.isEqual(work.operation.snapshotBinding, envelope.binding())))
                throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            checkTotpRead(work);
            key = work.key.clone();
            plain = envelope.decrypt(key);
            checkTotpRead(work);
            manifest = VaultManifest.decode(plain);
            work.mixed = hasSsh(manifest);
            checkTotpRead(work);
            if (work.mixed) {
                try (VaultManifest loan = VaultManifest.decode(plain)) {
                    checkTotpRead(work);
                    work.context.validator.validator.validate(loan);
                    checkTotpRead(work);
                    requireUnchangedLoan(loan, plain);
                    checkTotpRead(work);
                }
            }
            return new Verified(envelope, key, plain, manifest);
        } catch (Throwable failure) {
            if (manifest != null) manifest.close();
            VaultCodec.wipe(key); VaultCodec.wipe(plain);
            throw failure;
        }
    }

    private void unchangedTotpRead(TotpReadWork work) throws VaultException {
        try {
            byte[] actual = store.read();
            try {
                if (!Arrays.equals(work.ciphertext, actual))
                    throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            } finally { VaultCodec.wipe(actual); }
        } catch (IOException failure) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
    }

    private void finishTotpRead(TotpReadWork work) {
        try { work.close(); }
        finally { synchronized (this) { if (totpReadWork == work) totpReadWork = null; } }
    }

    private VaultOperation beginTotpRead(TotpReadContext context, VaultOperation.Purpose purpose,
            String resource) throws VaultException {
        TotpReadWork work = captureTotpRead(context, null, purpose, resource);
        try (Verified verified = openTotpRead(work)) {
            if (purpose == VaultOperation.Purpose.TOTP_CODE)
                findEntry(verified.manifest, resource, VaultManifest.EntryType.TOTP);
            VaultOperation result = new VaultOperation(VaultOperation.Consumer.TOTP, purpose, resource,
                    "", "", verified.envelope, context.epoch, context.deadline);
            result.attachTotpRead(new TotpReadState(context, work.revision, work.mixed));
            try {
                synchronized (this) {
                    checkTotpRead(work);
                    unchangedTotpRead(work);
                    checkTotpRead(work);
                    operations.values().removeIf(o -> o.consumed || o.cancelled || o.epoch != context.epoch
                            || clock.now() >= o.deadline);
                    if (operations.size() >= 64) throw new VaultException("BUSY", "Too many secret operations");
                    context.guard.publish(() -> {
                        synchronized (publicationLock) { synchronized (session) {
                            checkTotpRead(work);
                            context.issued = result;
                            registerOperation(result);
                        } }
                    });
                }
                checkTotpRead(work);
                return result;
            } catch (Throwable failure) {
                cancelOperation(result);
                throw failure;
            }
        } finally { finishTotpRead(work); }
    }

    private void completeTotpRead(TotpReadWork work, long unixSeconds) throws VaultException {
        try {
            synchronized (this) {
                checkTotpRead(work);
                unchangedTotpRead(work);
                checkTotpRead(work);
                work.context.guard.publish(() -> {
                    synchronized (publicationLock) { synchronized (session) {
                        checkTotpRead(work);
                        TotpReadState state = work.operation.totpReadState();
                        state.window = unixSeconds / 30;
                        state.completed = true;
                    } }
                });
            }
            checkTotpRead(work);
        }
        catch (Throwable failure) { cancelOperation(work.operation); throw failure; }
    }

    void publishTotpRead(VaultOperation operation, EffectGuard effect) throws VaultException {
        publishTotpRead(operation, () -> System.currentTimeMillis() / 1000, effect);
    }

    /** Clock and effect are bounded host callbacks. List transport must not re-enter core. */
    void publishTotpRead(VaultOperation operation, java.util.function.LongSupplier unixSeconds,
            EffectGuard effect) throws VaultException {
        if (operation == null || operation.totpReadState() == null) throw denied();
        TotpReadState state = operation.totpReadState();
        if (state.context.owner != this || state.context.issued != operation) throw denied();
        state.context.guard.publish(() -> {
            synchronized (publicationLock) { synchronized (session) {
                checkTotpReceipt(operation, state);
                if (state.reserved) throw denied();
                state.reserved = true;
            } }
        });
        // Original phase has been released; preserve actual disclosure ordering vs session.lock.
        synchronized (publicationLock) { synchronized (session) {
            checkTotpReceipt(operation, state);
            long seconds = unixSeconds.getAsLong();
            if (seconds < 0 || seconds / 30 != state.window)
                throw new VaultException("TOTP_TIME_CHANGED", "Code time changed; refresh and retry");
            checkTotpReceipt(operation, state);
            effect.check();
        } }
    }

    private void checkTotpReceipt(VaultOperation operation, TotpReadState state) throws VaultException {
        if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        state.context.guard.check();
        checkTotpReadMetadata(state.context, state.mixed);
        if (!operation.consumed || !state.completed) throw denied();
        if (contentRevision != state.revision)
            throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
    }
    TotpMutationOrigin newTotpMutationOrigin(long epoch, long deadline, EffectGuard guard) throws VaultException {
        if (guard == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        return new TotpMutationOrigin(this, epoch, deadline, validatorRegistration, guard);
    }
    TotpMutationOrigin newTotpMutationOrigin(EffectGuard guard) throws VaultException {
        long deadline = clock.now() + 120000;
        foreground(); return newTotpMutationOrigin(session.epoch(), deadline, guard);
    }
    private TotpMutationContext newTotpMutationContext() throws VaultException {
        return newTotpMutationOrigin(() -> { }).context();
    }
    void checkTotpMutationOrigin(TotpMutationOrigin origin) throws VaultException {
        if (origin == null || origin.owner != this) throw denied();
        origin.check();
        synchronized (session) { checkTotpMutationOriginMetadata(origin, contentRevision, false); }
    }
    private void checkTotpMutationMetadata(TotpMutationContext context, long revision, boolean mixed) throws VaultException {
        if (context.cancelled || context.issued != null && context.issued.cancelled)
            throw new VaultException("VAULT_CANCELLED", "TOTP request was cancelled");
        checkTotpMutationOriginMetadata(context.origin, revision, mixed);
    }
    private void checkTotpMutationOriginMetadata(TotpMutationOrigin origin, long revision, boolean mixed) throws VaultException {
        if (origin.cancelled) throw new VaultException("VAULT_CANCELLED", "TOTP request was cancelled");
        if (clock.now() >= origin.deadline) throw new VaultException("VAULT_EXPIRED", "TOTP request expired");
        foreground(); session.assertEpoch(origin.epoch);
        if (!session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        if (contentRevision != revision || origin.pinned && origin.revision != revision)
            throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
        if (mixed || origin.pinned && origin.mixed) {
            if (validatorRegistration != origin.validator) throw new VaultException("VAULT_CHANGED", "SSH validation context changed");
            if (origin.validator.validator == null) throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH validation is unavailable");
        }
    }
    private void checkTotpMutation(TotpMutationWork work) throws VaultException {
        work.context.origin.check();
        synchronized (session) { checkTotpMutationMetadata(work.context, work.revision, work.mixed); }
    }
    private TotpMutationWork captureTotpMutation(TotpMutationContext context, VaultOperation operation,
            VaultOperation.Purpose purpose, String id) throws VaultException {
        TotpMutationWork work = null;
        try {
            synchronized (this) {
                if (operation != null) {
                    if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "TOTP request was cancelled");
                    TotpMutationState state = operation.totpMutationState();
                    if (state == null || operations.get(operation.requestId) != operation || operation.consumed
                            || operation.consumer != VaultOperation.Consumer.TOTP || operation.purpose != purpose
                            || !operation.resource.equals(id) || !operation.target.isEmpty() || !operation.connectionId.isEmpty()
                            || state.context.origin.owner != this || state.context.issued != operation) throw denied();
                    context = state.context;
                }
                if (context == null || context.origin.owner != this || operation == null && context.admitted) throw denied();
                if (operation == null) context.admitted = true;
                if (totpMutationWork != null) throw new VaultException("BUSY", "A TOTP mutation is in progress");
                if (operation != null) { operation.consumed = true; operations.remove(operation.requestId, operation); }
                work = new TotpMutationWork(context, operation, contentRevision); totpMutationWork = work;
                if (operation != null) work.mixed = operation.totpMutationState().mixed;
                checkTotpMutation(work);
                synchronized (session) { work.key = session.key(); }
                work.ciphertext = store.read(); checkTotpMutation(work);
                if (work.ciphertext == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
            }
            return work;
        } catch (IOException failure) {
            if (work != null) finishTotpMutation(work);
            throw new VaultException("VAULT_IO", "Unable to read the vault");
        } catch (Throwable failure) { if (work != null) finishTotpMutation(work); throw failure; }
    }
    private Verified openTotpMutation(TotpMutationWork work) throws VaultException {
        byte[] key = null, plain = null; VaultManifest manifest = null;
        try {
            checkTotpMutation(work);
            VaultEnvelope envelope = VaultEnvelope.parse(work.ciphertext);
            key = work.key.clone(); plain = envelope.decrypt(key); manifest = VaultManifest.decode(plain);
            work.mixed = hasSsh(manifest);
            checkTotpMutation(work);
            byte[] binding = envelope.binding();
            try {
                synchronized (session) {
                    TotpMutationOrigin origin = work.context.origin;
                    checkTotpMutationMetadata(work.context, work.revision, work.mixed);
                    if (origin.pinned && (origin.mixed != work.mixed || !MessageDigest.isEqual(origin.binding, binding)))
                        throw new VaultException("VAULT_CHANGED", "The original TOTP snapshot changed");
                    if (!origin.pinned) {
                        origin.binding = binding.clone(); origin.revision = work.revision; origin.mixed = work.mixed; origin.pinned = true;
                    }
                }
            } finally { VaultCodec.wipe(binding); }
            if (work.mixed) validateTotpMutationLoan(work, plain);
            checkTotpMutation(work);
            return new Verified(envelope, key, plain, manifest);
        } catch (Throwable failure) {
            if (manifest != null) manifest.close(); VaultCodec.wipe(key); VaultCodec.wipe(plain); throw failure;
        }
    }
    private void validateTotpMutationLoan(TotpMutationWork work, byte[] canonical) throws VaultException {
        checkTotpMutation(work);
        try (VaultManifest loan = VaultManifest.decode(canonical)) {
            work.context.origin.validator.validator.validate(loan);
            checkTotpMutation(work); requireUnchangedLoan(loan, canonical); checkTotpMutation(work);
        }
    }
    private void unchangedTotpMutation(TotpMutationWork work) throws VaultException {
        try {
            byte[] actual = store.read();
            try { if (!Arrays.equals(actual, work.ciphertext)) throw new VaultException("VAULT_CHANGED", "The vault changed"); }
            finally { VaultCodec.wipe(actual); }
        } catch (IOException failure) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
    }
    private void finishTotpMutation(TotpMutationWork work) {
        try { work.close(); } finally { synchronized (this) { if (totpMutationWork == work) totpMutationWork = null; } }
    }
    private VaultOperation beginTotpMutation(String id, VaultOperation.Purpose purpose, TotpMutationContext context) throws VaultException {
        if (id == null || id.isEmpty()) throw denied();
        TotpMutationWork work = captureTotpMutation(context, null, purpose, id);
        try (Verified verified = openTotpMutation(work)) {
            if (purpose != VaultOperation.Purpose.TOTP_SAVE) findEntry(verified.manifest, id, VaultManifest.EntryType.TOTP);
            VaultOperation result = new VaultOperation(VaultOperation.Consumer.TOTP, purpose, id, "", "",
                    verified.envelope, context.origin.epoch, context.origin.deadline);
            result.attachTotpMutation(new TotpMutationState(context, work.revision, work.mixed));
            try {
                synchronized (this) { checkTotpMutation(work); unchangedTotpMutation(work); checkTotpMutation(work); }
                context.origin.guard.publish(() -> {
                    synchronized (publicationLock) { synchronized (session) {
                        checkTotpMutationMetadata(context, work.revision, work.mixed);
                        if (context.origin.handedOff) throw denied();
                        operations.values().removeIf(o -> o.consumed || o.cancelled || o.epoch != context.origin.epoch
                                || clock.now() >= o.deadline);
                        context.issued = result; registerOperation(result);
                    } }
                });
                checkTotpMutation(work); return result;
            } catch (Throwable failure) { cancelOperation(result); throw failure; }
        } finally { finishTotpMutation(work); }
    }
    VaultOperation beginTotpSave(String id) throws VaultException { return beginTotpSave(id, newTotpMutationContext()); }
    VaultOperation beginTotpSave(String id, TotpMutationContext context) throws VaultException {
        return beginTotpMutation(id, VaultOperation.Purpose.TOTP_SAVE, context);
    }
    VaultOperation beginTotpRename(String id) throws VaultException { return beginTotpRename(id, newTotpMutationContext()); }
    VaultOperation beginTotpRename(String id, TotpMutationContext context) throws VaultException {
        return beginTotpMutation(id, VaultOperation.Purpose.TOTP_RENAME, context);
    }
    VaultOperation beginTotpDelete(String id) throws VaultException { return beginTotpDelete(id, newTotpMutationContext()); }
    VaultOperation beginTotpDelete(String id, TotpMutationContext context) throws VaultException {
        return beginTotpMutation(id, VaultOperation.Purpose.TOTP_DELETE, context);
    }
    void renameTotp(VaultOperation operation, String id, String label, EffectGuard guard) throws VaultException {
        TotpInput.metadata(label, false); mutateTotp(operation, VaultOperation.Purpose.TOTP_RENAME, id, null, label, false, guard);
    }
    void deleteTotp(VaultOperation operation, String id, EffectGuard guard) throws VaultException {
        mutateTotp(operation, VaultOperation.Purpose.TOTP_DELETE, id, null, null, false, guard);
    }
    void saveTotp(VaultOperation operation, String id, String identity, String label, byte[] seed, EffectGuard guard) throws VaultException {
        saveTotp(operation, id, identity, label, seed, false, guard);
    }
    void addTotp(VaultOperation operation, String id, String identity, String label, byte[] seed, EffectGuard guard) throws VaultException {
        saveTotp(operation, id, identity, label, seed, true, guard);
    }
    private void saveTotp(VaultOperation operation, String id, String identity, String label, byte[] seed, boolean add, EffectGuard guard) throws VaultException {
        if (seed == null || seed.length < 10 || seed.length > 128) throw VaultException.invalid();
        VaultManifest.Entry replacement = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, id, identity, label, "SHA1", 6, 30, seed);
        try { mutateTotp(operation, VaultOperation.Purpose.TOTP_SAVE, id, replacement, null, add, guard); }
        finally { VaultCodec.wipe(replacement.secret); }
    }
    private void mutateTotp(VaultOperation operation, VaultOperation.Purpose purpose, String id,
            VaultManifest.Entry replacement, String label, boolean add, EffectGuard guard) throws VaultException {
        TotpMutationWork work = captureTotpMutation(null, operation, purpose, id);
        try (Verified verified = openTotpMutation(work)) {
            guard.check();
            try (VaultManifest candidate = totpCandidate(verified.manifest, purpose, id, replacement, label, add)) {
                byte[] canonical = candidate.encode();
                try {
                    if (work.mixed) validateTotpMutationLoan(work, canonical);
                    checkTotpMutation(work); guard.check();
                    VaultEnvelope envelope = VaultEnvelope.encrypt(verified.envelope.id, verified.envelope.generation, verified.envelope.salt, verified.key, canonical);
                    verifyReadback(envelope, verified.key, canonical); checkTotpMutation(work); guard.check();
                    byte[] encoded = envelope.encode();
                    try {
                        synchronized (this) {
                            checkTotpMutation(work); guard.check(); byte[] quick = quickMaterial();
                            try {
                                unchangedTotpMutation(work); checkTotpMutation(work); guard.check();
                                commitEncoded(encoded, quick, boundGuard(guard, () -> checkTotpMutation(work)));
                                operations.clear(); cancelPreview(); session.unlockIfEpoch(operation.epoch, verified.key);
                            } finally { VaultCodec.wipe(quick); }
                        }
                    } finally { VaultCodec.wipe(encoded); }
                } finally { VaultCodec.wipe(canonical); }
            }
        } finally {
            if (replacement != null) VaultCodec.wipe(replacement.secret);
            finishTotpMutation(work);
        }
    }
    private static VaultManifest totpCandidate(VaultManifest source, VaultOperation.Purpose purpose, String id,
            VaultManifest.Entry replacement, String label, boolean add) throws VaultException {
        List<VaultManifest.Entry> entries = new ArrayList<>(); List<VaultManifest.FileObject> files = new ArrayList<>();
        boolean found = false;
        try {
            for (VaultManifest.Entry old : source.entries) {
                if (old.id.equals(id)) {
                    if (old.type != VaultManifest.EntryType.TOTP || add) throw denied();
                    found = true;
                    if (purpose == VaultOperation.Purpose.TOTP_RENAME)
                        entries.add(new VaultManifest.Entry(old.type, old.id, old.identity, label, old.algorithm, old.digits, old.period, old.secret));
                } else entries.add(new VaultManifest.Entry(old.type, old.id, old.identity, old.label, old.algorithm, old.digits, old.period, old.secret));
            }
            if (purpose != VaultOperation.Purpose.TOTP_SAVE && !found) throw denied();
            if (replacement != null) entries.add(new VaultManifest.Entry(replacement.type, replacement.id, replacement.identity,
                    replacement.label, replacement.algorithm, replacement.digits, replacement.period, replacement.secret));
            for (VaultManifest.FileObject file : source.files)
                files.add(new VaultManifest.FileObject(file.type, file.id, file.path, file.identity, file.content, file.references));
            return source.withObjects(entries, files);
        } catch (Throwable failure) {
            for (VaultManifest.Entry entry : entries) VaultCodec.wipe(entry.secret);
            for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
            throw failure;
        }
    }
    private TotpMutationState pendingTotpState(VaultOperation operation) throws VaultException {
        if (operation == null || operation.consumer != VaultOperation.Consumer.TOTP || operation.purpose != VaultOperation.Purpose.TOTP_SAVE
                || operations.get(operation.requestId) != operation || operation.consumed) throw denied();
        TotpMutationState state = operation.totpMutationState();
        if (state == null || state.context.origin.owner != this || state.context.issued != operation) throw denied();
        return state;
    }
    void checkTotpPending(VaultOperation operation) throws VaultException {
        TotpMutationState state = pendingTotpState(operation); state.context.origin.check();
        synchronized (this) {
            synchronized (session) { checkTotpMutationMetadata(state.context, state.revision, state.mixed); }
            unchanged(operation.snapshotBinding);
            synchronized (session) { checkTotpMutationMetadata(state.context, state.revision, state.mixed); }
        }
    }
    long totpPublication(VaultOperation operation) throws VaultException { checkTotpPending(operation); return operation.totpMutationState().revision; }
    void bindTotpPreview(TotpMutationOrigin origin, Object ticket, List<VaultOperation> candidates) throws VaultException {
        if (origin == null || origin.owner != this || ticket == null || candidates.isEmpty() || candidates.size() > 16) throw denied();
        synchronized (session) {
            if (origin.ticket != null || origin.handedOff || origin.cancelled) throw denied();
            for (VaultOperation candidate : candidates) {
                TotpMutationState state = pendingTotpState(candidate);
                if (state.context.origin != origin) throw denied();
                checkTotpMutationMetadata(state.context, state.revision, state.mixed);
            }
            origin.ticket = ticket; origin.candidates = new ArrayList<>(candidates);
        }
    }
    void publishTotpPending(TotpMutationOrigin origin, Object ticket, EffectGuard transport) throws VaultException {
        try {
            origin.guard.publish(() -> {
                synchronized (publicationLock) { synchronized (session) {
                    if (origin.owner != this || origin.ticket != ticket || origin.handedOff || origin.candidates == null) throw denied();
                    for (VaultOperation candidate : origin.candidates) {
                        TotpMutationState state = pendingTotpState(candidate);
                        checkTotpMutationMetadata(state.context, state.revision, state.mixed);
                    }
                    origin.handedOff = true;
                    transport.check();
                } }
            });
        } catch (Throwable failure) { origin.cancel(); throw failure; }
    }

    VaultOperation beginSshSave(String id, String target, String connectionId) throws VaultException {
        return beginSshSave(id, target, connectionId, newSshEntryMutationContext(), () -> { });
    }
    VaultOperation beginSshSave(String id, String target, String connectionId, SshEntryMutationContext context,
            EffectGuard authorization) throws VaultException {
        if (connectionId.isEmpty()) throw denied();
        return beginSshEntryMutation(VaultOperation.Purpose.SSH_SAVE, id, target, connectionId, context, authorization);
    }
    void saveSsh(VaultOperation operation, String id, String target, String connectionId,
            String label, boolean keyPassphrase, byte[] secret, EffectGuard guard) throws VaultException {
        if (secret.length == 0 || secret.length > 8192) throw VaultException.invalid();
        VaultManifest.Entry entry = new VaultManifest.Entry(keyPassphrase ? VaultManifest.EntryType.SSH_KEY_PASSPHRASE
                : VaultManifest.EntryType.SSH_PASSWORD, id, target, label, "", 0, 0, secret);
        try { mutateSshEntry(operation, VaultOperation.Purpose.SSH_SAVE, id, target, connectionId, entry, guard); }
        finally { VaultCodec.wipe(entry.secret); }
    }
    VaultOperation beginSshDelete(String id, String target) throws VaultException {
        return beginSshDelete(id, target, newSshEntryMutationContext());
    }
    VaultOperation beginSshDelete(String id, String target, SshEntryMutationContext context) throws VaultException {
        return beginSshEntryMutation(VaultOperation.Purpose.SSH_DELETE, id, target, "", context, () -> { });
    }
    void deleteSsh(VaultOperation operation, String id, String target, EffectGuard guard) throws VaultException {
        mutateSshEntry(operation, VaultOperation.Purpose.SSH_DELETE, id, target, "", null, guard);
    }

    SshEntryMutationContext newSshEntryMutationContext(long epoch, long deadline, EffectGuard guard)
            throws VaultException {
        if (guard == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        return new SshEntryMutationContext(this, epoch, deadline, guard, validatorRegistration);
    }

    SshEntryMutationContext newPasswordMutationContext(long epoch, long deadline, EffectGuard guard,
            SshPasswordProfile profile, boolean all) throws VaultException {
        if (guard == null || profile == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        return new SshEntryMutationContext(this, epoch, deadline, guard, validatorRegistration,
                new SshSavedPassword(profile), all);
    }

    SshEntryMutationContext newPasswordForgetAllContext(long epoch, long deadline, EffectGuard guard)
            throws VaultException {
        if (guard == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        return new SshEntryMutationContext(this, epoch, deadline, guard, validatorRegistration, null, true);
    }

    VaultOperation beginPasswordMutation(SshEntryMutationContext context, String connectionId, boolean save)
            throws VaultException {
        if (context == null || context.owner != this || context.passwordAccount == null && !context.forgetAllPasswords
                || save && (context.forgetAllPasswords || connectionId.isEmpty())) throw denied();
        SshSavedPassword account = context.passwordAccount;
        return beginSshEntryMutation(save ? VaultOperation.Purpose.SSH_SAVE : VaultOperation.Purpose.SSH_DELETE,
                context.forgetAllPasswords ? SshSavedPassword.ALL_ID : account.entryId,
                context.forgetAllPasswords ? SshPasswordProfile.TARGET : account.target,
                save ? connectionId : "", context, () -> { });
    }

    void savePassword(VaultOperation operation, byte[] secret, EffectGuard guard) throws VaultException {
        SshEntryMutationState state = operation == null ? null : operation.sshEntryMutationState();
        if (state == null || state.context.passwordAccount == null || state.context.forgetAllPasswords) throw denied();
        SshSavedPassword account = state.context.passwordAccount;
        SshSavedPassword.validateSecret(secret);
        saveSsh(operation, account.entryId, account.target, operation.connectionId,
                account.profile.username, false, secret, guard);
    }

    void forgetPassword(VaultOperation operation, EffectGuard guard) throws VaultException {
        SshEntryMutationState state = operation == null ? null : operation.sshEntryMutationState();
        if (state == null || state.context.passwordAccount == null && !state.context.forgetAllPasswords) throw denied();
        deleteSsh(operation, operation.resource, operation.target, guard);
    }

    SshEntryMutationContext newSshEntryMutationContext(long deadline, EffectGuard guard) throws VaultException {
        synchronized (session) {
            foreground();
            return newSshEntryMutationContext(session.epoch(), deadline, guard);
        }
    }

    SshEntryMutationContext newSshEntryMutationContext() throws VaultException {
        return newSshEntryMutationContext(clock.now() + 120000, () -> { });
    }

    private void checkSshEntryMutationMetadata(SshEntryMutationWork work) throws VaultException {
        SshEntryMutationContext context = work.context;
        if (context.cancelled || work.operation != null && work.operation.cancelled)
            throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        if (clock.now() >= context.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        foreground();
        session.assertEpoch(context.epoch);
        if (!session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        if (validatorRegistration != context.validator || contentRevision != work.revision)
            throw new VaultException("VAULT_CHANGED", "SSH validation context changed; try again");
        if (context.validator.validator == null)
            throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH file validation is not available yet");
    }

    private void checkSshEntryMutation(SshEntryMutationWork work) throws VaultException {
        work.context.guard.check();
        work.authorization.check();
        synchronized (session) { checkSshEntryMutationMetadata(work); }
    }

    private SshEntryMutationWork captureSshEntryMutation(SshEntryMutationContext context, VaultOperation operation,
            VaultOperation.Purpose purpose, String id, String target, String connectionId,
            EffectGuard authorization) throws VaultException {
        SshEntryMutationWork captured = null;
        try {
            if (operation != null) {
                if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                SshEntryMutationState state = operation.sshEntryMutationState();
                if (state == null) throw denied();
                context = state.context;
                authorization = state.authorization;
            }
            if (context == null || context.owner != this || authorization == null) throw denied();
            context.guard.check();
            authorization.check();
            synchronized (this) {
                if (operation != null) {
                    if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                    if (operations.get(operation.requestId) != operation || operation.consumed
                            || operation.consumer != VaultOperation.Consumer.SSH || operation.purpose != purpose
                            || !operation.resource.equals(id) || !operation.target.equals(target)
                            || !operation.connectionId.equals(connectionId) || context.issued != operation) throw denied();
                } else {
                    if (context.admitted) throw denied();
                    context.admitted = true;
                }
                if (sshEntryMutationWork != null)
                    throw new VaultException("BUSY", "An SSH entry mutation is already in progress");
                if (operation != null) {
                    operation.consumed = true;
                    operations.remove(operation.requestId, operation);
                }
                captured = new SshEntryMutationWork(context, operation, authorization, contentRevision);
                sshEntryMutationWork = captured;
                if (operation != null && operation.sshEntryMutationState().revision != captured.revision)
                    throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
                synchronized (session) {
                    checkSshEntryMutationMetadata(captured);
                    captured.key = session.key();
                }
                captured.ciphertext = store.read();
                synchronized (session) { checkSshEntryMutationMetadata(captured); }
                if (captured.ciphertext == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
                return captured;
            }
        } catch (IOException failure) {
            if (captured != null) finishSshEntryMutation(captured);
            throw new VaultException("VAULT_IO", "Unable to read the vault");
        } catch (Throwable failure) {
            if (captured != null) finishSshEntryMutation(captured);
            throw failure;
        }
    }

    private Verified openSshEntryMutation(SshEntryMutationWork work) throws VaultException {
        byte[] key = null, plain = null;
        VaultManifest manifest = null;
        try {
            checkSshEntryMutation(work);
            VaultEnvelope envelope = VaultEnvelope.parse(work.ciphertext);
            if (work.operation != null && (!work.operation.vaultId.equals(envelope.id)
                    || work.operation.generation != envelope.generation
                    || !MessageDigest.isEqual(work.operation.snapshotBinding, envelope.binding())))
                throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            checkSshEntryMutation(work);
            key = work.key.clone(); plain = envelope.decrypt(key);
            checkSshEntryMutation(work);
            manifest = VaultManifest.decode(plain);
            checkSshEntryMutation(work);
            if (hasSsh(manifest)) validateSshEntryMutationLoan(work, plain);
            return new Verified(envelope, key, plain, manifest);
        } catch (Throwable failure) {
            if (manifest != null) manifest.close();
            VaultCodec.wipe(key); VaultCodec.wipe(plain);
            throw failure;
        }
    }

    private void validateSshEntryMutationLoan(SshEntryMutationWork work, byte[] canonical) throws VaultException {
        checkSshEntryMutation(work);
        try (VaultManifest loan = VaultManifest.decode(canonical)) {
            checkSshEntryMutation(work);
            work.context.validator.validator.validate(loan);
            checkSshEntryMutation(work);
            requireUnchangedLoan(loan, canonical);
            checkSshEntryMutation(work);
        }
    }

    private void unchangedSshEntryMutation(SshEntryMutationWork work) throws VaultException {
        try {
            byte[] actual = store.read();
            try {
                if (!Arrays.equals(work.ciphertext, actual))
                    throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            } finally { VaultCodec.wipe(actual); }
        } catch (IOException failure) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
    }

    private void finishSshEntryMutation(SshEntryMutationWork work) {
        try { work.close(); }
        finally { synchronized (this) { if (sshEntryMutationWork == work) sshEntryMutationWork = null; } }
    }

    private VaultOperation beginSshEntryMutation(VaultOperation.Purpose purpose, String id, String target,
            String connectionId, SshEntryMutationContext context, EffectGuard authorization) throws VaultException {
        if (id.isEmpty()) throw denied();
        SshEntryMutationWork work = captureSshEntryMutation(context, null, purpose, id, target, connectionId, authorization);
        try (Verified verified = openSshEntryMutation(work)) {
            if (purpose == VaultOperation.Purpose.SSH_DELETE && context.passwordAccount == null && !context.forgetAllPasswords
                    && !findSshEntry(verified.manifest, id).identity.equals(target)) throw denied();
            VaultOperation result = new VaultOperation(VaultOperation.Consumer.SSH, purpose, id, target,
                    connectionId, verified.envelope, context.epoch, context.deadline);
            result.attachSshEntryMutation(new SshEntryMutationState(context, authorization, work.revision));
            try {
                synchronized (this) {
                    checkSshEntryMutation(work);
                    unchangedSshEntryMutation(work);
                    checkSshEntryMutation(work);
                }
                context.guard.publish(() -> {
                    authorization.check();
                    synchronized (publicationLock) { synchronized (session) {
                        checkSshEntryMutationMetadata(work);
                        operations.values().removeIf(o -> o.consumed || o.cancelled || o.epoch != context.epoch
                                || clock.now() >= o.deadline);
                        context.issued = result;
                        registerOperation(result);
                    } }
                });
                checkSshEntryMutation(work);
                return result;
            } catch (Throwable failure) { cancelOperation(result); throw failure; }
        } finally { finishSshEntryMutation(work); }
    }

    private void mutateSshEntry(VaultOperation operation, VaultOperation.Purpose purpose,
            String id, String target, String connectionId, VaultManifest.Entry replacement, EffectGuard guard)
            throws VaultException {
        if (guard == null) throw VaultException.invalid();
        SshEntryMutationWork work = captureSshEntryMutation(null, operation, purpose, id, target, connectionId, null);
        try (Verified verified = openSshEntryMutation(work)) {
            guard.check();
            try (VaultManifest candidate = work.context.passwordAccount == null && !work.context.forgetAllPasswords
                    ? sshEntryCandidate(verified.manifest, id, target, replacement)
                    : SshSavedPassword.candidate(verified.manifest, work.context.passwordAccount,
                            work.context.forgetAllPasswords, replacement)) {
                checkSshEntryMutation(work); guard.check();
                byte[] canonical = candidate.encode();
                try {
                    validateSshEntryMutationLoan(work, canonical);
                    guard.check();
                    VaultEnvelope envelope = VaultEnvelope.encrypt(verified.envelope.id, verified.envelope.generation,
                            verified.envelope.salt, verified.key, canonical);
                    checkSshEntryMutation(work); guard.check();
                    verifyReadback(envelope, verified.key, canonical);
                    checkSshEntryMutation(work); guard.check();
                    byte[] encoded = envelope.encode();
                    try {
                        synchronized (this) {
                            checkSshEntryMutation(work); guard.check();
                            byte[] quick = quickMaterial();
                            try {
                                unchangedSshEntryMutation(work);
                                checkSshEntryMutation(work); guard.check();
                                commitEncoded(encoded, quick, boundGuard(guard, () -> checkSshEntryMutation(work)));
                                // commitEncoded advanced our revision. No stale-snapshot check after
                                // this durable claim: late cancellation is the caller's uncertain path.
                                operations.clear();
                                cancelPreview();
                                session.unlockIfEpoch(operation.epoch, verified.key);
                            } finally { VaultCodec.wipe(quick); }
                        }
                    } finally { VaultCodec.wipe(encoded); }
                } finally { VaultCodec.wipe(canonical); }
            }
        } finally {
            if (replacement != null) VaultCodec.wipe(replacement.secret);
            finishSshEntryMutation(work);
        }
    }

    private static VaultManifest sshEntryCandidate(VaultManifest source, String id, String target,
            VaultManifest.Entry replacement) throws VaultException {
        List<VaultManifest.Entry> entries = new ArrayList<>();
        List<VaultManifest.FileObject> files = new ArrayList<>();
        try {
            for (VaultManifest.Entry old : source.entries) {
                if (old.id.equals(id)) {
                    if (old.type == VaultManifest.EntryType.TOTP || !old.identity.equals(target)) throw denied();
                } else entries.add(new VaultManifest.Entry(old.type, old.id, old.identity, old.label,
                        old.algorithm, old.digits, old.period, old.secret));
            }
            if (replacement != null) entries.add(new VaultManifest.Entry(replacement.type, replacement.id,
                    replacement.identity, replacement.label, replacement.algorithm, replacement.digits,
                    replacement.period, replacement.secret));
            for (VaultManifest.FileObject file : source.files)
                files.add(new VaultManifest.FileObject(file.type, file.id, file.path, file.identity, file.content, file.references));
            return source.withObjects(entries, files);
        } catch (Throwable failure) {
            for (VaultManifest.Entry entry : entries) VaultCodec.wipe(entry.secret);
            for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
            throw failure;
        }
    }

    SshTrustContext newSshTrustContext(long epoch, long deadline, SshTrustProposal proposal, EffectGuard guard)
            throws VaultException {
        if (guard == null || proposal == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        return new SshTrustContext(this, epoch, deadline, guard, validatorRegistration, proposal);
    }

    SshTrustContext newSshTrustContext(SshTrustProposal proposal) throws VaultException {
        long deadline = clock.now() + 120000;
        synchronized (session) {
            foreground();
            return newSshTrustContext(session.epoch(), deadline, proposal, () -> { });
        }
    }

    private void checkSshTrustMetadata(SshTrustWork work, boolean committed) throws VaultException {
        SshTrustContext context = work.context;
        if (context.cancelled || work.operation != null && work.operation.cancelled)
            throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        if (clock.now() >= context.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        foreground();
        session.assertEpoch(context.epoch);
        if (!session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        if (validatorRegistration != context.validator || contentRevision != work.revision + (committed ? 1 : 0))
            throw new VaultException("VAULT_CHANGED", "SSH validation context changed; try again");
        if (context.validator.validator == null)
            throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH file validation is not available yet");
    }

    private void checkSshTrustWork(SshTrustWork work, boolean committed) throws VaultException {
        work.context.guard.check();
        work.authorization.check();
        work.context.proposal.requireOpen();
        synchronized (session) { checkSshTrustMetadata(work, committed); }
        if (work.continuation != null) {
            work.role.requireActive(clock::now);
            synchronized (session) {
                foreground();
                if (session.attachedRevocation(work.context.epoch) != work.continuation
                        || !work.continuation.active()
                        || !work.role.ownsPublicView((SshRoleContext.PublicView) work.continuation))
                    throw new VaultException("VAULT_CHANGED", "The SSH public loan changed");
            }
        }
    }

    private SshTrustWork captureSshTrust(SshTrustContext context, VaultOperation operation,
            SshTrustProposal proposal, EffectGuard authorization) throws VaultException {
        SshTrustWork captured = null;
        try {
            if (operation != null) {
                if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                SshTrustState state = operation.sshTrustState();
                if (state == null) throw denied();
                context = state.context; authorization = state.authorization;
            }
            if (context == null || context.owner != this || context.proposal != proposal || authorization == null)
                throw denied();
            context.guard.check(); authorization.check(); proposal.requireOpen();
            synchronized (this) {
                if (operation != null) {
                    if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                    if (operations.get(operation.requestId) != operation || operation.consumed
                            || operation.consumer != VaultOperation.Consumer.SSH
                            || operation.purpose != VaultOperation.Purpose.SSH_TRUST_UPDATE
                            || !operation.resource.equals(proposal.binding)
                            || !operation.target.equals(proposal.targetIdentity)
                            || !operation.connectionId.equals(proposal.connectionId) || context.issued != operation)
                        throw denied();
                } else {
                    if (context.admitted) throw denied();
                    context.admitted = true;
                }
                if (sshTrustWork != null) throw new VaultException("BUSY", "A trust update is already in progress");
                if (operation != null) {
                    operation.consumed = true;
                    operations.remove(operation.requestId, operation);
                }
                captured = new SshTrustWork(context, operation, authorization, contentRevision);
                sshTrustWork = captured;
                if (operation != null && operation.sshTrustState().revision != captured.revision)
                    throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
                synchronized (session) {
                    checkSshTrustMetadata(captured, false);
                    captured.key = session.key();
                    if (operation != null) {
                        captured.role = operation.boundTrustRole();
                        captured.continuation = captured.role == null ? null : session.attachedRevocation(context.epoch);
                        if (captured.continuation != null && (!(captured.continuation instanceof SshRoleContext.PublicView)
                                || !captured.role.ownsPublicView((SshRoleContext.PublicView) captured.continuation)))
                            throw new VaultException("VAULT_CHANGED", "The SSH public loan changed");
                    }
                }
                captured.ciphertext = store.read();
                synchronized (session) { checkSshTrustMetadata(captured, false); }
                if (captured.ciphertext == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
            }
            // A close may wipe the proposal, but never this worker's independent body.
            if (operation != null) {
                captured.replacement = proposal.newContent();
                captured.baseDigest = proposal.baseDigest();
            }
            checkSshTrustWork(captured, false);
            return captured;
        } catch (IOException failure) {
            if (captured != null) finishSshTrust(captured);
            throw new VaultException("VAULT_IO", "Unable to read the vault");
        } catch (Throwable failure) {
            if (captured != null) finishSshTrust(captured);
            throw failure;
        }
    }

    private Verified openSshTrust(SshTrustWork work) throws VaultException {
        byte[] key = null, plain = null;
        VaultManifest manifest = null;
        try {
            checkSshTrustWork(work, false);
            VaultEnvelope envelope = VaultEnvelope.parse(work.ciphertext);
            if (work.operation != null && (!work.operation.vaultId.equals(envelope.id)
                    || work.operation.generation != envelope.generation
                    || !MessageDigest.isEqual(work.operation.snapshotBinding, envelope.binding())))
                throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            checkSshTrustWork(work, false);
            key = work.key.clone(); plain = envelope.decrypt(key);
            checkSshTrustWork(work, false);
            manifest = VaultManifest.decode(plain);
            validateSshTrustLoan(work, plain);
            return new Verified(envelope, key, plain, manifest);
        } catch (Throwable failure) {
            if (manifest != null) manifest.close();
            VaultCodec.wipe(key); VaultCodec.wipe(plain);
            throw failure;
        }
    }

    private void validateSshTrustLoan(SshTrustWork work, byte[] canonical) throws VaultException {
        checkSshTrustWork(work, false);
        try (VaultManifest loan = VaultManifest.decode(canonical)) {
            checkSshTrustWork(work, false);
            work.context.validator.validator.validate(loan);
            checkSshTrustWork(work, false);
            requireUnchangedLoan(loan, canonical);
            checkSshTrustWork(work, false);
        }
    }

    private void unchangedSshTrust(SshTrustWork work) throws VaultException {
        try {
            byte[] actual = store.read();
            try {
                if (!Arrays.equals(work.ciphertext, actual))
                    throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            } finally { VaultCodec.wipe(actual); }
        } catch (IOException failure) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
    }

    private void finishSshTrust(SshTrustWork work) {
        try { work.close(); }
        finally { synchronized (this) { if (sshTrustWork == work) sshTrustWork = null; } }
    }

    VaultOperation beginTrustUpdate(SshTrustProposal proposal) throws VaultException {
        return beginTrustUpdate(proposal, newSshTrustContext(proposal), () -> { });
    }

    VaultOperation beginTrustUpdate(SshTrustProposal proposal, SshTrustContext context, EffectGuard authorization)
            throws VaultException {
        SshTrustWork work = captureSshTrust(context, null, proposal, authorization);
        try (Verified verified = openSshTrust(work)) {
            requireTrustObject(verified.manifest, proposal);
            VaultOperation result = new VaultOperation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_TRUST_UPDATE,
                    proposal.binding, proposal.targetIdentity, proposal.connectionId, verified.envelope, context.epoch, context.deadline);
            result.attachSshTrust(new SshTrustState(context, authorization, work.revision));
            try {
                synchronized (this) {
                    checkSshTrustWork(work, false); unchangedSshTrust(work); checkSshTrustWork(work, false);
                }
                context.guard.publish(() -> {
                    authorization.check(); proposal.requireOpen();
                    synchronized (publicationLock) { synchronized (session) {
                        checkSshTrustMetadata(work, false);
                        operations.values().removeIf(o -> o.consumed || o.cancelled || o.epoch != context.epoch
                                || clock.now() >= o.deadline);
                        context.issued = result;
                        registerOperation(result);
                    } }
                });
                checkSshTrustWork(work, false);
                return result;
            } catch (Throwable failure) { cancelOperation(result); throw failure; }
        } finally { finishSshTrust(work); }
    }

    private static VaultManifest.FileObject requireTrustObject(VaultManifest manifest, SshTrustProposal proposal)
            throws VaultException {
        VaultManifest.FileObject file = findFile(manifest, proposal.path);
        if (file.type != VaultManifest.FileType.KNOWN_HOSTS || !file.id.equals(proposal.objectId)
                || proposal.objectType != null && file.type != proposal.objectType
                || proposal.objectIdentity != null && !file.identity.equals(proposal.objectIdentity)) throw denied();
        return file;
    }

    void updateTrust(VaultOperation operation, SshTrustProposal proposal, EffectGuard guard) throws VaultException {
        SshTrustWork work = captureSshTrust(null, operation, proposal, null);
        boolean persisted = false;
        try (Verified verified = openSshTrust(work)) {
            guard.check();
            try (VaultManifest candidate = trustCandidate(verified.manifest, proposal, work.baseDigest, work.replacement)) {
                byte[] canonical = candidate.encode();
                try {
                    validateSshTrustLoan(work, canonical); guard.check();
                    VaultEnvelope envelope = VaultEnvelope.encrypt(verified.envelope.id, verified.envelope.generation,
                            verified.envelope.salt, verified.key, canonical);
                    checkSshTrustWork(work, false); guard.check();
                    verifyReadback(envelope, verified.key, canonical);
                    checkSshTrustWork(work, false); guard.check();
                    byte[] encoded = envelope.encode();
                    try {
                        synchronized (this) {
                            checkSshTrustWork(work, false); guard.check();
                            byte[] quick = quickMaterial();
                            try {
                                unchangedSshTrust(work); checkSshTrustWork(work, false); guard.check();
                                commitEncoded(encoded, quick, boundGuard(guard, () -> checkSshTrustWork(work, false)));
                                persisted = true;
                                // Only our exact revision increment is accepted before own-CAS continuation.
                                checkSshTrustWork(work, true); guard.check();
                                synchronized (session) {
                                    checkSshTrustMetadata(work, true);
                                    operations.clear(); cancelPreview();
                                    if (work.continuation == null) {
                                        if (!session.unlockIfEpoch(operation.epoch, verified.key))
                                            throw new VaultException("VAULT_CHANGED", "The trust session changed");
                                    } else work.role.publishBound(clock::now, () -> {
                                        checkSshTrustMetadata(work, true);
                                        if (!session.advanceTrustEpoch(operation.epoch, work.continuation))
                                            throw new VaultException("VAULT_CHANGED", "The SSH public loan changed");
                                    });
                                }
                            } finally { VaultCodec.wipe(quick); }
                        }
                    } finally { VaultCodec.wipe(encoded); }
                } finally { VaultCodec.wipe(canonical); }
            }
        } catch (Throwable failure) {
            if (persisted) throw uncertainTrustCommit(failure);
            throw failure;
        } finally { finishSshTrust(work); }
    }

    private static VaultManifest trustCandidate(VaultManifest source, SshTrustProposal proposal,
            byte[] baseDigest, byte[] replacement) throws VaultException {
        VaultManifest.FileObject selected = requireTrustObject(source, proposal);
        byte[] currentDigest = VaultCodec.digest(selected.content);
        try {
            if (!MessageDigest.isEqual(baseDigest, currentDigest))
                throw new VaultException("VAULT_CHANGED", "Trust content changed; review again");
        } finally { VaultCodec.wipe(currentDigest); }
        List<VaultManifest.Entry> entries = new ArrayList<>();
        List<VaultManifest.FileObject> files = new ArrayList<>();
        try {
            for (VaultManifest.Entry entry : source.entries)
                entries.add(new VaultManifest.Entry(entry.type, entry.id, entry.identity, entry.label,
                        entry.algorithm, entry.digits, entry.period, entry.secret));
            for (VaultManifest.FileObject file : source.files)
                files.add(new VaultManifest.FileObject(file.type, file.id, file.path, file.identity,
                        file == selected ? replacement : file.content, file.references));
            return source.withObjects(entries, files);
        } catch (Throwable failure) {
            for (VaultManifest.Entry entry : entries) VaultCodec.wipe(entry.secret);
            for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
            throw failure;
        }
    }

    VaultException uncertainTrustCommit(Throwable cause) {
        VaultException failure = new VaultException("VAULT_COMMIT_UNCERTAIN",
                "Trust may have been saved; reopen the vault before trying again");
        if (cause != null) failure.addSuppressed(cause);
        try { lock(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
        return failure;
    }

    BundleContext newBundleContext(EffectGuard guard) throws VaultException {
        return newBundleContext(clock.now() + 120000, guard);
    }

    synchronized BundleContext newBundleContext(long deadline, EffectGuard guard) throws VaultException {
        if (guard == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        return new BundleContext(this, session.epoch(), deadline, guard, validatorRegistration);
    }

    void cancelBundleContext(BundleContext context) {
        if (context != null && context.owner == this) context.cancel();
    }

    /** Pins the original registration across only the connector's separately verified own writes.
     * This is not bundle admission: callers still need each context's complete epoch checks. */
    void requirePasswordValidationOrigin(BundleContext context) throws VaultException {
        if (context == null || context.owner != this) throw denied();
        if (context.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        if (clock.now() >= context.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        if (validatorRegistration != context.validator)
            throw new VaultException("VAULT_CHANGED", "SSH validation context changed; try again");
        if (context.validator.validator == null)
            throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH file validation is not available yet");
    }

    void checkBundleContext(BundleContext context) throws VaultException {
        if (context == null || context.owner != this) throw denied();
        context.guard.check();
        synchronized (session) { checkBundleMetadata(context); }
    }

    // Only metadata is checked while holding session/publication fences. In particular,
    // foreground is checked before the session's lazy background expiry methods.
    private void checkBundleMetadata(BundleContext context) throws VaultException {
        if (context.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        if (clock.now() >= context.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        foreground();
        session.assertEpoch(context.epoch);
        if (!session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        requireSshValidator();
        if (validatorRegistration != context.validator)
            throw new VaultException("VAULT_CHANGED", "SSH validation context changed; review again");
    }

    private void checkBundleWork(BundleWork work, SshBundleProposal proposal, VaultOperation operation,
            EffectGuard guard) throws VaultException {
        guard.check();
        checkBundleContext(work.context);
        proposal.requireOpen();
        if (operation != null && operation.cancelled)
            throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
    }

    private BundleWork captureBundle(BundleContext context, SshBundleProposal proposal,
            VaultOperation operation) throws VaultException {
        BundleWork captured = null;
        try {
            synchronized (this) {
                if (context == null || context.owner != this) throw denied();
                if (operation == null) {
                    if (context.admitted) throw denied();
                    context.admitted = true;
                    context.proposalIdentity = proposal.requestIdentity;
                }
                if (bundleWork != null) throw new VaultException("BUSY", "A managed file bundle is already in progress");
                if (operation != null) {
                    if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                    if (operations.get(operation.requestId) != operation || operation.consumed
                            || operation.consumer != VaultOperation.Consumer.SSH
                            || operation.purpose != VaultOperation.Purpose.SSH_BUNDLE_WRITE
                            || operation.bundleContext != context || context.proposalIdentity != proposal.requestIdentity
                            || !operation.resource.equals(proposal.binding)
                            || !operation.target.isEmpty() || !operation.connectionId.isEmpty()) throw denied();
                    // Every accepted effect attempt is consumed, including failed detached work.
                    operation.consumed = true;
                    operations.remove(operation.requestId);
                }
                // Reserve before any caller/store seam so synchronous reentry is BUSY too.
                captured = new BundleWork(context, null, null);
                bundleWork = captured;
                checkBundleContext(context);
                proposal.requireOpen();
                captured.key = session.key();
                captured.ciphertext = store.read();
                checkBundleContext(context);
                if (captured.ciphertext == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
                return captured;
            }
        } catch (IOException error) {
            if (captured != null) finishBundle(captured);
            throw new VaultException("VAULT_IO", "Unable to read the vault");
        } catch (Throwable failure) {
            if (captured != null) finishBundle(captured);
            throw failure;
        }
    }

    private Verified openBundle(BundleWork work, SshBundleProposal proposal, VaultOperation operation,
            EffectGuard guard) throws VaultException {
        byte[] key = null, plain = null;
        VaultManifest manifest = null;
        try {
            checkBundleWork(work, proposal, operation, guard);
            if (work.context.snapshotBinding != null && !work.context.snapshotBinding.equals(
                    SshConnectionMaterial.hex(VaultCodec.digest(work.ciphertext))))
                throw new VaultException("VAULT_CHANGED", "The reviewed SSH source changed");
            VaultEnvelope envelope = VaultEnvelope.parse(work.ciphertext);
            if (operation != null && (!operation.vaultId.equals(envelope.id)
                    || operation.generation != envelope.generation
                    || !MessageDigest.isEqual(operation.snapshotBinding, envelope.binding())))
                throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            checkBundleWork(work, proposal, operation, guard);
            key = work.key.clone();
            plain = envelope.decrypt(key);
            checkBundleWork(work, proposal, operation, guard);
            manifest = VaultManifest.decode(plain);
            checkBundleWork(work, proposal, operation, guard);
            return new Verified(envelope, key, plain, manifest);
        } catch (Throwable failure) {
            if (manifest != null) manifest.close();
            VaultCodec.wipe(key); VaultCodec.wipe(plain);
            throw failure;
        }
    }

    private void validateBundleLoan(BundleWork work, SshBundleProposal proposal, VaultOperation operation,
            EffectGuard guard, byte[] canonical) throws VaultException {
        checkBundleWork(work, proposal, operation, guard);
        try (VaultManifest loan = VaultManifest.decode(canonical)) {
            checkBundleWork(work, proposal, operation, guard);
            work.context.validator.validator.validate(loan);
            checkBundleWork(work, proposal, operation, guard);
            requireUnchangedLoan(loan, canonical);
            checkBundleWork(work, proposal, operation, guard);
        }
    }

    private void unchangedBundle(BundleWork work) throws VaultException {
        try {
            byte[] actual = store.read();
            try {
                if (!Arrays.equals(work.ciphertext, actual))
                    throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            } finally { VaultCodec.wipe(actual); }
        } catch (IOException error) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
    }

    private void finishBundle(BundleWork work) {
        try { work.close(); }
        finally { synchronized (this) { if (bundleWork == work) bundleWork = null; } }
    }

    VaultOperation beginSshBundle(SshBundleProposal proposal) throws VaultException {
        return beginSshBundle(proposal, newBundleContext(() -> { }), () -> { });
    }

    VaultOperation beginSshBundle(SshBundleProposal proposal, BundleContext context, EffectGuard approval)
            throws VaultException {
        BundleWork work = captureBundle(context, proposal, null);
        try {
            checkBundleWork(work, proposal, null, approval);
            try (Verified verified = openBundle(work, proposal, null, approval)) {
                if (hasSsh(verified.manifest)) validateBundleLoan(work, proposal, null, approval, verified.plaintext);
                checkBundleWork(work, proposal, null, approval);
                try (VaultManifest next = proposal.applyTo(verified.manifest)) {
                    checkBundleWork(work, proposal, null, approval);
                    byte[] canonical = next.encode();
                    try {
                        // Always validate the complete candidate, even deletion of the final SSH object.
                        validateBundleLoan(work, proposal, null, approval, canonical);
                        VaultOperation result = new VaultOperation(VaultOperation.Consumer.SSH,
                                VaultOperation.Purpose.SSH_BUNDLE_WRITE, proposal.binding, "", "",
                                verified.envelope, context.epoch, context.deadline, context);
                        try {
                            synchronized (this) {
                                checkBundleWork(work, proposal, null, approval);
                                unchangedBundle(work);
                                checkBundleWork(work, proposal, null, approval);
                                operations.values().removeIf(o -> o.consumed || o.cancelled
                                        || o.epoch != session.epoch() || clock.now() >= o.deadline);
                                if (operations.size() >= 64) throw new VaultException("BUSY", "Too many secret operations");
                                context.guard.publish(() -> {
                                    synchronized (publicationLock) { synchronized (session) {
                                        checkBundleMetadata(context);
                                        proposal.requireOpen();
                                        registerOperation(result);
                                    } }
                                });
                            }
                            // Publication owns only a metadata fence. Recheck exact approval
                            // outside it, including a provider revoked by the publish seam.
                            checkBundleWork(work, proposal, null, approval);
                            return result;
                        } catch (Throwable failure) {
                            operations.remove(result.requestId, result);
                            result.cancel();
                            throw failure;
                        }
                    } finally { VaultCodec.wipe(canonical); }
                }
            }
        } finally { finishBundle(work); }
    }

    void commitSshBundle(VaultOperation operation, SshBundleProposal proposal, EffectGuard guard)
            throws VaultException {
        if (operation == null) throw denied();
        BundleWork work = captureBundle(operation.bundleContext, proposal, operation);
        try (Verified verified = openBundle(work, proposal, operation, guard)) {
            if (hasSsh(verified.manifest)) validateBundleLoan(work, proposal, operation, guard, verified.plaintext);
            checkBundleWork(work, proposal, operation, guard);
            try (VaultManifest next = proposal.applyTo(verified.manifest)) {
                checkBundleWork(work, proposal, operation, guard);
                byte[] canonical = next.encode();
                try {
                    validateBundleLoan(work, proposal, operation, guard, canonical);
                    VaultEnvelope envelope = VaultEnvelope.encrypt(verified.envelope.id, verified.envelope.generation,
                            verified.envelope.salt, verified.key, canonical);
                    checkBundleWork(work, proposal, operation, guard);
                    verifyReadback(envelope, verified.key, canonical);
                    checkBundleWork(work, proposal, operation, guard);
                    byte[] encoded = envelope.encode();
                    try {
                        synchronized (this) {
                            checkBundleWork(work, proposal, operation, guard);
                            byte[] quick = quickMaterial();
                            try {
                                unchangedBundle(work);
                                checkBundleWork(work, proposal, operation, guard);
                                commitEncoded(encoded, quick, boundGuard(guard, () -> {
                                    // Store.commit may already own an AtomicFile write. Its guards
                                    // check metadata only; reopening it here can discard that write.
                                    checkBundleWork(work, proposal, operation, () -> { });
                                }));
                                operations.clear();
                                cancelPreview();
                                session.unlockIfEpoch(operation.epoch, verified.key);
                            } finally { VaultCodec.wipe(quick); }
                        }
                    } finally { VaultCodec.wipe(encoded); }
                } finally { VaultCodec.wipe(canonical); }
            }
        } finally { finishBundle(work); }
    }

    String totpCode(VaultOperation operation, String entryId, long unixSeconds) throws VaultException {
        TotpReadWork work = captureTotpRead(null, operation, VaultOperation.Purpose.TOTP_CODE, entryId);
        try (Verified verified = openTotpRead(work)) {
            if (unixSeconds < 0) throw new VaultException("PARAMETERS", "Invalid code time");
            VaultManifest.Entry entry = findEntry(verified.manifest, entryId, VaultManifest.EntryType.TOTP);
            String code = generateTotp(entry.secret, unixSeconds);
            completeTotpRead(work, unixSeconds);
            return code;
        } finally { finishTotpRead(work); }
    }

    static String generateTotp(byte[] seed, long unixSeconds) throws VaultException {
        if (unixSeconds < 0) throw new VaultException("PARAMETERS", "Invalid code time");
        try {
                Mac mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(seed, "HmacSHA1"));
                byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(unixSeconds / 30).array());
                try {
                    int offset = digest[digest.length - 1] & 15;
                    int binary = (digest[offset] & 127) << 24 | (digest[offset + 1] & 255) << 16
                            | (digest[offset + 2] & 255) << 8 | digest[offset + 3] & 255;
                    String code = String.format(java.util.Locale.ROOT, "%06d", binary % 1000000);
                    return code;
                } finally { VaultCodec.wipe(digest); }
            } catch (java.security.GeneralSecurityException error) {
                throw new VaultException("VAULT_FAILED", "Unable to generate a code");
            }
    }

    VaultOperation beginSshCredential(String entryId, String target, String connectionId) throws VaultException {
        return beginSshCredential(entryId, target, connectionId, null);
    }

    VaultOperation beginSshCredential(String entryId, String target, String connectionId,
            VaultManifest.EntryType expectedType) throws VaultException {
        return beginSshCredential(entryId, target, connectionId, expectedType, newSshSecretReadContext());
    }

    VaultOperation beginSshCredential(String entryId, String target, String connectionId,
            VaultManifest.EntryType expectedType, SshSecretReadContext context) throws VaultException {
        return beginSshSecret(VaultOperation.Purpose.SSH_CREDENTIAL, entryId, target, connectionId, expectedType, context);
    }

    void sshCredential(VaultOperation operation, String entryId, String target,
            String connectionId, EffectGuard hostKeyGuard, SshVaultAdapter.CredentialReceiver receiver) throws VaultException {
        sshCredential(operation, entryId, target, connectionId, null, hostKeyGuard, receiver);
    }

    void sshCredential(VaultOperation operation, String entryId, String target,
            String connectionId, VaultManifest.EntryType expectedType, EffectGuard hostKeyGuard,
            SshVaultAdapter.CredentialReceiver receiver) throws VaultException {
        releaseSshSecret(operation, VaultOperation.Purpose.SSH_CREDENTIAL, entryId, target, connectionId,
                expectedType, hostKeyGuard, receiver);
    }

    SshSecretReadContext newSshSecretReadContext(long epoch, long deadline, EffectGuard guard)
            throws VaultException {
        if (guard == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        return new SshSecretReadContext(this, epoch, deadline, guard, validatorRegistration);
    }

    SshSecretReadContext newSshSecretReadContext(long deadline, EffectGuard guard) throws VaultException {
        synchronized (session) {
            foreground();
            return newSshSecretReadContext(session.epoch(), deadline, guard);
        }
    }

    SshSecretReadContext newSavedPasswordReadContext(long deadline, EffectGuard guard,
            SshAuthenticationSelection selection) throws VaultException {
        if (guard == null || selection == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        synchronized (session) {
            foreground();
            return new SshSecretReadContext(this, session.epoch(), deadline, guard,
                    validatorRegistration, selection);
        }
    }

    private SshSecretReadContext newSshSecretReadContext() throws VaultException {
        return newSshSecretReadContext(clock.now() + 120000, () -> { });
    }

    private void checkSshSecretMetadata(SshSecretReadWork work) throws VaultException {
        SshSecretReadContext context = work.context;
        if (context.cancelled || work.operation != null && work.operation.cancelled)
            throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        if (clock.now() >= context.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        foreground();
        session.assertEpoch(context.epoch);
        if (!session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        if (validatorRegistration != context.validator || contentRevision != work.revision)
            throw new VaultException("VAULT_CHANGED", "SSH validation context changed; try again");
        if (context.validator.validator == null)
            throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH file validation is not available yet");
    }

    private void checkSshSecretRead(SshSecretReadWork work) throws VaultException {
        work.context.guard.check();
        synchronized (session) { checkSshSecretMetadata(work); }
    }

    private SshSecretReadWork captureSshSecretRead(SshSecretReadContext context, VaultOperation operation,
            VaultOperation.Purpose purpose, String resource, String target, String connectionId,
            VaultManifest.EntryType expectedType) throws VaultException {
        SshSecretReadWork captured = null;
        try {
            if (operation != null) {
                if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                SshSecretReadState state = operation.sshSecretReadState();
                if (state == null) throw denied();
                context = state.context;
            }
            if (context == null || context.owner != this) throw denied();
            context.guard.check();
            synchronized (this) {
                if (operation != null) {
                    SshSecretReadState state = operation.sshSecretReadState();
                    if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                    if (operations.get(operation.requestId) != operation || operation.consumed
                            || operation.consumer != VaultOperation.Consumer.SSH || operation.purpose != purpose
                            || !operation.resource.equals(resource) || !operation.target.equals(target)
                            || !operation.connectionId.equals(connectionId) || state.expectedType != expectedType
                            || context.issued != operation) throw denied();
                } else {
                    if (context.admitted) throw denied();
                    context.admitted = true;
                }
                if (sshSecretReadWork != null) throw new VaultException("BUSY", "An SSH secret read is already in progress");
                if (operation != null) {
                    operation.consumed = true;
                    operations.remove(operation.requestId, operation);
                }
                captured = new SshSecretReadWork(context, operation, contentRevision);
                sshSecretReadWork = captured;
                if (operation != null && operation.sshSecretReadState().revision != captured.revision)
                    throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
                synchronized (session) {
                    checkSshSecretMetadata(captured);
                    captured.key = session.key();
                }
                captured.ciphertext = store.read();
                synchronized (session) { checkSshSecretMetadata(captured); }
                if (captured.ciphertext == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
                return captured;
            }
        } catch (IOException failure) {
            if (captured != null) finishSshSecretRead(captured);
            throw new VaultException("VAULT_IO", "Unable to read the vault");
        } catch (Throwable failure) {
            if (captured != null) finishSshSecretRead(captured);
            throw failure;
        }
    }

    private Verified openSshSecretRead(SshSecretReadWork work) throws VaultException {
        byte[] key = null, plain = null;
        VaultManifest manifest = null;
        try {
            checkSshSecretRead(work);
            VaultEnvelope envelope = VaultEnvelope.parse(work.ciphertext);
            if (work.operation != null && (!work.operation.vaultId.equals(envelope.id)
                    || work.operation.generation != envelope.generation
                    || !MessageDigest.isEqual(work.operation.snapshotBinding, envelope.binding())))
                throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            checkSshSecretRead(work);
            key = work.key.clone();
            plain = envelope.decrypt(key);
            checkSshSecretRead(work);
            manifest = VaultManifest.decode(plain);
            checkSshSecretRead(work);
            try (VaultManifest loan = VaultManifest.decode(plain)) {
                checkSshSecretRead(work);
                work.context.validator.validator.validate(loan);
                checkSshSecretRead(work);
                requireUnchangedLoan(loan, plain);
                checkSshSecretRead(work);
            }
            return new Verified(envelope, key, plain, manifest);
        } catch (Throwable failure) {
            if (manifest != null) manifest.close();
            VaultCodec.wipe(key); VaultCodec.wipe(plain);
            throw failure;
        }
    }

    private void unchangedSshSecretRead(SshSecretReadWork work) throws VaultException {
        synchronized (this) {
            synchronized (session) { checkSshSecretMetadata(work); }
            try {
                byte[] actual = store.read();
                try {
                    if (!Arrays.equals(work.ciphertext, actual))
                        throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
                } finally { VaultCodec.wipe(actual); }
            } catch (IOException failure) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
            synchronized (session) { checkSshSecretMetadata(work); }
        }
    }

    private void finishSshSecretRead(SshSecretReadWork work) {
        try { work.close(); }
        finally { synchronized (this) { if (sshSecretReadWork == work) sshSecretReadWork = null; } }
    }

    private VaultOperation beginSshSecret(VaultOperation.Purpose purpose, String resource, String target,
            String connectionId, VaultManifest.EntryType expectedType, SshSecretReadContext context) throws VaultException {
        if (target.isEmpty() || connectionId.isEmpty()) throw denied();
        SshSecretReadWork work = captureSshSecretRead(context, null, purpose, resource, target, connectionId, expectedType);
        try (Verified verified = openSshSecretRead(work)) {
            checkSavedPasswordRead(verified.manifest, context, purpose, resource, target, connectionId, expectedType);
            sshSecretMaterial(verified.manifest, purpose, resource, target, expectedType, false);
            VaultOperation result = new VaultOperation(VaultOperation.Consumer.SSH, purpose, resource, target,
                    connectionId, verified.envelope, context.epoch, context.deadline);
            result.attachSshSecretRead(new SshSecretReadState(context, work.revision, expectedType));
            try {
                unchangedSshSecretRead(work);
                context.guard.publish(() -> {
                    synchronized (publicationLock) { synchronized (session) {
                        checkSshSecretMetadata(work);
                        operations.values().removeIf(o -> o.consumed || o.cancelled || o.epoch != context.epoch
                                || clock.now() >= o.deadline);
                        if (operations.size() >= 64) throw new VaultException("BUSY", "Too many secret operations");
                        context.issued = result;
                        registerOperation(result);
                    } }
                });
                checkSshSecretRead(work);
                return result;
            } catch (Throwable failure) { cancelOperation(result); throw failure; }
        } finally { finishSshSecretRead(work); }
    }

    /** A receiver is a trusted bounded copy only: no core reentry, JNI or waits. */
    private void releaseSshSecret(VaultOperation operation, VaultOperation.Purpose purpose, String resource,
            String target, String connectionId, VaultManifest.EntryType expectedType, EffectGuard releaseGuard,
            SshVaultAdapter.CredentialReceiver receiver) throws VaultException {
        if (releaseGuard == null || receiver == null) throw VaultException.invalid();
        releaseGuard.check();
        SshSecretReadWork work = captureSshSecretRead(null, operation, purpose, resource, target, connectionId, expectedType);
        byte[] secret = null;
        try (Verified verified = openSshSecretRead(work)) {
            checkSavedPasswordRead(verified.manifest, work.context, purpose, resource, target, connectionId, expectedType);
            secret = sshSecretMaterial(verified.manifest, purpose, resource, target, expectedType, true);
            unchangedSshSecretRead(work);
            final byte[] borrowed = secret;
            // The original request authority stays outermost; no core is held by any callback.
            work.context.guard.publish(() -> releaseGuard.publish(() -> {
                synchronized (publicationLock) { synchronized (session) { synchronized (operation) {
                    checkSshSecretMetadata(work);
                    SshSecretReadState state = operation.sshSecretReadState();
                    if (state.delivered) throw denied();
                    state.delivered = true;
                    releaseGuard.check();
                    withSshRole(releaseGuard, () -> {
                        checkSshSecretMetadata(work);
                        receiver.receive(borrowed);
                    });
                } } }
            }));
            releaseGuard.check();
            checkSshSecretRead(work);
        } finally { VaultCodec.wipe(secret); finishSshSecretRead(work); }
    }

    private static void checkSavedPasswordRead(VaultManifest manifest, SshSecretReadContext context,
            VaultOperation.Purpose purpose, String resource, String target, String connectionId,
            VaultManifest.EntryType expectedType) throws VaultException {
        SshAuthenticationSelection selection = context.passwordSelection;
        if (target.startsWith(SshConnectionMaterial.KEY_TARGET_PREFIX)) {
            if (selection == null || purpose != VaultOperation.Purpose.SSH_PRIVATE_KEY
                    || !(selection.path + "\0" + selection.keyIdentity).equals(resource)
                    || !selection.role.targetIdentity.equals(target)
                    || !selection.role.connectionId.equals(connectionId)) throw SshAuthenticationSelection.invalid();
            SshConnectionMaterial.requireSelection(manifest, selection);
            return;
        }
        if (selection == null && !target.startsWith(SshSavedPassword.TARGET_PREFIX)) return;
        if (selection == null || purpose != VaultOperation.Purpose.SSH_CREDENTIAL
                || expectedType != VaultManifest.EntryType.SSH_PASSWORD || !selection.entryId.equals(resource)
                || !selection.role.targetIdentity.equals(target) || !selection.role.connectionId.equals(connectionId))
            throw SshAuthenticationSelection.invalid();
        SshSavedPassword.requireSelection(manifest, selection);
    }

    private static byte[] sshSecretMaterial(VaultManifest manifest, VaultOperation.Purpose purpose, String resource,
            String target, VaultManifest.EntryType expectedType, boolean copy) throws VaultException {
        if (purpose == VaultOperation.Purpose.SSH_CREDENTIAL) {
            VaultManifest.Entry entry = findSshEntry(manifest, resource);
            if (!entry.identity.equals(target) || expectedType != null && entry.type != expectedType) throw denied();
            return copy ? entry.secret.clone() : null;
        }
        String[] fields = resource.split("\0", -1);
        if (purpose == VaultOperation.Purpose.SSH_PRIVATE_KEY && fields.length == 2) {
            VaultManifest.FileObject file = findFile(manifest, fields[0]);
            if (file.type != VaultManifest.FileType.PRIVATE_KEY || !file.identity.equals(fields[1])) throw denied();
            return copy ? file.content.clone() : null;
        }
        if (purpose != VaultOperation.Purpose.SSH_PRIVATE_KEY_BUNDLE || fields.length != 3) throw denied();
        VaultManifest.FileObject file = requirePrivateKeyBundle(manifest, fields[0], fields[1], fields[2]);
        VaultManifest.Entry entry = findEntry(manifest, fields[2], VaultManifest.EntryType.SSH_KEY_PASSPHRASE);
        int length = 16 + file.content.length + entry.secret.length;
        if (file.content.length == 0 || entry.secret.length == 0 || entry.secret.length > 8192
                || length > VaultManifest.MAX_FILE_BYTES) throw denied();
        if (!copy) return null;
        byte[] bundle = new byte[length];
        try {
            ByteBuffer.wrap(bundle).putInt(0x43534b42).putInt(1)
                    .putInt(file.content.length).put(file.content).putInt(entry.secret.length).put(entry.secret);
            return bundle;
        } catch (Throwable failure) { VaultCodec.wipe(bundle); throw failure; }
    }

    SshPublicReadContext newSshPublicReadContext(long epoch, long deadline, EffectGuard guard)
            throws VaultException {
        if (guard == null || deadline > clock.now() + 120000) throw VaultException.invalid();
        return new SshPublicReadContext(this, epoch, deadline, guard, validatorRegistration);
    }

    SshPublicReadContext newSshPublicReadContext(long deadline, EffectGuard guard) throws VaultException {
        synchronized (session) {
            foreground();
            return newSshPublicReadContext(session.epoch(), deadline, guard);
        }
    }

    private void checkSshPublicMetadata(SshPublicReadContext context) throws VaultException {
        if (context.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        if (clock.now() >= context.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        foreground();
        session.assertEpoch(context.epoch);
        if (!session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        if (validatorRegistration != context.validator)
            throw new VaultException("VAULT_CHANGED", "SSH validation context changed; try again");
        if (context.validator.validator == null)
            throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH file validation is not available yet");
    }

    private void checkSshPublicRead(SshPublicReadWork work) throws VaultException {
        if (work.operation != null && work.operation.cancelled)
            throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        work.context.guard.check();
        synchronized (session) { checkSshPublicMetadata(work.context); }
        if (contentRevision != work.revision)
            throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
    }

    private SshPublicReadWork captureSshPublicRead(SshPublicReadContext context, VaultOperation operation,
            String rootPath, String connectionId) throws VaultException {
        SshPublicReadWork captured = null;
        try {
            synchronized (this) {
                if (operation != null) {
                    if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                    SshPublicReadState state = operation.sshPublicReadState();
                    if (operations.get(operation.requestId) != operation || operation.consumed || state == null
                            || operation.consumer != VaultOperation.Consumer.SSH
                            || operation.purpose != VaultOperation.Purpose.SSH_FILES
                            || !operation.resource.equals(rootPath) || !operation.connectionId.equals(connectionId)
                            || !operation.target.isEmpty() || state.context.owner != this
                            || state.context.issued != operation) throw denied();
                    context = state.context;
                }
                if (context == null || context.owner != this || operation == null && context.admitted) throw denied();
                if (operation == null) context.admitted = true;
                if (sshPublicReadWork != null) throw new VaultException("BUSY", "An SSH public read is already in progress");
                if (operation != null) {
                    operation.consumed = true;
                    operations.remove(operation.requestId, operation);
                }
                captured = new SshPublicReadWork(context, operation, contentRevision);
                sshPublicReadWork = captured;
                if (operation != null && operation.sshPublicReadState().revision != captured.revision)
                    throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
                checkSshPublicRead(captured);
                synchronized (session) {
                    checkSshPublicMetadata(context);
                    captured.key = session.key();
                }
                captured.ciphertext = store.read();
                checkSshPublicRead(captured);
                if (captured.ciphertext == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
                return captured;
            }
        } catch (IOException failure) {
            if (captured != null) finishSshPublicRead(captured);
            throw new VaultException("VAULT_IO", "Unable to read the vault");
        } catch (Throwable failure) {
            if (captured != null) finishSshPublicRead(captured);
            throw failure;
        }
    }

    private Verified openSshPublicRead(SshPublicReadWork work) throws VaultException {
        byte[] key = null, plain = null;
        VaultManifest manifest = null;
        try {
            checkSshPublicRead(work);
            VaultEnvelope envelope = VaultEnvelope.parse(work.ciphertext);
            if (work.operation != null && (!work.operation.vaultId.equals(envelope.id)
                    || work.operation.generation != envelope.generation
                    || !MessageDigest.isEqual(work.operation.snapshotBinding, envelope.binding())))
                throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            checkSshPublicRead(work);
            key = work.key.clone();
            plain = envelope.decrypt(key);
            checkSshPublicRead(work);
            manifest = VaultManifest.decode(plain);
            checkSshPublicRead(work);
            try (VaultManifest loan = VaultManifest.decode(plain)) {
                checkSshPublicRead(work);
                work.context.validator.validator.validate(loan);
                checkSshPublicRead(work);
                requireUnchangedLoan(loan, plain);
                checkSshPublicRead(work);
            }
            return new Verified(envelope, key, plain, manifest);
        } catch (Throwable failure) {
            if (manifest != null) manifest.close();
            VaultCodec.wipe(key); VaultCodec.wipe(plain);
            throw failure;
        }
    }

    private void unchangedSshPublicRead(SshPublicReadWork work) throws VaultException {
        try {
            byte[] actual = store.read();
            try {
                if (!Arrays.equals(work.ciphertext, actual))
                    throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
            } finally { VaultCodec.wipe(actual); }
        } catch (IOException failure) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
    }

    private void finishSshPublicRead(SshPublicReadWork work) {
        try { work.close(); }
        finally { synchronized (this) { if (sshPublicReadWork == work) sshPublicReadWork = null; } }
    }

    VaultOperation beginSshFiles(String rootPath, String connectionId) throws VaultException {
        long deadline = clock.now() + 120000;
        return beginSshFiles(rootPath, connectionId, newSshPublicReadContext(deadline, () -> { }));
    }

    VaultOperation beginSshFiles(String rootPath, String connectionId, SshPublicReadContext context)
            throws VaultException {
        VaultManifest.path(rootPath);
        if (connectionId.isEmpty()) throw denied();
        SshPublicReadWork work = captureSshPublicRead(context, null, rootPath, connectionId);
        try (Verified verified = openSshPublicRead(work)) {
            if (findFile(verified.manifest, rootPath).type == VaultManifest.FileType.PRIVATE_KEY) throw denied();
            // Validate the actual selected closure before issuing any capability.
            selectFiles(verified.manifest, rootPath, new HashSet<>(), new ArrayList<>());
            VaultOperation result = new VaultOperation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_FILES,
                    rootPath, "", connectionId, verified.envelope, context.epoch, context.deadline);
            result.attachSshPublicRead(new SshPublicReadState(context, work.revision));
            try {
                synchronized (this) {
                    checkSshPublicRead(work);
                    unchangedSshPublicRead(work);
                    checkSshPublicRead(work);
                    operations.values().removeIf(o -> o.consumed || o.cancelled || o.epoch != context.epoch
                            || clock.now() >= o.deadline);
                    if (operations.size() >= 64) throw new VaultException("BUSY", "Too many secret operations");
                    context.guard.publish(() -> {
                        synchronized (publicationLock) { synchronized (session) {
                            checkSshPublicRead(work);
                            withSshRole(context.guard, () -> {
                                checkSshPublicRead(work);
                                context.issued = result;
                                registerOperation(result);
                            });
                        } }
                    });
                }
                checkSshPublicRead(work);
                return result;
            } catch (Throwable failure) { cancelOperation(result); throw failure; }
        } finally { finishSshPublicRead(work); }
    }

    void sshFiles(VaultOperation operation, String rootPath, String connectionId,
            SshVaultAdapter.FileReceiver receiver) throws VaultException {
        sshFileSnapshot(operation, rootPath, connectionId, snapshot -> receiver.receive(snapshot.publicFiles));
    }

    void sshFileSnapshot(VaultOperation operation, String rootPath, String connectionId,
            SshVaultAdapter.FileSnapshotReceiver receiver) throws VaultException {
        try (SshPublicReadPreparation prepared = prepareSshPublicRead(operation, rootPath, connectionId, false)) {
            claimSshPublicRead(prepared, null);
            // Public borrowed bodies stay owned by this worker until the synchronous receiver returns.
            checkSshPublicRead(prepared.work);
            receiver.receive(prepared.snapshot);
            checkSshPublicRead(prepared.work);
        }
    }

    SshPublicReadPreparation prepareSshPublicRead(VaultOperation operation, String rootPath, String connectionId)
            throws VaultException {
        return prepareSshPublicRead(operation, rootPath, connectionId, true);
    }

    private SshPublicReadPreparation prepareSshPublicRead(VaultOperation operation, String rootPath,
            String connectionId, boolean nativeLease) throws VaultException {
        SshPublicReadWork work = captureSshPublicRead(null, operation, rootPath, connectionId);
        Verified verified = null;
        try {
            verified = openSshPublicRead(work);
            List<VaultManifest.FileObject> selected = new ArrayList<>();
            selectFiles(verified.manifest, rootPath, new HashSet<>(), selected);
            SshFileSnapshot snapshot = new SshFileSnapshot(operation, selected);
            checkSshPublicRead(work);
            return new SshPublicReadPreparation(this, work, verified, snapshot, nativeLease);
        } catch (Throwable failure) {
            try { if (verified != null) verified.close(); }
            finally { finishSshPublicRead(work); }
            throw failure;
        }
    }

    private void claimSshPublicRead(SshPublicReadPreparation prepared, VaultSession.Revocation lease)
            throws VaultException {
        if (prepared == null || prepared.owner != this || prepared.closed) throw denied();
        SshPublicReadWork work = prepared.work;
        synchronized (this) {
            if (sshPublicReadWork != work || work.operation == null) throw denied();
            checkSshPublicRead(work);
            unchangedSshPublicRead(work);
            checkSshPublicRead(work);
        }
        // Mutation increments contentRevision under publicationLock before durable commit.
        // The metadata fence therefore rejects a mutation starting after the last core read.
        work.context.guard.publish(() -> {
            synchronized (publicationLock) { synchronized (session) {
                checkSshPublicRead(work);
                SshPublicReadState state = work.operation.sshPublicReadState();
                if (state.delivered) throw denied();
                state.delivered = true;
                if (lease != null) {
                    if (!lease.active()) throw new VaultException("SSH_FILE_VIEW", "The SSH file view was revoked");
                    // The operation monitor protects assignment only. Cancellation must still
                    // revoke while native/session liveness checks are in progress.
                    work.operation.attachRevocation(lease);
                }
                withSshRole(work.context.guard, () -> {
                    checkSshPublicRead(work);
                    if (lease != null) {
                        session.attachRevocation(work.context.epoch, lease);
                        if (!lease.active()) throw new VaultException("SSH_FILE_VIEW", "The SSH file view was revoked");
                        checkSshPublicRead(work);
                    }
                });
            } }
        });
        checkSshPublicRead(work);
    }

    /** Exact active preparation required; consumed operations alone cannot attach replacement views. */
    void attachSshPublicRead(SshPublicReadPreparation prepared, VaultSession.Revocation lease)
            throws VaultException {
        boolean accepted = false;
        try {
            if (lease == null) throw denied();
            if (prepared == null || prepared.owner != this || prepared.closed || !prepared.nativeLease
                    || prepared.attachAttempted) throw denied();
            synchronized (this) {
                if (prepared == null || prepared.owner != this || prepared.closed || !prepared.nativeLease
                        || sshPublicReadWork != prepared.work
                        || prepared.attachAttempted || prepared.work.operation.sshPublicReadState().delivered) throw denied();
                // This helper accepts a new local view, never ownership of an existing session view.
                synchronized (session) {
                    foreground();
                    if (session.attachedRevocation(prepared.work.context.epoch) == lease) throw denied();
                }
                prepared.attachAttempted = true;
                accepted = true;
            }
            claimSshPublicRead(prepared, lease);
            if (!lease.active()) throw new VaultException("SSH_FILE_VIEW", "The SSH file view was revoked");
            checkSshPublicRead(prepared.work);
        } catch (Throwable failure) {
            // Neither cancellation nor native destruction runs inside the claim's locks.
            // Before acceptance the parameter remains caller-owned, including rejected replays.
            if (accepted) {
                try { cancelOperation(prepared.work.operation); }
                catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                try { lease.revoke(); }
                catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    SshPublicFileLease sshPublicFiles(VaultOperation operation, String rootPath, String connectionId)
            throws VaultException {
        SshPublicFileLease result = null;
        try (SshPublicReadPreparation prepared = prepareSshPublicRead(operation, rootPath, connectionId)) {
            result = SshPublicFileLease.create(prepared.snapshot);
            attachSshPublicRead(prepared, result);
            return result;
        } catch (Throwable failure) {
            if (result != null) {
                try { result.close(); }
                catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private static void selectFiles(VaultManifest manifest, String path, Set<String> visited,
            List<VaultManifest.FileObject> selected) throws VaultException {
        if (!visited.add(path)) return;
        VaultManifest.FileObject file = findFile(manifest, path);
        selected.add(file);
        for (String ref : file.references) selectFiles(manifest, ref, visited, selected);
    }

    VaultOperation beginSshPrivateKey(String path, String keyIdentity, String target,
            String connectionId) throws VaultException {
        return beginSshPrivateKey(path, keyIdentity, target, connectionId, newSshSecretReadContext());
    }

    VaultOperation beginSshPrivateKey(String path, String keyIdentity, String target,
            String connectionId, SshSecretReadContext context) throws VaultException {
        return beginSshSecret(VaultOperation.Purpose.SSH_PRIVATE_KEY, path + "\0" + keyIdentity,
                target, connectionId, null, context);
    }

    void sshPrivateKey(VaultOperation operation, String path, String keyIdentity, String target,
            String connectionId, EffectGuard hostKeyGuard, SshVaultAdapter.CredentialReceiver receiver) throws VaultException {
        releaseSshSecret(operation, VaultOperation.Purpose.SSH_PRIVATE_KEY, path + "\0" + keyIdentity,
                target, connectionId, null, hostKeyGuard, receiver);
    }

    VaultOperation beginSshPrivateKeyBundle(String path, String keyIdentity,
            String passphraseEntryId, String target, String connectionId) throws VaultException {
        return beginSshPrivateKeyBundle(path, keyIdentity, passphraseEntryId, target, connectionId,
                newSshSecretReadContext());
    }

    VaultOperation beginSshPrivateKeyBundle(String path, String keyIdentity, String passphraseEntryId,
            String target, String connectionId, SshSecretReadContext context) throws VaultException {
        if (passphraseEntryId.isEmpty()) throw denied();
        return beginSshSecret(VaultOperation.Purpose.SSH_PRIVATE_KEY_BUNDLE,
                path + "\0" + keyIdentity + "\0" + passphraseEntryId, target, connectionId, null, context);
    }

    private static VaultManifest.FileObject requirePrivateKeyBundle(VaultManifest manifest,
            String path, String keyIdentity, String passphraseEntryId) throws VaultException {
        VaultManifest.FileObject file = findFile(manifest, path);
        VaultManifest.Entry entry = findEntry(manifest, passphraseEntryId, VaultManifest.EntryType.SSH_KEY_PASSPHRASE);
        if (file.type != VaultManifest.FileType.PRIVATE_KEY || !file.identity.equals(keyIdentity)
                || !entry.identity.equals("key:" + keyIdentity)) throw denied();
        return file;
    }

    void sshPrivateKeyBundle(VaultOperation operation, String path, String keyIdentity,
            String passphraseEntryId, String target, String connectionId, EffectGuard hostKeyGuard,
            SshVaultAdapter.CredentialReceiver receiver) throws VaultException {
        releaseSshSecret(operation, VaultOperation.Purpose.SSH_PRIVATE_KEY_BUNDLE,
                path + "\0" + keyIdentity + "\0" + passphraseEntryId, target, connectionId, null, hostKeyGuard, receiver);
    }

    private void registerOperation(VaultOperation operation) throws VaultException {
        synchronized (publicationLock) {
            if (operations.size() >= 64) throw new VaultException("BUSY", "Too many secret operations");
            operations.put(operation.requestId, operation);
        }
    }

    void cancelOperation(VaultOperation operation) {
        if (operation == null) return;
        operation.cancel();
        if (operations.get(operation.requestId) == operation) operations.remove(operation.requestId);
    }

    private static VaultManifest.Entry findEntry(VaultManifest manifest, String id, VaultManifest.EntryType type)
            throws VaultException {
        for (VaultManifest.Entry entry : manifest.entries) if (entry.id.equals(id) && entry.type == type) return entry;
        throw denied();
    }

    private static VaultManifest.Entry findSshEntry(VaultManifest manifest, String id) throws VaultException {
        for (VaultManifest.Entry entry : manifest.entries) if (entry.id.equals(id) && entry.type != VaultManifest.EntryType.TOTP) return entry;
        throw denied();
    }

    private static VaultManifest.FileObject findFile(VaultManifest manifest, String path) throws VaultException {
        for (VaultManifest.FileObject file : manifest.files) if (file.path.equals(path)) return file;
        throw denied();
    }

    private static VaultException denied() { return new VaultException("VAULT_OPERATION", "Secret operation does not match its resource"); }

    synchronized void cancelPreview(String id) {
        if (preview != null && preview.id.equals(id)) cancelPreview();
    }
    synchronized void expirePreview(String id) {
        if (preview != null && preview.id.equals(id) && clock.now() >= preview.deadline) cancelPreview();
    }

    void invalidatePreviewRequest(String id) {
        synchronized (session) {
            PreviewWork work = previewWork;
            if (work != null && work.requestId.equals(id)) work.cancelled = true;
            ImportPreview pending = preview;
            if (pending != null && pending.requestId.equals(id)) pending.valid = false;
        }
    }

    void cancelRequest(String id) {
        invalidatePreviewRequest(id);
        synchronized (this) { if (preview != null && preview.requestId.equals(id)) cancelPreview(); }
    }

    private void cancelPreview() {
        ImportPreview retired;
        synchronized (session) {
            retired = preview;
            if (retired != null) retired.valid = false;
            preview = null;
        }
        if (retired != null) retired.close();
    }

    private void foreground() throws VaultException {
        if (!session.foreground()) throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app to continue");
    }

    private VaultEnvelope requireCurrent() throws VaultException {
        VaultEnvelope envelope = current();
        if (envelope == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
        return envelope;
    }

    private void unchanged(byte[] binding) throws VaultException {
        VaultEnvelope current = current();
        if (binding == null ? current != null : current == null || !MessageDigest.isEqual(binding, current.binding())) {
            throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
        }
    }

    private void commit(VaultEnvelope envelope, byte[] quick, EffectGuard guard) throws VaultException {
        commitEncoded(envelope.encode(), quick, guard);
    }

    private void commitEncoded(byte[] encoded, byte[] quick, EffectGuard guard) throws VaultException {
        try { store.commit(encoded, quick, new EffectGuard() {
            @Override public void check() throws VaultException { guard.check(); }
            @Override public void beforeCommit() throws VaultException {
                guard.beforeCommit();
                synchronized (publicationLock) { contentRevision++; }
            }
        }); }
        catch (IOException error) { throw new VaultException("VAULT_IO", "Unable to save the vault; previous data retained"); }
        catch (VaultException error) {
            if (error.code.equals("VAULT_COMMIT_UNCERTAIN")) { session.lock(); operations.clear(); cancelPreview(); }
            throw error;
        }
    }

    static Verified verify(byte[] bytes, char[] password) throws VaultException {
        VaultEnvelope envelope = VaultEnvelope.parse(bytes);
        byte[] key = VaultEnvelope.derive(password, envelope.salt);
        byte[] plaintext = null;
        try {
            plaintext = envelope.decrypt(key);
            return new Verified(envelope, key, plaintext, VaultManifest.decode(plaintext));
        } catch (Throwable error) {
            VaultCodec.wipe(key);
            VaultCodec.wipe(plaintext);
            throw error;
        }
    }

    private static void verifyReadback(VaultEnvelope envelope, byte[] key, byte[] expected) throws VaultException {
        byte[] readback = VaultEnvelope.parse(envelope.encode()).decrypt(key);
        try (VaultManifest ignored = VaultManifest.decode(readback)) {
            if (!MessageDigest.isEqual(readback, expected)) throw VaultException.invalid();
        } finally { VaultCodec.wipe(readback); }
    }
}
