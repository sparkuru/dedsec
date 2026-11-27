package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.Test;

public final class SshJumpAuthenticationOrderTest {
    @Test public void offeredPasswordIsAllowedOnlyWhenItIsTheFirstConfiguredOfferedMethod() throws Exception {
        int password = SshNativeSession.AuthDiscoveryStatus.PASSWORD;
        int keyboard = SshNativeSession.AuthDiscoveryStatus.KEYBOARD_INTERACTIVE;
        int key = SshNativeSession.AuthDiscoveryStatus.PUBLIC_KEY;
        SshPasswordConnector.requireJumpPasswordMethod(List.of(SshConfigEvaluator.AuthenticationMethod.KEYBOARD_INTERACTIVE,
                SshConfigEvaluator.AuthenticationMethod.PASSWORD), password);
        SshPasswordConnector.requireJumpPasswordMethod(List.of(SshConfigEvaluator.AuthenticationMethod.PASSWORD,
                SshConfigEvaluator.AuthenticationMethod.KEYBOARD_INTERACTIVE), password | keyboard);
        reject(List.of(SshConfigEvaluator.AuthenticationMethod.KEYBOARD_INTERACTIVE,
                SshConfigEvaluator.AuthenticationMethod.PASSWORD), password | keyboard, "SSH_JUMP_AUTH_UNSUPPORTED");
        SshPasswordConnector.requireJumpPasswordMethod(List.of(SshConfigEvaluator.AuthenticationMethod.PUBLIC_KEY,
                SshConfigEvaluator.AuthenticationMethod.PASSWORD), password | key);
        reject(List.of(SshConfigEvaluator.AuthenticationMethod.PUBLIC_KEY,
                SshConfigEvaluator.AuthenticationMethod.PASSWORD), key, "SSH_JUMP_AUTH_UNSUPPORTED");
        try {
            SshPasswordConnector.chooseAutomaticMethod(List.of(SshConfigEvaluator.AuthenticationMethod.PUBLIC_KEY),
                    key, false);
            fail("direct authentication no longer requires a selected key");
        } catch (VaultException expected) { assertEquals("SSH_PUBLICKEY_REQUIRED", expected.code); }
        reject(List.of(SshConfigEvaluator.AuthenticationMethod.PASSWORD), keyboard, "SSH_AUTH_UNSUPPORTED");
    }

    @Test public void missingJumpUsernameIsDistinctFromMissingTargetUsername() throws Exception {
        SshPasswordConnector.requireJumpUser(false, false);
        SshPasswordConnector.requireJumpUser(false, true);
        try { SshPasswordConnector.requireJumpUser(true, false); fail("missing jump username accepted"); }
        catch (VaultException expected) { assertEquals("SSH_JUMP_USER_REQUIRED", expected.code); }
        try { SshPasswordConnector.requireJumpUser(true, true); fail("missing target username accepted"); }
        catch (VaultException expected) { assertEquals("SSH_USER_REQUIRED", expected.code); }
    }

    private static void reject(List<SshConfigEvaluator.AuthenticationMethod> order, int methods, String code)
            throws Exception {
        try {
            SshPasswordConnector.requireJumpPasswordMethod(order, methods);
            fail("an unavailable or later authentication method was accepted");
        } catch (VaultException expected) { assertEquals(code, expected.code); }
    }
}
