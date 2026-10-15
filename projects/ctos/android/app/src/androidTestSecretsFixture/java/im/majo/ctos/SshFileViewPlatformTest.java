package im.majo.ctos;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;

/** Exact production JNI bridge, own synthetic in-memory vault; no Activity,
 * disk payload, network, account, Root, clipboard or production vault. */
public final class SshFileViewPlatformTest {
    private static final String METHOD = "im.majo.ctos.SshFileViewPlatformTest#syntheticProtectedFileViews";

    private static final class Store implements AppSecrets.Store {
        byte[] bytes;
        @Override public byte[] read() { return bytes.clone(); }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard) throws VaultException {
            throw new VaultException("FIXTURE", "Synthetic memory store is read-only");
        }
    }

    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id, String path,
            byte[] content, List<String> refs) {
        return new VaultManifest.FileObject(type, id, path, "synthetic-" + id, content, refs);
    }

    private static List<VaultManifest.FileObject> files() {
        return VaultCollections.list(
                file(VaultManifest.FileType.CONFIG, "root", "ssh/config",
                        "Host synthetic\n".getBytes(StandardCharsets.UTF_8), VaultCollections.list("ssh/key", "ssh/known_hosts")),
                file(VaultManifest.FileType.PRIVATE_KEY, "key", "ssh/key",
                        "public-synthetic-private-marker".getBytes(StandardCharsets.UTF_8), VaultCollections.list()),
                file(VaultManifest.FileType.KNOWN_HOSTS, "trust", "ssh/known_hosts",
                        "synthetic-trust\n".getBytes(StandardCharsets.UTF_8), VaultCollections.list()));
    }

    private static SshFileSnapshot snapshot(List<VaultManifest.FileObject> files) throws Exception {
        VaultEnvelope envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 73, new byte[16], new byte[32], new byte[0]);
        return new SshFileSnapshot(new VaultOperation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_FILES,
                "ssh/config", "", "connection-1", envelope, 7, SystemClock.elapsedRealtime() + 120000), files);
    }

    private static void revoked(SshPublicFileLease view) throws Exception {
        assertFalse("Owned lease must be revoked", view.active());
        try { view.summary(); fail("Revoked view cannot be used"); }
        catch (VaultException failure) { assertEquals("VAULT_CHANGED", failure.code); }
        view.close();
    }

    @Test public void syntheticProtectedFileViews() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshFileViewPlatformProbe");
        assertTrue("Strict file-view opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Exact scoped execution required", "true".equals(optIn));
        assertEquals("Only the named file-view method", METHOD, arguments.getString("class"));
        SshMemoryPlatformTest.guard();
        Store store = new Store(); AppSecrets vault = new AppSecrets(store, SystemClock::elapsedRealtime);
        byte[] key = new byte[32];
        List<VaultManifest.FileObject> files = files();
        try (VaultManifest manifest = new VaultManifest(VaultCollections.list(), files)) {
            byte[] plaintext = manifest.encode();
            try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 73, new byte[16], key, plaintext).encode(); }
            finally { VaultCodec.wipe(plaintext); }
        }
        // This test admits known structural strings only; it does not register a
        // production key/config semantic validator or exercise password KDF.
        vault.attachSshValidator(manifest -> {});
        vault.session.foreground(true); vault.session.unlock(key);
        SshVaultAdapter adapter = new SshVaultAdapter(vault, (connection, target) -> {
            throw new VaultException("HOST_KEY", "No private-key approval in this fixture");
        });
        SshPublicFileLease owned = null;
        int cases = 0;
        try {
            owned = adapter.openPublicFiles(adapter.fileRequest("ssh/config", "connection-1"), "ssh/config", "connection-1");
            long[] summary = owned.summary();
            assertArrayEquals("Exact public view metadata", new long[]{3, 1, 31, 73, vault.session.epoch(), summary[5], 0600, 1}, summary);
            assertTrue("Bounded exact anonymous file length", summary[5] > 31 && summary[5] <= VaultEnvelope.MAX_PLAINTEXT);
            cases++;
            try { adapter.privateKeyRequest("ssh/key", "synthetic-key", "target:x", "connection-1"); fail(); }
            catch (VaultException failure) { assertEquals("HOST_KEY", failure.code); } cases++;
            try { adapter.openPublicFiles(adapter.fileRequest("ssh/config", "connection-1"), "ssh/config", "connection-1"); fail(); }
            catch (VaultException failure) { assertEquals("SSH_FILE_VIEW", failure.code); }
            assertTrue(owned.active()); assertEquals(3, owned.summary()[0]); cases++;
            vault.lock(); revoked(owned); cases++;

            vault.session.unlock(key);
            SshPublicFileLease old = owned;
            owned = adapter.openPublicFiles(adapter.fileRequest("ssh/config", "connection-1"), "ssh/config", "connection-1");
            old.close(); assertEquals(3, owned.summary()[0]); cases++;
            Field token = SshPublicFileLease.class.getDeclaredField("token"); token.setAccessible(true);
            long stale = token.getLong(owned); owned.close();
            old = owned;
            owned = adapter.openPublicFiles(adapter.fileRequest("ssh/config", "connection-1"), "ssh/config", "connection-1");
            Class<?> nativeClass = Class.forName("im.majo.ctos.SshPublicFileLease$Native");
            Method destroy = nativeClass.getDeclaredMethod("destroy", long.class); destroy.setAccessible(true);
            assertEquals(Boolean.TRUE, destroy.invoke(null, stale));
            old.close(); assertEquals(3, owned.summary()[0]); cases++;
            vault.session.timeout(0); vault.session.foreground(false); revoked(owned); cases++;
            vault.session.foreground(true); vault.session.unlock(key);
            VaultOperation cancelled = adapter.fileRequest("ssh/config", "connection-1"); adapter.cancel(cancelled);
            try { adapter.openPublicFiles(cancelled, "ssh/config", "connection-1"); fail(); }
            catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); } cases++;
            owned = adapter.openPublicFiles(adapter.fileRequest("ssh/config", "connection-1"), "ssh/config", "connection-1");
            vault.session.unlock(key); revoked(owned); cases++;
            VaultOperation published = adapter.fileRequest("ssh/config", "connection-1");
            owned = adapter.openPublicFiles(published, "ssh/config", "connection-1");
            adapter.cancel(published); revoked(owned); cases++;

            Method create = nativeClass.getDeclaredMethod("create", byte[].class); create.setAccessible(true);
            byte[] valid = SshPublicFileLease.encode(snapshot(files()));
            try {
                for (int mutation = 0; mutation < 4; mutation++) {
                    byte[] malformed = mutation == 3 ? Arrays.copyOf(valid, valid.length + 1) : valid.clone();
                    if (mutation == 0) malformed[0] = 0;
                    if (mutation == 1) malformed[7] = 2;
                    if (mutation == 2) { Arrays.fill(malformed, 40, 48, (byte)0); malformed[47] = 1; }
                    try { create.invoke(null, malformed); fail("Malformed view must be rejected"); }
                    catch (InvocationTargetException failure) { assertTrue(failure.getCause() instanceof IOException); }
                    finally { VaultCodec.wipe(malformed); }
                    cases++;
                }
            } finally { VaultCodec.wipe(valid); }
            owned = SshPublicFileLease.create(snapshot(files())); assertEquals(3, owned.summary()[0]); owned.close(); cases++;

            List<VaultManifest.FileObject> many = new ArrayList<>(); List<String> refs = new ArrayList<>();
            for (int i = 1; i < 128; i++) refs.add("ssh/p" + i);
            many.add(file(VaultManifest.FileType.CONFIG, "root", "ssh/config", new byte[0], refs));
            for (int i = 1; i < 128; i++) many.add(file(VaultManifest.FileType.PUBLIC_KEY, "p" + i, "ssh/p" + i,
                    new byte[0], VaultCollections.list()));
            owned = SshPublicFileLease.create(snapshot(many)); assertEquals(128, owned.summary()[0]); owned.close(); cases++;
            List<VaultManifest.FileObject> maximum = new ArrayList<>(); refs.clear();
            for (int i = 1; i < 8; i++) refs.add("ssh/p" + i);
            byte[] block = new byte[VaultManifest.MAX_FILE_BYTES]; Arrays.fill(block, (byte)'x');
            maximum.add(file(VaultManifest.FileType.CONFIG, "root", "ssh/config", block, refs));
            for (int i = 1; i < 8; i++) maximum.add(file(VaultManifest.FileType.PUBLIC_KEY, "p" + i,
                    "ssh/p" + i, block, VaultCollections.list()));
            try {
                owned = SshPublicFileLease.create(snapshot(maximum));
                assertEquals(VaultManifest.MAX_TOTAL_FILE_BYTES, owned.summary()[2]); cases++;
            } finally {
                if (owned != null) owned.close(); VaultCodec.wipe(block);
                for (VaultManifest.FileObject file : maximum) VaultCodec.wipe(file.content);
            }
            assertEquals("Complete file-view matrix", 17, cases);
            Log.i("CtosSshFileViewFixture", "phase=public_view_gate state=pass cases=" + cases + " private_release=false");
        } finally {
            try { if (owned != null) owned.close(); }
            finally {
                vault.lock(); VaultCodec.wipe(store.bytes); VaultCodec.wipe(key);
                SshMemoryPlatformTest.guard();
            }
        }
    }
}
