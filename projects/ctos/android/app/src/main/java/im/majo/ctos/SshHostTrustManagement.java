package im.majo.ctos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exact existing managed trust objects; query metadata never grants a mutation capability. */
final class SshHostTrustManagement {
    interface Guard { void check() throws VaultException; }
    interface Inspector {
        SshHostTrustMaterial.Result inspect(byte[] body, String host, int port,
                boolean prepare, long deadline) throws IOException;
    }

    enum Group {
        PASSWORD("password", SshPasswordProfile.HOSTS_PATH, SshPasswordProfile.HOSTS_ID,
                SshPasswordProfile.HOSTS_IDENTITY),
        CONFIG("config", SshConnectionMaterial.HOSTS,
                SshConnectionMaterial.id("hosts", SshConnectionMaterial.HOSTS),
                SshConnectionMaterial.id("hosts-owner", SshConnectionMaterial.HOSTS));
        final String value, path, objectId, identity;
        Group(String value, String path, String objectId, String identity) {
            this.value = value; this.path = path; this.objectId = objectId; this.identity = identity;
        }
        static Group parse(String value) throws VaultException {
            for (Group group : values()) if (group.value.equals(value)) return group;
            throw failure("PARAMETERS");
        }
        VaultManifest.FileObject select(VaultManifest manifest) throws VaultException {
            VaultManifest.FileObject file = SshConnectionMaterial.file(manifest, path);
            if (file != null && (file.type != VaultManifest.FileType.KNOWN_HOSTS
                    || !objectId.equals(file.id) || !identity.equals(file.identity) || !file.references.isEmpty()))
                throw failure("SSH_HOST_TRUST_FAILED");
            return file;
        }
    }

    static Map<String, Object> query(VaultManifest source, String host, int port, long deadline, Guard guard)
            throws IOException, VaultException {
        return query(source, host, port, deadline, guard, SshHostTrustMaterial::inspect);
    }

    static Map<String, Object> query(VaultManifest source, String host, int port, long deadline, Guard guard,
            Inspector inspector) throws IOException, VaultException {
        guard.check(); SshPasswordProfile.checkTarget(host, port); SshPasswordProfile.checkShape(source);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Group group : Group.values()) {
            guard.check();
            VaultManifest.FileObject file = group.select(source);
            if (file == null) {
                groups.add(VaultCollections.map("group", group.value, "path", group.path,
                        "exists", false, "records", List.of(), "canForget", false));
                continue;
            }
            try (SshHostTrustMaterial.Result result = inspector.inspect(file.content, host, port, false, deadline)) {
                guard.check();
                groups.add(VaultCollections.map("group", group.value, "path", group.path,
                        "exists", true, "records", result.metadata(), "canForget", result.canForget));
            }
        }
        guard.check();
        return VaultCollections.map("host", host, "port", port, "groups", VaultCollections.copyList(groups));
    }

    static final class Forget implements AutoCloseable {
        final String host;
        final int port;
        final Group group;
        final List<Map<String, Object>> records;
        final SshBundleProposal proposal;
        private Forget(String host, int port, Group group, List<Map<String, Object>> records,
                SshBundleProposal proposal) {
            this.host = host; this.port = port; this.group = group;
            this.records = records; this.proposal = proposal;
        }
        Map<String, Object> metadata(String ticketId) {
            return VaultCollections.map("ticketId", ticketId, "binding", proposal.binding,
                    "host", host, "port", port, "group", group.value, "path", group.path, "records", records);
        }
        @Override public void close() { proposal.close(); }
    }

    static Forget prepare(VaultManifest source, String host, int port, Group group,
            long deadline, Guard guard) throws IOException, VaultException {
        return prepare(source, host, port, group, deadline, guard, SshHostTrustMaterial::inspect);
    }

    static Forget prepare(VaultManifest source, String host, int port, Group group,
            long deadline, Guard guard, Inspector inspector) throws IOException, VaultException {
        guard.check(); SshPasswordProfile.checkTarget(host, port); SshPasswordProfile.checkShape(source);
        if (group == null) throw failure("PARAMETERS");
        VaultManifest.FileObject old = group.select(source);
        if (old == null) throw failure("SSH_HOST_TRUST_EMPTY");
        try (SshHostTrustMaterial.Result result = inspector.inspect(old.content, host, port, true, deadline)) {
            guard.check();
            if (result.records.isEmpty()) throw failure("SSH_HOST_TRUST_EMPTY");
            if (!result.canForget) throw failure("SSH_HOST_TRUST_BLOCKED");
            byte[] body = null;
            VaultManifest.FileObject replacement = null;
            SshBundleProposal proposal = null;
            try {
                body = result.replacement();
                replacement = new VaultManifest.FileObject(old.type, old.id, old.path, old.identity, body, old.references);
                try (SshBundleProposal.Change change = SshBundleProposal.Change.replace(old, replacement)) {
                    proposal = new SshBundleProposal(List.of(change));
                }
                guard.check();
                Forget prepared = new Forget(host, port, group, result.metadata(), proposal);
                proposal = null; return prepared;
            } finally {
                if (proposal != null) proposal.close();
                if (replacement != null) VaultCodec.wipe(replacement.content);
                VaultCodec.wipe(body);
            }
        }
    }

    static String errorCode(Throwable error) {
        for (int depth = 0; depth < 5 && error != null; depth++, error = error.getCause()) {
            if (error instanceof VaultException) return ((VaultException)error).code;
            if (error instanceof IOException && "SSH_HOST_TRUST_LIMIT".equals(error.getMessage()))
                return "SSH_HOST_TRUST_LIMIT";
        }
        return "SSH_HOST_TRUST_FAILED";
    }
    private static VaultException failure(String code) {
        return new VaultException(code, "The managed host-trust operation could not continue");
    }
}
