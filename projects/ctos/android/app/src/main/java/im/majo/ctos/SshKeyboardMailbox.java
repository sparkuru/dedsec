package im.majo.ctos;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Cross-thread answers are queued once; only the original native owner may consume them. */
final class SshKeyboardMailbox implements AutoCloseable {
    interface LiveCheck { void check() throws VaultException; }
    interface Sink {
        void present(Ticket ticket) throws VaultException;
        default void dismiss(Ticket ticket) { }
    }

    static final class Ticket {
        final String id = UUID.randomUUID().toString();
        final String binding;
        final SshKeyboardChallenge challenge;
        final long deadline;
        private final long started = System.nanoTime(), budgetNanos;
        private byte[] response;
        private boolean resolved;

        private Ticket(SshRoleContext role, SshKeyboardChallenge challenge, long now) {
            this.challenge = challenge; deadline = role.deadline;
            budgetNanos = Math.min(60_000, deadline - now) * 1_000_000L;
            byte[] publicBinding = ("ctos-keyboard-round-v1\0" + role.binding() + "\0"
                    + role.connectionId + "\0" + challenge.round + "\0" + id + UUID.randomUUID())
                    .getBytes(StandardCharsets.UTF_8);
            try { binding = SshConnectionMaterial.hex(VaultCodec.digest(publicBinding)); }
            finally { VaultCodec.wipe(publicBinding); }
        }

        private boolean liveWall() {
            long elapsed = System.nanoTime() - started;
            return elapsed >= 0 && elapsed < budgetNanos;
        }
    }

    private final Object lock = new Object();
    private final Thread owner = Thread.currentThread();
    private final SshRoleContext role;
    private final SshRoleContext.Clock clock;
    private final LiveCheck live;
    private final Sink sink;
    private Ticket pending;
    private int lastRound;
    private boolean cancelled;

    SshKeyboardMailbox(SshRoleContext role, SshRoleContext.Clock clock, LiveCheck live, Sink sink) {
        this.role = Objects.requireNonNull(role); this.clock = Objects.requireNonNull(clock);
        this.live = Objects.requireNonNull(live); this.sink = Objects.requireNonNull(sink);
    }

    byte[] await(SshKeyboardChallenge challenge, LiveCheck poll) throws VaultException {
        if (Thread.currentThread() != owner) throw failure("SSH_CALLBACK_THREAD");
        checkLive();
        if (challenge == null || challenge.round != lastRound + 1) throw SshKeyboardChallenge.failure();
        long now = clock.now();
        if (now <= 0 || now >= role.deadline) throw failure("SSH_TIMED_OUT");
        Ticket ticket = new Ticket(role, challenge, now);
        synchronized (lock) {
            if (cancelled) throw failure("SSH_CANCELLED");
            if (pending != null) throw SshKeyboardChallenge.failure();
            pending = ticket; lastRound = challenge.round;
        }
        byte[] response = null;
        boolean returned = false;
        try {
            sink.present(ticket);
            for (;;) {
                checkLive(); checkTime(ticket);
                synchronized (lock) {
                    if (cancelled || pending != ticket) throw failure("SSH_CANCELLED");
                    if (ticket.response != null) {
                        response = ticket.response; ticket.response = null;
                    }
                }
                if (response != null) {
                    checkLive(); checkTime(ticket);
                    synchronized (lock) {
                        if (cancelled || pending != ticket) throw failure("SSH_CANCELLED");
                        pending = null;
                    }
                    returned = true;
                    return response;
                }
                if (poll != null) poll.check();
                synchronized (lock) {
                    if (cancelled || pending != ticket) throw failure("SSH_CANCELLED");
                    if (ticket.response == null) {
                        try { lock.wait(20); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); throw failure("SSH_CANCELLED");
                        }
                    }
                }
            }
        } finally {
            if (!returned) VaultCodec.wipe(response);
            synchronized (lock) {
                VaultCodec.wipe(ticket.response); ticket.response = null; ticket.resolved = true;
                if (pending == ticket) pending = null;
                lock.notifyAll();
            }
            sink.dismiss(ticket);
        }
    }

    boolean respond(String id, String binding, List<?> answers) throws VaultException {
        Ticket ticket;
        synchronized (lock) {
            ticket = pending;
            if (cancelled || ticket == null || ticket.resolved || !ticket.id.equals(id)
                    || !ticket.binding.equals(binding)) return false;
        }
        checkLive(); checkTime(ticket);
        byte[] response = ticket.challenge.response(answers);
        try {
            checkLive(); checkTime(ticket);
            synchronized (lock) {
                if (cancelled || pending != ticket || ticket.resolved) return false;
                ticket.response = response; response = null; ticket.resolved = true;
                lock.notifyAll();
                return true;
            }
        } finally { VaultCodec.wipe(response); }
    }

    boolean pending(Ticket ticket) {
        synchronized (lock) { return !cancelled && pending == ticket && !ticket.resolved && ticket.liveWall(); }
    }

    void cancel() {
        synchronized (lock) {
            cancelled = true;
            if (pending != null) {
                VaultCodec.wipe(pending.response); pending.response = null; pending.resolved = true;
            }
            lock.notifyAll();
        }
    }

    @Override public void close() { cancel(); }
    private void checkLive() throws VaultException { role.requireActive(clock); live.check(); role.requireActive(clock); }
    private void checkTime(Ticket ticket) throws VaultException {
        long now = clock.now();
        if (now <= 0 || now >= ticket.deadline || !ticket.liveWall()) throw failure("SSH_TIMED_OUT");
    }
    private static VaultException failure(String code) { return new VaultException(code, "SSH keyboard-interactive authentication ended"); }
}
