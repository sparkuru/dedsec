package im.majo.ctos;

/**
 * Managed receiver for one native known_hosts candidate body.  The native
 * callback supplies an already role-bound Evidence record; this receiver adds
 * the explicit candidate identity, verifies the bounded body digest through
 * the concrete managed provider, and transfers ownership exactly once.
 *
 * <p>This is an acceptance handoff, not a CAS result.  The provider still has
 * to build the exact proposal and complete the guarded AppSecrets update
 * before the existing CSHR path can report COMMITTED.</p>
 */
final class SshKnownHostsCandidateHandoff implements AutoCloseable {
    static final int MAX_FILE_BYTES = VaultManifest.MAX_FILE_BYTES;

    private final SshManagedKnownHostsProvider provider;
    private final SshManagedKnownHostsProvider.SnapshotReceipt snapshot;
    private boolean used;
    private boolean closed;

    SshKnownHostsCandidateHandoff(SshManagedKnownHostsProvider provider,
            SshManagedKnownHostsProvider.SnapshotReceipt snapshot) throws VaultException {
        if (provider == null || snapshot == null) throw VaultException.invalid();
        snapshot.requireLive();
        this.provider = provider;
        this.snapshot = snapshot;
    }

    SshManagedKnownHostsProvider provider() { return provider; }

    /**
     * Consume the caller-owned managed byte array synchronously.  The input
     * array is wiped on every path; the provider owns its private clone only
     * after this method returns successfully.
     */
    synchronized void accept(SshHostKeyAuthority.Evidence evidence, String candidateId,
            byte[] replacementBody) throws VaultException {
        if (closed || used) {
            VaultCodec.wipe(replacementBody);
            throw new VaultException("SSH_HOST_KEY_REVIEW",
                    "The known_hosts candidate handoff is no longer available");
        }
        used = true;
        SshManagedKnownHostsProvider.KnownHostsCandidate candidate = null;
        try {
            if (evidence == null || evidence.classification != SshHostKeyAuthority.Classification.NEW
                    || replacementBody == null || replacementBody.length == 0
                    || replacementBody.length > MAX_FILE_BYTES) {
                throw new VaultException("SSH_HOST_KEY_REVIEW",
                        "The exact known_hosts candidate is unavailable");
            }
            snapshot.requireLive();
            candidate = SshManagedKnownHostsProvider.candidateFor(snapshot, evidence, candidateId,
                    replacementBody);
            if (!provider.acceptsNativeCandidate(candidate)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW",
                        "The exact known_hosts candidate is unavailable");
            }
            candidate = null;
        } catch (VaultException | RuntimeException | Error failure) {
            if (candidate != null) candidate.close();
            provider.abortNativeHandoff();
            throw failure;
        } finally {
            VaultCodec.wipe(replacementBody);
        }
    }

    /** Abort the one-shot receiver and wipe any provider-owned candidate. */
    void abort() { close(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        provider.abortNativeHandoff();
    }
}
