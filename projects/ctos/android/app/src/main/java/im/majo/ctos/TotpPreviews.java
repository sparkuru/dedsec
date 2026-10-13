package im.majo.ctos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** At most one host-only preview. Every candidate owns a purpose/snapshot-bound capability. */
final class TotpPreviews implements AutoCloseable {
    static final int MAX_CANDIDATES = 16;
    private final AppSecrets vault;
    private final VaultSession.Clock clock;
    private String id, requestId;
    private long deadline;
    private final List<Candidate> candidates = new ArrayList<>();
    private static final class Candidate {
        final String id = UUID.randomUUID().toString();
        final TotpInput input;
        final VaultOperation operation;
        Candidate(TotpInput input, VaultOperation operation) { this.input = input; this.operation = operation; }
    }
    TotpPreviews(AppSecrets vault, VaultSession.Clock clock) { this.vault = vault; this.clock = clock; }
    synchronized Map<String, Object> prepare(String request, List<TotpInput> inputs, int invalid,
            AppSecrets.EffectGuard guard) throws VaultException {
        close();
        try {
            if (inputs.isEmpty()) throw new VaultException("TOTP_NO_CODES", "No supported TOTP QR code was found");
            if (inputs.size() > MAX_CANDIDATES) throw new VaultException("TOTP_LIMIT", "Too many QR codes");
            id = UUID.randomUUID().toString(); requestId = request; deadline = clock.now() + 120000;
            for (TotpInput input : inputs) candidates.add(new Candidate(input, vault.beginTotpSave(UUID.randomUUID().toString())));
            guard.check(); check();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Candidate candidate : candidates) rows.add(VaultCollections.map("candidateId", candidate.id,
                    "identity", candidate.input.identity, "label", candidate.input.label, "algorithm", "SHA1", "digits", 6, "period", 30));
            return VaultCollections.map("previewId", id, "candidates", rows, "invalidCodes", invalid);
        } catch (VaultException failure) {
            close(); for (TotpInput input : inputs) input.close(); throw failure;
        }
    }
    synchronized void check() throws VaultException {
        if (id == null || clock.now() >= deadline) { close(); throw new VaultException("VAULT_EXPIRED", "TOTP preview expired"); }
        try { for (Candidate candidate : candidates) { vault.session.assertEpoch(candidate.operation.epoch); vault.checkTotpPending(candidate.operation); } }
        catch (VaultException failure) { close(); throw failure; }
    }
    synchronized long publication() throws VaultException {
        check(); return vault.totpPublication(candidates.get(0).operation);
    }
    synchronized String commit(String preview, String selected, AppSecrets.EffectGuard guard) throws VaultException {
        if (id == null || !id.equals(preview)) throw new VaultException("VAULT_CHANGED", "TOTP preview changed");
        try {
            check();
            Candidate candidate = null;
            for (Candidate item : candidates) if (item.id.equals(selected)) candidate = item;
            if (candidate == null) throw new VaultException("PARAMETERS", "Unknown TOTP candidate");
            guard.check();
            String entry = candidate.operation.resource;
            vault.addTotp(candidate.operation, entry, candidate.input.identity, candidate.input.label, candidate.input.seed, guard);
            return entry;
        } finally { close(); }
    }
    synchronized void cancel(String preview) { if (id != null && id.equals(preview)) close(); }
    synchronized void cancelRequest(String request) { if (request.equals(requestId)) close(); }
    synchronized void retireInvalid() {
        if (id == null) return;
        // Background alone does not retire the selected-photo workflow. Epoch/expiry still does.
        if (clock.now() >= deadline || candidates.stream().anyMatch(c -> c.operation.epoch != vault.session.epoch())) close();
    }
    @Override public synchronized void close() {
        for (Candidate candidate : candidates) { vault.cancelOperation(candidate.operation); candidate.input.close(); }
        candidates.clear(); id = null; requestId = null;
    }
}
