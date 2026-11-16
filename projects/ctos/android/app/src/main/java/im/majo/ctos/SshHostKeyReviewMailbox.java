package im.majo.ctos;

import java.util.Objects;
import java.util.UUID;

/** One exact NEW-key review. UI decisions never enter session JNI or its owner queue. */
final class SshHostKeyReviewMailbox implements SshHostKeyAuthority.UnknownKeyIssuer, AutoCloseable {
    static final long MAX_WAIT_MILLIS = 30_000L;
    private static final long WAIT_SLICE_MILLIS = 50L;

    interface LiveCheck { void check() throws VaultException; }
    /** Both hooks must publish without blocking; decisions arrive through resolve/cancel. */
    interface Sink {
        void present(Ticket ticket) throws VaultException;
        default void dismiss(Ticket ticket) { }
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
            this.deadline = deadline;
            waitNanos = maximumWaitMillis * 1_000_000L;
        }

        private Ticket(SshHostKeyAuthority.ChangedReview review, long deadline, long maximumWaitMillis) {
            this.review = null;
            changedReview = review;
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

        byte[] baseDigest() { return changed ? changedReview.baseDigest() : review.baseDigest(); }
        byte[] replacementDigest() { return changed ? changedReview.replacementDigest() : review.replacementDigest(); }

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
    private Ticket pending;
    private boolean started;
    private boolean cancelled;

    SshHostKeyReviewMailbox(SshRoleContext role, SshRoleContext.Clock clock, LiveCheck live, Sink sink) {
        this(role, clock, live, sink, MAX_WAIT_MILLIS);
    }

    /** Internal callers may shorten the review budget, never extend the hard ceiling. */
    SshHostKeyReviewMailbox(SshRoleContext role, SshRoleContext.Clock clock, LiveCheck live, Sink sink,
            long maximumWaitMillis) {
        this.role = Objects.requireNonNull(role);
        this.clock = Objects.requireNonNull(clock);
        this.live = Objects.requireNonNull(live);
        this.sink = sink; // Missing UI remains an immediate refusal, never auto-approval.
        if (maximumWaitMillis < 1 || maximumWaitMillis > MAX_WAIT_MILLIS)
            throw new IllegalArgumentException("Invalid host review wait budget");
        this.maximumWaitMillis = maximumWaitMillis;
    }

    @Override public void requireApproved(SshHostKeyAuthority.Review review) throws VaultException {
        if (Thread.currentThread() != owner) throw failure("SSH_CALLBACK_THREAD");
        if (review == null || !review.belongsTo(role)
                || review.classification != SshHostKeyAuthority.Classification.NEW) throw refusal();
        checkLive();
        if (sink == null) throw refusal();
        long now = clock.now();
        if (now <= 0 || now >= role.deadline) throw failure("SSH_ROLE_EXPIRED");
        Ticket ticket = new Ticket(review, now + Math.min(role.deadline - now, maximumWaitMillis),
                maximumWaitMillis);
        awaitDecision(ticket);
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
                maximumWaitMillis));
    }

    private void awaitDecision(Ticket ticket) throws VaultException {
        synchronized (lock) {
            if (cancelled) throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
            if (started) throw refusal();
            started = true;
            pending = ticket;
        }
        try {
            sink.present(ticket);
            for (;;) {
                checkLive();
                checkTime(ticket);
                int decision;
                synchronized (lock) {
                    if (cancelled || pending != ticket || ticket.decision == 3)
                        throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                    decision = ticket.decision;
                    if (decision == 0) {
                        try { lock.wait(ticket.waitSliceMillis()); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw failure("SSH_HOST_KEY_REVIEW_CANCELLED");
                        }
                        continue;
                    }
                }
                if (decision != 1) throw refusal();
                // Approval is only a UI decision. The caller still performs exact CAS and
                // its native post-callback proof checks before KEX or any credential use.
                checkLive();
                checkTime(ticket);
                synchronized (lock) {
                    if (cancelled || pending != ticket || ticket.decision != 1)
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
            sink.dismiss(ticket);
        }
    }

    /** Receives a decision, not authority to trust a key. The owner rechecks every guard. */
    boolean resolve(Ticket ticket, boolean approve) {
        synchronized (lock) {
            if (cancelled || ticket == null || pending != ticket || ticket.decision != 0
                    || !ticket.withinWallLimit()) return false;
        }
        // Do not execute caller-supplied clocks while holding the decision monitor.
        final long now;
        try { now = clock.now(); }
        catch (RuntimeException failure) { return false; }
        synchronized (lock) {
            if (cancelled || pending != ticket || ticket.decision != 0
                    || now <= 0 || now >= ticket.deadline || !ticket.withinWallLimit()) return false;
            ticket.decision = approve ? 1 : 2;
            lock.notifyAll();
            return true;
        }
    }

    /** A posted UI event is valid only while its exact one-shot review is live. */
    boolean pending(Ticket ticket) throws VaultException {
        checkLive();
        synchronized (lock) {
            if (cancelled || ticket == null || pending != ticket || ticket.decision != 0
                    || !ticket.withinWallLimit()) return false;
        }
        long now = clock.now();
        synchronized (lock) {
            return !cancelled && pending == ticket && ticket.decision == 0
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
