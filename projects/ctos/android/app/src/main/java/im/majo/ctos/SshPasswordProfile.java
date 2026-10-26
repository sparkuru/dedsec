package im.majo.ctos;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The exact app-generated direct-password profile, not a general SSH config parser. */
final class SshPasswordProfile {
    static final String CONFIG_PATH = "ssh/config", HOSTS_PATH = "ssh/known_hosts";
    static final String CONFIG_ID = "ctos-password-config", HOSTS_ID = "ctos-password-hosts";
    static final String CONFIG_IDENTITY = "ctos-password-profile", HOSTS_IDENTITY = "ctos-password-trust";
    static final String ALIAS = "ctos-password", TARGET = "target:ctos-password";
    private static final String PREFIX = "# ctOS password connection\nHost " + ALIAS + "\n    HostName ";
    final String host, username;
    final int port;

    SshPasswordProfile(String host, int port, String username) throws VaultException {
        if (host == null || !host.matches("[A-Za-z0-9.:%\\[\\]-]{1,253}")
                || port < 1 || port > 65535) throw parameters();
        byte[] checked = VaultCodec.sshText(username, 255);
        VaultCodec.wipe(checked);
        this.host = host; this.port = port; this.username = username;
    }

    byte[] configBytes() {
        String user = username.replace("\\", "\\\\").replace("\"", "\\\"");
        return (PREFIX + host + "\n    Port " + port + "\n    User \"" + user
                + "\"\n    UserKnownHostsFile " + HOSTS_PATH + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static SshPasswordProfile parse(byte[] body) throws VaultException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException failure) { throw unsupported(); }
        if (!text.startsWith(PREFIX)) throw unsupported();
        int hostEnd = text.indexOf('\n', PREFIX.length());
        if (hostEnd < 0) throw unsupported();
        String host = text.substring(PREFIX.length(), hostEnd);
        String portPrefix = "\n    Port ";
        if (!text.startsWith(portPrefix, hostEnd)) throw unsupported();
        int portStart = hostEnd + portPrefix.length(), portEnd = text.indexOf('\n', portStart);
        if (portEnd < 0) throw unsupported();
        String portText = text.substring(portStart, portEnd);
        if (!portText.matches("[1-9][0-9]{0,4}")) throw unsupported();
        int port;
        try { port = Integer.parseInt(portText); }
        catch (NumberFormatException failure) { throw unsupported(); }
        String userPrefix = "\n    User \"", suffix = "\"\n    UserKnownHostsFile " + HOSTS_PATH + "\n";
        if (!text.startsWith(userPrefix, portEnd) || !text.endsWith(suffix)) throw unsupported();
        int userStart = portEnd + userPrefix.length(), userEnd = text.length() - suffix.length();
        if (userEnd < userStart) throw unsupported();
        StringBuilder username = new StringBuilder();
        for (int i = userStart; i < userEnd; i++) {
            char value = text.charAt(i);
            if (value == '\\') {
                if (++i >= userEnd) throw unsupported();
                value = text.charAt(i);
                if (value != '\\' && value != '"') throw unsupported();
            } else if (value == '"') throw unsupported();
            username.append(value);
        }
        SshPasswordProfile result;
        try { result = new SshPasswordProfile(host, port, username.toString()); }
        catch (VaultException failure) { throw unsupported(); }
        byte[] canonical = result.configBytes();
        try { if (!Arrays.equals(body, canonical)) throw unsupported(); }
        finally { VaultCodec.wipe(canonical); }
        return result;
    }

    boolean same(SshPasswordProfile other) {
        return other != null && host.equals(other.host) && port == other.port && username.equals(other.username);
    }

    static final class Snapshot implements AutoCloseable {
        final SshPasswordProfile profile;
        final long epoch, revision;
        final VaultManifest.FileObject config, knownHosts;
        final List<VaultSshMetadata.Account> accounts;
        Snapshot(VaultManifest manifest, long epoch, long revision) throws VaultException {
            this.epoch = epoch; this.revision = revision;
            SshPasswordProfile parsed = checkShape(manifest);
            accounts = VaultCollections.copyList(manifest.accounts);
            VaultManifest.FileObject cfg = null, hosts = null;
            try {
                for (VaultManifest.FileObject file : manifest.files) {
                    if (!file.path.equals(CONFIG_PATH) && !file.path.equals(HOSTS_PATH)) continue;
                    VaultManifest.FileObject copy = new VaultManifest.FileObject(file.type, file.id, file.path,
                            file.identity, file.content, file.references);
                    if (file.type == VaultManifest.FileType.CONFIG) cfg = copy; else hosts = copy;
                }
                config = cfg; knownHosts = hosts; profile = parsed;
            } catch (Throwable failure) {
                if (cfg != null) VaultCodec.wipe(cfg.content);
                if (hosts != null) VaultCodec.wipe(hosts.content);
                throw failure;
            }
        }

        SshBundleProposal changes(SshPasswordProfile next) throws VaultException {
            if (next.same(profile)) return null;
            byte[] body = next.configBytes();
            List<SshBundleProposal.Change> changes = new ArrayList<>();
            VaultManifest.FileObject cfg = null, hosts = null;
            try {
                cfg = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, CONFIG_ID, CONFIG_PATH,
                        CONFIG_IDENTITY, body, List.of(HOSTS_PATH));
                changes.add(config == null ? SshBundleProposal.Change.add(cfg)
                        : SshBundleProposal.Change.replace(config, cfg));
                if (knownHosts == null) {
                    hosts = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, HOSTS_ID,
                            HOSTS_PATH, HOSTS_IDENTITY, new byte[0], List.of());
                    changes.add(SshBundleProposal.Change.add(hosts));
                }
                return new SshBundleProposal(changes);
            } finally {
                VaultCodec.wipe(body);
                if (cfg != null) VaultCodec.wipe(cfg.content);
                if (hosts != null) VaultCodec.wipe(hosts.content);
                for (SshBundleProposal.Change change : changes) change.close();
            }
        }

        @Override public void close() {
            if (config != null) VaultCodec.wipe(config.content);
            if (knownHosts != null) VaultCodec.wipe(knownHosts.content);
        }
    }

    static SshPasswordProfile checkShape(VaultManifest manifest) throws VaultException {
        manifest.validate();
        SshSavedPassword.checkAccounts(manifest);
        if (manifest.files.isEmpty()) return null;
        if (SshConnectionMaterial.extended(manifest)) SshConnectionMaterial.checkShape(manifest);
        VaultManifest.FileObject config = null, hosts = null;
        for (VaultManifest.FileObject file : manifest.files) {
            if (file.path.startsWith(".ssh/")) continue;
            if (file.type == VaultManifest.FileType.CONFIG && CONFIG_PATH.equals(file.path)
                    && CONFIG_ID.equals(file.id) && CONFIG_IDENTITY.equals(file.identity)
                    && file.references.equals(List.of(HOSTS_PATH))) config = file;
            else if (file.type == VaultManifest.FileType.KNOWN_HOSTS && HOSTS_PATH.equals(file.path)
                    && HOSTS_ID.equals(file.id) && HOSTS_IDENTITY.equals(file.identity)
                    && file.references.isEmpty()) hosts = file;
            else throw unsupported();
        }
        if (config == null && hosts == null) return null;
        if (config == null || hosts == null) throw unsupported();
        return parse(config.content);
    }

    static VaultException unsupported() {
        return new VaultException("SSH_PROFILE_UNSUPPORTED", "This vault contains SSH material not supported by password connections");
    }
    static VaultException parameters() {
        return new VaultException("PARAMETERS", "Enter a host, port and SSH username");
    }
}
