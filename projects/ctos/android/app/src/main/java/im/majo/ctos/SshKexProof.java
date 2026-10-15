package im.majo.ctos;

/**
 * Opaque completion evidence issued by the native KEX event loop.  Creating
 * this object means the native side already observed signature verification
 * and inbound NEWKEYS installation; there is no Boolean/UI setter.
 */
final class SshKexProof {
    final String roleBinding;
    final int hop;
    final String connectionId;
    final String targetIdentity;
    final String fingerprint;
    final long cycle;
    private final byte[] sessionIdDigest;

    private SshKexProof(SshRoleContext role, SshHostKeyAuthority.Proof hostKey, long cycle,
            byte[] sessionIdDigest) {
        roleBinding = role.binding();
        hop = role.hop;
        connectionId = role.connectionId;
        targetIdentity = role.targetIdentity;
        fingerprint = hostKey.fingerprint;
        this.cycle = cycle;
        this.sessionIdDigest = sessionIdDigest.clone();
    }

    /** Native KEX glue is the only production caller of this package-private factory. */
    static SshKexProof fromNative(SshRoleContext role, SshHostKeyAuthority.Proof hostKey,
            long cycle, byte[] sessionIdDigest) throws VaultException {
        if (role == null || hostKey == null || cycle <= 0 || sessionIdDigest == null
                || sessionIdDigest.length != 32) {
            throw new VaultException("SSH_KEX_REQUIRED", "Signed KEX and NEWKEYS evidence is unavailable");
        }
        role.requireBound();
        hostKey.requireMatches(role);
        if (hostKey.classification != SshHostKeyAuthority.Classification.KNOWN
                && hostKey.classification != SshHostKeyAuthority.Classification.NEW) {
            throw new VaultException("SSH_HOST_KEY_CHANGED", "The KEX proof has no approved host key");
        }
        byte combined = 0;
        for (byte value : sessionIdDigest) combined |= value;
        if (combined == 0) throw new VaultException("SSH_KEX_REQUIRED", "The KEX session binding is empty");
        return new SshKexProof(role, hostKey, cycle, sessionIdDigest);
    }

    void requireMatches(SshRoleContext role, SshHostKeyAuthority.Proof hostKey) throws VaultException {
        if (role == null || hostKey == null || !role.binding().equals(roleBinding)
                || role.hop != hop || !role.connectionId.equals(connectionId)
                || !role.targetIdentity.equals(targetIdentity) || !fingerprint.equals(hostKey.fingerprint))
            throw new VaultException("SSH_KEX_REQUIRED", "The KEX proof is bound to another hop");
        hostKey.requireMatches(role);
        role.requireBound();
    }

    byte[] sessionIdDigest() { return sessionIdDigest.clone(); }
}
