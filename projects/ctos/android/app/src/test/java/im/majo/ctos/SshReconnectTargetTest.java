package im.majo.ctos;

import static org.junit.Assert.*;
import java.util.Set;
import org.junit.Test;

public final class SshReconnectTargetTest {
    @Test public void wireContainsOnlyExactPublicSelectionAndIndependentCopies() throws Exception {
        SshPasswordProfile profile = new SshPasswordProfile("host.invalid", 2200, "user");
        SshReconnectTarget password = new SshReconnectTarget(profile, null, "");
        assertEquals(Set.of("mode", "host", "port", "username"), password.wire().keySet());
        assertEquals("password", password.wire().get("mode"));
        SshReconnectTarget key = new SshReconnectTarget(profile, "key_1", "");
        assertEquals(Set.of("mode", "host", "port", "username", "keyId"), key.wire().keySet());
        assertEquals("key", key.wire().get("mode"));
        SshReconnectTarget config = new SshReconnectTarget(profile, "key_1", "selected-alias");
        assertEquals(Set.of("mode", "host", "port", "username", "keyId", "configAlias"), config.wire().keySet());
        assertEquals("config", config.wire().get("mode"));
        assertEquals("host.invalid", config.wire().get("host"));
        assertEquals(2200, config.wire().get("port"));
        assertEquals("user", config.wire().get("username"));
        assertEquals("key_1", config.wire().get("keyId"));
        assertEquals("selected-alias", config.wire().get("configAlias"));
        config.wire().clear();
        assertEquals("selected-alias", config.wire().get("configAlias"));
    }

    @Test public void malformedIdentityAndAliasCannotBecomeAReconnectSelection() throws Exception {
        SshPasswordProfile profile = new SshPasswordProfile("host.invalid", 22, "user");
        for (String id : new String[] {"", "key/other", "key\nsecret", "x".repeat(129)}) {
            try { new SshReconnectTarget(profile, id, ""); fail("Malformed key ID must be rejected"); }
            catch (VaultException expected) { }
        }
        for (String alias : new String[] {null, "two hosts", "alias\nsecret", "x".repeat(256)}) {
            try { new SshReconnectTarget(profile, "key_1", alias); fail("Malformed alias must be rejected"); }
            catch (VaultException expected) { }
        }
        try { new SshReconnectTarget(profile, null, "alias"); fail("Config reconnect must preserve an exact identity"); }
        catch (VaultException expected) { }
    }
}
