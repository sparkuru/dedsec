package im.majo.ctos;

import java.util.Objects;
import java.util.UUID;

/** One exact NEW-key review. UI decisions never enter session JNI or its owner queue. */
final class SshHostKeyReviewMailbox implements SshHostKeyAuthority.UnknownKeyIssuer, AutoCloseable {
    static final long MAX_WAIT_MILLIS = 30_000L;
    private static final long WAIT_SLICE_MILLIS = 50L;

    interface LiveCheck { void check() throws VaultException; }
    /** Bounded, non-authenticating owner work; it supplies no trust or effect authority. */
    interface PendingPoll { void poll() throws VaultException; }
    /** Fixture observer sees a native result but cannot manufacture readiness. */
    interface SignedFixtureObserver { void onPoll(int observation) throws VaultException; }
    private interface ReadyPoll { boolean poll() throws VaultException; }
    /** Both hooks must publish without blocking; decisions arrive through resolve/cancel. */
    interface Sink {
        void present(Ticket ticket) throws VaultException;
        default void dismiss(Ticket ticket) { }
    }

    /** Minted only after the exact signed wait and dismissal completed successfully. */
    static final class SignedFixtureApproval {
        private final SshHostKeyReviewMailbox mailbox;
        private final SshNativeCallbackAdapter.SignedNewFixtureReview review;
        private final SshNativeSession child;
        private final Ticket ticket;
        private boolean claimed;

        /** An immutable one-shot binding checked without taking the mailbox monitor. */
        static final class Claim {
            private final SignedFixtureApproval approval;
            private final java.util.concurrent.atomic.AtomicBoolean consumed =
                    new java.util.concurrent.atomic.AtomicBoolean();

            private Claim(SignedFixtureApproval approval) { this.approval = approval; }

            boolean matches(SshNativeCallbackAdapter.SignedNewFixtureReview review, SshNativeSession child,
                    SshManagedKnownHostsProvider.ChildTrustSnapshot source,
                    SshHostKeyAuthority.Evidence evidence, String candidateId, Object candidateIdentity) {
                return Thread.currentThread() == approval.mailbox.owner && approval.review == review
                        && approval.child == child && review != null
                        && review.matchesFixtureCandidate(child, source, evidence, candidateId, candidateIdentity);
            }

            boolean consume(SshNativeCallbackAdapter.SignedNewFixtureReview review, SshNativeSession child,
                    SshManagedKnownHostsProvider.ChildTrustSnapshot source,
                    SshHostKeyAuthority.Evidence evidence, String candidateId, Object candidateIdentity) {
                return matches(review, child, source, evidence, candidateId, candidateIdentity)
                        && approval.mailbox.cancellation.admitTerminalIo()
                        && consumed.compareAndSet(false, true);
            }
        }

        private SignedFixtureApproval(SshHostKeyReviewMailbox mailbox,
                SshNativeCallbackAdapter.SignedNewFixtureReview review,
                SshNativeSession child, Ticket ticket) {
            this.mailbox = mailbox;
            this.review = review;
            this.child = child;
            this.ticket = ticket;
        }

        Claim claim(SshNativeCallbackAdapter.SignedNewFixtureReview exact, SshNativeSession session)
                throws VaultException {
            if (Thread.currentThread() != mailbox.owner || exact != review || session != child)
                throw failure("SSH_HOST_KEY_REVIEW");
            synchronized (mailbox.lock) {
                if (claimed || mailbox.cancelled || !ticket.withinWallLimit()
                        || !mailbox.cancellation.admitTerminalIo())
                    throw failure("SSH_HOST_KEY_REVIEW");
                claimed = true;
                return new Claim(this);
            }
        }

        void requireActive() throws VaultException {
            synchronized (mailbox.lock) {
                if (!claimed || mailbox.cancelled || mailbox.cancellation.stopRequested() || !ticket.withinWallLimit())
                    throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
            }
        }
    }

