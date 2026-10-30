package im.majo.ctos;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Native-launch binding for one SSH hop.  It carries identifiers only; it is
 * not a launcher, an argv container, or a secret/file body transport.
 */
final class SshRoleContext implements AutoCloseable {
    static final int MAX_HOPS = 8;
    static final long MAX_LIFETIME_MILLIS = 60_000L;

    enum Program { CLIENT, JUMP }

    interface Clock { long now(); }

    /** A public view exposes liveness only. Revocation must synchronize on this exact object. */
    interface PublicView { boolean active(); }

    /** Fixed metadata supplied by the native launcher after it owns the child. */
    static final class Bootstrap {
        final Program program;
        final int ownerPid;
        final int hop;
        final String connectionId;
        final String targetIdentity;
        final String host;
        final int port;
        final String publicViewId;
        final byte[] roleNonce;
        final byte[] connectionBinding;
        final long deadline;

        Bootstrap(Program program, int ownerPid, int hop, String connectionId,
                String targetIdentity, String host, int port, String publicViewId,
                byte[] roleNonce, byte[] connectionBinding, long deadline) {
            this.program = program;
            this.ownerPid = ownerPid;
            this.hop = hop;
            this.connectionId = connectionId;
            this.targetIdentity = targetIdentity;
            this.host = host;
            this.port = port;
            this.publicViewId = publicViewId;
            this.roleNonce = roleNonce == null ? null : roleNonce.clone();
            this.connectionBinding = connectionBinding == null ? null : connectionBinding.clone();
            this.deadline = deadline;
        }
    }

    final Program program;
    final int ownerPid;
    final int hop;
    final String connectionId;
    final String targetIdentity;
    final String host;
    final int port;
    final String publicViewId;
    final long deadline;
    private final byte[] roleNonce;
    private final byte[] connectionBinding;
    private final String binding;
    private final PublicView publicView;
    private boolean active = true;
    private boolean establishedTransport;

    private SshRoleContext(Bootstrap request, PublicView publicView, String binding) {
        program = request.program;
        ownerPid = request.ownerPid;
        hop = request.hop;
        connectionId = request.connectionId;
        targetIdentity = request.targetIdentity;
        host = request.host;
        port = request.port;
        publicViewId = request.publicViewId;
        deadline = request.deadline;
        roleNonce = request.roleNonce.clone();
        connectionBinding = request.connectionBinding.clone();
        this.publicView = publicView;
        this.binding = binding;
    }

    static SshRoleContext issue(Bootstrap request, PublicView publicView, Clock clock)
            throws VaultException {
        if (request == null || publicView == null || clock == null || request.program == null
                || request.ownerPid <= 0 || request.hop < 0 || request.hop >= MAX_HOPS
                || !identifier(request.connectionId, 128)
                || !text(request.targetIdentity, 512)
                || !host(request.host) || request.port < 1 || request.port > 65535
                || !identifier(request.publicViewId, 128)
                || !nonce(request.roleNonce) || !nonce(request.connectionBinding)) {
            throw new VaultException("SSH_ROLE_REQUIRED", "The SSH launch context is invalid");
        }
        long now = clock.now();
        if (now <= 0 || request.deadline <= now || request.deadline - now > MAX_LIFETIME_MILLIS
                || !publicView.active()) {
            throw new VaultException("SSH_ROLE_REQUIRED", "The SSH launch context is unavailable");
        }
        String binding = binding(request);
        return new SshRoleContext(request, publicView, binding);
    }

    synchronized boolean active() { return active; }

    synchronized void requireBound() throws VaultException {
        if (!active) throw new VaultException("SSH_ROLE_EXPIRED", "The SSH role is closed");
        if (!publicView.active()) throw new VaultException("SSH_FILE_VIEW", "The SSH file view was revoked");
    }

    synchronized void requireActive(Clock clock) throws VaultException {
        requireBound();
        if (clock == null || clock.now() <= 0 || clock.now() >= deadline)
            throw new VaultException("SSH_ROLE_EXPIRED", "The SSH role has expired");
    }

    /** Same-session shell continuation only; authentication checks still use the original deadline. */
    synchronized void retainEstablishedTransport() throws VaultException {
        requireBound();
        establishedTransport = true;
    }

    /** Exact retained transport only; it grants no public-file or authentication rights. */
    synchronized void requireEstablishedTransport() throws VaultException {
        if (!active || !establishedTransport)
            throw new VaultException("SSH_ROLE_EXPIRED", "The established SSH role is closed");
    }

    /** Only bounded metadata/publication or trusted copy; no core/native calls or waits. */
    void publishBound(Clock clock, AppSecrets.EffectGuard publication) throws VaultException {
        synchronized (this) { synchronized (publicView) {
            requireActive(clock);
            publication.check();
        } }
    }

    /** The native session must use the exact view that issued this role. */
    synchronized boolean ownsPublicView(PublicView candidate) {
        return publicView == candidate;
    }

    /**
     * A retained transport keeps its slot until explicit close. Authentication-only
     * roles may also expire with their view or deadline; a failed clock cannot
     * make a still-live role disappear from the registry.
     */
    synchronized boolean reclaimable(Clock clock) {
        if (!active) return true;
        if (establishedTransport) return false;
        if (!publicView.active()) return true;
        if (clock == null) return false;
        long now = clock.now();
        return now > 0 && now >= deadline;
    }

    String binding() { return binding; }
    byte[] roleNonce() { return roleNonce.clone(); }
    byte[] connectionBinding() { return connectionBinding.clone(); }

    @Override public synchronized void close() { active = false; establishedTransport = false; }

    private static String binding(Bootstrap request) throws VaultException {
        String text = request.program.name() + '\0' + request.ownerPid + '\0' + request.hop + '\0'
                + request.connectionId + '\0' + request.targetIdentity + '\0' + request.host + '\0'
                + request.port + '\0' + request.publicViewId + '\0' + request.deadline;
        byte[] fields = text.getBytes(StandardCharsets.UTF_8);
        byte[] digest = VaultCodec.digest(fields);
        try {
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } finally {
            VaultCodec.wipe(fields);
            VaultCodec.wipe(digest);
        }
    }

    private static boolean nonce(byte[] value) {
        if (value == null || value.length != 32) return false;
        byte combined = 0;
        for (byte item : value) combined |= item;
        return combined != 0;
    }

    private static boolean identifier(String value, int maximum) {
        return value != null && value.length() > 0 && value.length() <= maximum
                && value.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static boolean text(String value, int maximum) {
        return value != null && !value.isEmpty() && value.getBytes(StandardCharsets.UTF_8).length <= maximum
                && value.indexOf('\0') < 0 && value.chars().noneMatch(ch -> ch < 32 || ch == 127);
    }

    private static boolean host(String value) {
        return value != null && value.matches("[A-Za-z0-9.:%\\[\\]\\-]{1,253}");
    }

    static boolean same(byte[] left, byte[] right) { return Arrays.equals(left, right); }
}
