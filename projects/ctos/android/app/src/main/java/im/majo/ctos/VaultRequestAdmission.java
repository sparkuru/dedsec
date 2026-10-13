package im.majo.ctos;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Main-thread admission. Read IDs stay permanently read-only even after bounded replay eviction. */
final class VaultRequestAdmission {
    private final Set<String> writes = new HashSet<>();
    private final LinkedHashSet<String> reads = new LinkedHashSet<>();
    void admit(String id, boolean read) throws VaultException {
        if (!id.matches("[A-Za-z0-9_-]{1,128}") || read != id.startsWith("read_")
                || writes.contains(id) || reads.contains(id)) throw new VaultException("PARAMETERS", "Invalid or reused request ID");
        if (read) {
            reads.add(id);
            if (reads.size() > 128) reads.remove(reads.iterator().next());
        } else {
            if (writes.size() >= 4096) throw new VaultException("VAULT_EXPIRED", "Reopen the app to start a new operation");
            writes.add(id);
        }
    }
}
