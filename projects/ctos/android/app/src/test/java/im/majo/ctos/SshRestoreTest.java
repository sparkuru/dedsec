package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Synthetic projection fixtures test orchestration; none of these rows parse real SSH trust. */
public final class SshRestoreTest {
    private static final char[] PASSWORD = "synthetic source password".toCharArray();
    private static final char[] TARGET_PASSWORD = "synthetic target password".toCharArray();
    private static byte[] mixed;
    private static byte[] totp;
    private static byte[] target;
    private MemoryStore store;
    private AppSecrets vault;
    private FakeClock clock;

    private static final class FakeClock implements VaultSession.Clock {
        long now;
        @Override public long now() { return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        byte[] wrapper = {9};
        boolean fail;
        Runnable afterClaim;
        @Override public byte[] read() { return bytes == null ? null : bytes.clone(); }
        @Override public byte[] quickMaterial() { return wrapper == null ? null : wrapper.clone(); }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard) throws IOException, VaultException {
            if (fail) throw new IOException("synthetic precommit failure");
            guard.beforeCommit();
            if (afterClaim != null) afterClaim.run();
            bytes = next.clone(); wrapper = quick == null ? null : quick.clone();
        }
    }
    @BeforeClass public static void fixtures() throws Exception {
        mixed = encrypt(true, PASSWORD, "source");
        totp = encrypt(false, PASSWORD, "totp");
        target = encrypt(true, TARGET_PASSWORD, "target");
    }
    @Before public void setup() {
        store = new MemoryStore(); store.bytes = target.clone();
        clock = new FakeClock(); vault = new AppSecrets(store, clock);
        // Explicit bounded synthetic validator; not a production SSH parser.
        vault.attachSshValidator(manifest -> {
            for (VaultManifest.FileObject file : manifest.files) if (!file.identity.startsWith("synthetic")) throw VaultException.invalid();
        });
        vault.session.foreground(true);
    }

    static void attachSyntheticReviewer(AppSecrets vault) throws VaultException {
        vault.attachSshRestoreReviewer("synthetic-test", "1", SshRestoreTest::syntheticReview,
                (review, consent) -> { if (!review.binding.equals(consent.reviewBinding)) throw VaultException.invalid(); });
    }
    static SshRestoreReview syntheticReview(VaultManifest source, VaultManifest target) throws VaultException {
        List<SshRestoreReview.Coverage> coverage = coverage(source, target);
        List<SshRestoreReview.TrustChange> trust = new ArrayList<>();
        List<SshRestoreReview.Conflict> conflicts = new ArrayList<>();
        for (VaultManifest.FileObject file : source.files) if (file.type == VaultManifest.FileType.KNOWN_HOSTS) {
            VaultManifest.FileObject old = target == null ? null : find(target, file.id);
            if (old != null && old.type != VaultManifest.FileType.KNOWN_HOSTS) old = null;
            SshRestoreReview.TrustStatus status = old == null ? SshRestoreReview.TrustStatus.IMPORTED
                    : Arrays.equals(file.content, old.content) ? SshRestoreReview.TrustStatus.SAME : SshRestoreReview.TrustStatus.CHANGED;
            trust.add(new SshRestoreReview.TrustChange(status, file.id, old == null ? "" : old.id,
                    "synthetic.example.invalid", 22, "synthetic-key", syntheticFingerprint(file),
                    old == null ? "" : syntheticFingerprint(old), ""));
            if (status == SshRestoreReview.TrustStatus.CHANGED) conflicts.add(new SshRestoreReview.Conflict(
                    SshRestoreReview.Kind.TRUST, SshRestoreReview.ConflictStatus.CONTENT_CHANGED, file.id, old.id));
        }
        if (target != null) for (VaultManifest.FileObject old : target.files) if (old.type == VaultManifest.FileType.KNOWN_HOSTS
                && find(source, old.id) == null) {
            trust.add(new SshRestoreReview.TrustChange(SshRestoreReview.TrustStatus.TARGET_REMOVED, "", old.id,
                    "synthetic.example.invalid", 22, "synthetic-key", "", syntheticFingerprint(old), ""));
            conflicts.add(new SshRestoreReview.Conflict(SshRestoreReview.Kind.TRUST,
                    SshRestoreReview.ConflictStatus.TARGET_REMOVED, "", old.id));
        }
        return new SshRestoreReview(coverage, trust, conflicts);
    }
    private static List<SshRestoreReview.Coverage> coverage(VaultManifest source, VaultManifest target) throws VaultException {
        List<SshRestoreReview.Coverage> result = new ArrayList<>();
        addCoverage(result, SshRestoreReview.Side.SOURCE, source);
        if (target != null) addCoverage(result, SshRestoreReview.Side.TARGET, target);
        return result;
    }
    private static void addCoverage(List<SshRestoreReview.Coverage> result, SshRestoreReview.Side side, VaultManifest manifest) throws VaultException {
        for (VaultManifest.FileObject file : manifest.files) result.add(SshRestoreReview.Coverage.file(side, file));
        for (VaultManifest.Entry entry : manifest.entries) if (entry.type != VaultManifest.EntryType.TOTP) result.add(SshRestoreReview.Coverage.credential(side, entry));
    }

