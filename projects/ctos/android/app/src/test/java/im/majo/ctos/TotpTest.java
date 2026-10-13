package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;

/** Offline JVM evidence; Android camera/SAF/window behavior is not established here. */
public final class TotpTest {
    static final String SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    static final String URI = "otpauth://totp/Example%3Aalice%2Btest%40example.com?secret=" + SEED + "&issuer=Example";
    static final AppSecrets.EffectGuard OK = () -> { };
    static final char[] PASSWORD = "synthetic TOTP fixture password".toCharArray();
    static byte[] encrypted;
    private AppSecrets vault;
    private Store store;
    private FakeClock clock;
    private TotpPreviews previews;
    private static final class FakeClock implements VaultSession.Clock { long time; public long now() { return time; } }
    private static final class Store implements AppSecrets.Store {
        byte[] bytes;
        int reads;
        Runnable admission;
        public byte[] read() { reads++; return bytes.clone(); }
        public byte[] quickMaterial() { return null; }
        public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard) throws IOException, VaultException {
            guard.beforeCommit(); if (admission != null) admission.run(); bytes = next.clone();
        }
    }
    interface Action { void run() throws Exception; }
    static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); } catch (VaultException error) { assertEquals(code, error.code); }
    }
    @BeforeClass public static void fixture() throws Exception {
        byte[] salt = new byte[16], key = VaultEnvelope.derive(PASSWORD, salt);
        try (VaultManifest manifest = new VaultManifest(VaultCollections.list(new VaultManifest.Entry(
                VaultManifest.EntryType.TOTP, "existing", "Example:original", "Original", "SHA1", 6, 30,
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII))), VaultCollections.list())) {
            byte[] plain = manifest.encode();
            try { encrypted = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        } finally { VaultCodec.wipe(key); }
    }
    @Before public void setup() throws Exception {
        store = new Store(); store.bytes = encrypted.clone(); clock = new FakeClock();
        vault = new AppSecrets(store, clock); vault.session.foreground(true); vault.unlock(PASSWORD, OK);
        previews = new TotpPreviews(vault, clock);
    }
    @Test public void rfc6238Sha1VectorsForApprovedSixDigitProfile() throws Exception {
        long[] time = {59, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < time.length; i++) assertEquals(expected[i], AppSecrets.generateTotp(seed, time[i]));
        assertEquals(AppSecrets.generateTotp(seed, 30), AppSecrets.generateTotp(seed, 59));
        assertNotEquals(AppSecrets.generateTotp(seed, 59), AppSecrets.generateTotp(seed, 60));
        rejects("PARAMETERS", () -> AppSecrets.generateTotp(seed, -1));
    }
    @Test public void uriPreservesEncodedAccountPlusAndIssuer() throws Exception {
        try (TotpInput input = TotpInput.uri(URI)) {
            assertEquals("Example:alice+test@example.com", input.identity);
            assertEquals("alice+test@example.com", input.label);
            assertArrayEquals("12345678901234567890".getBytes(StandardCharsets.US_ASCII), input.seed);
        }
        try (TotpInput input = TotpInput.uri("otpauth://totp/alice+test?secret=" + SEED)) { assertEquals("alice+test", input.identity); }
    }
    @Test public void unsupportedParametersAndMalformedUrisAreRejected() throws Exception {
        for (String text : new String[] {
                URI.replace("/totp/", "/hotp/"), URI.replace("otpauth:", "https:"), URI + "&secret=" + SEED,
                URI + "&%73ecret=" + SEED, URI + "&algorithm=SHA256", URI + "&digits=8", URI + "&period=60",
                URI + "&counter=0", URI + "#fragment", URI.replace("issuer=Example", "issuer=Other"),
                URI.replace("alice%2B", "alice%C0%AF"), URI.replace("alice%2B", "alice%zz"),
                URI.replace("alice%2B", "alice\ud800"),
                "otpauth://totp/Example%3Aa%3Ab?secret=" + SEED, "otpauth://totp/a?issuer=X",
                "otpauth://totp/a?secret=" + SEED + "&", "otpauth://totp:80/a?secret=" + SEED,
                "otpauth://totp/a?secret=" + SEED + "&algorithm=sha1", "a".repeat(8193)}) rejects("TOTP_INPUT", () -> TotpInput.uri(text));
    }
    @Test public void base32RejectsUnicodePaddingAndNoncanonicalResidualBits() throws Exception {
        for (String text : new String[] {"ı" + SEED.substring(1), "ß" + SEED.substring(2), SEED + "=", SEED + "1", SEED + "A", "A".repeat(209), "MZXW6===", "A".repeat(16) + "B"})
            rejects("TOTP_INPUT", () -> TotpInput.base32(text));
        assertArrayEquals(TotpInput.base32(SEED), TotpInput.base32(SEED.toLowerCase(java.util.Locale.ROOT)));
        assertArrayEquals(TotpInput.base32("AAAAAAAAAAAAAAAAAA"), TotpInput.base32("AAAAAAAAAAAAAAAAAA======"));
    }
    @Test public void metadataRejectsControlsAndMalformedUnicode() throws Exception {
        for (String label : new String[]{"", "a\n", "a\u202e", "\ud800", "界".repeat(171)}) rejects("TOTP_INPUT", () -> TotpInput.metadata(label, false));
    }
    @SuppressWarnings("unchecked") Map<String, Object> candidate(Map<String, Object> preview) { return ((List<Map<String, Object>>) preview.get("candidates")).get(0); }
    Map<String, Object> prepare() throws Exception { return previews.prepare("preview-request", VaultCollections.list(TotpInput.uri(URI)), 0, OK); }
    @Test public void previewProjectionHasNoSecretAndExplicitCommitAddsWithoutReplacing() throws Exception {
        byte[] before = store.bytes.clone(); Map<String, Object> preview = prepare();
        assertArrayEquals(before, store.bytes);
        assertEquals(VaultCollections.set("candidateId", "identity", "label", "algorithm", "digits", "period"), candidate(preview).keySet());
        String entry = previews.commit((String) preview.get("previewId"), (String) candidate(preview).get("candidateId"), OK);
        assertNotEquals("existing", entry);
        Map<String, Object> list = vault.totpList(vault.beginTotpList(), 59);
        assertEquals(2, ((List<?>) list.get("entries")).size());
        assertEquals("287082", vault.totpCode(vault.beginTotp("existing"), "existing", 59));
        rejects("VAULT_CHANGED", () -> previews.commit((String) preview.get("previewId"), (String) candidate(preview).get("candidateId"), OK));
    }
    @Test public void cancelledExpiredAndLockedPreviewsNeverWrite() throws Exception {
        Map<String, Object> preview = prepare(); byte[] before = store.bytes.clone();
        previews.cancel((String) preview.get("previewId"));
        rejects("VAULT_CHANGED", () -> previews.commit((String) preview.get("previewId"), (String) candidate(preview).get("candidateId"), OK));
        final Map<String, Object> expired = prepare(); clock.time += 120000;
        rejects("VAULT_EXPIRED", () -> previews.commit((String) expired.get("previewId"), (String) candidate(expired).get("candidateId"), OK));
        Map<String, Object> locked = prepare(); vault.lock();
        rejects("VAULT_CHANGED", () -> previews.commit((String) locked.get("previewId"), (String) candidate(locked).get("candidateId"), OK));
        assertArrayEquals(before, store.bytes);
    }
    @Test public void previewRejectsSnapshotMutationAndWrongCandidateWithoutWrite() throws Exception {
        Map<String, Object> preview = prepare();
        vault.renameTotp(vault.beginTotpRename("existing"), "existing", "New label", OK);
        byte[] changed = store.bytes.clone();
        rejects("VAULT_CHANGED", () -> previews.commit((String) preview.get("previewId"), (String) candidate(preview).get("candidateId"), OK));
        Map<String, Object> next = prepare();
        rejects("PARAMETERS", () -> previews.commit((String) next.get("previewId"), "unknown", OK));
        assertArrayEquals(changed, store.bytes);
    }
    @Test public void latePreviewPublicationRejectsNewSnapshotAndWipesCandidate() throws Exception {
        TotpInput input = TotpInput.uri(URI);
        previews.prepare("owned", VaultCollections.list(input), 0, OK);
        previews.publication();
        vault.renameTotp(vault.beginTotpRename("existing"), "existing", "Changed", OK);
        rejects("VAULT_CHANGED", previews::publication);
        assertArrayEquals(new byte[input.seed.length], input.seed);
    }
    @Test public void evictedReadsRemainReadOnlyAndNeverConsumeManagementAdmission() throws Exception {
        VaultRequestAdmission admission = new VaultRequestAdmission();
        admission.admit("read_old", true);
        for (int i = 0; i < 5000; i++) admission.admit("read_tick_" + i, true);
        rejects("PARAMETERS", () -> admission.admit("read_old", false));
        admission.admit("read_old", true);
        rejects("PARAMETERS", () -> admission.admit("read_old", true));
        rejects("PARAMETERS", () -> admission.admit("ordinary", true));
        admission.admit("write_once", false);
        rejects("PARAMETERS", () -> admission.admit("write_once", false));
        for (int i = 1; i < 4096; i++) admission.admit("write_" + i, false);
        rejects("VAULT_EXPIRED", () -> admission.admit("write_over_limit", false));
        admission.admit("read_still_allowed", true);
    }
    @Test public void addCannotOverwriteAndRenamePreservesSeedIdentityAndId() throws Exception {
        byte[] before = store.bytes.clone();
        rejects("VAULT_OPERATION", () -> vault.addTotp(vault.beginTotpSave("existing"), "existing", "replacement", "replacement", new byte[20], OK));
        assertArrayEquals(before, store.bytes);
        vault.renameTotp(vault.beginTotpRename("existing"), "existing", "Renamed", OK);
        try (AppSecrets.Verified verified = AppSecrets.verify(store.bytes, PASSWORD)) {
            VaultManifest.Entry entry = verified.manifest.entries.get(0);
            assertEquals("existing", entry.id); assertEquals("Example:original", entry.identity); assertEquals("Renamed", entry.label);
            assertArrayEquals("12345678901234567890".getBytes(StandardCharsets.US_ASCII), entry.secret);
        }
    }
    @Test public void publicationHasNoStoreReadAndRejectsAdmittedMutationLockCancelAndDeadline() throws Exception {
        VaultOperation op = vault.beginTotpList(); vault.totpList(op, 59);
        long revision = vault.totpPublication(op), epoch = vault.session.epoch();
        int reads = store.reads; AtomicBoolean published = new AtomicBoolean();
        vault.publishTotp(epoch, revision, clock.time + 120000, OK, () -> published.set(true));
        assertTrue(published.get()); assertEquals(reads, store.reads);
        store.admission = () -> {
            try { vault.publishTotp(epoch, revision, clock.time + 120000, OK, () -> fail("Old read published")); fail("Old revision accepted"); }
            catch (VaultException error) { assertEquals("VAULT_CHANGED", error.code); }
        };
        vault.renameTotp(vault.beginTotpRename("existing"), "existing", "Changed", OK);
        rejects("VAULT_CHANGED", () -> vault.publishTotp(epoch, revision, clock.time + 120000, OK, () -> fail("Published")));
        long currentEpoch = vault.session.epoch(), currentRevision = vault.contentRevision;
        rejects("VAULT_CANCELLED", () -> vault.publishTotp(currentEpoch, currentRevision, clock.time + 120000,
                () -> { throw new VaultException("VAULT_CANCELLED", "cancel"); }, () -> fail("Published")));
        rejects("VAULT_CHANGED", () -> vault.publishTotp(currentEpoch, currentRevision, clock.time, OK, () -> fail("Published")));
        vault.lock(); rejects("VAULT_CHANGED", () -> vault.publishTotp(currentEpoch, currentRevision, clock.time + 120000, OK, () -> fail("Published")));
    }
    @Test public void imageLimitsAreEnforcedBeforeResourceConsumption() throws Exception {
        for (int[] dim : new int[][]{{0, 1}, {8193, 1}, {8192, 8192}, {4001, 4000}}) rejects("TOTP_LIMIT", () -> TotpQrImages.dimensions(dim[0], dim[1]));
        TotpQrImages.dimensions(4000, 4000);
        rejects("TOTP_LIMIT", () -> TotpQrImages.read(new ByteArrayInputStream(new byte[TotpQrImages.MAX_BYTES + 1]), OK));
        rejects("TOTP_IMAGE", () -> TotpQrImages.decodePixels(new int[1], 2, 2, OK));
        rejects("TOTP_NO_CODES", () -> TotpQrImages.decodePixels(new int[10000], 100, 100, OK));
    }
    @Test public void previewCancelWipesSeedAndListDeleteNeverExposeIt() throws Exception {
        TotpInput input = TotpInput.uri(URI);
        previews.prepare("owned", VaultCollections.list(input), 0, OK);
        previews.cancelRequest("other"); assertNotEquals(0, input.seed[0]);
        previews.cancelRequest("owned"); assertArrayEquals(new byte[input.seed.length], input.seed);
        Map<String, Object> list = vault.totpList(vault.beginTotpList(), 59);
        @SuppressWarnings("unchecked") Map<String, Object> row = ((List<Map<String, Object>>) list.get("entries")).get(0);
        assertEquals(VaultCollections.set("entryId", "identity", "label", "code"), row.keySet());
        vault.deleteTotp(vault.beginTotpDelete("existing"), "existing", OK);
        assertTrue(((List<?>) vault.totpList(vault.beginTotpList(), 59).get("entries")).isEmpty());
    }
    @Test public void scannerCaptureIsEpochBoundAndWipesOnCancellation() throws Exception {
        long epoch = vault.session.epoch();
        TotpCameraActivity.Capture capture = new TotpCameraActivity.Capture("owned", () -> vault.session.assertEpoch(epoch), visible -> { });
        capture.accept(URI); assertNotNull(capture.input);
        byte[] seed = capture.input.seed; capture.close(); assertArrayEquals(new byte[seed.length], seed);
        TotpCameraActivity.Capture late = new TotpCameraActivity.Capture("late", () -> vault.session.assertEpoch(epoch), visible -> { });
        vault.lock(); late.accept(URI); assertNull(late.input); assertEquals("VAULT_CHANGED", late.failure.code);
    }
    @Test public void offlineImageDecodesMultipleRealQrCodesAndRejectsUnsupportedOnlyAtSelection() throws Exception {
        int[] pixels = new int[800 * 400]; Arrays.fill(pixels, 0xffffffff);
        String second = URI.replace("alice%2Btest%40example.com", "bob");
        drawQr(pixels, 800, 0, URI); drawQr(pixels, 800, 400, second);
        try (TotpQrImages.Decoded decoded = TotpQrImages.decodePixels(pixels, 800, 400, OK)) {
            assertEquals(2, decoded.inputs.size()); assertEquals(0, decoded.invalid);
        }
        try (TotpQrImages.Decoded mixed = TotpQrImages.parse(new String[]{URI, second, "otpauth-migration://unsupported", URI}, OK)) {
            assertEquals(2, mixed.inputs.size()); assertEquals(1, mixed.invalid);
        }
        rejects("TOTP_LIMIT", () -> TotpQrImages.parse(new String[17], OK));
        rejects("VAULT_CANCELLED", () -> TotpQrImages.parse(new String[]{URI}, () -> { throw new VaultException("VAULT_CANCELLED", "cancel"); }));
    }
    static void drawQr(int[] pixels, int width, int x, String text) throws Exception {
        BitMatrix qr = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400);
        for (int row = 0; row < 400; row++) for (int col = 0; col < 400; col++) pixels[row * width + x + col] = qr.get(col, row) ? 0xff000000 : 0xffffffff;
    }
}
