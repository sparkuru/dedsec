package im.majo.ctos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * One-shot provider for one exact managed KNOWN_HOSTS object.  This class is
 * intentionally package-private: the native callback receives only the
 * bounded proposal/CAS receipt, never a snapshot or candidate body.
 */
final class SshManagedKnownHostsProvider implements
        SshManagedProvider.TrustProposalSource, SshManagedProvider.TrustCasWriter, AutoCloseable {
    /* Nested close calls each own an invalidation; a JNI exception does not
     * establish whether that call changed the global invalidator count. */
    private static final class CleanupInvalidation {
        private static final int IDLE = 0, BEGIN_UNCONFIRMED = 1, HELD = 2,
                NO_FENCE = 3, END_UNCONFIRMED = 4;
        private int state;

        void begin() {
            if (state != IDLE) throw new IllegalStateException("SSH cleanup invalidation is unconfirmed");
            state = BEGIN_UNCONFIRMED;
            int result = SshNativeCallbackAdapter.beginJumpCleanupInvalidation();
            if (result == -1) {
                state = IDLE; // Native explicitly reported it did not begin.
                throw new UnsatisfiedLinkError("SSH cleanup invalidation did not begin");
            }
            if (result == 0) state = NO_FENCE;
            else if (result == 1) state = HELD;
            else throw new IllegalStateException("SSH cleanup invalidation is unconfirmed");
        }

        void end() {
            if (state == NO_FENCE) { state = IDLE; return; }
            if (state != HELD) throw new IllegalStateException("SSH cleanup invalidation is unconfirmed");
            state = END_UNCONFIRMED;
            SshNativeCallbackAdapter.endJumpEffectInvalidation(true);
            state = IDLE;
        }

        boolean unconfirmed() { return state == BEGIN_UNCONFIRMED || state == END_UNCONFIRMED; }
    }
    /** Publication hooks only update bounded metadata; role/view locks may be held. */
    interface TrustCommitObserver {
        void persisted();
        /** Chained observers must require the exact role they were issued for. */
        default void requireRole(SshRoleContext role) throws VaultException { }
        default void bindProvider(SshManagedKnownHostsProvider exact, SshRoleContext role)
                throws VaultException { requireRole(role); }
        default void proofPublished() throws VaultException { }
        default void newPersisted() { }
        default void newProofPublished() throws VaultException { }
    }
    /** Constructed only after this provider's exact CHANGED CAS has returned successfully. */
    static final class ChangedCommitReceipt {
        private final SshManagedKnownHostsProvider owner;
        private final SshNativeCallbackAdapter.ChangedAuthorization authorization;
        private final SshHostKeyAuthority.Evidence evidence;
        private final SshTrustProposal proposal;
        private boolean proofClaimed;

        private ChangedCommitReceipt(SshManagedKnownHostsProvider owner,
                SshNativeCallbackAdapter.ChangedAuthorization authorization,
                SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal) {
            this.owner = owner; this.authorization = authorization;
            this.evidence = evidence; this.proposal = proposal;
        }

        synchronized void requireMatches(SshHostKeyAuthority.Evidence actual, SshTrustProposal exact)
                throws VaultException {
            if (!owner.changedCommitConfirmed || actual != evidence || exact != proposal
                    || !proposal.matchesChangedEvidence(evidence))
                throw new VaultException("SSH_HOST_KEY_CAS", "The changed-host commit receipt is unavailable");
            authorization.requireMatches(evidence.role, evidence, proposal);
        }

        synchronized void claimProof(SshHostKeyAuthority.Evidence actual, SshTrustProposal exact)
                throws VaultException {
            requireMatches(actual, exact);
            if (proofClaimed) throw new VaultException("SSH_HOST_KEY_CAS", "The changed-host receipt was consumed");
            proofClaimed = true;
        }
    }

    static final class SnapshotReceipt implements AutoCloseable {
        final SshRoleContext role;
        final String rootPath;
        final String path;
        final String objectId;
        final VaultManifest.FileType objectType;
        final String objectIdentity;
        final String connectionId;
        final java.util.UUID vaultId;
        final long generation;
        final long epoch;
        final long deadline;
        final String requestId;
        private final SshRoleContext.Clock clock;
        private byte[] snapshotBinding;
        private byte[] baseDigest;
        private boolean closing;
        private boolean closed;
        private final CleanupInvalidation cleanupInvalidation = new CleanupInvalidation();

        private SnapshotReceipt(SshRoleContext role, String rootPath, String path, String objectId,
                String objectIdentity, SshFileSnapshot snapshot, byte[] baseDigest,
                SshRoleContext.Clock clock) throws VaultException {
            if (role == null || rootPath == null || path == null || objectId == null
                    || objectIdentity == null || snapshot == null || baseDigest == null
                    || clock == null || snapshot.publicFiles == null) throw VaultException.invalid();
            VaultManifest.path(rootPath);
            VaultManifest.path(path);
            if (objectId.isEmpty() || objectIdentity.isEmpty() || baseDigest.length != 32
                    || !snapshot.connectionId.equals(role.connectionId)
                    || !snapshot.rootPath.equals(rootPath) || !snapshot.requestId.matches(
                            "[A-Za-z0-9-]{1,128}")) throw VaultException.invalid();
            this.role = role;
            this.rootPath = rootPath;
            this.path = path;
            this.objectId = objectId;
            this.objectType = VaultManifest.FileType.KNOWN_HOSTS;
            this.objectIdentity = objectIdentity;
            connectionId = snapshot.connectionId;
            vaultId = snapshot.vaultId;
            generation = snapshot.generation;
            epoch = snapshot.epoch;
            deadline = snapshot.deadline;
            requestId = snapshot.requestId;
            snapshotBinding = snapshot.binding();
            this.baseDigest = baseDigest.clone();
            this.clock = clock;
        }

        synchronized void requireLive() throws VaultException {
            if (closing) throw new VaultException("VAULT_EXPIRED", "The managed trust snapshot is closed");
            role.requireActive(clock);
            long now = clock.now();
            if (now <= 0 || now >= deadline) {
                throw new VaultException("VAULT_EXPIRED", "The managed trust snapshot expired");
            }
        }

        synchronized byte[] binding() throws VaultException {
            if (closing) throw new VaultException("VAULT_EXPIRED", "The managed trust snapshot is closed");
            return snapshotBinding.clone();
        }

        synchronized byte[] baseDigest() throws VaultException {
            if (closing) throw new VaultException("VAULT_EXPIRED", "The managed trust snapshot is closed");
            return baseDigest.clone();
        }

        /** Classifier facts must originate from this exact protected hostfile. */
        synchronized void requireEvidence(SshRoleContext candidateRole,
                SshHostKeyAuthority.Evidence evidence) throws VaultException {
            requireLive();
            if (candidateRole != role || evidence == null || evidence.role != role)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust snapshot is not exact");
            byte[] actual = evidence.baseDigest();
            try {
                if (actual == null || !MessageDigest.isEqual(baseDigest, actual))
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust base changed");
            } finally { VaultCodec.wipe(actual); }
        }

        synchronized void requireMatches(SshRoleContext candidateRole,
                SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal) throws VaultException {
            requireLive();
            if (candidateRole != role || evidence == null || evidence.role != role
                    || evidence.classification != SshHostKeyAuthority.Classification.NEW
                    || !connectionId.equals(evidence.role.connectionId)
                    || !role.targetIdentity.equals(evidence.role.targetIdentity)
                    || !role.host.equals(evidence.role.host) || role.port != evidence.role.port
                    || proposal == null || !proposal.matchesManagedObject(objectType, objectId, path, objectIdentity)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust snapshot is not exact");
            }
            byte[] currentBase = evidence.baseDigest();
            try {
                if (!MessageDigest.isEqual(baseDigest, currentBase)) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust base changed");
                }
            } finally {
                VaultCodec.wipe(currentBase);
            }
        }

        synchronized boolean matchesEvidence(SshHostKeyAuthority.Evidence evidence) throws VaultException {
            return matchesEvidence(evidence, SshHostKeyAuthority.Classification.NEW);
        }

        synchronized boolean matchesChangedEvidence(SshHostKeyAuthority.Evidence evidence) throws VaultException {
            return matchesEvidence(evidence, SshHostKeyAuthority.Classification.CHANGED);
        }

        private boolean matchesEvidence(SshHostKeyAuthority.Evidence evidence,
                SshHostKeyAuthority.Classification expected) throws VaultException {
            requireLive();
            if (evidence == null || evidence.role != role
                    || evidence.classification != expected) return false;
            byte[] currentBase = evidence.baseDigest();
            try { return MessageDigest.isEqual(baseDigest, currentBase); }
            finally { VaultCodec.wipe(currentBase); }
        }

        synchronized void requireChangedMatches(SshRoleContext candidateRole,
                SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal) throws VaultException {
            if (candidateRole != role || !matchesChangedEvidence(evidence) || proposal == null
                    || !proposal.matchesManagedObject(objectType, objectId, path, objectIdentity)
                    || !proposal.matchesChangedEvidence(evidence)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The changed trust snapshot is not exact");
            }
        }

        private synchronized void markClosing() { closing = true; }

        private synchronized boolean cleanupUnconfirmed() { return cleanupInvalidation.unconfirmed(); }

        @Override public synchronized void close() {
            closing = true;
            if (closed) return;
            cleanupInvalidation.begin();
            try {
                VaultCodec.wipe(snapshotBinding);
                VaultCodec.wipe(baseDigest);
            } finally { cleanupInvalidation.end(); }
            closed = true;
        }
    }

    /**
     * Receipt and native wire captured from one authenticated vault read.
     * This is a preparation only: a freshness check here cannot authorize a
     * later credential copy. The connector must install a final-copy fence
     * before it can consume this preparation.
     */
    static final class ChildTrustSnapshot implements AutoCloseable {
        private final SshVaultAdapter adapter;
        private final SshRoleContext.PublicView view;
        private final SnapshotReceipt receipt;
        private final long revision;
        private byte[] wire;
        private volatile boolean closing;
        private boolean closed;
        private final CleanupInvalidation cleanupInvalidation = new CleanupInvalidation();

        private ChildTrustSnapshot(SshVaultAdapter adapter, SshRoleContext.PublicView view,
                SnapshotReceipt receipt, long revision, byte[] wire) {
            this.adapter = adapter;
            this.view = view;
            this.receipt = receipt;
            this.revision = revision;
            this.wire = wire;
        }

        SnapshotReceipt receipt() throws VaultException {
            if (closing) throw new VaultException("VAULT_EXPIRED", "The child trust source is closed");
            return receipt;
        }

        void requireFor(SshRoleContext role, String path) throws VaultException {
            if (role == null || role != receipt.role || role.hop == 0
                    || path == null || !path.equals(receipt.path) || !role.ownsPublicView(view))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The child trust source is not exact");
            requireCurrent();
        }

        void requireEvidence(SshRoleContext role, SshHostKeyAuthority.Evidence evidence)
                throws VaultException {
            requireCurrent();
            receipt.requireEvidence(role, evidence);
            requireCurrent();
        }

        void requireCurrent() throws VaultException {
            if (closing) throw new VaultException("VAULT_EXPIRED", "The child trust source is closed");
            receipt.requireLive();
            adapter.checkChildTrustSnapshot(receipt.epoch, revision, receipt.role, view);
            if (closing) throw new VaultException("VAULT_EXPIRED", "The child trust source is closed");
        }

        /** Read-only lifecycle check after a refused candidate has retired its receipt. */
        void requireRefusalContext(SshRoleContext role) throws VaultException {
            if (closing || role == null || role != receipt.role || role.hop == 0
                    || !role.ownsPublicView(view))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The child trust source is not exact");
            role.requireActive(receipt.clock);
            long now = receipt.clock.now();
            if (now <= 0 || now >= receipt.deadline)
                throw new VaultException("VAULT_EXPIRED", "The child trust source expired");
            adapter.checkChildTrustSnapshot(receipt.epoch, revision, role, view);
            if (closing) throw new VaultException("VAULT_EXPIRED", "The child trust source is closed");
        }

        boolean refusalContextAvailable() { return !closing; }

        /** Caller must wipe the transferred buffer even when native open fails. */
        byte[] takeWire() throws VaultException {
            requireCurrent();
            synchronized (this) {
                if (closing || wire == null)
                    throw new VaultException("VAULT_EXPIRED", "The child trust source was consumed");
                byte[] result = wire;
                wire = null;
                return result;
            }
        }

        @Override public synchronized void close() {
            /* Publish both barriers while holding the receipt monitor, so a
             * caller holding an earlier receipt cannot enter between them. */
            synchronized (receipt) {
                closing = true;
                receipt.markClosing();
            }
            if (closed) return;
            if (receipt.cleanupUnconfirmed())
                throw new IllegalStateException("SSH receipt cleanup is unconfirmed");
            cleanupInvalidation.begin();
            try {
                VaultCodec.wipe(wire);
                wire = null;
                receipt.close();
            } finally { cleanupInvalidation.end(); }
            closed = true;
        }
    }

    /**
     * Exact replacement supplied by a managed hostfile writer.  The bytes
     * are never derived from a fingerprint or formatted by this provider.
     */
    static final class KnownHostsCandidate implements AutoCloseable {
        private final Object provisionalFixtureIdentity = new Object();
        final String candidateId;
        final String path;
        final String objectId;
        final VaultManifest.FileType objectType;
        final String objectIdentity;
        final String roleBinding;
        final int roleHop;
        final String connectionId;
        final String targetIdentity;
        final String host;
        final int port;
        final SshHostKeyAuthority.Classification classification;
        final String keyType;
        final String fingerprint;
        final String snapshotRequestId;
        final String snapshotRootPath;
        final String snapshotConnectionId;
        final java.util.UUID snapshotVaultId;
        final long snapshotGeneration;
        final long snapshotEpoch;
        final long snapshotDeadline;
        private byte[] baseDigest;
        private byte[] snapshotBinding;
        private byte[] replacementDigest;
        private byte[] content;
        final String binding;
        private boolean consumed;
        private boolean closed;
        private FixtureCandidateReservation fixtureReservation;

        private KnownHostsCandidate(SnapshotReceipt snapshot, SshHostKeyAuthority.Evidence evidence,
                String candidateId, byte[] exactReplacement,
                SshHostKeyAuthority.Classification expected) throws VaultException {
            if (snapshot == null || evidence == null || exactReplacement == null
                    || candidateId == null || !candidateId.matches("[A-Za-z0-9_-]{1,128}")
                    || exactReplacement.length == 0
                    || exactReplacement.length > VaultManifest.MAX_FILE_BYTES) throw VaultException.invalid();
            byte[] candidateDigest = null;
            byte[] evidenceBase = null;
            byte[] evidenceReplacement = null;
            try {
                snapshot.requireLive();
                if (evidence.classification != expected || !(expected == SshHostKeyAuthority.Classification.NEW
                        ? snapshot.matchesEvidence(evidence) : expected == SshHostKeyAuthority.Classification.CHANGED
                        && snapshot.matchesChangedEvidence(evidence))) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW",
                            "The managed trust candidate is not exact");
                }
                candidateDigest = VaultCodec.digest(exactReplacement);
                evidenceBase = evidence.baseDigest();
                evidenceReplacement = evidence.replacementDigest();
                if (!MessageDigest.isEqual(snapshot.baseDigest, evidenceBase)
                        || !MessageDigest.isEqual(candidateDigest, evidenceReplacement)) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate digest changed");
                }
                this.candidateId = candidateId;
                path = snapshot.path;
                objectId = snapshot.objectId;
                objectType = snapshot.objectType;
                objectIdentity = snapshot.objectIdentity;
                roleBinding = evidence.role.binding();
                roleHop = evidence.role.hop;
                connectionId = evidence.role.connectionId;
                targetIdentity = evidence.role.targetIdentity;
                host = evidence.role.host;
                port = evidence.role.port;
                classification = evidence.classification;
                keyType = evidence.keyType;
                fingerprint = evidence.fingerprint;
                snapshotRequestId = snapshot.requestId;
                snapshotRootPath = snapshot.rootPath;
                snapshotConnectionId = snapshot.connectionId;
                snapshotVaultId = snapshot.vaultId;
                snapshotGeneration = snapshot.generation;
                snapshotEpoch = snapshot.epoch;
                snapshotDeadline = snapshot.deadline;
                snapshotBinding = snapshot.binding();
                baseDigest = snapshot.baseDigest();
                replacementDigest = candidateDigest.clone();
                content = exactReplacement.clone();
                String fields = candidateId + '\0' + snapshotRequestId + '\0' + roleBinding + '\0'
                        + roleHop + '\0' + path + '\0' + objectId + '\0' + objectType.name() + '\0'
                        + objectIdentity + '\0' + connectionId + '\0' + targetIdentity + '\0' + host
                        + '\0' + port + '\0' + classification.name() + '\0' + keyType + '\0'
                        + fingerprint + '\0' + hex(baseDigest) + '\0' + hex(replacementDigest);
                byte[] bindingBytes = fields.getBytes(StandardCharsets.UTF_8);
                try {
                    binding = hex(VaultCodec.digest(bindingBytes));
                } finally {
                    VaultCodec.wipe(bindingBytes);
                }
            } catch (VaultException | RuntimeException | Error failure) {
                // Construction can fail after the private body clone exists;
                // do not leave that partial object for eventual GC wiping.
                close();
                throw failure;
            } finally {
                VaultCodec.wipe(candidateDigest);
                VaultCodec.wipe(evidenceBase);
                VaultCodec.wipe(evidenceReplacement);
            }
        }

        synchronized void requireSnapshot(SnapshotReceipt snapshot) throws VaultException {
            requireSnapshot(snapshot, false);
        }

        private void requireSnapshot(SnapshotReceipt snapshot, boolean transferred) throws VaultException {
            if (closed || consumed != transferred || snapshot == null) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate is not exact");
            }
            snapshot.requireLive();
            if (!snapshot.requestId.equals(snapshotRequestId)
                    || !snapshot.rootPath.equals(snapshotRootPath)
                    || !snapshot.connectionId.equals(snapshotConnectionId)
                    || !snapshot.vaultId.equals(snapshotVaultId)
                    || snapshot.generation != snapshotGeneration
                    || snapshot.epoch != snapshotEpoch
                    || snapshot.deadline != snapshotDeadline
                    || !snapshot.role.binding().equals(roleBinding) || snapshot.role.hop != roleHop
                    || !snapshot.path.equals(path) || !snapshot.objectId.equals(objectId)
                    || snapshot.objectType != objectType || !snapshot.objectIdentity.equals(objectIdentity)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate is not exact");
            }
            byte[] currentBinding = snapshot.binding();
            try {
                if (!MessageDigest.isEqual(snapshotBinding, currentBinding)) {
                    throw new VaultException("VAULT_CHANGED", "The managed trust snapshot changed");
                }
            } finally {
                VaultCodec.wipe(currentBinding);
            }
        }

        synchronized void requireMatches(SnapshotReceipt snapshot,
                SshHostKeyAuthority.Evidence evidence) throws VaultException {
            requireMatches(snapshot, evidence, false);
        }

        private void requireMatches(SnapshotReceipt snapshot,
                SshHostKeyAuthority.Evidence evidence, boolean transferred) throws VaultException {
            requireSnapshot(snapshot, transferred);
            if (evidence == null || evidence.role != snapshot.role
                    || evidence.classification != classification || !keyType.equals(evidence.keyType)
                    || !fingerprint.equals(evidence.fingerprint)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate is not exact");
            }
            byte[] currentBase = evidence.baseDigest();
            byte[] currentReplacement = evidence.replacementDigest();
            try {
                if (!MessageDigest.isEqual(baseDigest, currentBase)
                        || !MessageDigest.isEqual(replacementDigest, currentReplacement)) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate digest changed");
                }
            } finally {
                VaultCodec.wipe(currentBase);
                VaultCodec.wipe(currentReplacement);
            }
        }

        synchronized byte[] consume() throws VaultException {
            if (closed || consumed || content == null || fixtureReservation != null) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate is unavailable");
            }
            consumed = true;
            return content.clone();
        }

        private synchronized void reserveFixture(FixtureCandidateReservation reservation) throws VaultException {
            if (closed || consumed || content == null || fixtureReservation != null || reservation == null)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture candidate is unavailable");
            fixtureReservation = reservation;
        }

        private synchronized void requireFixtureReservation(FixtureCandidateReservation reservation)
                throws VaultException {
            if (closed || consumed || content == null || fixtureReservation != reservation)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture candidate is unavailable");
        }

        private synchronized void transferFixtureReservation(FixtureCandidateReservation reservation,
                FixtureCandidateTransfer transfer) throws VaultException {
            requireFixtureReservation(reservation);
            synchronized (reservation) {
                if (reservation.revoked || reservation.transfer != null)
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture reservation is unavailable");
                transfer.accept(content);
                content = null;
                consumed = true;
                reservation.transfer = transfer;
            }
        }

        private synchronized void requireTransferredFixture(SnapshotReceipt snapshot,
                SshHostKeyAuthority.Evidence evidence, FixtureCandidateReservation reservation)
                throws VaultException {
            if (fixtureReservation != reservation || content != null || !consumed)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture candidate is unavailable");
            requireMatches(snapshot, evidence, true);
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (fixtureReservation != null) fixtureReservation.revoke();
            VaultCodec.wipe(baseDigest);
            VaultCodec.wipe(snapshotBinding);
            VaultCodec.wipe(replacementDigest);
            VaultCodec.wipe(content);
        }
    }

    private final SshVaultAdapter adapter;
    private final SnapshotReceipt snapshot;
    private KnownHostsCandidate candidate;
    private FixtureCandidateReservation fixtureReservation;
    private SshTrustProposal activeProposal;
    private VaultOperation activeRequest;
    private AppSecrets.SshTrustContext activeContext;
    private final Object cleanupLock = new Object();
    private final CleanupInvalidation cleanupInvalidation = new CleanupInvalidation();
    private boolean cleanupStarted, cleanupNeedsInvalidation, cleanupRevoked, cleanupComplete;
    private boolean cleanupResourceUnconfirmed;
    private boolean writing;
    private volatile boolean confirmedCommit;
    private volatile boolean newCommitConfirmed;
    private boolean newNativeAcknowledged;
    private boolean newInvalidated;
    private volatile boolean changedCommitConfirmed;
    private boolean changedNativeAcknowledged;
    private boolean changedInvalidated;
    private final TrustCommitObserver commitObserver;
    private boolean closed;

    private SshManagedKnownHostsProvider(SshVaultAdapter adapter, SnapshotReceipt snapshot,
            KnownHostsCandidate candidate) throws VaultException {
        this(adapter, snapshot, candidate, () -> { });
    }

    private SshManagedKnownHostsProvider(SshVaultAdapter adapter, SnapshotReceipt snapshot,
            KnownHostsCandidate candidate, TrustCommitObserver commitObserver) throws VaultException {
        if (adapter == null || snapshot == null) throw VaultException.invalid();
        snapshot.requireLive();
        if (candidate != null) candidate.requireSnapshot(snapshot);
        this.adapter = adapter;
        this.snapshot = snapshot;
        this.candidate = candidate;
        this.commitObserver = java.util.Objects.requireNonNull(commitObserver);
        this.commitObserver.bindProvider(this, snapshot.role);
    }

    /** Create a provider owning exactly one candidate and its snapshot receipt. */
    static SshManagedKnownHostsProvider bind(SshVaultAdapter adapter, SnapshotReceipt snapshot,
            KnownHostsCandidate candidate) throws VaultException {
        if (candidate == null) throw new VaultException("SSH_HOST_KEY_REVIEW", "A managed trust candidate is required");
        return new SshManagedKnownHostsProvider(adapter, snapshot, candidate);
    }

    /**
     * Create the managed side of the separate native candidate handoff.  It
     * starts without a candidate and accepts exactly one through
     * SshKnownHostsCandidateHandoff. A fixture transfer only moves ownership;
     * it cannot create a normal proposal or authorize a trust write.
     */
    static SshManagedKnownHostsProvider bindForNativeHandoff(SshVaultAdapter adapter,
            SnapshotReceipt snapshot) throws VaultException {
        return new SshManagedKnownHostsProvider(adapter, snapshot, null);
    }

    static SshManagedKnownHostsProvider bindForNativeHandoff(SshVaultAdapter adapter,
            SnapshotReceipt snapshot, TrustCommitObserver commitObserver) throws VaultException {
        return new SshManagedKnownHostsProvider(adapter, snapshot, null, commitObserver);
    }

    /** The ledger must be the observer this provider will actually notify. */
    boolean hasCommitObserver(TrustCommitObserver observer) {
        return commitObserver == observer;
    }

    /** Metadata equality is insufficient; the native source and provider must share this receipt. */
    synchronized boolean ownsChildSnapshot(ChildTrustSnapshot source) {
        return !closed && source != null && !source.closing
                && snapshot == source.receipt && adapter == source.adapter;
    }

    boolean sharesVault(SshManagedKnownHostsProvider other) {
        return other != null && adapter.sharesVault(other.adapter);
    }

    boolean sharesVault(AppSecrets other) { return adapter.sharesVault(other); }

    SshKnownHostsCandidateHandoff nativeCandidateHandoff() throws VaultException {
        synchronized (this) {
            if (closed || candidate != null || activeProposal != null) {
                throw new VaultException("SSH_HOST_KEY_REVIEW",
                        "The exact managed trust candidate is unavailable");
            }
            snapshot.requireLive();
            return new SshKnownHostsCandidateHandoff(this, snapshot);
        }
    }

    synchronized boolean acceptsNativeCandidate(KnownHostsCandidate next) throws VaultException {
        if (closed || next == null || candidate != null || activeProposal != null) {
            throw new VaultException("SSH_HOST_KEY_REVIEW",
                    "The exact managed trust candidate is unavailable");
        }
        next.requireSnapshot(snapshot);
        candidate = next;
        return true;
    }

    /* A point-in-time identity check for the isolated JNI fixture.  The
     * returned token carries no candidate operations or bytes; this neither
     * reserves the candidate nor authorizes a proposal or trust write. */
    synchronized Object requireProvisionalFixtureCandidate(ChildTrustSnapshot source,
            SshHostKeyAuthority.Evidence evidence, String candidateId, Object expected)
            throws VaultException {
        if (closed || source == null || source.closing || source.receipt != snapshot || source.adapter != adapter
                || candidate == null || activeProposal != null
                || expected != null && candidate.provisionalFixtureIdentity != expected
                || candidateId == null || !candidateId.equals(candidate.candidateId)
                || evidence == null || evidence.classification != SshHostKeyAuthority.Classification.NEW)
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture candidate is unavailable");
        FixtureCandidateTransfer transfer = fixtureReservation == null ? null : fixtureReservation.transfer();
        if (transfer == null) candidate.requireMatches(snapshot, evidence);
        else {
            if (fixtureReservation.revoked() || !transfer.available())
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture transfer is unavailable");
            candidate.requireTransferredFixture(snapshot, evidence, fixtureReservation);
        }
        return candidate.provisionalFixtureIdentity;
    }

    /** Fixture ownership only: it exposes neither candidate bytes nor write authority. */
    static final class FixtureCandidateReservation implements AutoCloseable {
        private final SshManagedKnownHostsProvider provider;
        private final KnownHostsCandidate candidate;
        private final ChildTrustSnapshot source;
        private final SshNativeCallbackAdapter.SignedNewFixtureReview review;
        private final SshNativeSession child;
        private final Thread owner;
        private volatile boolean revoked;
        private FixtureCandidateTransfer transfer;

        private FixtureCandidateReservation(SshManagedKnownHostsProvider provider,
                KnownHostsCandidate candidate, ChildTrustSnapshot source,
                SshNativeCallbackAdapter.SignedNewFixtureReview review, SshNativeSession child) {
            this.provider = provider;
            this.candidate = candidate;
            this.source = source;
            this.review = review;
            this.child = child;
            owner = Thread.currentThread();
        }

        void requireHeldFor(SshNativeSession session) throws VaultException {
            if (Thread.currentThread() != owner || session != child || revoked)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture reservation is unavailable");
            review.requireLiveFor(child);
            provider.requireFixtureReservation(this);
            review.requireLiveFor(child);
            if (revoked) throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture reservation expired");
        }

        FixtureCandidateTransfer transferFor(SshNativeSession session) throws VaultException {
            if (Thread.currentThread() != owner || session != child || revoked)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture reservation is unavailable");
            review.requireLiveFor(child);
            FixtureCandidateTransfer result = provider.transferFixtureReservation(this);
            try {
                result.requireHeldFor(child);
                return result;
            } catch (VaultException | RuntimeException | Error failure) {
                revoke();
                throw failure;
            }
        }

        synchronized FixtureCandidateTransfer transfer() { return transfer; }

        void revoke() {
            FixtureCandidateTransfer owned;
            synchronized (this) { revoked = true; owned = transfer; }
            if (owned != null) owned.revoke();
        }

        boolean revoked() { return revoked; }

        @Override public void close() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("The fixture reservation belongs to another thread");
            revoke();
        }
    }

    /** Owns the moved body under the original provider; it grants no write authority. */
    static final class FixtureCandidateTransfer implements AutoCloseable {
        private final FixtureCandidateReservation reservation;
        private final Thread owner;
        private byte[] content;
        private boolean closed;

        private FixtureCandidateTransfer(FixtureCandidateReservation reservation) {
            this.reservation = reservation;
            owner = Thread.currentThread();
        }

        private synchronized void accept(byte[] exact) {
            if (closed || content != null || exact == null || exact.length == 0)
                throw new IllegalStateException("The fixture transfer is unavailable");
            content = exact;
        }

        private synchronized boolean available() { return !closed && content != null; }

        void requireHeldFor(SshNativeSession session) throws VaultException {
            if (Thread.currentThread() != owner || session != reservation.child || reservation.revoked())
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture transfer is unavailable");
            reservation.review.requireLiveFor(session);
            reservation.provider.requireFixtureTransfer(this);
            reservation.review.requireLiveFor(session);
            if (reservation.revoked() || !available())
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture transfer expired");
        }

        private synchronized void revoke() {
            if (closed) return;
            closed = true;
            VaultCodec.wipe(content);
            content = null;
        }

        @Override public void close() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("The fixture transfer belongs to another thread");
            reservation.revoke();
        }
    }

    synchronized FixtureCandidateReservation reserveProvisionalFixture(ChildTrustSnapshot source,
            SshHostKeyAuthority.Evidence evidence, String candidateId, Object expected,
            SshNativeCallbackAdapter.SignedNewFixtureReview review, SshNativeSession child,
            SshHostKeyReviewMailbox.SignedFixtureApproval.Claim claim)
            throws VaultException {
        if (fixtureReservation != null || review == null || child == null || claim == null
                || !claim.matches(review, child, source, evidence, candidateId, expected))
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture candidate is unavailable");
        requireProvisionalFixtureCandidate(source, evidence, candidateId, expected);
        FixtureCandidateReservation reservation = new FixtureCandidateReservation(this, candidate,
                source, review, child);
        if (!claim.consume(review, child, source, evidence, candidateId, expected))
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture candidate is unavailable");
        candidate.reserveFixture(reservation);
        fixtureReservation = reservation;
        return reservation;
    }

    private synchronized void requireFixtureReservation(FixtureCandidateReservation reservation)
            throws VaultException {
        if (closed || reservation == null || reservation.revoked || fixtureReservation != reservation
                || candidate != reservation.candidate || reservation.source.closing
                || reservation.source.receipt != snapshot || reservation.source.adapter != adapter)
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture reservation is unavailable");
        candidate.requireFixtureReservation(reservation);
    }

    private synchronized FixtureCandidateTransfer transferFixtureReservation(
            FixtureCandidateReservation reservation) throws VaultException {
        requireFixtureReservation(reservation);
        FixtureCandidateTransfer result = new FixtureCandidateTransfer(reservation);
        candidate.transferFixtureReservation(reservation, result);
        return result;
    }

    private synchronized void requireFixtureTransfer(FixtureCandidateTransfer transfer)
            throws VaultException {
        if (closed || transfer == null || fixtureReservation != transfer.reservation
                || transfer.reservation.revoked() || transfer.reservation.transfer() != transfer
                || candidate != transfer.reservation.candidate || transfer.reservation.source.closing
                || transfer.reservation.source.receipt != snapshot
                || transfer.reservation.source.adapter != adapter || !transfer.available())
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture transfer is unavailable");
    }

    void abortNativeHandoff() { failClosed(); }

    /**
     * Loans the exact managed file closure synchronously and retains only the
     * exact metadata, base digest and AppSecrets snapshot receipt.
     */
    static SnapshotReceipt snapshotFor(SshVaultAdapter adapter, SshRoleContext role,
            SshRoleContext.Clock clock, String rootPath, String path, String objectId,
            String objectIdentity) throws VaultException {
        if (adapter == null || role == null || clock == null || rootPath == null || path == null
                || objectId == null || objectIdentity == null) throw VaultException.invalid();
        VaultManifest.path(rootPath);
        VaultManifest.path(path);
        role.requireActive(clock);
        AppSecrets.EffectGuard origin = new AppSecrets.SshRoleGuard() {
            @Override public void check() throws VaultException { role.requireActive(clock); }
            @Override public void withRole(AppSecrets.EffectGuard publication) throws VaultException {
                role.publishBound(clock, publication);
            }
        };
        AppSecrets.SshPublicReadContext context = adapter.fileContext(role.deadline, origin);
        VaultOperation request = adapter.fileRequest(rootPath, role.connectionId, context);
        final SnapshotReceipt[] result = new SnapshotReceipt[1];
        try {
            adapter.useFileSnapshot(request, rootPath, role.connectionId, snapshot -> {
                role.requireActive(clock);
                VaultManifest.FileObject selected = selectedTrustFile(snapshot, path, objectId, objectIdentity);
                byte[] digest = VaultCodec.digest(selected.content);
                try {
                    result[0] = new SnapshotReceipt(role, rootPath, path, objectId,
                            objectIdentity, snapshot, digest, clock);
                } finally {
                    VaultCodec.wipe(digest);
                }
            });
            role.requireActive(clock);
            if (result[0] == null) throw new VaultException("VAULT_OPERATION", "The managed snapshot is unavailable");
            result[0].requireLive();
            return result[0];
        } catch (VaultException | RuntimeException | Error failure) {
            if (result[0] != null) result[0].close();
            throw failure;
        } finally {
            // The public snapshot was copied synchronously; do not retain the
            // consumed SSH_FILES operation while the trust review is pending.
            adapter.cancel(request);
        }
    }

    /** Capture the receipt and native source without replacing the original public lease. */
    static ChildTrustSnapshot snapshotForChild(SshVaultAdapter adapter, SshRoleContext role,
            SshRoleContext.Clock clock, SshRoleContext.PublicView originalView, String rootPath,
            String path, String objectId, String objectIdentity) throws VaultException {
        if (adapter == null || role == null || clock == null || originalView == null
                || rootPath == null || path == null || objectId == null || objectIdentity == null)
            throw VaultException.invalid();
        VaultManifest.path(rootPath);
        VaultManifest.path(path);
        role.requireActive(clock);
        AppSecrets.EffectGuard origin = new AppSecrets.SshRoleGuard() {
            @Override public void check() throws VaultException { role.requireActive(clock); }
            @Override public void withRole(AppSecrets.EffectGuard publication) throws VaultException {
                role.publishBound(clock, publication);
            }
        };
        AppSecrets.SshPublicReadContext context = adapter.fileContext(role.deadline, origin);
        VaultOperation request = adapter.fileRequest(rootPath, role.connectionId, context);
        final ChildTrustSnapshot[] result = new ChildTrustSnapshot[1];
        try {
            adapter.useFileSnapshot(request, rootPath, role.connectionId, snapshot -> {
                long revision = adapter.bindChildTrustSnapshot(request, snapshot, role, originalView);
                VaultManifest.FileObject selected = selectedTrustFile(snapshot, path, objectId, objectIdentity);
                byte[] digest = null, wire = null;
                SnapshotReceipt receipt = null;
                try {
                    digest = VaultCodec.digest(selected.content);
                    receipt = new SnapshotReceipt(role, rootPath, path, objectId,
                            objectIdentity, snapshot, digest, clock);
                    wire = SshPublicFileLease.encode(snapshot);
                    if (adapter.bindChildTrustSnapshot(request, snapshot, role, originalView) != revision)
                        throw new VaultException("VAULT_CHANGED", "The child trust source changed; try again");
                    result[0] = new ChildTrustSnapshot(adapter, originalView, receipt, revision, wire);
                    receipt = null;
                    wire = null;
                } finally {
                    if (receipt != null) receipt.close();
                    VaultCodec.wipe(digest);
                    VaultCodec.wipe(wire);
                }
            });
            if (result[0] == null) throw new VaultException("VAULT_OPERATION", "The child trust source is unavailable");
            result[0].requireCurrent();
            return result[0];
        } catch (VaultException | RuntimeException | Error failure) {
            if (result[0] != null) result[0].close();
            throw failure;
        } finally { adapter.cancel(request); }
    }

    private static VaultManifest.FileObject selectedTrustFile(SshFileSnapshot snapshot,
            String path, String objectId, String objectIdentity) throws VaultException {
        VaultManifest.FileObject selected = null;
        for (VaultManifest.FileObject file : snapshot.publicFiles) {
            if (file.path.equals(path) && file.id.equals(objectId)
                    && file.type == VaultManifest.FileType.KNOWN_HOSTS
                    && file.identity.equals(objectIdentity)) {
                if (selected != null)
                    throw new VaultException("VAULT_CONTENT", "The managed trust object is ambiguous");
                selected = file;
            }
        }
        if (selected == null)
            throw new VaultException("VAULT_OPERATION", "The exact managed trust object is unavailable");
        return selected;
    }

    /** Build a candidate only from caller-supplied exact replacement bytes. */
    static KnownHostsCandidate candidateFor(SnapshotReceipt snapshot,
            SshHostKeyAuthority.Evidence evidence, byte[] exactReplacement) throws VaultException {
        return candidateFor(snapshot, evidence, UUID.randomUUID().toString(), exactReplacement);
    }

    /** Build a candidate with the issuer's explicit one-shot identity. */
    static KnownHostsCandidate candidateFor(SnapshotReceipt snapshot,
            SshHostKeyAuthority.Evidence evidence, String candidateId, byte[] exactReplacement)
            throws VaultException {
        return new KnownHostsCandidate(snapshot, evidence, candidateId, exactReplacement,
                SshHostKeyAuthority.Classification.NEW);
    }

    static KnownHostsCandidate changedCandidateFor(SnapshotReceipt snapshot,
            SshHostKeyAuthority.Evidence evidence, String candidateId, byte[] exactReplacement)
            throws VaultException {
        return new KnownHostsCandidate(snapshot, evidence, candidateId, exactReplacement,
                SshHostKeyAuthority.Classification.CHANGED);
    }

    @Override public synchronized SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence)
            throws VaultException {
        return proposalFor(evidence, null);
    }

    @Override public synchronized SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence,
            SshHostKeyAuthority.Proof proof) throws VaultException {
        return proposalForExact(evidence, proof, SshHostKeyAuthority.Classification.NEW);
    }

    synchronized SshTrustProposal changedProposalFor(SshHostKeyAuthority.Evidence evidence)
            throws VaultException {
        return proposalForExact(evidence, null, SshHostKeyAuthority.Classification.CHANGED);
    }

    private SshTrustProposal proposalForExact(SshHostKeyAuthority.Evidence evidence,
            SshHostKeyAuthority.Proof proof, SshHostKeyAuthority.Classification expected)
            throws VaultException {
        if (evidence == null || evidence.classification != expected)
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact host candidate is unavailable");
        if (closed || activeProposal != null || candidate == null || fixtureReservation != null) {
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact managed trust candidate is unavailable");
        }
        snapshot.requireLive();
        if (proof != null) proof.requireMatches(snapshot.role, evidence);
        candidate.requireMatches(snapshot, evidence);
        byte[] replacement = candidate.consume();
        try {
            SshTrustProposal proposal = new SshTrustProposal(snapshot.role, snapshot.path,
                    snapshot.objectId, snapshot.objectType, snapshot.objectIdentity,
                    evidence.role.targetIdentity, evidence.role.connectionId, evidence.role.host,
                    evidence.role.port, evidence.keyType, evidence.fingerprint,
                    candidate.binding, snapshot.baseDigest(), replacement);
            AppSecrets.SshTrustContext context;
            try {
                context = adapter.trustContext(snapshot, proposal, () -> {
                    synchronized (SshManagedKnownHostsProvider.this) {
                        if (closed || activeProposal != proposal)
                            throw new VaultException("VAULT_CANCELLED", "The managed trust request was closed");
                    }
                });
            } catch (VaultException | RuntimeException | Error failure) {
                proposal.close();
                throw failure;
            }
            activeProposal = proposal;
            activeContext = context;
            candidate.close();
            candidate = null;
            return proposal;
        } catch (VaultException | RuntimeException | Error failure) {
            candidate.close();
            candidate = null;
            snapshot.close();
            throw failure;
        } finally {
            VaultCodec.wipe(replacement);
        }
    }

    /** The old no-proof writer is intentionally unavailable for this provider. */
    @Override public synchronized SshTrustProposal.CasResult write(
            SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal) throws VaultException {
        throw new VaultException("SSH_HOST_KEY_CAS", "The role-bound managed trust writer is required");
    }

    @Override public SshTrustProposal.CasResult write(
            SshHostKeyAuthority.Evidence evidence, SshHostKeyAuthority.Proof proof,
            SshTrustProposal proposal) throws VaultException {
        AppSecrets.SshTrustContext context;
        synchronized (this) {
            if (closed || writing || activeProposal != proposal || proposal == null || proof == null)
                throw new VaultException("SSH_HOST_KEY_CAS", "The exact managed trust writer is unavailable");
            writing = true;
            context = activeContext;
        }
        boolean persisted = false;
        boolean notified = false;
        try {
            snapshot.requireLive();
            proof.requireMatches(snapshot.role, evidence);
            snapshot.requireMatches(snapshot.role, evidence, proposal);
            if (!proposal.matchesEvidence(evidence))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact unknown host-key review is required");
            // Metadata receipt is prepared before persistence, but is published only after continuation.
            SshTrustProposal.CasResult result = SshTrustProposal.CasResult.committed(evidence, proposal);
            VaultOperation request = adapter.trustRequest(snapshot.role, evidence, proof, snapshot, proposal, context);
            synchronized (this) {
                if (closed || activeProposal != proposal)
                    throw new VaultException("VAULT_CANCELLED", "The managed trust request was closed");
                activeRequest = request;
            }
            adapter.updateTrust(request, snapshot.role, evidence, proof, snapshot, proposal, () -> { });
            persisted = true;
            confirmedCommit = true;
            newCommitConfirmed = true;
            notified = true;
            commitObserver.newPersisted();
            synchronized (this) {
                if (closed || activeProposal != proposal)
                    throw new VaultException("VAULT_CANCELLED", "The managed trust request was closed");
                snapshot.requireLive();
                if (!result.matches(evidence, proposal, SshTrustProposal.CasStatus.COMMITTED))
                    throw new VaultException("SSH_HOST_KEY_CAS", "The trust receipt changed");
                activeRequest = null;
                activeContext = null;
                activeProposal = null;
                closed = true;
            }
            snapshot.close();
            return result;
        } catch (VaultException | RuntimeException | Error failure) {
            if (persisted || failure instanceof VaultException
                    && "VAULT_COMMIT_UNCERTAIN".equals(((VaultException)failure).code)) {
                confirmedCommit = true;
                newCommitConfirmed = true;
                if (!notified) {
                    try { commitObserver.newPersisted(); }
                    catch (RuntimeException | Error observerFailure) { failure.addSuppressed(observerFailure); }
                }
                VaultException uncertain = adapter.uncertainTrustCommit(failure);
                try { failClosed(); } catch (RuntimeException | Error cleanup) { uncertain.addSuppressed(cleanup); }
                throw uncertain;
            }
            try { failClosed(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public VaultException invalidateCommittedResult() {
        return confirmedCommit ? adapter.uncertainTrustCommit(null) : null;
    }

    /** Only the owner may acknowledge NEW after the native KEX step completed. */
    synchronized void newProofPublished() throws VaultException {
        if (!newCommitConfirmed || newNativeAcknowledged || newInvalidated)
            throw new VaultException("VAULT_COMMIT_UNCERTAIN", "The new-host result was not confirmed");
        commitObserver.newProofPublished();
        newNativeAcknowledged = true;
    }

    VaultException invalidateUnacknowledgedNew() {
        synchronized (this) {
            if (!newCommitConfirmed || newNativeAcknowledged) return null;
            // Prevent a concurrent owner confirmation from overtaking vault invalidation.
            newInvalidated = true;
        }
        return adapter.uncertainTrustCommit(null);
    }

    /** Commit one separately authorized CHANGED candidate only after native signed KEX/NEWKEYS. */
    ChangedCommitReceipt writeChanged(SshHostKeyAuthority.Evidence evidence,
            SshNativeCallbackAdapter.ChangedAuthorization authorization, SshTrustProposal proposal)
            throws VaultException {
        AppSecrets.SshTrustContext context;
        synchronized (this) {
            if (closed || writing || activeProposal != proposal || proposal == null || authorization == null)
                throw new VaultException("SSH_HOST_KEY_CAS", "The exact changed-host writer is unavailable");
            writing = true;
            context = activeContext;
        }
        boolean persisted = false;
        try {
            snapshot.requireChangedMatches(snapshot.role, evidence, proposal);
            authorization.requireMatches(snapshot.role, evidence, proposal);
            VaultOperation request = adapter.changedTrustRequest(snapshot.role, evidence, authorization,
                    snapshot, proposal, context);
            synchronized (this) {
                if (closed || activeProposal != proposal)
                    throw new VaultException("VAULT_CANCELLED", "The managed trust request was closed");
                activeRequest = request;
            }
            adapter.updateChangedTrust(request, snapshot.role, evidence, authorization, snapshot, proposal, () -> { });
            persisted = true;
            changedCommitConfirmed = true;
            confirmedCommit = true;
            commitObserver.persisted();
            synchronized (this) {
                if (closed || activeProposal != proposal)
                    throw new VaultException("VAULT_CANCELLED", "The managed trust request was closed");
                snapshot.requireLive();
                authorization.requireMatches(snapshot.role, evidence, proposal);
                activeRequest = null;
                activeContext = null;
                activeProposal = null;
                closed = true;
            }
            ChangedCommitReceipt receipt = new ChangedCommitReceipt(this, authorization, evidence, proposal);
            snapshot.close();
            return receipt;
        } catch (VaultException | RuntimeException | Error failure) {
            boolean uncertain = persisted || failure instanceof VaultException
                    && "VAULT_COMMIT_UNCERTAIN".equals(((VaultException)failure).code);
            if (uncertain) {
                changedCommitConfirmed = true;
                confirmedCommit = true;
                commitObserver.persisted();
                VaultException result = adapter.uncertainTrustCommit(failure);
                try { failClosed(); } catch (RuntimeException | Error cleanup) { result.addSuppressed(cleanup); }
                throw result;
            }
            try { failClosed(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    synchronized void changedProofPublished() throws VaultException {
        if (!changedCommitConfirmed || changedNativeAcknowledged || changedInvalidated)
            throw new VaultException("SSH_HOST_KEY_CAS", "The changed-host commit is unavailable");
        commitObserver.proofPublished();
        changedNativeAcknowledged = true;
    }

    VaultException invalidateUnacknowledgedChanged() {
        synchronized (this) {
            if (!changedCommitConfirmed || changedNativeAcknowledged) return null;
            changedInvalidated = true;
        }
        return adapter.uncertainTrustCommit(null);
    }

    private void failClosed() {
        synchronized (cleanupLock) {
            synchronized (this) {
                if (cleanupComplete) return;
                if (!cleanupStarted) {
                    cleanupStarted = true;
                    cleanupNeedsInvalidation = !closed;
                    if (fixtureReservation != null) fixtureReservation.revoke();
                    synchronized (snapshot) {
                        closed = true;
                        snapshot.markClosing();
                    }
                }
                if (cleanupResourceUnconfirmed)
                    throw new IllegalStateException("SSH provider cleanup is unconfirmed");
                if (!cleanupRevoked && cleanupNeedsInvalidation) {
                    cleanupInvalidation.begin();
                    try { /* The closed flag revokes new provider use. */ }
                    finally { cleanupInvalidation.end(); }
                }
                cleanupRevoked = true;
            }
            AppSecrets.SshTrustContext context;
            VaultOperation request;
            KnownHostsCandidate ownedCandidate;
            SshTrustProposal proposal;
            synchronized (this) {
                context = activeContext;
                request = activeRequest;
                ownedCandidate = candidate;
                proposal = activeProposal;
            }
            Throwable failure = null;
            if (context != null) try {
                context.cancel();
                synchronized (this) { if (activeContext == context) activeContext = null; }
            } catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
            if (request != null) try {
                adapter.cancel(request);
                synchronized (this) { if (activeRequest == request) activeRequest = null; }
            } catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
            if (ownedCandidate != null) try {
                ownedCandidate.close();
                synchronized (this) { if (candidate == ownedCandidate) candidate = null; }
            } catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
            if (proposal != null) try {
                proposal.close();
                synchronized (this) { if (activeProposal == proposal) activeProposal = null; }
            } catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
            if (failure != null) synchronized (this) { cleanupResourceUnconfirmed = true; }
            /* Receipt cleanup is independent and tracks its own uncertainty. */
            try { snapshot.close(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
            if (failure instanceof RuntimeException) throw (RuntimeException)failure;
            if (failure != null) throw (Error)failure;
            synchronized (this) { cleanupComplete = true; }
        }
    }

    private static Throwable cleanupFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    @Override public void close() { failClosed(); }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
