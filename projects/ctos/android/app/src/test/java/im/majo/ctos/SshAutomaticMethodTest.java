package im.majo.ctos;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class SshAutomaticMethodTest {
    private static final SshConfigEvaluator.AuthenticationMethod KEY = SshConfigEvaluator.AuthenticationMethod.PUBLIC_KEY;
    private static final SshConfigEvaluator.AuthenticationMethod KBI = SshConfigEvaluator.AuthenticationMethod.KEYBOARD_INTERACTIVE;
    private static final SshConfigEvaluator.AuthenticationMethod PASSWORD = SshConfigEvaluator.AuthenticationMethod.PASSWORD;

    @Test public void selectsOnlyOfferedAndPermittedMethodsInConfiguredOrder() throws Exception {
        List<SshConfigEvaluator.AuthenticationMethod> order = List.of(KEY, KBI, PASSWORD);
        assertEquals(7, SshPasswordConnector.automaticMethodMask(order, true));
        assertEquals(6, SshPasswordConnector.automaticMethodMask(order, false));
        assertEquals(KEY, SshPasswordConnector.chooseAutomaticMethod(order, 7, true));
        assertEquals(KBI, SshPasswordConnector.chooseAutomaticMethod(order, 7, false));
        assertEquals(PASSWORD, SshPasswordConnector.chooseAutomaticMethod(order, 5, false));
        assertEquals(PASSWORD, SshPasswordConnector.chooseAutomaticMethod(List.of(PASSWORD, KEY, KBI), 7, true));
        assertEquals(KBI, SshPasswordConnector.chooseAutomaticMethod(List.of(KBI), 7, true));
    }

    @Test public void keyOnlyAndUnknownMethodsHaveDistinctFailures() throws Exception {
        try { SshPasswordConnector.chooseAutomaticMethod(List.of(KEY, PASSWORD), 1, false); fail("missing key"); }
        catch (VaultException expected) { assertEquals("SSH_PUBLICKEY_REQUIRED", expected.code); }
        try { SshPasswordConnector.chooseAutomaticMethod(List.of(PASSWORD), 1, false); fail("disabled key"); }
        catch (VaultException expected) { assertEquals("SSH_AUTH_UNSUPPORTED", expected.code); }
        try { SshPasswordConnector.chooseAutomaticMethod(List.of(KEY, KBI, PASSWORD), 8, true); fail("unknown method"); }
        catch (VaultException expected) { assertEquals("SSH_AUTH_UNSUPPORTED", expected.code); }
    }

    @Test public void rejectedKeyUsesOnlyOtherMethodsStillOfferedByTheServer() throws Exception {
        VaultException rejected = new VaultException("SSH_KEY_REJECTED", "synthetic rejection");
        List<SshConfigEvaluator.AuthenticationMethod> remaining = List.of(KBI, PASSWORD);
        assertEquals(PASSWORD, SshPasswordConnector.nextAutomaticMethod(remaining, 4, true, false, rejected));
        assertEquals(KBI, SshPasswordConnector.nextAutomaticMethod(remaining, 6, true, false, rejected));
        try { SshPasswordConnector.nextAutomaticMethod(remaining, 1, true, false, rejected); fail("key-only rejection"); }
        catch (VaultException expected) { assertSame(rejected, expected); }
        try { SshPasswordConnector.nextAutomaticMethod(List.of(KBI), 4, true, false, rejected); fail("disabled password"); }
        catch (VaultException expected) { assertSame(rejected, expected); }
    }
}
