package im.majo.ctos;

import static org.junit.Assert.*;
import io.flutter.plugin.common.MethodCall;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** Calls the actual bridge dispatch grammar; Android Looper and transport are separate gates. */
public final class SshAuthenticationArgumentsTest {
    private static Map<String,Object> arguments(String method) {
        Map<String,Object> values = new HashMap<>(VaultCollections.map("requestId","request","username","user","columns",80,"rows",24));
        if (method.contains("config")) { values.put("alias","host"); values.put("binding","a".repeat(64)); }
        else { values.put("host","host.invalid"); values.put("port",22); }
        if (method.equals("ssh.passwordConnect") || method.equals("ssh.configPasswordConnect")) {
            values.put("password","synthetic"); values.put("useSavedPassword",false); values.put("savePassword",false);
        } else if (method.equals("ssh.keyConnect") || method.equals("ssh.configConnect")) {
            values.put("keyId","key"); values.put("passphrase",""); values.put("useSavedPassphrase",false); values.put("savePassphrase",false);
        }
        return values;
    }
    private static void refused(String method, Map<String,Object> values) throws Exception {
        try { SshPasswordConnectBridge.authenticationContinuation(new MethodCall(method,values)); fail("Invalid production request grammar"); }
        catch (VaultException expected) { assertEquals("PARAMETERS",expected.code); }
    }
    @Test public void firstMethodsDefaultFalseAndAcceptOnlyExactTypedContinuation() throws Exception {
        for (String method : new String[]{"ssh.passwordConnect","ssh.keyConnect","ssh.configConnect","ssh.configPasswordConnect"}) {
            Map<String,Object> values=arguments(method);
            assertFalse(SshPasswordConnectBridge.authenticationContinuation(new MethodCall(method,values)));
            values.put("continueKeyboardInteractive",true);
            assertTrue(SshPasswordConnectBridge.authenticationContinuation(new MethodCall(method,values)));
            values.put("continueKeyboardInteractive",false);
            assertFalse(SshPasswordConnectBridge.authenticationContinuation(new MethodCall(method,values)));
            for (Object invalid : new Object[]{null,0,1,"true"}) {
                values.put("continueKeyboardInteractive",invalid); refused(method,values);
            }
            values.remove("continueKeyboardInteractive"); values.put("extra",true); refused(method,values);
        }
    }
    @Test public void standaloneMethodsRejectEvenFalseContinuationAndAllSecretFields() throws Exception {
        for (String method : new String[]{"ssh.keyboardConnect","ssh.configKeyboardConnect"}) {
            assertFalse(SshPasswordConnectBridge.authenticationContinuation(new MethodCall(method,arguments(method))));
            for (String field : new String[]{"continueKeyboardInteractive","autoKeyboardInteractive","password","passphrase","keyId","savePassword"}) {
                Map<String,Object> values=arguments(method); values.put(field,false); refused(method,values);
            }
        }
    }
    @Test public void automaticModeIsTypedMutuallyExclusiveAndCompatibleWithLegacyRequests() throws Exception {
        for (String method : new String[]{"ssh.passwordConnect","ssh.keyConnect","ssh.configConnect","ssh.configPasswordConnect"}) {
            Map<String,Object> values=arguments(method);
            assertEquals(SshPasswordConnector.ContinuationMode.NONE,
                    SshPasswordConnectBridge.authenticationContinuationMode(new MethodCall(method,values)));
            values.put("autoKeyboardInteractive",true);
            assertEquals(SshPasswordConnector.ContinuationMode.AUTOMATIC,
                    SshPasswordConnectBridge.authenticationContinuationMode(new MethodCall(method,values)));
            values.put("autoKeyboardInteractive",false);
            assertEquals(SshPasswordConnector.ContinuationMode.NONE,
                    SshPasswordConnectBridge.authenticationContinuationMode(new MethodCall(method,values)));
            for (Object invalid : new Object[]{null,0,1,"true"}) {
                values.put("autoKeyboardInteractive",invalid); refused(method,values);
            }
            for (boolean automatic : new boolean[]{false,true}) for (boolean strict : new boolean[]{false,true}) {
                values.put("autoKeyboardInteractive",automatic); values.put("continueKeyboardInteractive",strict);
                refused(method,values);
            }
            values.remove("autoKeyboardInteractive"); values.put("continueKeyboardInteractive",true);
            assertEquals(SshPasswordConnector.ContinuationMode.STRICT,
                    SshPasswordConnectBridge.authenticationContinuationMode(new MethodCall(method,values)));

            if (!method.equals("ssh.configPasswordConnect")) {
                values.remove("useSavedPassword"); values.remove("savePassword");
                values.remove("passphrase"); values.remove("useSavedPassphrase"); values.remove("savePassphrase");
                values.remove("continueKeyboardInteractive"); values.put("autoKeyboardInteractive",true);
                assertEquals(SshPasswordConnector.ContinuationMode.AUTOMATIC,
                        SshPasswordConnectBridge.authenticationContinuationMode(new MethodCall(method,values)));
            }
        }
        for (String method : new String[]{"ssh.keyboardConnect","ssh.configKeyboardConnect"}) {
            Map<String,Object> values=arguments(method); values.put("autoKeyboardInteractive",true); refused(method,values);
        }
    }
    @Test public void configPasswordCannotSupplyForeignTupleOrOmitBindingAndSaveFlags() throws Exception {
        String method="ssh.configPasswordConnect";
        for (String field : new String[]{"host","port","keyId"}) {
            Map<String,Object> values=arguments(method); values.put(field,"foreign"); refused(method,values);
        }
        for (String field : new String[]{"alias","binding","username","useSavedPassword","savePassword"}) {
            Map<String,Object> values=arguments(method); values.remove(field); refused(method,values);
        }
        refused("ssh.unknownConnect",arguments(method));
    }

    @Test public void serverDrivenRequestsContainOnlyTargetAndOptionalKey() throws Exception {
        for (String method : new String[]{"ssh.autoConnect", "ssh.configAutoConnect"}) {
            boolean configured = method.contains("config");
            Map<String,Object> values = arguments(method); values.put("keyId", "");
            SshPasswordConnectBridge.automaticArguments(new MethodCall(method, values), configured);
            values.put("keyId", "key_1");
            SshPasswordConnectBridge.automaticArguments(new MethodCall(method, values), configured);
            for (String forbidden : new String[]{"password", "passphrase", "savePassword", "autoKeyboardInteractive"}) {
                values.put(forbidden, "secret");
                try { SshPasswordConnectBridge.automaticArguments(new MethodCall(method, values), configured); fail("unexpected input"); }
                catch (VaultException expected) { assertEquals("PARAMETERS", expected.code); }
                values.remove(forbidden);
            }
            values.put("keyId", "../key");
            try { SshPasswordConnectBridge.automaticArguments(new MethodCall(method, values), configured); fail("invalid key"); }
            catch (VaultException expected) { assertNotNull(expected.code); }
        }
    }
}