    @Test public void missingHooksRejectEitherSshSideAndCredentialOnlySource() throws Exception {
        rejects("VAULT_SSH_REVIEW_REQUIRED", () -> preview(mixed));
        rejects("VAULT_SSH_REVIEW_REQUIRED", () -> preview(totp));
        store.bytes = null;
        rejects("VAULT_SSH_REVIEW_REQUIRED", () -> preview(mixed));
        try (AppSecrets.Verified verified = AppSecrets.verify(mixed, PASSWORD);
                VaultManifest credentials = new VaultManifest(verified.manifest.entries, Collections.emptyList())) {
            byte[] plain = credentials.encode();
            try {
                byte[] source = VaultEnvelope.encrypt(verified.envelope.id, 1, verified.envelope.salt, verified.key, plain).encode();
                rejects("VAULT_SSH_REVIEW_REQUIRED", () -> preview(source));
            } finally { VaultCodec.wipe(plain); }
        }
    }

    @Test public void suppliedReviewWithoutApprovalHookCannotCommit() throws Exception {
        vault.attachSshRestoreReviewer("synthetic-test", "1", SshRestoreTest::syntheticReview, null);
        AppSecrets.ImportPreview preview = preview(mixed);
        rejects("VAULT_TRUST_REVIEW", () -> vault.importCommit(preview.id, preview.reviewBinding, true, () -> { }));
        assertOriginal();
    }

    @Test public void pureTotpLegacyPathNeedsNoSshHookButCannotDropTargetSshSilently() throws Exception {
        rejects("VAULT_SSH_REVIEW_REQUIRED", () -> preview(totp));
        store.bytes = null;
        AppSecrets.ImportPreview preview = preview(totp);
        assertEquals("", preview.reviewBinding); assertNull(preview.reviewMap());
        vault.importCommit(preview.id, true, () -> { });
        try (AppSecrets.Verified restored = AppSecrets.verify(store.bytes, PASSWORD)) { assertEquals(1, restored.manifest.entries.size()); }
    }

    @Test public void actualFreshTargetPasswordRequiredAndTargetLoansAreWiped() throws Exception {
        vault.unlock(TARGET_PASSWORD, () -> { });
        attachSyntheticReviewer(vault);
        rejects("VAULT_AUTHENTICATION", () -> vault.preview(mixed, PASSWORD, PASSWORD, "wrong-target", () -> { }));
        byte[][] retained = new byte[2][];
        vault.attachSshRestoreReviewer("synthetic-test", "1", (source, target) -> {
            retained[0] = source.files.get(0).content;
            retained[1] = target.entries.get(1).secret;
            return syntheticReview(source, target);
        }, (review, consent) -> { });
        AppSecrets.ImportPreview preview = preview(mixed);
        assertArrayEquals(new byte[retained[0].length], retained[0]);
        assertArrayEquals(new byte[retained[1].length], retained[1]);
        assertNotNull(preview.reviewMap());
    }

