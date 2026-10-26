package im.majo.ctos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One exact host-owned ticket; no manager monitor crosses core validation or storage. */
final class TotpPreviews implements AutoCloseable {
    static final int MAX_CANDIDATES = 16;
    private final AppSecrets vault;
    private final VaultSession.Clock clock;
    private Ticket current;
    private static final class Candidate {
        final String id = UUID.randomUUID().toString();
        final TotpInput input;
        VaultOperation operation;
        Candidate(TotpInput input) { this.input = input; }
    }
    private static final class Ticket {
        final String id = UUID.randomUUID().toString(), owner, request;
        final AppSecrets.TotpMutationOrigin origin;
        final List<Candidate> candidates = new ArrayList<>();
        boolean invalid, ready, committing;
        Ticket(String owner, String request, AppSecrets.TotpMutationOrigin origin) {
            this.owner = owner; this.request = request; this.origin = origin;
        }
    }
    TotpPreviews(AppSecrets vault, VaultSession.Clock clock) { this.vault = vault; this.clock = clock; }

    Map<String, Object> prepare(String request, List<TotpInput> inputs, int invalid, AppSecrets.EffectGuard guard) throws VaultException {
        AppSecrets.TotpMutationOrigin origin = vault.newTotpMutationOrigin(guard);
        Map<String, Object> result = prepare(request, request, inputs, invalid, origin);
        publish((String) result.get("previewId"), () -> { });
        return result;
    }
    Map<String, Object> prepare(String owner, String request, List<TotpInput> inputs, int invalid,
            AppSecrets.TotpMutationOrigin origin) throws VaultException {
        Ticket ticket = new Ticket(owner, request, origin), retired;
        boolean installed = false, preserveOrigin = false;
        try {
            vault.checkTotpMutationOrigin(origin);
            synchronized (this) {
                // Reject stale/reused authority before replacing a different live generation.
                if ((current != null && current.origin == origin) || origin.handedOff) {
                    preserveOrigin = true;
                    throw new VaultException("BUSY", "The TOTP origin already owns a preview");
                }
                if (current != null && (origin.epoch < current.origin.epoch
                        || origin.epoch == current.origin.epoch && origin.pinned && current.origin.pinned
                        && origin.revision < current.origin.revision)) throw changed();
                if (origin.cancelled) throw new VaultException("VAULT_CANCELLED", "TOTP request was cancelled");
                if (clock.now() >= origin.deadline) throw new VaultException("VAULT_EXPIRED", "TOTP preview expired");
                retired = current;
                if (retired != null) { retired.invalid = true; retired.origin.cancelled = true; }
                for (TotpInput input : inputs) ticket.candidates.add(new Candidate(input));
                current = ticket; installed = true;
            }
            dispose(retired);
            if (inputs.isEmpty()) throw new VaultException("TOTP_NO_CODES", "No supported TOTP QR code was found");
            if (inputs.size() > MAX_CANDIDATES) throw new VaultException("TOTP_LIMIT", "Too many QR codes");
            List<VaultOperation> operations = new ArrayList<>();
            for (Candidate candidate : ticket.candidates) {
                VaultOperation operation = vault.beginTotpSave(UUID.randomUUID().toString(), origin.context());
                synchronized (this) {
                    if (current != ticket || ticket.invalid) throw changed();
                    candidate.operation = operation;
                }
                operations.add(operation);
            }
            vault.bindTotpPreview(origin, ticket, operations);
            for (VaultOperation operation : operations) vault.checkTotpPending(operation);
            List<Map<String, Object>> rows = new ArrayList<>();
            synchronized (this) {
                require(ticket); ticket.ready = true;
                for (Candidate candidate : ticket.candidates) rows.add(VaultCollections.map("candidateId", candidate.id,
                        "identity", candidate.input.identity, "label", candidate.input.label, "algorithm", "SHA1", "digits", 6, "period", 30));
            }
            return VaultCollections.map("previewId", ticket.id, "candidates", rows, "invalidCodes", invalid);
        } catch (Throwable failure) {
            if (installed) retire(ticket);
            else {
                synchronized (this) {
                    if (origin != null && (origin.owner != vault || (current != null && current.origin == origin) || origin.handedOff))
                        preserveOrigin = true;
                }
                if (origin != null && !preserveOrigin) origin.cancel();
                for (TotpInput input : inputs) input.close();
            }
            throw failure;
        }
    }
    void publish(String preview, AppSecrets.EffectGuard transport) throws VaultException {
        Ticket ticket;
        synchronized (this) { ticket = current; if (ticket == null || !ticket.id.equals(preview)) throw changed(); }
        try {
            synchronized (this) { require(ticket); }
            vault.publishTotpPending(ticket.origin, ticket, transport);
        }
        catch (Throwable failure) { retire(ticket); throw failure; }
    }
    void check() throws VaultException {
        Ticket ticket;
        synchronized (this) { ticket = current; }
        if (ticket == null) throw changed();
        try {
            synchronized (this) { require(ticket); if (!ticket.ready) throw changed(); }
            vault.session.assertEpoch(ticket.origin.epoch);
            for (Candidate candidate : ticket.candidates) vault.checkTotpPending(candidate.operation);
            synchronized (this) { require(ticket); }
        } catch (Throwable failure) { retire(ticket); throw failure; }
    }
    long publication() throws VaultException {
        Ticket ticket = snapshot(); check(); return vault.totpPublication(ticket.candidates.get(0).operation);
    }
    String commit(String preview, String selected, AppSecrets.EffectGuard guard) throws VaultException {
        Ticket ticket;
        synchronized (this) { ticket = current; if (ticket == null || !ticket.id.equals(preview)) throw changed(); }
        Candidate candidate = null; byte[] seed = null;
        boolean ownsAttempt = false;
        try {
            synchronized (this) {
                // A losing concurrent caller owns neither the reservation nor its cleanup.
                if (current == ticket && ticket.committing)
                    throw new VaultException("BUSY", "A TOTP preview commit is in progress");
                ownsAttempt = true;
                require(ticket);
                if (!ticket.ready || !ticket.origin.handedOff) throw changed();
                for (Candidate item : ticket.candidates) if (item.id.equals(selected)) candidate = item;
                if (candidate == null) throw new VaultException("PARAMETERS", "Unknown TOTP candidate");
                ticket.committing = true;
                seed = candidate.input.seed.clone();
            }
            guard.check(); vault.session.assertEpoch(ticket.origin.epoch); vault.checkTotpPending(candidate.operation);
            String entry = candidate.operation.resource;
            vault.addTotp(candidate.operation, entry, candidate.input.identity, candidate.input.label, seed, guard);
            return entry;
        } finally { VaultCodec.wipe(seed); if (ownsAttempt) retire(ticket); }
    }
    private synchronized Ticket snapshot() throws VaultException {
        Ticket ticket = current; require(ticket); if (!ticket.ready) throw changed(); return ticket;
    }
    private void require(Ticket ticket) throws VaultException {
        if (ticket == null || current != ticket || ticket.invalid) throw changed();
        if (clock.now() >= ticket.origin.deadline) throw new VaultException("VAULT_EXPIRED", "TOTP preview expired");
    }
    void cancel(String preview) {
        Ticket ticket; synchronized (this) { ticket = current; if (ticket == null || !ticket.id.equals(preview)) return; }
        retire(ticket);
    }
    void cancelRequest(String request) {
        Ticket ticket; synchronized (this) { ticket = current; if (ticket == null || !ticket.request.equals(request)) return; }
        retire(ticket);
    }
    void cancelOwner(String owner) {
        Ticket ticket; synchronized (this) { ticket = current; if (ticket == null || !ticket.owner.equals(owner)) return; }
        retire(ticket);
    }
    void retireInvalid() {
        Ticket ticket;
        synchronized (this) { ticket = current; }
        if (ticket == null || clock.now() < ticket.origin.deadline && ticket.origin.epoch == vault.session.epoch()) return;
        retire(ticket);
    }
    private void retire(Ticket ticket) {
        synchronized (this) { ticket.invalid = true; ticket.origin.cancelled = true; if (current == ticket) current = null; }
        dispose(ticket);
    }
    private void dispose(Ticket ticket) {
        if (ticket == null) return;
        ticket.origin.cancel();
        synchronized (this) { for (Candidate candidate : ticket.candidates) candidate.input.close(); }
    }
    @Override public void close() {
        Ticket ticket;
        synchronized (this) { ticket = current; current = null; if (ticket != null) { ticket.invalid = true; ticket.origin.cancelled = true; } }
        dispose(ticket);
    }
    private static VaultException changed() { return new VaultException("VAULT_CHANGED", "TOTP preview changed"); }
}
