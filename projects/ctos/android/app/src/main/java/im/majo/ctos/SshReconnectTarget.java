package im.majo.ctos;

import java.util.Map;

/** RAM-only connection selection; never a credential, approval receipt, or reusable binding. */
final class SshReconnectTarget {
    final String mode, host, username, keyId, configAlias;
    final int port;
    final boolean continueKeyboardInteractive;

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias) throws VaultException {
        this(profile, keyId, configAlias, false);
    }

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias, boolean keyboardInteractive) throws VaultException {
        this(profile, keyId, configAlias, keyboardInteractive, false);
    }

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias, boolean keyboardInteractive,
            boolean continueKeyboardInteractive) throws VaultException {
        if (profile == null || configAlias == null || keyboardInteractive && (keyId != null || continueKeyboardInteractive))
            throw SshPasswordProfile.parameters();
        if (keyId != null) VaultManifest.identifier(keyId);
        if (!configAlias.isEmpty()) {
            if (configAlias.length() > 255 || configAlias.codePoints().anyMatch(c -> Character.isWhitespace(c)
                    || Character.isISOControl(c))) throw SshPasswordProfile.parameters();
            byte[] checked = VaultCodec.sshText(configAlias, 255);
            VaultCodec.wipe(checked);
        }
        mode = keyboardInteractive ? configAlias.isEmpty() ? "keyboard" : "configKeyboard"
                : keyId == null ? configAlias.isEmpty() ? "password" : "configPassword" : configAlias.isEmpty() ? "key" : "config";
        host = profile.host;
        port = profile.port;
        username = profile.username;
        this.keyId = keyId;
        this.configAlias = configAlias;
        this.continueKeyboardInteractive = continueKeyboardInteractive;
    }

    Map<String, Object> wire() {
        Map<String, Object> value = new java.util.HashMap<>(VaultCollections.map(
                "mode", mode, "host", host, "port", port, "username", username));
        if (keyId != null) value.put("keyId", keyId);
        if (!configAlias.isEmpty()) value.put("configAlias", configAlias);
        if (continueKeyboardInteractive) value.put("continueKeyboardInteractive", true);
        return value;
    }
}
