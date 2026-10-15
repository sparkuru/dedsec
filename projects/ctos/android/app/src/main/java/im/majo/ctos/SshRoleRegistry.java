package im.majo.ctos;

import java.util.ArrayList;
import java.util.List;

/**
 * Native-launch role ownership.  There is deliberately no method accepting
 * arbitrary argv, executable paths, shell commands, or network destinations.
 */
final class SshRoleRegistry implements AutoCloseable {
    private final SshRoleContext.Clock clock;
    private final List<SshRoleContext> roles = new ArrayList<>();

    SshRoleRegistry(SshRoleContext.Clock clock) { this.clock = clock; }

    synchronized SshRoleContext issue(SshRoleContext.Bootstrap request, SshRoleContext.PublicView view)
            throws VaultException {
        for (int index = roles.size() - 1; index >= 0; index--) {
            SshRoleContext existing = roles.get(index);
            if (existing.reclaimable(clock)) {
                roles.remove(index);
                existing.close();
            }
        }
        if (roles.size() >= SshRoleContext.MAX_HOPS)
            throw new VaultException("SSH_ROLE_BUSY", "The SSH hop limit was reached");
        SshRoleContext candidate = SshRoleContext.issue(request, view, clock);
        for (SshRoleContext existing : roles) {
            if (existing.ownerPid == candidate.ownerPid && existing.hop == candidate.hop
                    || existing.connectionId.equals(candidate.connectionId) && existing.hop == candidate.hop
                    || SshRoleContext.same(existing.roleNonce(), candidate.roleNonce())
                    || existing.hop == candidate.hop
                    && SshRoleContext.same(existing.connectionBinding(), candidate.connectionBinding())) {
                candidate.close();
                throw new VaultException("SSH_ROLE_REPLAY", "The SSH role binding is already in use");
            }
        }
        roles.add(candidate);
        return candidate;
    }

    synchronized void requireOwned(SshRoleContext role) throws VaultException {
        if (role == null || !roles.contains(role))
            throw new VaultException("SSH_ROLE_REQUIRED", "The SSH role is not owned by this connection");
        role.requireActive(clock);
    }

    synchronized void release(SshRoleContext role) {
        if (role != null && roles.remove(role)) role.close();
    }

    synchronized int size() { return roles.size(); }

    @Override public synchronized void close() {
        for (SshRoleContext role : roles) role.close();
        roles.clear();
    }
}