    /** Public metadata only. Object identity and its private review are the decision receipt. */
    static final class Ticket {
        final String id = UUID.randomUUID().toString();
        final String binding;
        final int hop;
        final String connectionId;
        final String targetIdentity;
        final String host;
        final int port;
        final String keyType;
        final String fingerprint;
        final boolean changed;
        final String source;
        final java.util.List<SshHostTrustMaterial.Record> oldRecords;
        final long deadline;
        private final SshHostKeyAuthority.Review review;
        private final SshHostKeyAuthority.ChangedReview changedReview;
        private final SshNativeCallbackAdapter.SignedNewFixtureReview signedReview;
        private final long startedNanos = System.nanoTime();
        private final long waitNanos;
        private int decision; // 0 pending, 1 approve, 2 reject, 3 cancelled

        private Ticket(SshHostKeyAuthority.Review review, long deadline, long maximumWaitMillis) {
            this.review = review;
            binding = review.binding;
            hop = review.hop;
            connectionId = review.connectionId;
            targetIdentity = review.targetIdentity;
            host = review.host;
            port = review.port;
            keyType = review.keyType;
            fingerprint = review.fingerprint;
            changed = false;
            source = "";
            oldRecords = java.util.Collections.emptyList();
            changedReview = null;
            signedReview = null;
            this.deadline = deadline;
            waitNanos = maximumWaitMillis * 1_000_000L;
        }

        private Ticket(SshHostKeyAuthority.ChangedReview review, long deadline, long maximumWaitMillis) {
            this.review = null;
            changedReview = review;
            signedReview = null;
            binding = review.binding;
            hop = review.hop;
            connectionId = review.connectionId;
            targetIdentity = review.targetIdentity;
            host = review.host;
            port = review.port;
            keyType = review.keyType;
            fingerprint = review.fingerprint;
            changed = true;
            source = review.source;
            oldRecords = review.oldRecords;
            this.deadline = deadline;
            waitNanos = maximumWaitMillis * 1_000_000L;
        }

        private Ticket(SshNativeCallbackAdapter.SignedNewFixtureReview review, long deadline,
                long maximumWaitMillis) {
            this.review = null;
            changedReview = null;
            signedReview = review;
            binding = review.binding;
            hop = review.hop;
            connectionId = review.connectionId;
            targetIdentity = review.targetIdentity;
            host = review.host;
            port = review.port;
            keyType = review.keyType;
            fingerprint = review.fingerprint;
            changed = false;
            source = "";
            oldRecords = java.util.Collections.emptyList();
            this.deadline = deadline;
            waitNanos = maximumWaitMillis * 1_000_000L;
        }

        byte[] baseDigest() {
            if (signedReview != null) throw new IllegalStateException("Fixture review digests are private");
            return changed ? changedReview.baseDigest() : review.baseDigest();
        }
        byte[] replacementDigest() {
            if (signedReview != null) throw new IllegalStateException("Fixture review digests are private");
            return changed ? changedReview.replacementDigest() : review.replacementDigest();
        }

        private boolean withinWallLimit() {
            long elapsed = System.nanoTime() - startedNanos;
            return elapsed >= 0 && elapsed < waitNanos;
        }

        private long waitSliceMillis() {
            long remaining = waitNanos - (System.nanoTime() - startedNanos);
            return Math.max(1L, Math.min(WAIT_SLICE_MILLIS, (remaining + 999_999L) / 1_000_000L));
        }
    }

    private final Object lock = new Object();
    private final Thread owner = Thread.currentThread();
    private final SshRoleContext role;
    private final SshRoleContext.Clock clock;
    private final LiveCheck live;
    private final Sink sink;
    private final long maximumWaitMillis;
    private final SshSessionOwner.Cancellation cancellation;
    private Ticket pending;
    private boolean started;
    private boolean cancelled;

    SshHostKeyReviewMailbox(SshRoleContext role, SshRoleContext.Clock clock, LiveCheck live, Sink sink) {
        this(role, clock, live, sink, MAX_WAIT_MILLIS, new SshSessionOwner.Cancellation());
    }

