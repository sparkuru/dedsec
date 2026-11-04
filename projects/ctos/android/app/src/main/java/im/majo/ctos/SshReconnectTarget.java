package im.majo.ctos;

import java.util.Map;

/** RAM-only connection selection; never a credential, approval receipt, or reusable binding. */
final class SshReconnectTarget {
    final String mode, host, username, keyId, configAlias;
    final int port;

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias) throws VaultException {
        this(profile, keyId, configAlias, false);
    }

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias, boolean keyboardInteractive) throws VaultException {
        if (profile == null || configAlias == null || !keyboardInteractive && keyId == null && !configAlias.isEmpty()
                || keyboardInteractive && keyId != null)
            throw SshPasswordProfile.parameters();
        if (keyId != null) VaultManifest.identifier(keyId);
        if (!configAlias.isEmpty()) {
            if (configAlias.length() > 255 || configAlias.codePoints().anyMatch(c -> Character.isWhitespace(c)
                    || Character.isISOControl(c))) throw SshPasswordProfile.parameters();
            byte[] checked = VaultCodec.sshText(configAlias, 255);
            VaultCodec.wipe(checked);
        }
        mode = keyboardInteractive ? configAlias.isEmpty() ? "keyboard" : "configKeyboard"
                : keyId == null ? "password" : configAlias.isEmpty() ? "key" : "config";
        host = profile.host;
        port = profile.port;
        username = profile.username;
        this.keyId = keyId;
        this.configAlias = configAlias;
    }

    Map<String, Object> wire() {
        Map<String, Object> value = new java.util.HashMap<>(VaultCollections.map(
                "mode", mode, "host", host, "port", port, "username", username));
        if (keyId != null) value.put("keyId", keyId);
        if (!configAlias.isEmpty()) value.put("configAlias", configAlias);
        return value;
    }
}
