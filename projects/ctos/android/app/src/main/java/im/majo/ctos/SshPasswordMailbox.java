package im.majo.ctos;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** One connection-bound local secret prompt; only the native owner may consume its answer. */
final class SshPasswordMailbox implements AutoCloseable {
    enum Purpose { PASSWORD, KEY_PASSPHRASE }
    enum Choice { ONCE, SAVE, SAVED }
    interface LiveCheck { void check() throws VaultException; }
    interface Sink {
        void present(Ticket ticket) throws VaultException;
        default void dismiss(Ticket ticket) { }
    }

    static final class Response implements AutoCloseable {
        final Choice choice;
        final byte[] bytes;

        private Response(Choice choice, byte[] bytes) {
            this.choice = choice;
            this.bytes = bytes.clone();
        }

        @Override public void close() { VaultCodec.wipe(bytes); }
    }

    static final class Ticket {
        final String id = UUID.randomUUID().toString();
        final String binding;
        final long deadline;
        final Purpose purpose;
        final boolean savedAvailable, savedOnly;
        final String host, username;
        final int port, hop;
        private final long started = System.nanoTime(), budgetNanos;
        private Response response;
        private boolean resolved;

        private Ticket(SshRoleContext role, SshAuthenticationSelection selection, long now,
                Purpose purpose, boolean savedAvailable, boolean savedOnly) {
            deadline = role.deadline;
            this.purpose = purpose;
            this.savedAvailable = savedAvailable;
            this.savedOnly = savedOnly;
            host = role.host; port = role.port; hop = role.hop; username = selection.username;
            budgetNanos = Math.min(60_000, deadline - now) * 1_000_000L;
            byte[] publicBinding = ("ctos-secret-prompt-v1\0" + purpose.name() + "\0" + role.binding() + "\0"
                    + role.connectionId + "\0" + id + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
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
    private final SshAuthenticationSelection selection;
    private final SshRoleContext.Clock clock;
    private final LiveCheck live;
    private final Sink sink;
    private final Purpose purpose;
    private final SshSessionOwner.Cancellation cancellation;
    private Ticket pending;
    private boolean issued, cancelled;

    SshPasswordMailbox(SshRoleContext role, SshAuthenticationSelection selection, SshRoleContext.Clock clock,
            LiveCheck live, Sink sink, Purpose purpose) throws VaultException {
        this(role, selection, clock, live, sink, purpose, new SshSessionOwner.Cancellation());
    }

    SshPasswordMailbox(SshRoleContext role, SshAuthenticationSelection selection, SshRoleContext.Clock clock,
            LiveCheck live, Sink sink, Purpose purpose, SshSessionOwner.Cancellation cancellation)
            throws VaultException {
        this.role = Objects.requireNonNull(role); this.clock = Objects.requireNonNull(clock);
        this.selection = Objects.requireNonNull(selection);
        selection.requireMatches(role, selection.kind, clock);
        this.live = Objects.requireNonNull(live); this.sink = Objects.requireNonNull(sink);
        this.purpose = Objects.requireNonNull(purpose);
        this.cancellation = Objects.requireNonNull(cancellation);
    }

    Response await(LiveCheck poll, boolean savedAvailable) throws VaultException {
        return await(poll, savedAvailable, false);
    }

    Response await(LiveCheck poll, boolean savedAvailable, boolean savedOnly) throws VaultException {
        if (Thread.currentThread() != owner) throw failure("SSH_CALLBACK_THREAD");
        if (savedOnly && (!savedAvailable || purpose != Purpose.PASSWORD))
            throw SshPasswordProfile.parameters();
        checkLive();
        long now = clock.now();
        if (now <= 0 || now >= role.deadline) throw failure("SSH_TIMED_OUT");
        Ticket ticket = new Ticket(role, selection, now, purpose, savedAvailable, savedOnly);
        synchronized (lock) {
            if (cancelled) throw failure("SSH_CANCELLED");
            if (issued || pending != null) throw failure("SSH_AUTH_SELECTION");
            if (!cancellation.admitTerminalIo()) throw failure("SSH_CANCELLED");
            issued = true; pending = ticket;
        }
        Response response = null;
        boolean returned = false;
        try {
            sink.present(ticket);
            for (;;) {
                checkLive(); checkTime(ticket);
                synchronized (lock) {
                    if (cancelled || cancellation.stopRequested() || pending != ticket)
                        throw failure("SSH_CANCELLED");
                    if (ticket.response != null) { response = ticket.response; ticket.response = null; }
                }
                if (response != null) {
                    checkLive(); checkTime(ticket);
                    synchronized (lock) {
                        if (cancelled || pending != ticket) throw failure("SSH_CANCELLED");
                        if (!cancellation.admitTerminalIo()) throw failure("SSH_CANCELLED");
                        pending = null;
                    }
                    returned = true;
                    return response;
                }
                if (poll != null) poll.check();
                synchronized (lock) {
                    if (cancelled || cancellation.stopRequested() || pending != ticket)
                        throw failure("SSH_CANCELLED");
                    if (ticket.response == null) {
                        try { lock.wait(20); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); throw failure("SSH_CANCELLED");
                        }
                    }
                }
            }
        } finally {
            if (!returned && response != null) response.close();
            synchronized (lock) {
                if (ticket.response != null) ticket.response.close();
                ticket.response = null; ticket.resolved = true;
                if (pending == ticket) pending = null;
                lock.notifyAll();
            }
            sink.dismiss(ticket);
        }
    }

    boolean respond(String id, String binding, byte[] password, Choice choice) throws VaultException {
        Ticket ticket;
        synchronized (lock) {
            ticket = pending;
            if (cancelled || cancellation.stopRequested() || ticket == null || ticket.resolved || !ticket.id.equals(id)
                    || !ticket.binding.equals(binding)) return false;
        }
        checkLive(); checkTime(ticket);
        if (password == null || choice == null) throw SshPasswordProfile.parameters();
        if (ticket.savedOnly && choice != Choice.SAVED) throw SshPasswordProfile.parameters();
        if (choice == Choice.SAVED) {
            if (!ticket.savedAvailable || password.length != 0) throw SshPasswordProfile.parameters();
        } else SshSavedPassword.validateSecret(password);
        Response response = new Response(choice, password);
        try {
            checkLive(); checkTime(ticket);
            synchronized (lock) {
                if (cancelled || pending != ticket || ticket.resolved) return false;
                if (!cancellation.admitTerminalIo()) return false;
                ticket.response = response; response = null; ticket.resolved = true;
                lock.notifyAll();
                return true;
            }
        } finally { if (response != null) response.close(); }
    }

    boolean pending(Ticket ticket) {
        synchronized (lock) {
            return !cancelled && !cancellation.stopRequested()
                    && pending == ticket && !ticket.resolved && ticket.liveWall();
        }
    }

    void cancel() {
        synchronized (lock) {
            cancelled = true;
            if (pending != null) {
                if (pending.response != null) pending.response.close();
                pending.response = null; pending.resolved = true;
            }
            lock.notifyAll();
        }
    }

    @Override public void close() { cancel(); }
    private void checkLive() throws VaultException {
        selection.requireMatches(role, selection.kind, clock); live.check();
        selection.requireMatches(role, selection.kind, clock);
        if (cancellation.stopRequested()) throw failure("SSH_CANCELLED");
    }
    private void checkTime(Ticket ticket) throws VaultException {
        long now = clock.now();
        if (now <= 0 || now >= ticket.deadline || !ticket.liveWall()) throw failure("SSH_TIMED_OUT");
    }
    private static VaultException failure(String code) { return new VaultException(code, "SSH password authentication ended"); }
}