    SshHostKeyReviewMailbox(SshRoleContext role, SshRoleContext.Clock clock, LiveCheck live, Sink sink,
            SshSessionOwner.Cancellation cancellation) {
        this(role, clock, live, sink, MAX_WAIT_MILLIS, cancellation);
    }

    /** Internal callers may shorten the review budget, never extend the hard ceiling. */
    SshHostKeyReviewMailbox(SshRoleContext role, SshRoleContext.Clock clock, LiveCheck live, Sink sink,
            long maximumWaitMillis) {
        this(role, clock, live, sink, maximumWaitMillis, new SshSessionOwner.Cancellation());
    }

    private SshHostKeyReviewMailbox(SshRoleContext role, SshRoleContext.Clock clock, LiveCheck live, Sink sink,
            long maximumWaitMillis, SshSessionOwner.Cancellation cancellation) {
        this.role = Objects.requireNonNull(role);
        this.clock = Objects.requireNonNull(clock);
        this.live = Objects.requireNonNull(live);
        this.sink = sink; // Missing UI remains an immediate refusal, never auto-approval.
        if (maximumWaitMillis < 1 || maximumWaitMillis > MAX_WAIT_MILLIS)
            throw new IllegalArgumentException("Invalid host review wait budget");
        this.maximumWaitMillis = maximumWaitMillis;
        this.cancellation = Objects.requireNonNull(cancellation);
    }

    @Override public void requireApproved(SshHostKeyAuthority.Review review) throws VaultException {
        requireApprovedInternal(review, Long.MAX_VALUE, null);
    }

    /**
     * The owner may poll only after leaving session JNI and other native locks. Cancellation may
     * race with a poll; the poll must enforce its own effect guards and return promptly.
     * This overload still accepts an ordinary Review, not a signed-pending candidate. Routing
     * that candidate through requireVerified would create a credential-permitting NEW proof;
     * a future provisional flow needs a separate non-authorizing review and ticket.
     */
    void requireApproved(SshHostKeyAuthority.Review review, long transportDeadline, PendingPoll poll)
            throws VaultException {
        requireApprovedInternal(review, transportDeadline, Objects.requireNonNull(poll));
    }

    /** A signed fixture decision is not a host proof or permission to write trust. */
    SshManagedKnownHostsProvider.FixtureCandidateReservation requireSignedNewFixtureApproved(
            SshNativeCallbackAdapter.SignedNewFixtureReview review,
            SshNativeSession child) throws VaultException {
        return requireSignedNewFixtureApprovedForTest(review, child, null, null);
    }

    /** Test peers may advance or observe, but only the real child poll supplies idle. */
    SshManagedKnownHostsProvider.FixtureCandidateReservation requireSignedNewFixtureApprovedForTest(
            SshNativeCallbackAdapter.SignedNewFixtureReview review,
            SshNativeSession child, PendingPoll beforePoll, SignedFixtureObserver observer) throws VaultException {
        if (Thread.currentThread() != owner) throw failure("SSH_CALLBACK_THREAD");
        if (review == null || child == null) throw refusal();
        // Wrong-role and wrong-thread callers cannot consume another owner's review.
        review.requireClaimant(role);
        review.requireSession(child);
        boolean approved = false;
        SshManagedKnownHostsProvider.FixtureCandidateReservation reservation = null;
        try {
            checkLive();
            review.requireLiveFor(child);
            review.claim(role);
            if (sink == null) throw refusal();
            long now = clock.now();
            if (now <= 0 || now >= role.deadline) throw failure("SSH_ROLE_EXPIRED");
            if (now >= review.deadline) throw failure("SSH_TIMED_OUT");
            long deadline = Math.min(role.deadline, review.deadline);
            long budget = Math.min(deadline - now, maximumWaitMillis);
            Ticket ticket = new Ticket(review, now + budget, budget);
            awaitDecision(ticket, () -> {
                if (beforePoll != null) beforePoll.poll();
                int observation = child.pollSignedNewFixture(review);
                if (observer != null) observer.onPoll(observation);
                return (observation & 1) != 0;
            });
            checkLive(ticket);
            checkTime(ticket);
            SignedFixtureApproval approval;
            synchronized (lock) {
                if (cancelled || !ticket.withinWallLimit() || !cancellation.admitTerminalIo())
                    throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                approval = new SignedFixtureApproval(this, review, child, ticket);
            }
            reservation = review.reserveFixtureCandidate(approval, child);
            approval.requireActive();
            checkLive(ticket);
            checkTime(ticket);
            synchronized (lock) {
                if (cancelled || !ticket.withinWallLimit() || reservation.revoked()
                        || !cancellation.admitTerminalIo())
                    throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                approved = true;
                return reservation;
            }
        } finally {
            if (!approved) {
                if (reservation != null) reservation.revoke();
                review.close();
            }
        }
    }