    @Test public void partialDuplicateWrongIdentityAndWrongContentCoverageReject() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            final int variant = mode;
            vault.attachSshRestoreReviewer("synthetic-test", "1", (source, target) -> {
                List<SshRestoreReview.Coverage> rows = coverage(source, target);
                SshRestoreReview.Coverage old = rows.get(0);
                if (variant == 0) rows.remove(0);
                else if (variant == 1) rows.add(old);
                else rows.set(0, new SshRestoreReview.Coverage(old.side, old.kind, old.type, old.objectId,
                        old.path, variant == 2 ? "wrong identity" : old.identity,
                        variant == 3 ? repeat('0', 64) : old.objectBinding));
                if (variant == 4) rows.set(0, new SshRestoreReview.Coverage(old.side, old.kind, "PUBLIC_KEY", old.objectId,
                        old.path, old.identity, old.objectBinding));
                return new SshRestoreReview(rows, Collections.emptyList(), Collections.emptyList());
            }, (review, consent) -> { });
            rejects("VAULT_CONTENT", () -> preview(mixed)); assertOriginal();
        }
    }

    @Test public void boundsAndInvalidTrustReferencesRejectWithoutTruncation() throws Exception {
        vault.attachSshRestoreReviewer("synthetic-test", "1", (source, target) -> {
            List<SshRestoreReview.Coverage> rows = coverage(source, target);
            while (rows.size() <= SshRestoreReview.MAX_COVERAGE) rows.add(rows.get(0));
            return new SshRestoreReview(rows, Collections.emptyList(), Collections.emptyList());
        }, (review, consent) -> { });
        rejects("VAULT_CONTENT", () -> preview(mixed));
        vault.attachSshRestoreReviewer("synthetic-test", "1", (source, target) -> {
            List<SshRestoreReview.TrustChange> changes = new ArrayList<>();
            for (int index = 0; index <= SshRestoreReview.MAX_ROWS; index++) changes.add(trust(source, repeat('h', 512)));
            return new SshRestoreReview(coverage(source, target), changes, Collections.emptyList());
        }, (review, consent) -> { });
        rejects("VAULT_CONTENT", () -> preview(mixed));
        vault.attachSshRestoreReviewer("synthetic-test", "1", (source, target) -> {
            List<SshRestoreReview.TrustChange> changes = new ArrayList<>();
            for (int index = 0; index < SshRestoreReview.MAX_ROWS; index++) changes.add(trust(source, repeat('h', 512)));
            return new SshRestoreReview(coverage(source, target), changes, Collections.emptyList());
        }, (review, consent) -> { });
        rejects("VAULT_CONTENT", () -> preview(mixed));
        vault.attachSshRestoreReviewer("synthetic-test", "1", (source, target) -> new SshRestoreReview(coverage(source, target),
                Arrays.asList(new SshRestoreReview.TrustChange(SshRestoreReview.TrustStatus.IMPORTED, "credential", "",
                        "synthetic.invalid", 22, "synthetic", "SHA256:" + repeat('A', 43), "", "")), Collections.emptyList()), (review, consent) -> { });
        rejects("VAULT_CONTENT", () -> preview(mixed)); assertOriginal();
    }

    @Test public void mutableProviderListsAndReturnedMapsCannotChangeBoundProjection() throws Exception {
        List<SshRestoreReview.Coverage> retained = new ArrayList<>();
        vault.attachSshRestoreReviewer("synthetic-test", "1", (source, target) -> {
            retained.addAll(coverage(source, target));
            return new SshRestoreReview(retained, Collections.emptyList(), Collections.emptyList());
        }, (review, consent) -> { });
        byte[] callerSource = mixed.clone();
        AppSecrets.ImportPreview preview = preview(callerSource);
        String binding = preview.reviewBinding;
        Arrays.fill(callerSource, (byte) 0); retained.clear();
        Map<String, Object> projection = preview.reviewMap();
        try { projection.put("providerId", "other"); fail("mutable projection"); } catch (UnsupportedOperationException expected) { }
        List<?> objects = (List<?>) projection.get("coverage");
        try { objects.clear(); fail("mutable coverage"); } catch (UnsupportedOperationException expected) { }
        @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) objects.get(0);
        try { row.put("identity", "wrong"); fail("mutable row"); } catch (UnsupportedOperationException expected) { }
        assertEquals(binding, preview.reviewBinding);
        vault.importCommit(preview.id, binding, true, () -> { });
        assertSourceRestored();
    }

    @Test public void reviewerMutationCannotApproveDifferentAuthenticatedSource() throws Exception {
        vault.attachSshRestoreReviewer("synthetic-test", "1", (source, target) -> {
            Arrays.fill(source.files.get(0).content, (byte) 0);
            return syntheticReview(source, target);
        }, (review, consent) -> { });
        rejects("VAULT_CONTENT", () -> preview(mixed)); assertOriginal();
    }

    @Test public void oldBooleanWrongBindingAndDeclinedConsentCannotAuthorizeMixed() throws Exception {
        attachSyntheticReviewer(vault);
        AppSecrets.ImportPreview legacy = preview(mixed);
        rejects("VAULT_TRUST_REVIEW", () -> vault.importCommit(legacy.id, true, () -> { }));
        AppSecrets.ImportPreview wrong = preview(mixed);
        rejects("VAULT_TRUST_REVIEW", () -> vault.importCommit(wrong.id, repeat('0', 64), true, () -> { }));
        AppSecrets.ImportPreview declined = preview(mixed);
        rejects("VAULT_TRUST_REVIEW", () -> vault.importCommit(declined.id, declined.reviewBinding, false, () -> { }));
        assertOriginal();
    }

    @Test public void providerReplacementRevocationAndApprovalWithdrawalReject() throws Exception {
        attachSyntheticReviewer(vault);
        AppSecrets.ImportPreview old = preview(mixed);
        attachSyntheticReviewer(vault); // Even reattaching identical version invalidates the registration.
        rejects("VAULT_EXPIRED", () -> vault.importCommit(old.id, old.reviewBinding, true, () -> { }));
        AppSecrets.ImportPreview revoked = preview(mixed);
        vault.attachSshRestoreReviewer("synthetic-test", "2", null, null);
        rejects("VAULT_EXPIRED", () -> vault.importCommit(revoked.id, revoked.reviewBinding, true, () -> { }));
        boolean[] allowed = {true};
        vault.attachSshRestoreReviewer("synthetic-test", "3", SshRestoreTest::syntheticReview, (review, consent) -> {
            if (!allowed[0]) throw new VaultException("VAULT_TRUST_REVIEW", "synthetic withdrawal");
        });
        AppSecrets.ImportPreview preview = preview(mixed); allowed[0] = false;
        rejects("VAULT_TRUST_REVIEW", () -> vault.importCommit(preview.id, preview.reviewBinding, true, () -> { }));
        assertOriginal();
    }

    @Test public void finalGuardRechecksApprovalProviderAndExpiryBeforeCommit() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            boolean[] allowed = {true};
            vault.attachSshRestoreReviewer("synthetic-test", "1", SshRestoreTest::syntheticReview, (review, consent) -> {
                if (!allowed[0]) throw new VaultException("VAULT_TRUST_REVIEW", "synthetic withdrawn");
            });
            AppSecrets.ImportPreview preview = preview(mixed);
            final int variant = mode;
            rejects(mode == 0 ? "VAULT_TRUST_REVIEW" : "VAULT_EXPIRED", () -> vault.importCommit(preview.id, preview.reviewBinding, true,
                    new AppSecrets.EffectGuard() {
                        @Override public void check() { }
                        @Override public void beforeCommit() throws VaultException {
                            if (variant == 0) allowed[0] = false;
                            else if (variant == 1) vault.attachSshRestoreReviewer("synthetic-test", "2", null, null);
                            else clock.now = 120000;
                        }
                    }));
            assertOriginal(); clock.now = 0;
        }
    }

    @Test public void cancellationLockExpiryAndSnapshotChangePreserveOldRecord() throws Exception {
        attachSyntheticReviewer(vault);
        AppSecrets.ImportPreview first = preview(mixed); vault.cancelRequest("restore");
        rejects("VAULT_EXPIRED", () -> vault.importCommit(first.id, first.reviewBinding, true, () -> { }));
        AppSecrets.ImportPreview locked = preview(mixed); vault.session.lock();
        rejects("VAULT_CHANGED", () -> vault.importCommit(locked.id, locked.reviewBinding, true, () -> { }));
        AppSecrets.ImportPreview expired = preview(mixed); clock.now = 120000;
        rejects("VAULT_EXPIRED", () -> vault.importCommit(expired.id, expired.reviewBinding, true, () -> { }));
        clock.now = 0;
        AppSecrets.ImportPreview changed = preview(mixed); store.bytes[store.bytes.length - 1] ^= 1;
        rejects("VAULT_CHANGED", () -> vault.importCommit(changed.id, changed.reviewBinding, true, () -> { }));
        store.bytes = target.clone(); assertOriginal();
    }

    @Test public void syntheticTrustDiffShowsChangesAndTargetOnlyRemoval() throws Exception {
        attachSyntheticReviewer(vault);
        AppSecrets.ImportPreview mixedPreview = preview(mixed);
        List<?> trust = (List<?>) mixedPreview.reviewMap().get("trustChanges");
        assertEquals("changed", ((Map<?, ?>) trust.get(0)).get("status"));
        assertFalse(((List<?>) mixedPreview.reviewMap().get("conflicts")).isEmpty());
        String firstBinding = mixedPreview.reviewBinding;
        AppSecrets.ImportPreview removed = preview(totp);
        assertEquals("target_removed", ((Map<?, ?>) ((List<?>) removed.reviewMap().get("trustChanges")).get(0)).get("status"));
        assertNotEquals(firstBinding, removed.reviewBinding);
        vault.importCommit(removed.id, removed.reviewBinding, true, () -> { });
        try (AppSecrets.Verified verified = AppSecrets.verify(store.bytes, PASSWORD)) { assertEquals(0, verified.manifest.files.size()); }
    }

    @Test public void consentBindsAllSnapshotsAndSuccessfulRestoreKeepsExactWholeSource() throws Exception {
        List<AppSecrets.RestoreConsent> approvals = new ArrayList<>();
        vault.attachSshRestoreReviewer("synthetic-test", "fixed", SshRestoreTest::syntheticReview, (review, consent) -> {
            approvals.add(consent);
            assertEquals(review.binding, consent.reviewBinding);
            assertEquals(review.providerRevision, consent.providerRevision);
            assertEquals(review.sourceCipherDigest, consent.sourceCipherDigest);
            assertEquals(review.targetCipherDigest, consent.targetCipherDigest);
        });
        AppSecrets.ImportPreview preview = preview(mixed);
        vault.importCommit(preview.id, preview.reviewBinding, true, () -> { });
        assertTrue(approvals.size() >= 3);
        for (AppSecrets.RestoreConsent consent : approvals) assertSame(approvals.get(0), consent);
        assertNull(store.wrapper); assertFalse(vault.session.unlocked());
        assertSourceRestored();
        rejects("VAULT_EXPIRED", () -> vault.importCommit(preview.id, preview.reviewBinding, true, () -> { }));
    }

    @Test public void preclaimIoAndCancellationRetainRecordButAfterClaimNeverReopens() throws Exception {
        attachSyntheticReviewer(vault);
        AppSecrets.ImportPreview failure = preview(mixed); store.fail = true;
        rejects("VAULT_IO", () -> vault.importCommit(failure.id, failure.reviewBinding, true, () -> { }));
        assertOriginal(); store.fail = false;
        AppSecrets.ImportPreview cancel = preview(mixed);
        rejects("VAULT_CANCELLED", () -> vault.importCommit(cancel.id, cancel.reviewBinding, true,
                () -> { throw new VaultException("VAULT_CANCELLED", "synthetic cancelled"); }));
        assertOriginal();
        AppSecrets.ImportPreview pending = preview(mixed);
        VaultRequestPhase phase = new VaultRequestPhase();
        store.afterClaim = () -> { phase.cancel(); vault.session.lock(); };
        vault.importCommit(pending.id, pending.reviewBinding, true, new AppSecrets.EffectGuard() {
            @Override public void check() { }
            @Override public void beforeCommit() throws VaultException { phase.claimCommit(this::check); }
        });
        assertEquals("VAULT_COMMIT_UNCERTAIN", phase.interrupted(new VaultException("VAULT_CANCELLED", "synthetic cancelled")).code);
        assertFalse(vault.session.unlocked()); assertSourceRestored();
    }

    @Test public void internalSourceMutationCannotCommitAgainstDisplayedReview() throws Exception {
        attachSyntheticReviewer(vault);
        AppSecrets.ImportPreview preview = preview(mixed);
        preview.source.plaintext[0] ^= 1;
        rejects("VAULT_CHANGED", () -> vault.importCommit(preview.id, preview.reviewBinding, true, () -> { }));
        assertOriginal();
    }

    @Test public void publicProjectionNeverExposesCredentialDigestAndTokensCannotReplay() throws Exception {
        attachSyntheticReviewer(vault);
        String raw;
        try (AppSecrets.Verified source = AppSecrets.verify(mixed, PASSWORD)) {
            raw = SshRestoreReview.Coverage.credential(SshRestoreReview.Side.SOURCE, source.manifest.entries.get(1)).objectBinding;
        }
        AppSecrets.ImportPreview first = preview(mixed);
        Map<String, Object> map = first.reviewMap();
        assertFalse(map.toString().contains(raw));
        String firstBinding = first.reviewBinding;
        String token = sourceCredentialToken(map);
        assertNotEquals(raw, token);
        AppSecrets.ImportPreview second = preview(mixed);
        assertNotEquals(token, sourceCredentialToken(second.reviewMap()));
        assertNotEquals(firstBinding, second.reviewBinding);
        rejects("VAULT_TRUST_REVIEW", () -> vault.importCommit(second.id, firstBinding, true, () -> { }));
        assertOriginal();
    }

    private static String sourceCredentialToken(Map<String, Object> map) {
        for (Object value : (List<?>) map.get("coverage")) {
            Map<?, ?> row = (Map<?, ?>) value;
            if (row.get("side").equals("source") && row.get("kind").equals("credential")) return (String) row.get("objectBinding");
        }
        throw new AssertionError("synthetic missing credential coverage");
    }

    private AppSecrets.ImportPreview preview(byte[] source) throws Exception {
        return vault.preview(source, PASSWORD, store.bytes == null ? null : TARGET_PASSWORD, "restore", () -> { });
    }
    private void assertOriginal() { assertArrayEquals(target, store.bytes); assertArrayEquals(new byte[] {9}, store.wrapper); }
    private void assertSourceRestored() throws Exception {
        try (AppSecrets.Verified source = AppSecrets.verify(mixed, PASSWORD); AppSecrets.Verified restored = AppSecrets.verify(store.bytes, PASSWORD)) {
            assertArrayEquals(source.plaintext, restored.plaintext); assertNotEquals(source.envelope.id, restored.envelope.id);
        }
    }
    private static byte[] encrypt(boolean ssh, char[] password, String suffix) throws Exception {
        VaultManifest.Entry seed = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp", "synthetic:account", "synthetic", "SHA1", 6, 30, new byte[20]);
        List<VaultManifest.Entry> entries = new ArrayList<>(); entries.add(seed);
        List<VaultManifest.FileObject> files = new ArrayList<>();
        if (ssh) {
            entries.add(new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "credential", "target:synthetic@example.invalid:22",
                    "synthetic", "", 0, 0, ("synthetic-password-" + suffix).getBytes(StandardCharsets.UTF_8)));
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, "config", "config", "synthetic config",
                    ("synthetic-config-" + suffix).getBytes(StandardCharsets.UTF_8), Arrays.asList("trust")));
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, "trust", "trust", "synthetic trust",
                    ("synthetic-trust-" + suffix).getBytes(StandardCharsets.UTF_8), Collections.emptyList()));
        }
        byte[] salt = VaultEnvelope.random(16), key = VaultEnvelope.derive(password, salt);
        try (VaultManifest manifest = new VaultManifest(entries, files)) {
            byte[] plain = manifest.encode();
            try { return VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        } finally { VaultCodec.wipe(key); }
    }
    private static VaultManifest.FileObject find(VaultManifest manifest, String id) {
        for (VaultManifest.FileObject file : manifest.files) if (file.id.equals(id)) return file;
        return null;
    }
    private static String syntheticFingerprint(VaultManifest.FileObject file) {
        // Deliberately fake fingerprint of fixture bytes, never an SSH key parser or identity claim.
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(VaultCodec.digest(file.content));
    }
    private static SshRestoreReview.TrustChange trust(VaultManifest source, String host) throws VaultException {
        VaultManifest.FileObject file = find(source, "trust");
        return new SshRestoreReview.TrustChange(SshRestoreReview.TrustStatus.IMPORTED, file.id, "", host, 22,
                "synthetic", syntheticFingerprint(file), "", "");
    }
    private static String repeat(char value, int count) { char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars); }
    private interface Throwing { void run() throws Exception; }
    private static void rejects(String code, Throwing operation) throws Exception {
        try { operation.run(); fail("expected " + code); } catch (VaultException failure) { assertEquals(code, failure.code); }
    }
}
