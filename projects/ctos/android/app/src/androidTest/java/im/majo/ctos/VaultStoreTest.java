package im.majo.ctos;

import static org.junit.Assert.*;
import android.content.Context;
import android.util.AtomicFile;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Isolated application-owned fixtures. Compile does not establish actual Android recovery behavior. */
public final class VaultStoreTest {
    private File directory;
    private File state;
    private VaultStore store;

    @Before public void createFixture() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = new File(context.getCacheDir(), "vault-test-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        state = new File(directory, "state.bin");
        store = new VaultStore(state);
    }

    @After public void removeFixture() {
        if (directory == null) return;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) assertTrue(child.delete());
            }
            assertTrue(file.delete());
        }
        assertTrue(directory.delete());
    }

    @Test public void interruptedCandidateRecoversCompleteOldGenerationAndWrapper() throws Exception {
        byte[] old = envelope(1);
        byte[] wrapper = new byte[] {1, 2, 3};
        store.commit(old, wrapper, () -> { });
        AtomicFile interrupted = new AtomicFile(state);
        // Simulate an unfinished on-disk candidate, without finishWrite/failWrite.
        try (FileOutputStream stream = interrupted.startWrite()) {
            stream.write(new byte[] {9, 8, 7});
            stream.getFD().sync();
        }
        VaultStore reopened = new VaultStore(state);
        assertArrayEquals(old, reopened.read());
        assertArrayEquals(wrapper, reopened.quickMaterial());
    }

    @Test public void cancellationAfterCandidateSyncRetainsCompleteOldGeneration() throws Exception {
        byte[] old = envelope(1);
        byte[] wrapper = new byte[] {1, 2, 3};
        store.commit(old, wrapper, () -> { });
        try {
            store.commit(envelope(2), null, () -> { throw new VaultException("VAULT_CANCELLED", "fixture cancel"); });
            fail();
        } catch (VaultException error) { assertEquals("VAULT_CANCELLED", error.code); }
        VaultStore reopened = new VaultStore(state);
        assertArrayEquals(old, reopened.read());
        assertArrayEquals(wrapper, reopened.quickMaterial());
    }

    @Test public void successfulSwitchReopensCompleteNewGenerationAndDisabledWrapper() throws Exception {
        store.commit(envelope(1), new byte[] {1, 2, 3}, () -> { });
        byte[] next = envelope(2);
        store.commit(next, null, () -> { });
        VaultStore reopened = new VaultStore(state);
        assertArrayEquals(next, reopened.read());
        assertNull(reopened.quickMaterial());
        VaultEnvelope parsed = VaultEnvelope.parse(reopened.read());
        assertEquals(2, parsed.generation);
        byte[] plaintext = parsed.decrypt(new byte[32]);
        try (VaultManifest manifest = VaultManifest.decode(plaintext)) { assertTrue(manifest.entries.isEmpty()); }
        finally { VaultCodec.wipe(plaintext); }
    }

    @Test public void silentFinishWriteFailureIsNeverReportedAsSuccessfulCommit() throws Exception {
        store.commit(envelope(1), new byte[] {1, 2, 3}, () -> { });
        try {
            store.commit(envelope(2), null, () -> {
                // Force a base-path rename failure after candidate fsync, solely in this test directory.
                assertTrue(state.renameTo(new File(directory, "retained-candidate.bin")));
                assertTrue(state.mkdir());
                try { assertTrue(new File(state, "rename-blocker").createNewFile()); }
                catch (java.io.IOException failure) { throw new AssertionError(failure); }
            });
            fail("Silent AtomicFile rename failures must not authorize a new session");
        } catch (VaultException failure) { assertEquals("VAULT_COMMIT_UNCERTAIN", failure.code); }
    }

    private static byte[] envelope(long generation) throws Exception {
        byte[] plaintext = VaultManifest.empty().encode();
        try { return VaultEnvelope.encrypt(new UUID(0, 1), generation, new byte[16], new byte[32], plaintext).encode(); }
        finally { VaultCodec.wipe(plaintext); }
    }
}