    private void requireApprovedInternal(SshHostKeyAuthority.Review review, long transportDeadline, PendingPoll poll)
            throws VaultException {
        if (Thread.currentThread() != owner) throw failure("SSH_CALLBACK_THREAD");
        if (review == null || !review.belongsTo(role)
                || review.classification != SshHostKeyAuthority.Classification.NEW) throw refusal();
        checkLive();
        if (sink == null) throw refusal();
        long now = clock.now();
        if (now <= 0 || now >= role.deadline) throw failure("SSH_ROLE_EXPIRED");
        if (poll != null && now >= transportDeadline) throw failure("SSH_TIMED_OUT");
        long deadline = Math.min(role.deadline, transportDeadline);
        long budget = Math.min(deadline - now, maximumWaitMillis);
        Ticket ticket = new Ticket(review, now + budget, poll == null ? maximumWaitMillis : budget);
        awaitDecision(ticket, poll == null ? null : () -> { poll.poll(); return true; });
    }

    /** Changed reviews are separate from NEW approvals and grant no proof or write authority. */
    void requireChangedApproved(SshHostKeyAuthority.ChangedReview review, long transportDeadline) throws VaultException {
        if (Thread.currentThread() != owner) throw failure("SSH_CALLBACK_THREAD");
        if (review == null || !review.belongsTo(role)) throw refusal();
        checkLive();
        if (sink == null) throw refusal();
        long now = clock.now();
        if (now <= 0 || now >= role.deadline) throw failure("SSH_ROLE_EXPIRED");
        if (now >= transportDeadline) throw failure("SSH_TIMED_OUT");
        long deadline = Math.min(role.deadline, transportDeadline);
        awaitDecision(new Ticket(review, now + Math.min(deadline - now, maximumWaitMillis),
                maximumWaitMillis), null);
    }

