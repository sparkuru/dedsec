package im.majo.ctos;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.Test;
import org.json.JSONObject;

/** Opt-in real-provider check. No MainActivity, production vault owner, authentication or Keystore. */
public final class RealSafExportProviderTest {
    private static final int MODES = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final int MAX_FIXTURE_BYTES = 131072;
    private static final String BACKUP_NAME = "isolated-backup.ctvault";
    private static final String TREE_PREFIX = "primary:Download/ctos/ctos-saf-";
    private static final Pattern PRIVATE_FIXTURE = Pattern.compile("ctos-real-saf-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final VaultExportTransaction.Guard LIVE = () -> { };
    private static final class CheckingJournal implements VaultExportTransaction.Journal {
        final File directory;
        final VaultExportJournal actual;
        int saves, clears;
        CheckingJournal(File directory) { this.directory = directory; actual = new VaultExportJournal(directory); }
        @Override public void save(VaultExportTransaction.State state) throws Exception {
            actual.save(state);
            // Every real transition must also be accepted by the fresh restart decoder.
            VaultExportTransaction.State loaded = new VaultExportJournal(directory).load(); assertNotNull(loaded);
            assertEquals(state.id, loaded.id); assertEquals(state.tree, loaded.tree); assertEquals(state.location, loaded.location);
            assertEquals(state.name, loaded.name); assertEquals(state.phase, loaded.phase); assertEquals(state.digest, loaded.digest);
            assertEquals(state.length, loaded.length); assertEquals(state.pendingRole, loaded.pendingRole); assertEquals(state.pendingName, loaded.pendingName);
            assertEquals(state.finalVerified, loaded.finalVerified); assertEquals(state.oldRenameStarted, loaded.oldRenameStarted);
            sameRole(state.original, loaded.original); sameRole(state.stage, loaded.stage);
            sameRole(state.rollback, loaded.rollback); sameRole(state.finalFile, loaded.finalFile); saves++;
        }
        @Override public void clear() throws Exception {
            actual.clear(); assertNull(new VaultExportJournal(directory).load()); clears++;
        }
        private static void sameRole(VaultExportTransaction.Document expected, VaultExportTransaction.Document observed) {
            if (expected == null) assertNull(observed);
            else { assertTrue(expected.sameVersion(observed)); assertEquals(expected.location, observed.location); }
        }
    }

    private static final class PickerLease {
        final String nonce = UUID.randomUUID().toString();
        final CountDownLatch selected = new CountDownLatch(1), closed = new CountDownLatch(1), releaseReady = new CountDownLatch(1);
        volatile Uri tree;
        volatile ResultReceiver release;
        volatile boolean accepted, revoked, launched;
        volatile boolean invalidReply;
        final ResultReceiver callback = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int code, Bundle value) {
                try {
                    if (value != null) value.setClassLoader(ResultReceiver.class.getClassLoader());
                    if (value == null || !nonce.equals(value.getString(ExportTreePickerActivity.NONCE))) return;
                    if (code == ExportTreePickerActivity.WAITING) { release = value.getParcelable("release"); releaseReady.countDown(); }
                    else if (code == ExportTreePickerActivity.GRANTED && (value.getInt("modes") & MODES) == MODES) {
                        tree = value.getParcelable("tree"); accepted = true; selected.countDown();
                    } else if (code == ExportTreePickerActivity.REJECTED || code == ExportTreePickerActivity.RELEASED) {
                        revoked = value.getBoolean("revoked"); selected.countDown(); closed.countDown();
                    }
                } catch (RuntimeException malformed) { invalidReply = true; accepted = false; selected.countDown(); releaseReady.countDown(); }
            }
        };
        void close() throws Exception {
            if (launched && closed.getCount() != 0 && !releaseReady.await(5, TimeUnit.SECONDS))
                throw new IOException("Fixture picker release handshake missing");
            ResultReceiver end = release;
            if (end != null && closed.getCount() != 0) {
                Bundle value = new Bundle(); value.putString(ExportTreePickerActivity.NONCE, nonce);
                end.send(ExportTreePickerActivity.RELEASE, value);
                if (!closed.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture picker did not close");
            }
            if (end != null && !revoked) throw new IOException("Fixture URI grant was not revoked");
            if (invalidReply) throw new IOException("Fixture callback could not be decoded");
        }
    }
    private static String expectedTreeId() {
        String expected = InstrumentationRegistry.getArguments().getString(ExportTreePickerActivity.EXPECTED_ID);
        org.junit.Assume.assumeTrue("Requires an explicit freshly owned UUID leaf", expected != null);
        assertTrue("Unsafe expectedTreeId", ExportTreePickerActivity.validExpectedId(expected));
        return expected;
    }
    private static void revokeExactTree(Context target, String expected) {
        Uri tree = DocumentsContract.buildTreeDocumentUri(ExportTreePickerActivity.AUTHORITY, expected);
        target.revokeUriPermission(ExportTreePickerActivity.TARGET, tree, MODES);
        for (int mode : new int[]{Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION})
            assertEquals("Exact fixture grant remained", PackageManager.PERMISSION_DENIED,
                    target.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(), mode));
    }
    @Test public void revokeExactTreeAndReportOrphanCandidates() throws Exception {
        String expected = expectedTreeId();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        revokeExactTree(target, expected);
        System.out.println("Real SAF fixture stage=exact_tree_revoked");
        File cache = target.getCacheDir().getCanonicalFile();
        File[] candidates = cache.listFiles((parent, name) -> PRIVATE_FIXTURE.matcher(name).matches());
        assertNotNull(candidates); assertTrue("Too many fixture candidates", candidates.length <= 16);
        for (File candidate : candidates) {
            if (!candidate.isDirectory() || !candidate.getCanonicalFile().equals(new File(cache, candidate.getName()))) {
                System.out.println("Real SAF orphan candidate=" + candidate.getName() + " layout=unverified"); continue;
            }
            String[] names = candidate.list(); assertNotNull(names);
            boolean exact = names.length <= 3;
            for (String name : names) if (!Arrays.asList("vault.bin", "vault.bin.new", "vault.bin.bak").contains(name)) exact = false;
            System.out.println("Real SAF orphan candidate=" + candidate.getAbsolutePath() + " modified=" + candidate.lastModified()
                    + " layout_exact=" + exact + (exact ? " members=" + Arrays.toString(names) : ""));
            if (!exact) continue;
            for (String suffix : new String[]{"", ".new", ".bak"}) {
                File record = new File(candidate, "vault.bin" + suffix);
                if (!record.isFile() || !record.getCanonicalFile().equals(record.getAbsoluteFile())) continue;
                byte[] header = new byte[8 + VaultEnvelope.HEADER_BYTES]; int count = 0;
                try (FileInputStream input = new FileInputStream(record)) {
                    while (count < header.length) { int got = input.read(header, count, header.length - count); if (got <= 0) break; count += got; }
                }
                java.nio.ByteBuffer fields = java.nio.ByteBuffer.wrap(header);
                boolean recognizable = count == header.length && fields.getInt() == 0x43545331;
                int length = recognizable ? fields.getInt() : 0;
                recognizable &= length >= VaultEnvelope.HEADER_BYTES + 16 && length <= VaultEnvelope.MAX_BYTES;
                boolean envelope = recognizable && fields.getLong() == 0x43544f53564c5431L;
                boolean noWrapperSpace = recognizable && record.length() == 12L + length;
                System.out.println("Real SAF orphan record=" + candidate.getName() + "/vault.bin" + suffix
                        + " bytes=" + record.length() + " modified=" + record.lastModified() + " container_header=" + recognizable
                        + " envelope_header=" + envelope + " no_wrapper_space=" + noWrapperSpace);
                VaultCodec.wipe(header);
            }
        }
        // Metadata/header recognition is not ownership proof. This preflight NEVER deletes a candidate.
    }
    @Test public void removeApprovedCrashFixture() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String id = arguments.getString("cleanupFixtureId");
        org.junit.Assume.assumeTrue("Requires the explicitly approved crash fixture", id != null);
        assertTrue(PRIVATE_FIXTURE.matcher("ctos-real-saf-" + id).matches());
        assertEquals("3e0c2b6c-d365-4335-8db1-a4f1550518a7", id);
        assertEquals("217", arguments.getString("expectedSize"));
        assertEquals("1791606899000", arguments.getString("expectedMtime"));
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(ExportTreePickerActivity.TARGET, target.getPackageName());
        File cache = target.getCacheDir().getCanonicalFile();
        File directory = new File(cache, "ctos-real-saf-" + id);
        File record = new File(directory, "vault.bin");
        checkApprovedCrashLayout(cache, directory, record);
        byte[] header = new byte[8 + VaultEnvelope.HEADER_BYTES];
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(record, "r")) {
            input.readFully(header);
            java.nio.ByteBuffer fields = java.nio.ByteBuffer.wrap(header);
            assertEquals(0x43545331, fields.getInt());
            int length = fields.getInt();
            assertTrue(length >= VaultEnvelope.HEADER_BYTES + 16 && length <= VaultEnvelope.MAX_BYTES);
            assertEquals(217L, 12L + length);
            assertEquals(0x43544f53564c5431L, fields.getLong());
            assertEquals(1, fields.getInt()); assertEquals(1, fields.getInt());
            assertEquals(VaultEnvelope.MEMORY_KIB, fields.getInt());
            assertEquals(VaultEnvelope.ITERATIONS, fields.getInt()); assertEquals(VaultEnvelope.LANES, fields.getInt());
            assertEquals(0x13, fields.getInt());
            fields.position(56); assertTrue(fields.getLong() >= 1);
            fields.position(92); assertEquals(length - VaultEnvelope.HEADER_BYTES, fields.getInt());
            // Read only the public wrapper-length word, seeking over all ciphertext without reading it.
            input.seek(8L + length); assertEquals(0, input.readInt()); assertEquals(217L, input.getFilePointer());
        } finally { VaultCodec.wipe(header); }
        // Recheck the exact approved metadata/sole member immediately before deleting this file.
        checkApprovedCrashLayout(cache, directory, record);
        assertTrue("Approved encrypted fixture could not be deleted", record.delete());
        assertFalse(record.exists());
        String[] remaining = directory.list(); assertNotNull(remaining); assertEquals(0, remaining.length);
        assertTrue("Approved empty fixture directory could not be removed", directory.delete());
        assertFalse(directory.exists()); assertFalse(record.exists());
        System.out.println("Real SAF cleanup stage=approved_exact_crash_fixture_removed");
    }
    private static void checkApprovedCrashLayout(File cache, File directory, File record) throws Exception {
        assertEquals(cache, directory.getCanonicalFile().getParentFile());
        assertEquals(directory.getAbsoluteFile(), directory.getCanonicalFile());
        assertTrue(directory.isDirectory());
        String[] names = directory.list(); assertNotNull(names); assertEquals(1, names.length); assertEquals("vault.bin", names[0]);
        assertEquals(record.getAbsoluteFile(), record.getCanonicalFile()); assertTrue(record.isFile());
        assertEquals(217L, record.length()); assertEquals(1791606899000L, record.lastModified());
    }
    private static byte[] read(InputStream input) throws Exception {
        try (InputStream owned = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = owned.read(buffer)) != -1) {
                if (count == 0 || output.size() + count > MAX_FIXTURE_BYTES) throw new IOException("Unbounded fixture bytes");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    private static VaultExportTransaction.Document byId(List<VaultExportTransaction.Document> list, String id) {
        for (VaultExportTransaction.Document value : list) if (value.id.equals(id)) return value;
        return null;
    }

    /** Records returned current IDs/versions, including path-ID changes and reuse after rename. */
    private static final class RecordingProvider implements VaultExportTransaction.Provider {
        final SafExportProvider actual;
        final Map<String, Owned> owned = new LinkedHashMap<>();
        final java.util.Set<String> writable = new java.util.HashSet<>();
        byte[] candidate;
        String lastRollbackId;
        boolean failNextStage, failNextFinal;
        String injectedId;
        int injectedBytes, observedPartialBytes;
        RecordingProvider(SafExportProvider actual) { this.actual = actual; }
        static final class Owned {
            VaultExportTransaction.Document version;
            final byte[] content;
            boolean complete;
            Owned(VaultExportTransaction.Document version, byte[] content) { this.version = version; this.content = content; }
        }
        void begin(byte[] source) {
            candidate = source; writable.clear(); lastRollbackId = null;
            failNextStage = false; failNextFinal = false; injectedId = null; injectedBytes = 0; observedPartialBytes = 0;
        }
        @Override public String tree() { return actual.tree(); }
        @Override public String location() { return actual.location(); }
        @Override public boolean canCreate() { return actual.canCreate(); }
        @Override public List<VaultExportTransaction.Document> children() throws Exception { return actual.children(); }
        @Override public VaultExportTransaction.Document create(String name) throws Exception {
            List<VaultExportTransaction.Document> before = children();
            VaultExportTransaction.Document made = actual.create(name);
            if (byId(before, made.id) != null || owned.containsKey(made.id)) throw new IOException("Provider reused an extant ID");
            owned.put(made.id, new Owned(made, candidate)); writable.add(made.id);
            if ((failNextStage && name.startsWith(".ctos-stage-")) || (failNextFinal && name.equals(BACKUP_NAME))) injectedId = made.id;
            return made;
        }
        private Owned verified(VaultExportTransaction.Document document) throws Exception {
            Owned evidence = owned.get(document.id);
            VaultExportTransaction.Document current = byId(children(), document.id);
            if (evidence == null || current == null || !evidence.version.sameVersion(current)
                    || !document.sameVersion(current)) throw new IOException("Fixture ownership/version changed");
            byte[] bytes = RealSafExportProviderTest.read(actual.read(current));
            try {
                if (bytes.length > evidence.content.length || evidence.complete && bytes.length != evidence.content.length)
                    throw new IOException("Fixture content changed");
                for (int i = 0; i < bytes.length; i++) if (bytes[i] != evidence.content[i]) throw new IOException("Fixture content changed");
            } finally { VaultCodec.wipe(bytes); }
            return evidence;
        }
        @Override public VaultExportTransaction.Document rename(VaultExportTransaction.Document document, String name) throws Exception {
            Owned evidence = verified(document);
            List<VaultExportTransaction.Document> before = children();
            VaultExportTransaction.Document renamed = actual.rename(document, name);
            if (!renamed.id.equals(document.id) && byId(before, renamed.id) != null) throw new IOException("Rename reused an extant ID");
            owned.remove(document.id); writable.remove(document.id);
            evidence.version = renamed; owned.put(renamed.id, evidence);
            verified(renamed);
            lastRollbackId = renamed.id;
            return renamed;
        }
        @Override public void delete(VaultExportTransaction.Document document) throws Exception {
            verified(document); actual.delete(document);
            if (byId(children(), document.id) != null) throw new IOException("Deleted fixture still present");
            owned.remove(document.id); writable.remove(document.id);
        }
        @Override public InputStream read(VaultExportTransaction.Document document) throws Exception {
            if (!owned.containsKey(document.id)) throw new IOException("Not a returned fixture ID");
            return actual.read(document);
        }
        @Override public OutputStream write(VaultExportTransaction.Document document) throws Exception {
            if (!writable.contains(document.id)) throw new IOException("Attempt to write a preexisting document");
            Owned evidence = owned.get(document.id);
            return new FilterOutputStream(actual.write(document)) {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if ((failNextStage || failNextFinal) && document.id.equals(injectedId)) {
                        failNextStage = false; failNextFinal = false;
                        injectedBytes = Math.max(1, Math.min(length, evidence.content.length / 2));
                        out.write(bytes, offset, injectedBytes); out.flush();
                        throw new IOException("Injected failure after owned partial write");
                    }
                    out.write(bytes, offset, length);
                }
                @Override public void close() throws IOException {
                    try { super.close(); }
                    finally {
                        try {
                            VaultExportTransaction.Document current = byId(children(), document.id);
                            if (current != null && current.name.equals(evidence.version.name)) evidence.version = current;
                            byte[] bytes = RealSafExportProviderTest.read(actual.read(current));
                            if (document.id.equals(injectedId)) observedPartialBytes = bytes.length;
                            evidence.complete = Arrays.equals(bytes, evidence.content); VaultCodec.wipe(bytes);
                        } catch (Exception failure) { throw new IOException("Cannot confirm owned write", failure); }
                    }
                }
            };
        }
        void cleanup() throws Exception {
            IOException failures = null;
            // Snapshot current roles, not stale original paths. Every role is attempted independently.
            for (Owned evidence : new ArrayList<>(owned.values())) {
                try {
                    VaultExportTransaction.Document current = byId(children(), evidence.version.id);
                    if (current != null) { verified(current); delete(current); }
                } catch (Exception | Error failure) {
                    if (failures == null) failures = new IOException("Exact owned fixture cleanup incomplete");
                    failures.addSuppressed(failure);
                }
            }
            if (failures != null) throw failures;
        }
    }

    @Test(timeout = 120000) public void realTreeNewDeclinedConfirmedAndCancelledExports() throws Exception {
        String expected = expectedTreeId();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        revokeExactTree(target, expected);
        File directory = new File(target.getCacheDir(), "ctos-real-saf-" + expected.substring(TREE_PREFIX.length()));
        assertTrue("Private fixture must be new", directory.mkdir());
        System.out.println("Real SAF fixture private_path=" + directory.getAbsolutePath());
        File record = new File(directory, "vault.bin");
        CheckingJournal journal = new CheckingJournal(directory);
        AppSecrets own = new AppSecrets(new VaultStore(record), SystemClock::elapsedRealtime);
        char[] password = ("isolated-real-saf-" + UUID.randomUUID()).toCharArray();
        byte[] first = null, next = null;
        PickerLease lease = new PickerLease(); RecordingProvider provider = null;
        Throwable primary = null;
        try {
            JSONObject marker = new JSONObject(); marker.put("schema", 1); marker.put("fixture", "RealSafExportProviderTest");
            marker.put("expectedTreeId", expected); marker.put("nonce", lease.nonce); marker.put("created", System.currentTimeMillis());
            File owner = new File(directory, "fixture-owner.json"); assertTrue(owner.createNewFile());
            try (FileOutputStream output = new FileOutputStream(owner)) {
                output.write(marker.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.flush(); output.getFD().sync();
            }
            own.session.foreground(true); own.create(password, password, () -> { });
            first = own.export(password, () -> { });
            TotpVaultAdapter totp = new TotpVaultAdapter(own);
            byte[] seed = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            try { totp.save(totp.saveRequest("isolated-saf"), "isolated-saf", "Fixture:synthetic", "Fixture", seed, () -> { }); }
            finally { VaultCodec.wipe(seed); }
            next = own.export(password, () -> { }); assertFalse(Arrays.equals(first, next));
            target.startActivity(new Intent().setClassName(test.getPackageName(), ExportTreePickerActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ExportTreePickerActivity.EXPECTED_ID, expected)
                    .putExtra(ExportTreePickerActivity.NONCE, lease.nonce)
                    .putExtra(ExportTreePickerActivity.CALLBACK, ExportTreePickerActivity.frameworkReceiver(lease.callback)));
            lease.launched = true;
            System.out.println("Real SAF fixture stage=picker_waiting");
            assertTrue("Picker lease expired", lease.selected.await(90, TimeUnit.SECONDS));
            assertTrue("Wrong folder, cancellation or unavailable grant", lease.accepted);
            assertEquals(DocumentsContract.buildTreeDocumentUri(ExportTreePickerActivity.AUTHORITY, expected), lease.tree);
            provider = new RecordingProvider(new SafExportProvider(target.getContentResolver(), lease.tree));
            assertTrue("The newly owned leaf must be empty", provider.children().isEmpty());
            assertTrue("Real provider cannot create", provider.canCreate());
            String name = BACKUP_NAME;

            provider.begin(first);
            VaultExportTransaction initial = new VaultExportTransaction(provider, journal, name, first);
            assertEquals(false, initial.prepare(LIVE).get("collision"));
            assertReceipt("exported", initial.commit(false, LIVE));
            assertOnly(provider, initial.state.finalFile.id, first);
            verifyCipher(provider, initial.state.finalFile.id, password, 0);
            System.out.println("Real SAF fixture stage=new_export_passed");

            provider.begin(next);
            VaultExportTransaction declined = new VaultExportTransaction(provider, journal, name, next);
            Map<String, Object> preview = declined.prepare(LIVE);
            assertEquals(true, preview.get("collision")); assertEquals(true, preview.get("overwriteAvailable"));
            VaultExportTransaction.Document preserved = declined.state.original;
            assertArrayEquals(first, read(provider.read(preserved)));
            assertReceipt("cancelled", declined.commit(false, LIVE));
            assertOnly(provider, preserved.id, first);
            assertTrue(preserved.sameVersion(byId(provider.children(), preserved.id)));
            System.out.println("Real SAF fixture stage=declined_overwrite_passed");

            provider.begin(next);
            VaultExportTransaction confirmed = new VaultExportTransaction(provider, journal, name, next);
            assertEquals(true, confirmed.prepare(LIVE).get("collision"));
            assertArrayEquals(first, read(provider.read(confirmed.state.original)));
            String historicOriginalId = confirmed.state.original.id;
            String[] returnedRollbackId = {null};
            RecordingProvider observedProvider = provider;
            byte[] historicCipher = first;
            VaultExportTransaction.Guard beforeCleanup = new VaultExportTransaction.Guard() {
                @Override public void check() { }
                @Override public void finalVerified() throws Exception {
                    assertNotNull(confirmed.state.rollback);
                    returnedRollbackId[0] = confirmed.state.rollback.id;
                    assertEquals(observedProvider.lastRollbackId, returnedRollbackId[0]);
                    assertArrayEquals(historicCipher, read(observedProvider.read(confirmed.state.rollback)));
                }
            };
            assertReceipt("exported", confirmed.commit(true, beforeCleanup));
            assertNotNull(returnedRollbackId[0]);
            // ExternalStorageProvider may reuse the old path ID AFTER the old object was renamed.
            assertOnly(provider, confirmed.state.finalFile.id, next);
            assertNull(byId(provider.children(), returnedRollbackId[0]));
            verifyCipher(provider, confirmed.state.finalFile.id, password, 1);
            System.out.println("Real SAF fixture path_id_reused=" + historicOriginalId.equals(confirmed.state.finalFile.id));
            System.out.println("Real SAF fixture stage=confirmed_overwrite_passed");

            provider.begin(first);
            VaultExportTransaction cancelled = new VaultExportTransaction(provider, journal, name, first);
            assertEquals(true, cancelled.prepare(LIVE).get("collision"));
            VaultExportTransaction.Document current = cancelled.state.original;
            assertReceipt("cancelled", cancelled.cancel());
            assertOnly(provider, current.id, next);
            assertTrue(current.sameVersion(byId(provider.children(), current.id)));
            System.out.println("Real SAF fixture stage=cancelled_preview_passed");

            provider.begin(first); provider.failNextStage = true;
            VaultExportTransaction broken = new VaultExportTransaction(provider, journal, name, first);
            VaultExportTransaction.Document baseline = byId(provider.children(), current.id);
            try { broken.prepare(LIVE); fail("Partial stage write must fail"); }
            catch (IOException injected) {
                assertTrue(provider.injectedBytes > 0 && provider.injectedBytes < first.length);
                assertEquals(provider.injectedBytes, provider.observedPartialBytes);
                assertReceipt("failed", broken.failed(injected));
            }
            assertOnly(provider, current.id, next);
            assertTrue(baseline.sameVersion(byId(provider.children(), current.id)));
            assertNull(byId(provider.children(), provider.injectedId));
            assertNull(new VaultExportJournal(directory).load());
            System.out.println("Real SAF fixture stage=partial_write_compensated_passed");

            provider.begin(first); provider.failNextFinal = true;
            VaultExportTransaction brokenFinal = new VaultExportTransaction(provider, journal, name, first);
            VaultExportTransaction.Document originalFinal = byId(provider.children(), current.id);
            assertEquals(true, brokenFinal.prepare(LIVE).get("collision"));
            assertReceipt("failed", brokenFinal.commit(true, LIVE));
            assertTrue(provider.injectedBytes > 0 && provider.injectedBytes < first.length);
            assertEquals(provider.injectedBytes, provider.observedPartialBytes);
            // The failed new final may share the original's path ID. Current role retirement must
            // precede restoring the old bytes to that same ID; no stale role may delete the restore.
            assertOnly(provider, originalFinal.id, next);
            VaultExportTransaction.Document restored = byId(provider.children(), originalFinal.id);
            assertEquals(originalFinal.name, restored.name); assertEquals(Long.valueOf(next.length), restored.bytes);
            assertNotNull(provider.owned.get(restored.id));
            assertTrue(provider.owned.get(restored.id).version.sameVersion(restored));
            assertTrue(journal.saves > 0); assertEquals(6, journal.clears);
            assertNull(new VaultExportJournal(directory).load());
            System.out.println("Real SAF fixture stage=partial_final_restored_passed");
        } catch (Exception | Error failure) { primary = failure; throw failure; }
        finally {
            Throwable cleanup = null;
            if (provider != null) {
                try { provider.cleanup(); assertTrue("Fixture leaf must end empty", provider.children().isEmpty()); }
                catch (Exception | Error failure) { cleanup = append(cleanup, failure); }
            }
            own.lock(); VaultCodec.wipe(first); VaultCodec.wipe(next); Arrays.fill(password, '\0');
            // These exact AtomicFile variants are inside the freshly created private fixture only.
            for (File base : new File[]{record, new File(directory, "vault-export-journal.json")}) {
                for (String suffix : new String[]{"", ".new", ".bak"}) {
                    File owned = new File(base.getPath() + suffix);
                    if (owned.exists() && !owned.delete()) cleanup = append(cleanup, new IOException("Private fixture record remained"));
                }
            }
            File owner = new File(directory, "fixture-owner.json");
            if (owner.exists() && !owner.delete()) cleanup = append(cleanup, new IOException("Private fixture marker remained"));
            if (!directory.delete()) cleanup = append(cleanup, new IOException("Private fixture directory remained"));
            try { revokeExactTree(target, expected); } catch (Exception | Error failure) { cleanup = append(cleanup, failure); }
            try { lease.close(); } catch (Exception | Error failure) { cleanup = append(cleanup, failure); }
            if (cleanup != null) {
                if (primary != null) primary.addSuppressed(cleanup);
                else if (cleanup instanceof Exception) throw (Exception) cleanup;
                else throw (Error) cleanup;
            }
        }
    }
    private static Throwable append(Throwable previous, Throwable next) {
        if (previous == null) return next;
        previous.addSuppressed(next); return previous;
    }
    private static void assertReceipt(String status, Map<String, Object> receipt) {
        assertEquals(status, receipt.get("status"));
        assertTrue(((List<?>) receipt.get("remaining")).isEmpty());
    }
    private static void assertOnly(RecordingProvider provider, String id, byte[] expected) throws Exception {
        List<VaultExportTransaction.Document> children = provider.children(); assertEquals(1, children.size());
        assertEquals(id, children.get(0).id); assertArrayEquals(expected, read(provider.read(children.get(0))));
    }
    private static void verifyCipher(RecordingProvider provider, String id, char[] password, int entries) throws Exception {
        byte[] bytes = read(provider.read(byId(provider.children(), id)));
        try (AppSecrets.Verified verified = AppSecrets.verify(bytes, password)) { assertEquals(entries, verified.manifest.entries.size()); }
        finally { VaultCodec.wipe(bytes); }
    }
}
