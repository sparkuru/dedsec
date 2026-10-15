package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** JVM evidence only: platform Keystore/SAF/privacy still require device verification. */
public final class VaultTest {
    private static final char[] PASSWORD = "test-only master password".toCharArray();
    private static byte[] fixture;
    private MemoryStore store;
    private FakeClock clock;
    private AppSecrets vault;

    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        byte[] quick;
        boolean fail;
        boolean uncertain;
        Runnable beforeGuard;
        Runnable afterGuard;
        Runnable afterCommit;
        @Override public byte[] read() { return bytes == null ? null : bytes.clone(); }
        @Override public byte[] quickMaterial() { return quick == null ? null : quick.clone(); }
        @Override public void commit(byte[] next, byte[] material, AppSecrets.EffectGuard guard) throws IOException, VaultException {
            if (fail) throw new IOException("Injected before commit");
            if (beforeGuard != null) beforeGuard.run();
            guard.beforeCommit();
            if (afterGuard != null) afterGuard.run();
            bytes = next.clone();
            quick = material == null ? null : material.clone();
            if (afterCommit != null) afterCommit.run();
            if (uncertain) throw new VaultException("VAULT_COMMIT_UNCERTAIN", "Fixture uncertain after complete switch");
        }
    }
    private static final class FakeClock implements VaultSession.Clock {
        long milliseconds;
        @Override public long now() { return milliseconds; }
    }
    private interface Failure { void run() throws Exception; }
    private static void rejects(String code, Failure operation) throws Exception {
        try { operation.run(); fail("Expected " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }

    @BeforeClass public static void prepareFixture() throws Exception {
        byte[] salt = new byte[16];
        byte[] key = VaultEnvelope.derive(PASSWORD, salt);
        try (VaultManifest manifest = mixedManifest()) {
            byte[] plain = manifest.encode();
            try { fixture = VaultEnvelope.encrypt(UUID.randomUUID(), 7, salt, key, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        } finally { VaultCodec.wipe(key); }
    }

    @Before public void setup() throws Exception {
        store = new MemoryStore();
        store.bytes = fixture.clone();
        clock = new FakeClock();
        vault = new AppSecrets(store, clock);
        // Restricted fixture validator is a test adapter, not a production OpenSSH parser.
        vault.attachSshValidator(manifest -> { });
        SshRestoreTest.attachSyntheticReviewer(vault);
        vault.session.foreground(true);
    }

    @Test public void argon2idMatchesRfc9106Section53() {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(0x13).withMemoryAsKB(32).withIterations(3).withParallelism(4)
                .withSalt(repeat(16, 2)).withSecret(repeat(8, 3)).withAdditional(repeat(12, 4)).build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] output = new byte[32];
        generator.generateBytes(repeat(32, 1), output);
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", hex(output));
    }

    @Test public void aes256GcmMatchesNistVector() throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[32], "AES"), new GCMParameterSpec(128, new byte[12]));
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919", hex(cipher.doFinal(new byte[16])));
    }

    @Test public void headerAndCipherTamperingNeverReturnPlaintext() throws Exception {
        VaultEnvelope envelope = VaultEnvelope.parse(fixture);
        byte[] key = VaultEnvelope.derive(PASSWORD, envelope.salt);
        try {
            for (int offset : new int[] {32, 48, 56, 72, VaultEnvelope.HEADER_BYTES, fixture.length - 1}) {
                byte[] damaged = fixture.clone();
                damaged[offset] ^= 1;
                rejects("VAULT_AUTHENTICATION", () -> VaultEnvelope.parse(damaged).decrypt(key));
            }
        } finally { VaultCodec.wipe(key); }
    }

    @Test public void resourceOrVersionHeaderRejectedBeforeKdf() throws Exception {
        for (int offset : new int[] {0, 8, 12, 16, 20, 24, 28, 84}) {
            byte[] damaged = fixture.clone();
            damaged[offset] ^= 1;
            rejects("VAULT_FORMAT", () -> VaultEnvelope.parse(damaged));
        }
        rejects("VAULT_FORMAT", () -> VaultEnvelope.parse(Arrays.copyOf(fixture, fixture.length - 1)));
        rejects("VAULT_FORMAT", () -> VaultEnvelope.parse(new byte[VaultEnvelope.MAX_BYTES + 1]));
    }

    @Test public void wrongPasswordFailsWhileAlreadyUnlocked() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        rejects("VAULT_AUTHENTICATION", () -> vault.export("wrong".toCharArray(), () -> { }));
        assertTrue(vault.session.unlocked());
        assertArrayEquals(fixture, store.bytes);
    }

    @Test public void uncertainCommitInvalidatesSessionAndLeavesCompleteRecoverableGeneration() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        VaultOperation pending = new TotpVaultAdapter(vault).request("totp1");
        store.uncertain = true;
        char[] replacement = "replacement".toCharArray();
        rejects("VAULT_COMMIT_UNCERTAIN", () -> vault.changePassword(PASSWORD, replacement, replacement, () -> { }));
        assertFalse(vault.session.unlocked());
        rejects("VAULT_OPERATION", () -> vault.totpCode(pending, "totp1", 59));
        try (AppSecrets.Verified recovered = AppSecrets.verify(store.bytes, replacement)) { assertEquals(2, recovered.manifest.entries.size()); }
    }

    @Test public void cancellationBeforeCommitClaimPreservesOldGenerationAndWrapper() throws Exception {
        store.quick = new byte[] {7};
        VaultRequestPhase phase = new VaultRequestPhase();
        AppSecrets.EffectGuard guard = phaseGuard(phase);
        phase.cancel();
        char[] replacement = "replacement".toCharArray();
        rejects("VAULT_CANCELLED", () -> vault.changePassword(PASSWORD, replacement, replacement, guard));
        assertFalse(phase.committing());
        assertEquals("VAULT_CANCELLED", phase.interrupted(new VaultException("VAULT_CANCELLED", "cancelled")).code);
        assertArrayEquals(fixture, store.bytes);
        assertArrayEquals(new byte[] {7}, store.quick);
    }

    @Test(timeout = 10000) public void cancellationAfterFinalGuardCannotPromiseRollbackOrBlockUi() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        VaultRequestPhase phase = new VaultRequestPhase();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch continueStore = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        store.afterGuard = () -> {
            claimed.countDown();
            try { if (!continueStore.await(8, TimeUnit.SECONDS)) throw new AssertionError("Commit timeout"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        };
        char[] replacement = "replacement".toCharArray();
        Thread commit = new Thread(() -> {
            try { vault.changePassword(PASSWORD, replacement, replacement, phaseGuard(phase)); }
            catch (Throwable error) { failure.set(error); }
        });
        commit.start();
        assertTrue(claimed.await(8, TimeUnit.SECONDS));
        assertTrue(phase.committing());
        // Production cancellation uses these two fast owners, never the disk/crypto monitor.
        phase.cancel();
        vault.session.lock();
        VaultException receipt = phase.interrupted(new VaultException("VAULT_CANCELLED", "cancelled"));
        assertEquals("VAULT_COMMIT_UNCERTAIN", receipt.code);
        continueStore.countDown();
        commit.join(8000);
        assertFalse(commit.isAlive());
        assertNull(failure.get());
        assertFalse(vault.session.unlocked());
        try (AppSecrets.Verified recovered = AppSecrets.verify(store.bytes, replacement)) {
            assertEquals(2, recovered.manifest.entries.size());
        }
    }

    @Test public void cancellationAfterPersistenceProducesUncertainReceiptAndCannotReopenSession() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        store.quick = new byte[] {7};
        VaultRequestPhase phase = new VaultRequestPhase();
        store.afterCommit = () -> { phase.cancel(); vault.session.lock(); };
        TotpVaultAdapter adapter = new TotpVaultAdapter(vault);
        adapter.save(adapter.saveRequest("totp2"), "totp2", "Test:account", "Test", new byte[20], phaseGuard(phase));
        assertEquals("VAULT_COMMIT_UNCERTAIN", phase.interrupted(new VaultException("VAULT_TIMED_OUT", "timeout")).code);
        assertFalse(vault.session.unlocked());
        assertArrayEquals(new byte[] {7}, store.quick);
        try (AppSecrets.Verified recovered = AppSecrets.verify(store.bytes, PASSWORD)) {
            assertEquals(3, recovered.manifest.entries.size());
        }
    }

    @Test public void durablePhaseHookReachesCreateImportShortcutAndTypedSshCommits() throws Exception {
        VaultRequestPhase registration = new VaultRequestPhase();
        vault.setQuick(vault.current(), new byte[] {1}, vault.session.epoch(), phaseGuard(registration));
        assertTrue(registration.committing());
        VaultRequestPhase disable = new VaultRequestPhase();
        vault.disableQuick(PASSWORD, phaseGuard(disable));
        assertTrue(disable.committing());
        AppSecrets.ImportPreview preview = vault.preview(fixture, PASSWORD, PASSWORD, "import", () -> { });
        VaultRequestPhase importing = new VaultRequestPhase();
        vault.importCommit(preview.id, preview.reviewBinding, true, phaseGuard(importing));
        assertTrue(importing.committing());
        store.bytes = null;
        VaultRequestPhase creation = new VaultRequestPhase();
        vault.create(PASSWORD, PASSWORD, phaseGuard(creation));
        assertTrue(creation.committing());
        store.bytes = fixture.clone();
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> { });
        VaultRequestPhase saving = new VaultRequestPhase();
        String target = "target:test@example.invalid:22";
        ssh.save(ssh.saveRequest("ssh2", target, "connection1"), "ssh2", target, "connection1", "test2", false,
                "test-only-password".getBytes(StandardCharsets.UTF_8), phaseGuard(saving));
        assertTrue(saving.committing());
    }

    private static AppSecrets.EffectGuard phaseGuard(VaultRequestPhase phase) {
        return new AppSecrets.EffectGuard() {
            @Override public void check() throws VaultException {
                if (phase.cancelled()) throw new VaultException("VAULT_CANCELLED", "cancelled");
            }
            @Override public void beforeCommit() throws VaultException { phase.claimCommit(this); }
        };
    }

    @Test public void lockAfterDurableCommitCannotResurrectCreateOrMutationSession() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        store.afterCommit = vault.session::lock;
        TotpVaultAdapter adapter = new TotpVaultAdapter(vault);
        adapter.save(adapter.saveRequest("totp2"), "totp2", "Test:account", "Test", new byte[20], () -> { });
        assertFalse(vault.session.unlocked());
        store.bytes = null;
        vault.create(PASSWORD, PASSWORD, () -> { });
        assertNotNull(store.bytes);
        assertFalse(vault.session.unlocked());
    }

    @Test public void typedMutationsKeepMaterialBindingQuickAndRevokeOldSnapshot() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        store.quick = new byte[] {5, 6};
        VaultEnvelope original = vault.current();
        TotpVaultAdapter adapter = new TotpVaultAdapter(vault);
        VaultOperation old = adapter.request("totp1");
        VaultOperation save = adapter.saveRequest("totp2");
        adapter.save(save, "totp2", "Test:account", "Test", new byte[20], () -> { });
        assertTrue(vault.session.unlocked());
        assertArrayEquals(new byte[] {5, 6}, store.quick);
        assertEquals(original.generation, vault.current().generation);
        assertArrayEquals(original.materialBinding(), vault.current().materialBinding());
        assertFalse(Arrays.equals(original.binding(), vault.current().binding()));
        assertFalse(Arrays.equals(original.nonce, vault.current().nonce));
        rejects("VAULT_OPERATION", () -> adapter.currentCode(old, "totp1"));
        adapter.delete(adapter.deleteRequest("totp2"), "totp2", () -> { });
        rejects("VAULT_OPERATION", () -> adapter.request("totp2"));
    }

    @Test public void failedOrCancelledTypedMutationRetainsVaultAndShortcut() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        store.quick = new byte[] {5, 6};
        TotpVaultAdapter adapter = new TotpVaultAdapter(vault);
        VaultOperation save = adapter.saveRequest("totp2");
        rejects("VAULT_CANCELLED", () -> adapter.save(save, "totp2", "Test:account", "Test", new byte[20],
                () -> { throw new VaultException("VAULT_CANCELLED", "Fixture cancel"); }));
        assertArrayEquals(fixture, store.bytes);
        assertArrayEquals(new byte[] {5, 6}, store.quick);
    }

    @Test public void sshSaveDeleteAndCrossConsumerMutationStayScoped() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> { });
        String target = "target:test@example.invalid:22";
        VaultOperation save = ssh.saveRequest("ssh2", target, "connection1");
        rejects("VAULT_OPERATION", () -> new TotpVaultAdapter(vault).save(save, "ssh2", "Test:account", "Test", new byte[20], () -> { }));
        ssh.save(save, "ssh2", target, "connection1", "test2", false,
                "test-only-new-password".getBytes(StandardCharsets.UTF_8), () -> { });
        ssh.useCredential(ssh.credentialRequest("ssh2", target, "connection1"), "ssh2", target, "connection1",
                bytes -> assertEquals("test-only-new-password", new String(bytes, StandardCharsets.UTF_8)));
        ssh.delete(ssh.deleteRequest("ssh2", target), "ssh2", target, () -> { });
        rejects("VAULT_OPERATION", () -> ssh.credentialRequest("ssh2", target, "connection1"));
    }

    @Test public void verifiedHostCannotAuthorizeArbitraryTrustReplacement() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter noApproval = new SshVaultAdapter(vault, (connection, target) -> { });
        try (SshTrustProposal proposal = trustProposal("test-only-new-trust")) {
            rejects("VAULT_TRUST_REVIEW", () -> noApproval.trustRequest(proposal));
            SshVaultAdapter approved = new SshVaultAdapter(vault, (connection, target) -> { }, exact -> {
                if (!exact.binding.equals(proposal.binding)) throw new VaultException("VAULT_TRUST_REVIEW", "Unapproved change");
            });
            VaultOperation operation = approved.trustRequest(proposal);
            try (SshTrustProposal unrelated = trustProposal("unrelated-host-row")) {
                rejects("VAULT_TRUST_REVIEW", () -> approved.updateTrust(operation, unrelated, () -> { }));
            }
            approved.updateTrust(operation, proposal, () -> { });
            assertFalse(Arrays.equals(fixture, store.bytes));
            try (AppSecrets.Verified verified = AppSecrets.verify(store.bytes, PASSWORD)) {
                assertEquals("test-only-new-trust", new String(verified.manifest.files.get(2).content, StandardCharsets.UTF_8));
            }
        }
    }

    @Test public void trustCasWrongBaseOrClosedProposalFailsWithoutWrite() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> { }, exact -> { });
        try (SshTrustProposal wrongBase = new SshTrustProposal("known_hosts", "trust1", "target:test@example.invalid:22", "connection1",
                "example.invalid", 22, "SHA256:" + "A".repeat(43), new byte[32], "new".getBytes(StandardCharsets.UTF_8))) {
            VaultOperation operation = ssh.trustRequest(wrongBase);
            rejects("VAULT_CHANGED", () -> ssh.updateTrust(operation, wrongBase, () -> { }));
        }
        SshTrustProposal closed = trustProposal("new");
        closed.close();
        rejects("VAULT_EXPIRED", () -> ssh.trustRequest(closed));
        assertArrayEquals(fixture, store.bytes);
    }

    private static SshTrustProposal trustProposal(String newContent) throws Exception {
        return new SshTrustProposal("known_hosts", "trust1", "target:test@example.invalid:22", "connection1", "example.invalid", 22,
                "SHA256:" + "A".repeat(43), VaultCodec.digest("example.invalid test-only-trust".getBytes(StandardCharsets.UTF_8)),
                newContent.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void mixedManifestRestoresOnIndependentInstallation() throws Exception {
        AppSecrets.ImportPreview preview = vault.preview(fixture, PASSWORD, PASSWORD, "import1", () -> { });
        assertEquals(2, preview.source.manifest.entries.size());
        assertEquals(3, preview.source.manifest.files.size());
        vault.importCommit(preview.id, preview.reviewBinding, true, () -> { });
        assertFalse(vault.session.unlocked());
        assertFalse(VaultEnvelope.parse(fixture).id.equals(vault.current().id));
        MemoryStore otherStore = new MemoryStore();
        otherStore.bytes = vault.export(PASSWORD, () -> { });
        AppSecrets other = new AppSecrets(otherStore, clock);
        other.attachSshValidator(manifest -> { });
        other.session.foreground(true);
        other.unlock(PASSWORD, () -> { });
        assertTrue(other.session.unlocked());
        assertArrayEquals(AppSecrets.verify(fixture, PASSWORD).plaintext,
                AppSecrets.verify(otherStore.bytes, PASSWORD).plaintext);
    }

    @Test public void missingSshSemanticValidatorRejectsProductionMixedImport() throws Exception {
        vault.attachSshValidator(null);
        rejects("VAULT_SSH_VALIDATION_REQUIRED", () -> vault.preview(fixture, PASSWORD, PASSWORD, "import", () -> { }));
        assertArrayEquals(fixture, store.bytes);
    }

    @Test public void malformedPathDuplicatesMissingReferencesAndCyclesReject() throws Exception {
        for (String path : List.of("/absolute", "../escape", "a/../b", "a//b", "a\\b", "content:uri", "~/key")) {
            rejects("VAULT_CONTENT", () -> new VaultManifest(List.of(), List.of(file("id", path, List.of()))));
        }
        rejects("VAULT_CONTENT", () -> new VaultManifest(List.of(), List.of(file("a", "config", List.of()), file("b", "config", List.of()))));
        rejects("VAULT_CONTENT", () -> new VaultManifest(List.of(), List.of(file("a", "config", List.of("missing")))));
        rejects("VAULT_CONTENT", () -> new VaultManifest(List.of(), List.of(file("a", "config", List.of("config")))));
    }

    @Test public void manifestDigestTrailingBytesAndUnsupportedTotpReject() throws Exception {
        try (VaultManifest manifest = mixedManifest()) {
            byte[] bytes = manifest.encode();
            rejects("VAULT_CONTENT", () -> VaultManifest.decode(Arrays.copyOf(bytes, bytes.length + 1)));
            bytes[bytes.length - 10] ^= 1;
            rejects("VAULT_CONTENT", () -> VaultManifest.decode(bytes));
        }
        rejects("VAULT_CONTENT", () -> new VaultManifest(List.of(new VaultManifest.Entry(VaultManifest.EntryType.TOTP,
                "totp", "issuer:account", "issuer", "SHA256", 8, 60, new byte[20])), List.of()));
    }

    @Test public void passphraseChangePreservesContentsAndOldBackupUsesOldPassword() throws Exception {
        store.quick = new byte[] {1, 2};
        char[] next = "new test-only password".toCharArray();
        vault.changePassword(PASSWORD, next, next, () -> { });
        assertNull(store.quick);
        assertFalse(vault.session.unlocked());
        assertEquals(8, vault.current().generation);
        rejects("VAULT_AUTHENTICATION", () -> AppSecrets.verify(store.bytes, PASSWORD));
        try (AppSecrets.Verified old = AppSecrets.verify(fixture, PASSWORD);
                AppSecrets.Verified changed = AppSecrets.verify(store.bytes, next)) {
            assertArrayEquals(old.plaintext, changed.plaintext);
        }
    }

    @Test public void failedChangeAndMismatchPreserveOldVaultAndQuickMaterial() throws Exception {
        store.quick = new byte[] {1, 2};
        rejects("PARAMETERS", () -> vault.changePassword(PASSWORD, "new".toCharArray(), "other".toCharArray(), () -> { }));
        store.fail = true;
        rejects("VAULT_IO", () -> vault.changePassword(PASSWORD, "new".toCharArray(), "new".toCharArray(), () -> { }));
        assertArrayEquals(fixture, store.bytes);
        assertArrayEquals(new byte[] {1, 2}, store.quick);
    }

    @Test public void disableQuickRequiresFreshDecryptionAndCommitsDisabledBeforeCleanup() throws Exception {
        store.quick = new byte[] {3};
        vault.unlock(PASSWORD, () -> { });
        rejects("VAULT_AUTHENTICATION", () -> vault.disableQuick("wrong".toCharArray(), () -> { }));
        assertArrayEquals(new byte[] {3}, store.quick);
        vault.disableQuick(PASSWORD, () -> { });
        assertNull(store.quick);
        assertArrayEquals(fixture, store.bytes);
        assertFalse(vault.session.unlocked());
    }

    @Test public void importWrongTargetCancelledAndTrustUnreviewedPreserveEverything() throws Exception {
        store.quick = new byte[] {3};
        rejects("VAULT_AUTHENTICATION", () -> vault.preview(fixture, PASSWORD, "wrong".toCharArray(), "bad", () -> { }));
        AppSecrets.ImportPreview first = vault.preview(fixture, PASSWORD, PASSWORD, "cancel", () -> { });
        vault.cancelRequest("cancel");
        rejects("VAULT_EXPIRED", () -> vault.importCommit(first.id, true, () -> { }));
        AppSecrets.ImportPreview second = vault.preview(fixture, PASSWORD, PASSWORD, "trust", () -> { });
        rejects("VAULT_TRUST_REVIEW", () -> vault.importCommit(second.id, false, () -> { }));
        assertArrayEquals(fixture, store.bytes);
        assertArrayEquals(new byte[] {3}, store.quick);
    }

    @Test public void previewExpirationAndTargetReplacementRejectCommit() throws Exception {
        AppSecrets.ImportPreview first = vault.preview(fixture, PASSWORD, PASSWORD, "late", () -> { });
        clock.milliseconds = 120000;
        rejects("VAULT_EXPIRED", () -> vault.importCommit(first.id, true, () -> { }));
        clock.milliseconds = 0;
        AppSecrets.ImportPreview pending = vault.preview(fixture, PASSWORD, PASSWORD, "changed", () -> { });
        String id = pending.id;
        store.bytes = fixture.clone();
        store.bytes[fixture.length - 1] ^= 1;
        rejects("VAULT_CHANGED", () -> vault.importCommit(id, true, () -> { }));
    }

    @Test public void emptyTargetNeedsOnlySourcePassword() throws Exception {
        store.bytes = null;
        AppSecrets.ImportPreview pending = vault.preview(fixture, PASSWORD, null, "empty", () -> { });
        vault.importCommit(pending.id, pending.reviewBinding, true, () -> { });
        assertNotNull(store.bytes);
    }

    @Test public void previewExpiryWipesTemporaryKeyPlaintextAndSeeds() throws Exception {
        AppSecrets.ImportPreview pending = vault.preview(fixture, PASSWORD, PASSWORD, "expiry", () -> { });
        clock.milliseconds = pending.deadline;
        vault.expirePreview(pending.id);
        assertArrayEquals(new byte[pending.source.key.length], pending.source.key);
        assertArrayEquals(new byte[pending.source.plaintext.length], pending.source.plaintext);
        assertArrayEquals(new byte[pending.source.manifest.entries.get(0).secret.length], pending.source.manifest.entries.get(0).secret);
        rejects("VAULT_EXPIRED", () -> vault.importCommit(pending.id, true, () -> { }));
    }

    @Test public void lockDuringCreateRejectsLateEffectWithoutHalfVault() throws Exception {
        store.bytes = null;
        CountDownLatch beforeEffect = new CountDownLatch(1);
        CountDownLatch allowEffect = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { vault.create(PASSWORD, PASSWORD, () -> {
                beforeEffect.countDown();
                try { if (!allowEffect.await(10, TimeUnit.SECONDS)) throw new AssertionError("Effect timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }); } catch (Throwable error) { failure.set(error); }
        });
        thread.start();
        assertTrue(beforeEffect.await(10, TimeUnit.SECONDS));
        // Lifecycle lock is owned by VaultSession and must not wait for the crypto owner's effect monitor.
        vault.session.lock();
        allowEffect.countDown();
        thread.join(10000);
        assertFalse(thread.isAlive());
        assertTrue(failure.get() instanceof VaultException);
        assertEquals("VAULT_CHANGED", ((VaultException) failure.get()).code);
        assertNull(store.bytes);
        assertFalse(vault.session.unlocked());
    }

    @Test public void twoConsumersRejectCrossPurposeTargetEntryReplayAndGeneration() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        TotpVaultAdapter totp = new TotpVaultAdapter(vault);
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> {
            if (!connection.equals("connection1") || !target.equals("target:test@example.invalid:22")) throw new VaultException("HOST_KEY", "Not verified");
        });
        VaultOperation code = totp.request("totp1");
        rejects("VAULT_OPERATION", () -> ssh.useCredential(code, "ssh1", "target:test@example.invalid:22", "connection1", bytes -> fail()));
        rejects("VAULT_OPERATION", () -> totp.currentCode(code, "different"));
        assertEquals("287082", vault.totpCode(code, "totp1", 59));
        rejects("VAULT_OPERATION", () -> totp.currentCode(code, "totp1"));
        rejects("HOST_KEY", () -> ssh.credentialRequest("ssh1", "target:other@example.invalid:22", "connection1"));
        VaultOperation credential = ssh.credentialRequest("ssh1", "target:test@example.invalid:22", "connection1");
        AtomicReference<byte[]> loan = new AtomicReference<>();
        ssh.useCredential(credential, "ssh1", "target:test@example.invalid:22", "connection1", bytes -> {
            assertEquals("test-only remote password", new String(bytes, StandardCharsets.UTF_8)); loan.set(bytes);
        });
        assertArrayEquals(new byte[loan.get().length], loan.get());
        VaultOperation stale = totp.request("totp1");
        vault.lock();
        rejects("VAULT_OPERATION", () -> totp.currentCode(stale, "totp1"));
    }

    @Test public void sshSnapshotContainsOnlyRequestedClosureAndLoanIsWiped() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> { });
        VaultOperation request = ssh.fileRequest("config", "evaluate1");
        rejects("VAULT_OPERATION", () -> ssh.useFiles(request, "config", "other", files -> fail()));
        List<byte[]> loans = new ArrayList<>();
        ssh.useFiles(request, "config", "evaluate1", files -> {
            assertEquals(2, files.size());
            assertTrue(files.stream().noneMatch(f -> f.type == VaultManifest.FileType.PRIVATE_KEY));
            for (VaultManifest.FileObject file : files) loans.add(file.content);
        });
        for (byte[] loan : loans) assertArrayEquals(new byte[loan.length], loan);
        rejects("VAULT_OPERATION", () -> ssh.fileRequest("keys/key", "evaluate1"));
    }

    @Test public void privateKeyReleaseNeedsMatchingHostIdentityAndConnection() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> {
            if (!connection.equals("hop1") || !target.equals("target:test@example.invalid:22")) throw new VaultException("HOST_KEY", "Unverified hop");
        });
        rejects("HOST_KEY", () -> ssh.privateKeyRequest("keys/key", "test-key-identity", "target:other", "hop1"));
        VaultOperation operation = ssh.privateKeyRequest("keys/key", "test-key-identity", "target:test@example.invalid:22", "hop1");
        rejects("VAULT_OPERATION", () -> ssh.usePrivateKey(operation, "keys/key", "wrong-key", "target:test@example.invalid:22", "hop1", bytes -> fail()));
        rejects("HOST_KEY", () -> ssh.usePrivateKey(operation, "keys/key", "test-key-identity", "target:test@example.invalid:22", "hop2", bytes -> fail()));
        ssh.usePrivateKey(operation, "keys/key", "test-key-identity", "target:test@example.invalid:22", "hop1",
                bytes -> assertEquals("test-only-key", new String(bytes, StandardCharsets.UTF_8)));
    }

    @Test public void sshFileMetadataDescribesPrivateKeyWithoutReleasingItsBody() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> {
            throw new VaultException("HOST_KEY", "Unverified fixture host");
        });
        VaultOperation operation = ssh.fileRequest("config", "metadata1");
        AtomicReference<SshFileSnapshot> receipt = new AtomicReference<>();
        List<byte[]> loans = new ArrayList<>();
        rejects("VAULT_OPERATION", () -> ssh.useFileSnapshot(operation, "config", "other", snapshot -> fail()));
        ssh.useFileSnapshot(operation, "config", "metadata1", snapshot -> {
            receipt.set(snapshot);
            assertEquals("config", snapshot.rootPath);
            assertEquals("metadata1", snapshot.connectionId);
            assertEquals(operation.requestId, snapshot.requestId);
            assertEquals(operation.vaultId, snapshot.vaultId);
            assertEquals(operation.generation, snapshot.generation);
            assertEquals(operation.epoch, snapshot.epoch);
            assertEquals(operation.deadline, snapshot.deadline);
            assertArrayEquals(operation.snapshotBinding, snapshot.binding());
            byte[] changed = snapshot.binding();
            Arrays.fill(changed, (byte)0);
            assertArrayEquals(operation.snapshotBinding, snapshot.binding());
            assertEquals(3, snapshot.metadata.size());
            SshFileSnapshot.Metadata key = snapshot.metadata.get(1);
            assertEquals(VaultManifest.FileType.PRIVATE_KEY, key.type);
            assertEquals("key1", key.id);
            assertEquals("keys/key", key.path);
            assertEquals("test-key-identity", key.identity);
            assertEquals("test-only-key".getBytes(StandardCharsets.UTF_8).length, key.contentBytes);
            assertTrue(key.references.isEmpty());
            assertEquals(2, snapshot.publicFiles.size());
            for (VaultManifest.FileObject file : snapshot.publicFiles) {
                assertNotEquals(VaultManifest.FileType.PRIVATE_KEY, file.type);
                assertFalse(Arrays.equals("test-only-key".getBytes(StandardCharsets.UTF_8), file.content));
                loans.add(file.content);
            }
            try { snapshot.metadata.clear(); fail("mutable metadata"); }
            catch (UnsupportedOperationException expected) { }
            try { snapshot.publicFiles.clear(); fail("mutable public loan list"); }
            catch (UnsupportedOperationException expected) { }
            try {
                ssh.privateKeyRequest(key.path, key.identity, "target:test@example.invalid:22", "metadata1");
                fail("Private metadata cannot authorize a key release");
            } catch (VaultException failure) { assertEquals("HOST_KEY", failure.code); }
        });
        for (byte[] loan : loans) assertArrayEquals(new byte[loan.length], loan);
        assertEquals("test-key-identity", receipt.get().metadata.get(1).identity);
        rejects("VAULT_OPERATION", () -> ssh.useFiles(operation, "config", "metadata1", files -> fail()));
    }

    @Test public void sshFileSnapshotClosesBorrowedBodiesWhenReceiverThrows() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> { });
        VaultOperation operation = ssh.fileRequest("config", "metadata2");
        List<byte[]> loans = new ArrayList<>();
        rejects("FIXTURE", () -> ssh.useFileSnapshot(operation, "config", "metadata2", snapshot -> {
            for (VaultManifest.FileObject file : snapshot.publicFiles) loans.add(file.content);
            throw new VaultException("FIXTURE", "Synthetic receiver failure");
        }));
        assertEquals(2, loans.size());
        for (byte[] loan : loans) assertArrayEquals(new byte[loan.length], loan);
        rejects("VAULT_OPERATION", () -> ssh.useFileSnapshot(operation, "config", "metadata2", snapshot -> fail()));
    }

    @Test public void sshFileSnapshotRejectsCancelledAndPostValidationLockBeforeReceiver() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> { });
        VaultOperation cancelled = ssh.fileRequest("config", "metadata3");
        ssh.cancel(cancelled);
        rejects("VAULT_CANCELLED", () -> ssh.useFileSnapshot(cancelled, "config", "metadata3", snapshot -> fail()));
        VaultOperation locked = ssh.fileRequest("config", "metadata3");
        vault.attachSshValidator(manifest -> vault.session.lock());
        rejects("VAULT_CHANGED", () -> ssh.useFileSnapshot(locked, "config", "metadata3", snapshot -> fail()));
    }

    @Test public void lockDuringManifestValidationRejectsPreparedConsumerEffects() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        VaultOperation totp = new TotpVaultAdapter(vault).request("totp1");
        vault.attachSshValidator(manifest -> vault.session.lock());
        rejects("VAULT_CHANGED", () -> vault.totpCode(totp, "totp1", 59));
        vault.attachSshValidator(manifest -> { });
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> { });
        String target = "target:test@example.invalid:22";
        VaultOperation credential = ssh.credentialRequest("ssh1", target, "connection1");
        vault.attachSshValidator(manifest -> vault.session.lock());
        rejects("VAULT_CHANGED", () -> ssh.useCredential(credential, "ssh1", target, "connection1", bytes -> fail("Secret reached receiver")));
    }

    @Test public void cancellingOneConsumerDoesNotLockOthersAndStopsPreparedEffects() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, target) -> { });
        VaultOperation request = ssh.fileRequest("config", "evaluate1");
        ssh.cancel(request);
        rejects("VAULT_CANCELLED", () -> ssh.useFiles(request, "config", "evaluate1", files -> fail()));
        assertTrue(vault.session.unlocked());
        VaultOperation prepared = ssh.fileRequest("config", "evaluate2");
        vault.attachSshValidator(manifest -> ssh.cancel(prepared));
        rejects("VAULT_CANCELLED", () -> ssh.useFiles(prepared, "config", "evaluate2", files -> fail()));
        vault.attachSshValidator(manifest -> { });
        TotpVaultAdapter totp = new TotpVaultAdapter(vault);
        assertEquals(6, totp.currentCode(totp.request("totp1"), "totp1").length());
        VaultOperation save = totp.saveRequest("totp2");
        byte[] before = store.bytes.clone();
        vault.attachSshValidator(manifest -> totp.cancel(save));
        rejects("VAULT_CANCELLED", () -> totp.save(save, "totp2", "Test:account", "Test", new byte[20], () -> { }));
        assertArrayEquals(before, store.bytes);
    }

    @Test public void lateInvalidationCleanupCannotLockNewOwnerSessionOrRequests() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        vault.session.lock();
        vault.unlock(PASSWORD, () -> { });
        TotpVaultAdapter adapter = new TotpVaultAdapter(vault);
        VaultOperation request = adapter.request("totp1");
        vault.cleanupInvalidatedContexts();
        assertTrue(vault.session.unlocked());
        assertEquals(6, adapter.currentCode(request, "totp1").length());
    }

    @Test public void typedMutationExpiryAtFinalCommitRetainsCiphertextAndShortcut() throws Exception {
        vault.unlock(PASSWORD, () -> { });
        store.quick = new byte[] {1, 2, 3};
        byte[] before = store.bytes.clone();
        byte[] wrapper = store.quick.clone();
        TotpVaultAdapter totp = new TotpVaultAdapter(vault);
        VaultOperation operation = totp.saveRequest("totp2");
        store.beforeGuard = () -> clock.milliseconds = operation.deadline;
        rejects("VAULT_EXPIRED", () -> totp.save(operation, "totp2", "Test:account", "Test", new byte[20], () -> { }));
        assertArrayEquals(before, store.bytes);
        assertArrayEquals(wrapper, store.quick);
        assertTrue(vault.session.unlocked());
        store.beforeGuard = null;
        rejects("VAULT_OPERATION", () -> totp.save(operation, "totp2", "Test:account", "Test", new byte[20], () -> { }));
    }

    @Test public void finalHostKeyGateInvalidationCannotReleaseCredentialOrPrivateKey() throws Exception {
        String target = "target:test@example.invalid:22";
        for (boolean privateKey : new boolean[] {false, true}) {
            for (int mode = 0; mode < 3; mode++) {
                vault.unlock(PASSWORD, () -> { });
                AtomicReference<VaultOperation> operation = new AtomicReference<>();
                int invalidation = mode;
                int[] checks = {0};
                SshVaultAdapter ssh = new SshVaultAdapter(vault, (connection, identity) -> {
                    if (++checks[0] != 3) return;
                    if (invalidation == 0) vault.session.lock();
                    else if (invalidation == 1) vault.cancelOperation(operation.get());
                    else clock.milliseconds = operation.get().deadline;
                });
                operation.set(privateKey
                        ? ssh.privateKeyRequest("keys/key", "test-key-identity", target, "connection1")
                        : ssh.credentialRequest("ssh1", target, "connection1"));
                String expected = mode == 0 ? "VAULT_CHANGED" : mode == 1 ? "VAULT_CANCELLED" : "VAULT_EXPIRED";
                rejects(expected, () -> {
                    if (privateKey) ssh.usePrivateKey(operation.get(), "keys/key", "test-key-identity", target,
                            "connection1", bytes -> fail("Private key reached receiver after invalidation"));
                    else ssh.useCredential(operation.get(), "ssh1", target, "connection1",
                            bytes -> fail("Credential reached receiver after invalidation"));
                });
                assertEquals(3, checks[0]);
            }
        }
    }

    @Test(timeout = 2000) public void denseManifestDagHasBoundedValidationWork() throws Exception {
        List<VaultManifest.FileObject> files = new ArrayList<>();
        for (int layer = 7; layer >= 0; layer--) {
            List<String> refs = new ArrayList<>();
            if (layer < 7) for (int index = 0; index < 12; index++) refs.add("l" + (layer + 1) + "f" + index);
            for (int index = 0; index < 12; index++) files.add(file("l" + layer + "f" + index, "l" + layer + "f" + index, refs));
        }
        try (VaultManifest manifest = new VaultManifest(List.of(), files)) { assertEquals(96, manifest.files.size()); }
    }

    private static VaultManifest mixedManifest() throws Exception {
        VaultManifest.Entry totp = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp1", "Example:test",
                "Example", "SHA1", 6, 30, "12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        VaultManifest.Entry ssh = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "ssh1",
                "target:test@example.invalid:22", "test", "", 0, 0, "test-only remote password".getBytes(StandardCharsets.UTF_8));
        VaultManifest.FileObject config = file("config1", "config", List.of("keys/key", "known_hosts"));
        VaultManifest.FileObject key = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "key1", "keys/key",
                "test-key-identity", "test-only-key".getBytes(StandardCharsets.UTF_8), List.of());
        VaultManifest.FileObject trust = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, "trust1", "known_hosts",
                "test-trust", "example.invalid test-only-trust".getBytes(StandardCharsets.UTF_8), List.of());
        return new VaultManifest(List.of(totp, ssh), List.of(config, key, trust));
    }
    private static VaultManifest.FileObject file(String id, String path, List<String> references) {
        return new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, id, path, "test-config",
                "Host test\n HostName example.invalid\n".getBytes(StandardCharsets.UTF_8), references);
    }
    private static byte[] repeat(int count, int value) { byte[] bytes = new byte[count]; Arrays.fill(bytes, (byte) value); return bytes; }
    private static String hex(byte[] bytes) { StringBuilder text = new StringBuilder(); for (byte value : bytes) text.append(String.format("%02x", value)); return text.toString(); }
}