    private void awaitDecision(Ticket ticket, ReadyPoll poll) throws VaultException {
        synchronized (lock) {
            if (cancelled) throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
            if (started) throw refusal();
            if (!cancellation.admitTerminalIo()) throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
            started = true;
            pending = ticket;
        }
        boolean presentAttempted = false;
        try {
            if (poll != null) {
                // This samples owner eligibility. A signed sink can sample only the
                // ticket and its wall budget, not source or ancestor liveness.
                checkLive(ticket);
                checkTime(ticket);
                synchronized (lock) {
                    if (cancelled || cancellation.stopRequested() || pending != ticket || ticket.decision != 0)
                        throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                }
            }
            presentAttempted = true;
            sink.present(ticket);
            for (;;) {
                checkLive(ticket);
                checkTime(ticket);
                int decision;
                synchronized (lock) {
                    if (cancelled || cancellation.stopRequested() || pending != ticket || ticket.decision == 3)
                        throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                    decision = ticket.decision;
                    if (decision == 0 && poll == null) {
                        try { lock.wait(ticket.waitSliceMillis()); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                        }
                        continue;
                    }
                }
                if (decision != 0 && decision != 1) throw refusal();
                if (poll != null) {
                    boolean idle = poll.poll();
                    checkLive(ticket);
                    checkTime(ticket);
                    synchronized (lock) {
                        if (cancelled || cancellation.stopRequested() || pending != ticket || ticket.decision == 3)
                            throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                        decision = ticket.decision;
                        if (decision == 0 || decision == 1 && !idle) {
                            try { lock.wait(ticket.waitSliceMillis()); }
                            catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                            }
                            continue;
                        }
                    }
                }
                if (decision != 1) throw refusal();
                // Approval is only a UI decision; these samples authorize no trust write
                // or credential use.
                checkLive(ticket);
                checkTime(ticket);
                synchronized (lock) {
                    if (cancelled || pending != ticket || ticket.decision != 1
                            || !cancellation.admitTerminalIo())
                        throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                }
                return;
            }
        } finally {
            synchronized (lock) {
                ticket.decision = 3;
                if (pending == ticket) pending = null;
                lock.notifyAll();
            }
            if (presentAttempted) sink.dismiss(ticket);
        }
    }

    /** Receives a decision, not authority to trust a key. The owner rechecks every guard. */
    boolean resolve(Ticket ticket, boolean approve) {
        synchronized (lock) {
            if (cancelled || cancellation.stopRequested() || ticket == null || pending != ticket || ticket.decision != 0
                    || !ticket.withinWallLimit()) return false;
            if (ticket.signedReview != null) {
                // This acknowledges only a decision, never source or ancestor liveness.
                if (!cancellation.admitTerminalIo()) return false;
                ticket.decision = approve ? 1 : 2;
                lock.notifyAll();
                return true;
            }
        }
        // Do not execute caller-supplied clocks while holding the decision monitor.
        final long now;
        try { now = clock.now(); }
        catch (RuntimeException failure) { return false; }
        synchronized (lock) {
            if (cancelled || pending != ticket || ticket.decision != 0
                    || now <= 0 || now >= ticket.deadline || !ticket.withinWallLimit()) return false;
            if (!cancellation.admitTerminalIo()) return false;
            ticket.decision = approve ? 1 : 2;
            lock.notifyAll();
            return true;
        }
    }

    /** Sample whether the exact one-shot ticket is still pending and within its wall budget. */
    boolean pending(Ticket ticket) throws VaultException {
        // Signed-candidate source and ancestor checks can acquire vault/session
        // locks and are reserved for the owner thread, never the UI publisher.
        if (ticket == null || ticket.signedReview == null) checkLive();
        synchronized (lock) {
            if (cancelled || cancellation.stopRequested() || ticket == null || pending != ticket || ticket.decision != 0
                    || !ticket.withinWallLimit()) return false;
            // The owner independently rechecks source, ancestor and full deadline.
            if (ticket.signedReview != null) return true;
        }
        long now = clock.now();
        synchronized (lock) {
            return !cancelled && !cancellation.stopRequested() && pending == ticket && ticket.decision == 0
                    && now > 0 && now < ticket.deadline && ticket.withinWallLimit();
        }
    }

    void cancel() {
        synchronized (lock) {
            cancelled = true;
            if (pending != null) pending.decision = 3;
            lock.notifyAll();
        }
    }

    @Override public void close() { cancel(); }

    private void checkLive() throws VaultException {
        role.requireActive(clock);
        live.check();
        role.requireActive(clock);
        if (cancellation.stopRequested()) throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
    }

    private void checkLive(Ticket ticket) throws VaultException {
        checkLive();
        if (ticket != null && ticket.signedReview != null) {
            if (Thread.currentThread() != owner) throw failure("SSH_CALLBACK_THREAD");
            ticket.signedReview.requireLive();
            checkLive();
        }
    }

    private void checkTime(Ticket ticket) throws VaultException {
        long now = clock.now();
        if (now <= 0 || now >= ticket.deadline || !ticket.withinWallLimit())
            throw failure("SSH_HOST_KEY_REVIEW_EXPIRED");
    }

    private static VaultException refusal() { return failure("SSH_HOST_KEY_REVIEW"); }
    private static VaultException failure(String code) {
        return new VaultException(code, "The exact host-key review is unavailable");
    }
}
