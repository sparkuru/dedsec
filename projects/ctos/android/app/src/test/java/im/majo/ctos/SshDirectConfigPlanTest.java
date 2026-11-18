package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Native real-Options policy is tested separately; these assert typed projection and owner fences. */
public final class SshDirectConfigPlanTest {
    private interface Work { void run() throws Exception; }
    private static final class Binding implements SshConfigEvaluator.NativeBinding {
        int reserved, loaded, cancelled, closed, destroyed;
        boolean live = true;
        byte[] response;
        final List<byte[]> outputs = new ArrayList<>();
        Runnable during;
        @Override public long open(long view, byte[] request) { return 31; }
        @Override public long reserveDirect() { reserved++; return 31; }
        @Override public long reserveProfileDirect() { reserved++; return 31; }
        @Override public long reserveDetached(boolean catalog) { return 32; }
        @Override public void loadDetached(long handle, byte[] source, int root, long deadline, byte[] request) {
            loaded++; assertNotNull(request); assertEquals(0x43534551, ByteBuffer.wrap(request).getInt());
        }
        @Override public int[] run(long handle) { return new int[5]; }
        @Override public byte[] summary(long handle) { return null; }
        @Override public byte[] directPlan(long handle) {
            byte[] result = response.clone(); outputs.add(result);
            if (during != null) during.run(); return result;
        }
        @Override public byte[] profileDirectPlan(long handle) { return directPlan(handle); }
        @Override public void cancel(long handle) { cancelled++; }
        @Override public void close(long handle) { closed++; }
        @Override public void destroy(long handle) { destroyed++; }
        void clean() { for (byte[] output : outputs) assertArrayEquals(new byte[output.length], output); }
    }
    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
    }
    private static byte[] frame(String user, String... paths) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        for (int value : new int[] {0x43534350, 1, 2222, 1, 1, 0, 3000, 0, 3, user.isEmpty() ? 1 : 0, paths.length, 1}) out.writeInt(value);
        text(out, "actual-alias"); text(out, "actual.example"); text(out, user);
        for (String path : paths) text(out, path);
        text(out, ".ssh/known_hosts"); return bytes.toByteArray();
    }
    private static byte[] source() {
        byte[] path = ".ssh/config".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(36 + path.length).putInt(0x43534449).putInt(1).putInt(1)
                .putInt(10).putInt(1024).putInt(100).putInt(1).putInt(path.length).put(path).putInt(0).array();
    }
    private static SshConfigEvaluator.Request request() throws IOException {
        return new SshConfigEvaluator.Request("actual-alias", "", "", "actual-local", "local.example", "", "", 0, 1000);
    }
    private static SshConfigEvaluator prepare(Binding binding) throws IOException {
        return SshConfigEvaluator.prepareDirectDetached(() -> {
            if (!binding.live) throw new IOException("original guard expired");
        }, binding);
    }
    private static void rejected(Work work) throws Exception {
        try { work.run(); fail("Expected direct plan refusal"); } catch (IOException expected) { }
    }
    @Test public void profileHintsAreDistinctFromManagedPathsAndOldGettersStayStrict() throws Exception {
        Binding binding = new Binding();
        binding.response = frame("remote", "/home/source/.ssh/key with spaces", "relative/key", "${KEY}/%h");
        ByteBuffer.wrap(binding.response).putInt(0, 0x43535050);
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareProfileDirectDetached(() -> { }, binding)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            rejected(evaluator::directPlan);
            SshConfigEvaluator.ProfilePlan plan = evaluator.profileDirectPlan();
            assertTrue(plan.identityFiles.isEmpty());
            assertEquals(List.of("/home/source/.ssh/key with spaces", "relative/key", "${KEY}/%h"), plan.identityHints);
            assertEquals(List.of(".ssh/known_hosts"), plan.userKnownHostsFiles);
            try { plan.identityHints.add("foreign"); fail(); } catch (UnsupportedOperationException expected) { }
            evaluator.cancel(); rejected(evaluator::profileDirectPlan); binding.clean();
        }
        for (boolean oldMode : List.of(false, true)) {
            Binding wrong = new Binding(); wrong.response = frame("remote", ".ssh/key");
            if (oldMode) ByteBuffer.wrap(wrong.response).putInt(0, 0x43535050);
            try (SshConfigEvaluator evaluator = oldMode ? prepare(wrong)
                    : SshConfigEvaluator.prepareProfileDirectDetached(() -> { }, wrong)) {
                evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                if (oldMode) rejected(evaluator::directPlan); else rejected(evaluator::profileDirectPlan);
                wrong.clean();
            }
        }
        for (String hint : List.of("bad\u0001value", "bad\u0085value", "x".repeat(513))) {
            Binding wrong = new Binding(); wrong.response = frame("remote", hint);
            ByteBuffer.wrap(wrong.response).putInt(0, 0x43535050);
            try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareProfileDirectDetached(() -> { }, wrong)) {
                evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                rejected(evaluator::profileDirectPlan); wrong.clean();
            }
        }
    }

    @Test public void directProjectionRetainsActualTupleAndManagedPathsWithoutNativePointer() throws Exception {
        Binding binding = new Binding(); binding.response = frame("remote", ".ssh/id_ed25519");
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            rejected(evaluator::directPlan);
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            SshConfigEvaluator.DirectPlan plan = evaluator.directPlan();
            assertEquals("actual-alias", plan.alias); assertEquals("actual.example", plan.hostname);
            assertEquals("remote", plan.user); assertEquals(2222, plan.port);
            assertEquals(List.of(".ssh/id_ed25519"), plan.identityFiles);
            assertEquals(List.of(".ssh/known_hosts"), plan.userKnownHostsFiles);
            assertTrue(plan.identitiesOnly); assertTrue(plan.pubkeyAuthentication); assertFalse(plan.passwordAuthentication);
            assertFalse(plan.keyboardInteractiveAuthentication);
            assertFalse(plan.userRequired); assertEquals(3000, plan.connectTimeoutMillis);
            assertEquals(0, plan.serverAliveInterval); assertEquals(3, plan.serverAliveCountMax);
            assertEquals(0, plan.requestTty); assertEquals(3, plan.strictHostKeyChecking);
            try { plan.identityFiles.add("other"); fail(); } catch (UnsupportedOperationException expected) { }
            assertEquals(1, binding.reserved); assertEquals(1, binding.loaded); binding.clean();
        }
        assertEquals(1, binding.closed); assertEquals(1, binding.destroyed);
    }
    @Test public void versionTwoCarriesIndependentKeyboardPolicyAndOldVersionNeverGuessesIt() throws Exception {
        for (boolean profile : List.of(false,true)) for (int flag : new int[]{0,1,2}) {
            Binding binding=new Binding(); byte[] old=frame("remote");
            binding.response=ByteBuffer.allocate(old.length+4).put(old,0,24).putInt(flag)
                    .put(old,24,old.length-24).array();
            ByteBuffer.wrap(binding.response).putInt(4,2);
            if(profile)ByteBuffer.wrap(binding.response).putInt(0,0x43535050);
            try(SshConfigEvaluator evaluator=profile?SshConfigEvaluator.prepareProfileDirectDetached(()->{},binding):prepare(binding)){
                evaluator.loadDetached(source(),0,31000,request());evaluator.run();
                if(flag==2){if(profile)rejected(evaluator::profileDirectPlan);else rejected(evaluator::directPlan);}
                else{
                    SshConfigEvaluator.DirectPlan plan=profile?evaluator.profileDirectPlan():evaluator.directPlan();
                    assertEquals(flag==1,plan.keyboardInteractiveAuthentication);
                    assertTrue(plan.pubkeyAuthentication);assertFalse(plan.passwordAuthentication);
                    assertEquals("remote",plan.user);assertEquals(3000,plan.connectTimeoutMillis);
                }
                binding.clean();
            }
        }
    }

    @Test public void hostProjectionDoesNotRequirePublickeyForPasswordKbiOrNone() throws Exception {
        for (int enabled = 0; enabled < 4; enabled++) {
            Binding binding = new Binding(); byte[] old = frame("remote");
            binding.response = ByteBuffer.allocate(old.length + 4).put(old, 0, 24).putInt(enabled & 1)
                    .put(old, 24, old.length - 24).array();
            ByteBuffer.wrap(binding.response).putInt(0, 0x43535050).putInt(4, 2)
                    .putInt(16, 0).putInt(20, enabled >> 1);
            try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareProfileDirectDetached(() -> {}, binding)) {
                evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                SshConfigEvaluator.ProfilePlan plan = evaluator.profileDirectPlan();
                java.util.Map<String,Object> row = SshProfilesBridge.profileHostRow("alias", "a".repeat(64), plan, List.of("key_1"));
                assertEquals(List.of(), row.get("keyIds"));
                assertFalse(row.containsKey("error"));
                assertEquals("actual.example", row.get("host"));
            }
        }
    }
    @Test public void versionThreeCarriesStrictConsumedChainPolicyAndLegacyNeverAuthorizesIt() throws Exception {
        for (boolean profile : List.of(false,true)) for (int flags = 0; flags < 8; flags++) {
            Binding binding = new Binding(); byte[] old = frame("remote");
            binding.response = ByteBuffer.allocate(old.length + 12).put(old,0,24)
                    .putInt(1).putInt((flags & 1) != 0 ? 1 : 0).putInt((flags & 2) != 0 ? 1 : 0)
                    .put(old,24,old.length - 24).array();
            ByteBuffer.wrap(binding.response).putInt(4,3).putInt(20,(flags & 4) != 0 ? 1 : 0);
            if (profile) ByteBuffer.wrap(binding.response).putInt(0,0x43535050);
            try (SshConfigEvaluator evaluator = profile ? SshConfigEvaluator.prepareProfileDirectDetached(() -> {},binding) : prepare(binding)) {
                evaluator.loadDetached(source(),0,31000,request()); evaluator.run();
                if ((flags & 1) != 0 && (flags & 4) == 0) {
                    if (profile) rejected(evaluator::profileDirectPlan); else rejected(evaluator::directPlan);
                } else {
                    SshConfigEvaluator.DirectPlan plan = profile ? evaluator.profileDirectPlan() : evaluator.directPlan();
                    assertEquals((flags & 1) != 0,plan.passwordThenKeyboardAllowed);
                    assertEquals((flags & 2) != 0,plan.publickeyThenKeyboardAllowed);
                    assertEquals(0,plan.serverAliveInterval); assertEquals(3,plan.serverAliveCountMax);
                }
                binding.clean();
            }
        }
        for (int version : new int[]{1,2}) {
            Binding binding = new Binding(); byte[] old = frame("remote");
            binding.response = version == 1 ? old : ByteBuffer.allocate(old.length + 4).put(old,0,24).putInt(1)
                    .put(old,24,old.length - 24).array();
            ByteBuffer.wrap(binding.response).putInt(4,version);
            try (SshConfigEvaluator evaluator = prepare(binding)) {
                evaluator.loadDetached(source(),0,31000,request()); evaluator.run();
                assertFalse(evaluator.directPlan().passwordThenKeyboardAllowed);
                assertFalse(evaluator.directPlan().publickeyThenKeyboardAllowed);
            }
        }
    }

    @Test public void versionFourCarriesServerAlivePolicyWithoutChangingAuthenticationFlags() throws Exception {
        for (boolean profile : List.of(false, true)) {
            for (int[] values : new int[][] {{0, 3}, {15, 2}, {0, 0}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {-1, 3}, {1, -1}}) {
                Binding binding = new Binding(); byte[] old = frame("remote");
                binding.response = ByteBuffer.allocate(old.length + 20).put(old, 0, 24)
                        .putInt(1).putInt(0).putInt(1).putInt(values[0]).putInt(values[1])
                        .put(old, 24, old.length - 24).array();
                ByteBuffer.wrap(binding.response).putInt(4, 4);
                if (profile) ByteBuffer.wrap(binding.response).putInt(0, 0x43535050);
                try (SshConfigEvaluator evaluator = profile
                        ? SshConfigEvaluator.prepareProfileDirectDetached(() -> { }, binding) : prepare(binding)) {
                    evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                    if (values[0] < 0 || values[1] < 0) {
                        if (profile) rejected(evaluator::profileDirectPlan); else rejected(evaluator::directPlan);
                    } else {
                        SshConfigEvaluator.DirectPlan plan = profile ? evaluator.profileDirectPlan() : evaluator.directPlan();
                        assertEquals(values[0], plan.serverAliveInterval);
                        assertEquals(values[1], plan.serverAliveCountMax);
                        assertTrue(plan.keyboardInteractiveAuthentication);
                        assertFalse(plan.passwordThenKeyboardAllowed);
                        assertTrue(plan.publickeyThenKeyboardAllowed);
                        assertEquals(3000, plan.connectTimeoutMillis);
                    }
                    binding.clean();
                }
            }
        }
    }

    @Test public void requestTtyIsDecodedStrictlyAcrossPlanVersionsAndModes() throws Exception {
        for (boolean profile : List.of(false, true)) for (int version = 1; version <= 4; version++) {
            for (int tty : new int[] {-1, 0, 1, 2, 3, 4}) {
                Binding binding = new Binding(); byte[] old = frame("remote");
                int extra = version == 1 ? 0 : version == 2 ? 4 : version == 3 ? 12 : 20;
                ByteBuffer wire = ByteBuffer.allocate(old.length + extra).put(old, 0, 24);
                for (int i = 0; i < extra / 4; i++) wire.putInt(0);
                wire.put(old, 24, old.length - 24).putInt(4, version).putInt(28 + extra, tty);
                if (profile) wire.putInt(0, 0x43535050);
                binding.response = wire.array();
                try (SshConfigEvaluator evaluator = profile
                        ? SshConfigEvaluator.prepareProfileDirectDetached(() -> { }, binding) : prepare(binding)) {
                    evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                    if (tty < 0 || tty > 3) {
                        if (profile) rejected(evaluator::profileDirectPlan); else rejected(evaluator::directPlan);
                    } else {
                        SshConfigEvaluator.DirectPlan plan = profile ? evaluator.profileDirectPlan() : evaluator.directPlan();
                        assertEquals(tty, plan.requestTty);
                    }
                    binding.clean();
                }
            }
        }
    }

    private static byte[] authenticationOrderFrame(boolean profile, int mask, int order) throws Exception {
        byte[] old = frame("remote");
        ByteBuffer wire = ByteBuffer.allocate(old.length + 24).put(old, 0, 24)
                .putInt((mask & 2) == 0 ? 0 : 1).putInt(0).putInt(0)
                .putInt(0).putInt(3).putInt(order).put(old, 24, old.length - 24);
        wire.putInt(4, 5).putInt(16, (mask & 1) == 0 ? 0 : 1)
                .putInt(20, (mask & 4) == 0 ? 0 : 1);
        if (profile) wire.putInt(0, 0x43535050);
        return wire.array();
    }

    private static SshConfigEvaluator.DirectPlan authenticationOrderPlan(int mask, int order) throws Exception {
        Binding binding = new Binding(); binding.response = authenticationOrderFrame(false, mask, order);
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            SshConfigEvaluator.DirectPlan result = evaluator.directPlan(); binding.clean(); return result;
        }
    }

    @Test public void versionFiveCarriesStrictOrderedEnabledMethods() throws Exception {
        int[][] cases = {{0, 0}, {1, 0x010000}, {2, 0x020000}, {4, 0x030000},
                {3, 0x010200}, {3, 0x020100}, {5, 0x010300}, {5, 0x030100},
                {6, 0x020300}, {6, 0x030200}, {7, 0x010203}, {7, 0x010302},
                {7, 0x020103}, {7, 0x020301}, {7, 0x030102}, {7, 0x030201}};
        for (boolean profile : List.of(false, true)) for (int[] row : cases) {
            Binding binding = new Binding(); binding.response = authenticationOrderFrame(profile, row[0], row[1]);
            ByteBuffer.wrap(binding.response).putInt(52, 1);
            try (SshConfigEvaluator evaluator = profile
                    ? SshConfigEvaluator.prepareProfileDirectDetached(() -> { }, binding) : prepare(binding)) {
                evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                SshConfigEvaluator.DirectPlan plan = profile ? evaluator.profileDirectPlan() : evaluator.directPlan();
                assertTrue(plan.authenticationOrderKnown); assertEquals(1, plan.requestTty);
                List<SshConfigEvaluator.AuthenticationMethod> expected = new ArrayList<>();
                for (int shift : new int[] {16, 8, 0}) switch (row[1] >>> shift & 0xff) {
                    case 1: expected.add(SshConfigEvaluator.AuthenticationMethod.PUBLIC_KEY); break;
                    case 2: expected.add(SshConfigEvaluator.AuthenticationMethod.KEYBOARD_INTERACTIVE); break;
                    case 3: expected.add(SshConfigEvaluator.AuthenticationMethod.PASSWORD); break;
                    default: break;
                }
                assertEquals(expected, plan.preferredAuthenticationOrder);
                try { plan.preferredAuthenticationOrder.add(SshConfigEvaluator.AuthenticationMethod.PUBLIC_KEY); fail(); }
                catch (UnsupportedOperationException expectedFailure) { }
                binding.clean();
            }
        }
        for (boolean profile : List.of(false, true)) for (int[] row : new int[][] {
                {7, 0}, {7, 0x01020304}, {7, 0x010103}, {7, 0x010002},
                {7, 0x010403}, {7, 0x010200}, {1, 0x010200}, {0, 0x030000}}) {
            Binding binding = new Binding(); binding.response = authenticationOrderFrame(profile, row[0], row[1]);
            try (SshConfigEvaluator evaluator = profile
                    ? SshConfigEvaluator.prepareProfileDirectDetached(() -> { }, binding) : prepare(binding)) {
                evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                if (profile) rejected(evaluator::profileDirectPlan); else rejected(evaluator::directPlan);
                binding.clean();
            }
        }
        Binding legacy = new Binding(); legacy.response = frame("remote");
        try (SshConfigEvaluator evaluator = prepare(legacy)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            SshConfigEvaluator.DirectPlan plan = evaluator.directPlan();
            assertFalse(plan.authenticationOrderKnown); assertTrue(plan.preferredAuthenticationOrder.isEmpty());
            legacy.clean();
        }
    }

    @Test public void authenticationOrderPolicyFreezesExactOrderAndLegacyUnknown() throws Exception {
        SshPasswordConnector.AuthenticationOrderPolicy policy = new SshPasswordConnector.AuthenticationOrderPolicy();
        assertFalse(policy.configured()); assertFalse(policy.known()); assertTrue(policy.order().isEmpty());
        SshConfigEvaluator.DirectPlan first = authenticationOrderPlan(7, 0x010203);
        policy.accept(first); policy.accept(authenticationOrderPlan(7, 0x010203));
        assertTrue(policy.configured()); assertTrue(policy.known());
        assertEquals(first.preferredAuthenticationOrder, policy.order());
        policyCode("VAULT_CHANGED", () -> policy.accept(authenticationOrderPlan(7, 0x020103)));
        policyCode("VAULT_CHANGED", () -> policy.accept(authenticationOrderPlan(3, 0x010200)));
        assertEquals(first.preferredAuthenticationOrder, policy.order());
        Binding legacy = new Binding(); legacy.response = frame("remote");
        try (SshConfigEvaluator evaluator = prepare(legacy)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            SshConfigEvaluator.DirectPlan old = evaluator.directPlan();
            policyCode("VAULT_CHANGED", () -> policy.accept(old));
            SshPasswordConnector.AuthenticationOrderPolicy legacyPolicy = new SshPasswordConnector.AuthenticationOrderPolicy();
            legacyPolicy.accept(old); legacyPolicy.accept(old);
            assertTrue(legacyPolicy.configured()); assertFalse(legacyPolicy.known());
            policyCode("VAULT_CHANGED", () -> legacyPolicy.accept(first));
        }
    }

    private static SshConfigEvaluator.DirectPlan ttyPlan(int tty) throws Exception {
        Binding binding = new Binding(); binding.response = frame("remote");
        ByteBuffer.wrap(binding.response).putInt(28, tty);
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            SshConfigEvaluator.DirectPlan result = evaluator.directPlan(); binding.clean(); return result;
        }
    }

    @Test public void ttyPolicyFreezesEveryEffectiveValueAcrossSnapshots() throws Exception {
        SshPasswordConnector.TtyPolicy manual = new SshPasswordConnector.TtyPolicy();
        assertFalse(manual.configured()); assertFalse(manual.noPty());
        for (int tty = 0; tty <= 3; tty++) {
            SshPasswordConnector.TtyPolicy policy = new SshPasswordConnector.TtyPolicy();
            policy.accept(ttyPlan(tty)); policy.accept(ttyPlan(tty));
            assertTrue(policy.configured()); assertEquals(tty == 1, policy.noPty());
            for (int other = 0; other <= 3; other++) if (tty != other) {
                SshConfigEvaluator.DirectPlan changed = ttyPlan(other);
                policyCode("VAULT_CHANGED", () -> policy.accept(changed));
                assertEquals(tty == 1, policy.noPty());
            }
        }
    }

    private static SshConfigEvaluator.DirectPlan serverAlivePlan(int interval, int count) throws Exception {
        Binding binding = new Binding(); byte[] old = frame("remote");
        binding.response = ByteBuffer.allocate(old.length + 20).put(old, 0, 24)
                .putInt(1).putInt(0).putInt(1).putInt(interval).putInt(count)
                .put(old, 24, old.length - 24).array();
        ByteBuffer.wrap(binding.response).putInt(4, 4);
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            SshConfigEvaluator.DirectPlan result = evaluator.directPlan(); binding.clean(); return result;
        }
    }

    @Test public void serverAlivePolicyFreezesTheEffectiveConfigAcrossSnapshots() throws Exception {
        SshPasswordConnector.ServerAlivePolicy manual = new SshPasswordConnector.ServerAlivePolicy();
        assertFalse(manual.configured()); assertEquals(0, manual.interval()); assertEquals(3, manual.countMax());
        for (int[] values : new int[][] {{0, 0}, {0, 3}, {15, 2}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            SshPasswordConnector.ServerAlivePolicy policy = new SshPasswordConnector.ServerAlivePolicy();
            policy.accept(serverAlivePlan(values[0], values[1]));
            policy.accept(serverAlivePlan(values[0], values[1]));
            assertTrue(policy.configured());
            assertEquals(values[0], policy.interval()); assertEquals(values[1], policy.countMax());
            int otherInterval = values[0] == 0 ? 1 : 0;
            int otherCount = values[1] == 0 ? 1 : 0;
            policyCode("VAULT_CHANGED", () -> policy.accept(serverAlivePlan(otherInterval, values[1])));
            policyCode("VAULT_CHANGED", () -> policy.accept(serverAlivePlan(values[0], otherCount)));
            assertEquals(values[0], policy.interval()); assertEquals(values[1], policy.countMax());
        }
    }

    private static SshConfigEvaluator.DirectPlan chainPlan(boolean passwordThen, boolean keyThen) throws Exception {
        Binding binding = new Binding(); byte[] old = frame("remote");
        binding.response = ByteBuffer.allocate(old.length + 12).put(old,0,24)
                .putInt(1).putInt(passwordThen ? 1 : 0).putInt(keyThen ? 1 : 0)
                .put(old,24,old.length - 24).array();
        ByteBuffer.wrap(binding.response).putInt(4,3).putInt(20,1);
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            evaluator.loadDetached(source(),0,31000,request()); evaluator.run();
            SshConfigEvaluator.DirectPlan result = evaluator.directPlan(); binding.clean(); return result;
        }
    }

    private static void policyCode(String code, Work work) throws Exception {
        try { work.run(); fail("Expected " + code); }
        catch (VaultException expected) { assertEquals(code,expected.code); }
    }

    @Test public void automaticChainUsesDecodedConfigWithoutPreemptingFirstFactorSuccess() throws Exception {
        for (boolean key : new boolean[]{false,true}) for (boolean allowed : new boolean[]{false,true}) {
            // The unrelated chain remains enabled to catch accidental use of the wrong flag.
            SshConfigEvaluator.DirectPlan plan = key ? chainPlan(true,allowed) : chainPlan(allowed,true);
            boolean capability = key ? plan.publickeyThenKeyboardAllowed : plan.passwordThenKeyboardAllowed;
            SshPasswordConnector.ContinuationPolicy automatic = new SshPasswordConnector.ContinuationPolicy(
                    SshPasswordConnector.ContinuationMode.AUTOMATIC,true);
            automatic.acceptConfiguredCapability(capability);
            assertEquals(allowed,automatic.enabled());
            assertFalse(SshPasswordConnector.firstFactorNeedsContinuation(true,false,automatic.enabled()));
            if (allowed) assertTrue(SshPasswordConnector.firstFactorNeedsContinuation(false,true,automatic.enabled()));
            else policyCode("SSH_AUTH_PARTIAL", () ->
                    SshPasswordConnector.firstFactorNeedsContinuation(false,true,automatic.enabled()));

            SshPasswordConnector.ContinuationPolicy strict = new SshPasswordConnector.ContinuationPolicy(
                    SshPasswordConnector.ContinuationMode.STRICT,true);
            if (allowed) { strict.acceptConfiguredCapability(capability); assertTrue(strict.enabled()); }
            else policyCode("SSH_MFA_POLICY", () -> strict.acceptConfiguredCapability(capability));
        }
    }

    @Test public void automaticPolicyFreezesAcrossSnapshotsAndFreshReconnectReevaluates() throws Exception {
        for (boolean key : new boolean[]{false,true}) for (boolean first : new boolean[]{false,true}) {
            SshConfigEvaluator.DirectPlan initial = key ? chainPlan(true,first) : chainPlan(first,true);
            SshConfigEvaluator.DirectPlan changed = key ? chainPlan(true,!first) : chainPlan(!first,true);
            boolean initialCapability = key ? initial.publickeyThenKeyboardAllowed : initial.passwordThenKeyboardAllowed;
            boolean changedCapability = key ? changed.publickeyThenKeyboardAllowed : changed.passwordThenKeyboardAllowed;
            SshPasswordConnector.ContinuationPolicy current = new SshPasswordConnector.ContinuationPolicy(
                    SshPasswordConnector.ContinuationMode.AUTOMATIC,true);
            current.acceptConfiguredCapability(initialCapability);
            current.acceptConfiguredCapability(initialCapability);
            policyCode("VAULT_CHANGED", () -> current.acceptConfiguredCapability(changedCapability));

            // A new connection retains only intent and independently evaluates the new protected snapshot.
            SshReconnectTarget target = new SshReconnectTarget(new SshPasswordProfile("host.invalid",22,"user"),
                    key ? "key_1" : null,"configured",false,false,true);
            assertEquals(true,target.wire().get("autoKeyboardInteractive"));
            SshPasswordConnector.ContinuationPolicy fresh = new SshPasswordConnector.ContinuationPolicy(
                    SshPasswordConnector.ContinuationMode.AUTOMATIC,true);
            fresh.acceptConfiguredCapability(changedCapability);
            assertEquals(!first,fresh.enabled());
        }
        SshPasswordConnector.ContinuationPolicy manual = new SshPasswordConnector.ContinuationPolicy(
                SshPasswordConnector.ContinuationMode.AUTOMATIC,false);
        assertTrue(manual.enabled());
    }

    @Test public void configPasswordUsesCallerUserOnlyWhenActualConfigPlanRequiresIt() throws Exception {
        for (String configured : new String[]{"remote",""}) {
            Binding binding = new Binding(); binding.response = frame(configured);
            try (SshConfigEvaluator evaluator = prepare(binding)) {
                evaluator.loadDetached(source(),0,31000,request()); evaluator.run();
                SshConfigEvaluator.DirectPlan plan = evaluator.directPlan();
                assertEquals("",SshPasswordConnector.configPasswordUserOverride(plan,""));
                if (configured.isEmpty()) assertEquals("typed",SshPasswordConnector.configPasswordUserOverride(plan,"typed"));
                else {
                    try { SshPasswordConnector.configPasswordUserOverride(plan,"foreign"); fail("Fixed config user must not be overridden"); }
                    catch (VaultException expected) { assertEquals("PARAMETERS",expected.code); }
                }
            }
        }
    }

    @Test public void malformedCountsFlagsPathsAndTrailingDataRejectWithWireCleanup() throws Exception {
        byte[] good = frame("remote", ".ssh/id");
        List<byte[]> invalid = new ArrayList<>();
        int[][] changes = {{0, 0}, {4, 2}, {8, 0}, {8, 65536}, {12, 2}, {16, 2}, {20, -1},
                {24, 0}, {24, 3001}, {24, 61000}, {28, 4}, {32, 0}, {36, 1}, {40, -1}, {40, 257}, {44, 0}, {44, 33}};
        for (int[] change : changes) {
            byte[] value = good.clone(); ByteBuffer.wrap(value).putInt(change[0], change[1]); invalid.add(value);
        }
        invalid.add(frame("remote", "../escape")); invalid.add(frame("remote", "/home/owner/key"));
        invalid.add(frame("remote", "a//b")); invalid.add(Arrays.copyOf(good, good.length + 1));
        invalid.add(Arrays.copyOf(good, 59)); invalid.add(new byte[SshConfigEvaluator.MAX_DIRECT_PLAN_BYTES + 1]);
        for (byte[] value : invalid) {
            Binding binding = new Binding(); binding.response = value;
            try (SshConfigEvaluator evaluator = prepare(binding)) {
                evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                rejected(evaluator::directPlan); binding.clean();
            }
        }
    }
    @Test public void noImplicitLocalUsernameAndOriginalOwnerGuardRemainRequired() throws Exception {
        Binding binding = new Binding(); binding.response = frame("", ".ssh/id");
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            assertTrue(evaluator.directPlan().userRequired); assertEquals("", evaluator.directPlan().user);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread other = new Thread(() -> { try { evaluator.directPlan(); } catch (Throwable failure) { error.set(failure); } });
            other.start(); other.join(); assertTrue(error.get() instanceof IOException);
            binding.during = () -> binding.live = false;
            rejected(evaluator::directPlan); binding.clean();
        }
        Binding ordinary = new Binding(); ordinary.response = frame("remote", ".ssh/id");
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetached(() -> { }, ordinary, false)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run(); rejected(evaluator::directPlan);
            assertEquals(0, ordinary.outputs.size());
        }
    }
    @Test public void gettersAfterCancelAndMissingActualRequestCannotAcquireAuthority() throws Exception {
        Binding binding = new Binding(); binding.response = frame("remote", ".ssh/id");
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            rejected(() -> evaluator.loadDetached(source(), 0, 31000, null));
            assertEquals(0, binding.loaded); rejected(evaluator::directPlan);
        }
        Binding cancelled = new Binding(); cancelled.response = binding.response;
        try (SshConfigEvaluator evaluator = prepare(cancelled)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run(); evaluator.cancel();
            rejected(evaluator::directPlan); assertEquals(0, cancelled.outputs.size());
        }
    }

    @Test public void actualTrustSourceMustBeConsumableBeforeEitherConnectionPreparation() throws Exception {
        VaultManifest.FileObject hosts = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "hosts", ".ssh/known_hosts", "trust", new byte[0], List.of());
        VaultManifest.FileObject second = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "second", "ssh/known_hosts", "second-trust", new byte[0], List.of());
        VaultManifest.FileObject wrong = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "wrong", ".ssh/known_hosts", "wrong-kind", new byte[0], List.of());
        byte[] one = frame("remote");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(one);
        text(new DataOutputStream(bytes), "ssh/known_hosts");
        byte[] multiple = bytes.toByteArray(); ByteBuffer.wrap(multiple).putInt(44, 2);
        for (int mode = 0; mode < 4; mode++) {
            Binding binding = new Binding(); binding.response = mode == 3 ? multiple : one;
            try (VaultManifest manifest = new VaultManifest(List.of(), mode == 0 ? List.of(hosts)
                    : mode == 1 ? List.of() : mode == 2 ? List.of(wrong) : List.of(hosts, second));
                    SshConfigEvaluator evaluator = prepare(binding)) {
                evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                if (mode == 0) assertEquals(".ssh/known_hosts", SshConnectionMaterial.singleTrustPath(manifest, evaluator.directPlan()));
                else try { SshConnectionMaterial.singleTrustPath(manifest, evaluator.directPlan()); fail("Unconsumable trust source"); }
                catch (VaultException expected) {
                    assertEquals(mode == 1 ? "SSH_CONFIG_DEPENDENCY" : "SSH_CONFIG_UNSUPPORTED", expected.code);
                }
            }
        }
    }

    @Test public void everyEffectiveIdentityMustExistEvenWhenAnotherAllowedKeyRemains() throws Exception {
        VaultManifest.FileObject present = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "present", ".ssh/present", "identity", new byte[]{1}, List.of());
        VaultSshMetadata.KeyBinding key = new VaultSshMetadata.KeyBinding("binding", "present", null, List.of(), null);
        try (VaultManifest manifest = new VaultManifest(List.of(), List.of(present), List.of(key), List.of())) {
            for (String[] paths : new String[][]{{".ssh/present"}, {".ssh/missing", ".ssh/present", ".ssh/missing"},
                    {".ssh/present.pub", ".ssh/present"}}) {
                Binding binding = new Binding(); binding.response = frame("remote", paths);
                try (SshConfigEvaluator evaluator = prepare(binding)) {
                    evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                    SshConfigEvaluator.DirectPlan plan = evaluator.directPlan();
                    if (paths.length == 1) {
                        assertTrue(SshConnectionMaterial.missingIdentityPaths(manifest, plan).isEmpty());
                        SshConnectionMaterial.requireIdentities(manifest, plan);
                    } else {
                        assertEquals(List.of(paths[0]), SshConnectionMaterial.missingIdentityPaths(manifest, plan));
                        try { SshConnectionMaterial.requireIdentities(manifest, plan); fail("Silent remaining-key fallback"); }
                        catch (VaultException expected) { assertEquals("SSH_KEY_MISSING", expected.code); }
                    }
                }
            }
            for (boolean outside : List.of(false, true)) {
                String[] paths = outside ? new String[]{"unmanaged-key"} : new String[129];
                if (!outside) for (int i = 0; i < paths.length; i++) paths[i] = ".ssh/missing-" + i;
                Binding binding = new Binding(); binding.response = frame("remote", paths);
                try (SshConfigEvaluator evaluator = prepare(binding)) {
                    evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                    try { SshConnectionMaterial.missingIdentityPaths(manifest, evaluator.directPlan()); fail("Unbounded/unmanaged metadata"); }
                    catch (VaultException expected) {
                        assertEquals(outside ? "SSH_CONFIG_UNSUPPORTED" : "SSH_CONFIG_LIMIT", expected.code);
                    }
                }
            }
        }
    }
}
