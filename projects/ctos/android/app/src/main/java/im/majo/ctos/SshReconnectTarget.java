package im.majo.ctos;

import java.util.Map;

/** RAM-only connection selection; never a credential, approval receipt, or reusable binding. */
final class SshReconnectTarget {
    final String mode, host, username, keyId, configAlias;
    final int port;
    final boolean continueKeyboardInteractive, autoKeyboardInteractive;

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias) throws VaultException {
        this(profile, keyId, configAlias, false);
    }

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias, boolean keyboardInteractive) throws VaultException {
        this(profile, keyId, configAlias, keyboardInteractive, false);
    }

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias, boolean keyboardInteractive,
            boolean continueKeyboardInteractive) throws VaultException {
        this(profile, keyId, configAlias, keyboardInteractive, continueKeyboardInteractive, false);
    }

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias, boolean keyboardInteractive,
            boolean continueKeyboardInteractive, boolean autoKeyboardInteractive) throws VaultException {
        this(profile, keyId, configAlias, keyboardInteractive, continueKeyboardInteractive, autoKeyboardInteractive, false);
    }

    SshReconnectTarget(SshPasswordProfile profile, String keyId, String configAlias, boolean keyboardInteractive,
            boolean continueKeyboardInteractive, boolean autoKeyboardInteractive, boolean serverDriven) throws VaultException {
        if (profile == null || configAlias == null || continueKeyboardInteractive && autoKeyboardInteractive
                || serverDriven && (continueKeyboardInteractive || autoKeyboardInteractive)
                || !serverDriven && keyboardInteractive && (keyId != null || continueKeyboardInteractive || autoKeyboardInteractive))
            throw SshPasswordProfile.parameters();
        if (keyId != null) VaultManifest.identifier(keyId);
        if (!configAlias.isEmpty()) {
            if (configAlias.length() > 255 || configAlias.codePoints().anyMatch(c -> Character.isWhitespace(c)
                    || Character.isISOControl(c))) throw SshPasswordProfile.parameters();
            byte[] checked = VaultCodec.sshText(configAlias, 255);
            VaultCodec.wipe(checked);
        }
        mode = serverDriven ? configAlias.isEmpty() ? "auto" : "configAuto"
                : keyboardInteractive ? configAlias.isEmpty() ? "keyboard" : "configKeyboard"
                : keyId == null ? configAlias.isEmpty() ? "password" : "configPassword" : configAlias.isEmpty() ? "key" : "config";
        host = profile.host;
        port = profile.port;
        username = profile.username;
        this.keyId = keyId;
        this.configAlias = configAlias;
        this.continueKeyboardInteractive = continueKeyboardInteractive;
        this.autoKeyboardInteractive = autoKeyboardInteractive;
    }

    Map<String, Object> wire() {
        Map<String, Object> value = new java.util.HashMap<>(VaultCollections.map(
                "mode", mode, "host", host, "port", port, "username", username));
        if (keyId != null) value.put("keyId", keyId);
        if (!configAlias.isEmpty()) value.put("configAlias", configAlias);
        if (continueKeyboardInteractive) value.put("continueKeyboardInteractive", true);
        if (autoKeyboardInteractive) value.put("autoKeyboardInteractive", true);
        return value;
    }
}
