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
                {24, 0}, {24, 3001}, {24, 61000}, {28, 1}, {32, 0}, {36, 1}, {40, -1}, {40, 257}, {44, 0}, {44, 33}};
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
