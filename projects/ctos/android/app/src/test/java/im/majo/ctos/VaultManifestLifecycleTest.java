package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.BeforeClass;
import org.junit.Test;

/** Real encrypted fixtures test preservation and gates, without certifying SSH semantics. */
public final class VaultManifestLifecycleTest {
    private static final byte[] KEY = new byte[32];
    private static final char[] PASSWORD = "synthetic lifecycle password".toCharArray();
    private static final char[] NEXT_PASSWORD = "synthetic replacement password".toCharArray();
    private static final String TARGET = "target:person@example.invalid:22";
    private static final String KEY_TARGET = "target:key-alias";
    private static final AppSecrets.EffectGuard OPEN = () -> { };
    private static final byte[] PRIVATE = {1, 2, 3}, PUBLIC = {4, 5, 6}, CERTIFICATE = {7, 8, 9};
    private static final byte[] OLD_HOSTS = {10, 11, 12}, NEXT_HOSTS = {13, 14, 15};
    private static final byte[] OLD_PASSWORD = {16, 17, 18}, NEXT_SECRET = {19, 20, 21};
    private static final byte[] LEGACY_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[][] CURRENT = new byte[3][], LEGACY_TOTP = new byte[2][];
    private static final byte[][] UNREVIEWED = new byte[3][];
    private static byte[] GRAPH;

    private static final class Clock implements VaultSession.Clock {
        long now = 100;
        @Override public long now() { return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes, quick = {31, 32};
        byte[] restoreTarget;
        int writes, claims, restoreReads;
        boolean committing, restoreCommit, failCommit;
        void permitRestoreRead() {
            restoreCommit = true; restoreTarget = bytes == null ? null : bytes.clone();
        }
        @Override public byte[] read() {
            if (committing) {
                // Restore rechecks its target inside the commit guard; mutation paths do not.
                assertTrue("Unexpected mutation read during commit", restoreCommit);
                assertArrayEquals(restoreTarget, bytes); restoreReads++;
            }
            return bytes == null ? null : bytes.clone();
        }
        @Override public byte[] quickMaterial() {
            assertFalse(committing);
            return quick == null ? null : quick.clone();
        }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            if (failCommit) throw new IOException("Synthetic failure before effect");
            committing = true;
            try {
                guard.beforeCommit(); claims++;
                bytes = next.clone(); quick = wrapper == null ? null : wrapper.clone(); writes++;
            } finally { committing = false; }
        }
    }
    private interface Action { void run() throws Exception; }

    @BeforeClass public static void encryptedFixtures() throws Exception {
        byte[] salt = new byte[16], key = VaultEnvelope.derive(PASSWORD, salt);
        try {
            for (int kind = 0; kind < 3; kind++) {
                try (VaultManifest source = fixture(false, kind)) { CURRENT[kind] = encrypt(source, key, salt); }
            }
            for (int metadata = 1; metadata <= 3; metadata++) {
                try (VaultManifest source = fixture(metadata, 2)) { UNREVIEWED[metadata - 1] = encrypt(source, key, salt); }
            }
            GRAPH = UNREVIEWED[2];
            for (int kind = 0; kind < 2; kind++) {
                byte[] plaintext = legacyPlaintext(kind != 0);
                try { LEGACY_TOTP[kind] = VaultEnvelope.encrypt(UUID.randomUUID(), 7, salt, key, plaintext).encode(); }
                finally { VaultCodec.wipe(plaintext); }
            }
        } finally { VaultCodec.wipe(key); }
    }

    private static VaultManifest fixture(boolean withMetadata, int kind) throws VaultException {
        return fixture(withMetadata ? 3 : 0, kind);
    }
    private static VaultManifest fixture(int metadata, int kind) throws VaultException {
        List<VaultManifest.Entry> entries = new ArrayList<>();
        List<VaultManifest.FileObject> files = new ArrayList<>();
        if (kind > 0) entries.add(new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp", "Issuer:account",
                "Original TOTP", "SHA1", 6, 30, new byte[20]));
        if (kind == 2) {
            entries.add(new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "password", TARGET,
                    "Original password", "", 0, 0, OLD_PASSWORD));
            entries.add(new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE, "phrase", "key:identity-one",
                    "Original phrase", "", 0, 0, new byte[] {22, 23, 24}));
            files.add(file(VaultManifest.FileType.PRIVATE_KEY, "private", "private", "identity-one", PRIVATE));
            files.add(file(VaultManifest.FileType.PUBLIC_KEY, "public", "public", "identity-one", PUBLIC));
            files.add(file(VaultManifest.FileType.CERTIFICATE, "certificate", "certificate", "identity-one", CERTIFICATE));
            files.add(file(VaultManifest.FileType.KNOWN_HOSTS, "hosts", "known_hosts", "trust-one", OLD_HOSTS));
        }
        if (metadata == 0) return new VaultManifest(entries, files);
        List<VaultSshMetadata.KeyBinding> bindings = kind == 2 && (metadata & 1) != 0 ? List.of(new VaultSshMetadata.KeyBinding(
                "binding", "private", "public", List.of("certificate"), "phrase")) : List.of();
        List<VaultSshMetadata.Account> accounts = new ArrayList<>();
        if (kind == 2 && (metadata & 2) != 0) {
            accounts.add(new VaultSshMetadata.Account("password-account", TARGET, "EXAMPLE.invalid.", 2200,
                    " Person \u03bb ", VaultSshMetadata.AuthKind.PASSWORD, "password"));
            if ((metadata & 1) != 0) accounts.add(new VaultSshMetadata.Account("key-account", KEY_TARGET,
                    "[fe80::1%test]", 2222, "key user", VaultSshMetadata.AuthKind.KEY, "binding"));
        }
        return new VaultManifest(entries, files, bindings, accounts);
    }

    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id, String path,
            String identity, byte[] content) {
        return new VaultManifest.FileObject(type, id, path, identity, content, List.of());
    }
    private static byte[] encrypt(VaultManifest manifest, byte[] key, byte[] salt) throws VaultException {
        byte[] plaintext = manifest.encode();
        try { return VaultEnvelope.encrypt(UUID.randomUUID(), 7, salt, key, plaintext).encode(); }
        finally { VaultCodec.wipe(plaintext); }
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static byte[] legacyPlaintext(boolean populated) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1); out.writeInt(populated ? 1 : 0);
        if (populated) {
            out.writeByte(0); text(out, "legacy-totp"); text(out, "Issuer:legacy");
            text(out, "Saved account"); text(out, "SHA1"); out.writeInt(6); out.writeInt(30);
            out.writeInt(LEGACY_SEED.length); out.write(LEGACY_SEED);
        }
        out.writeInt(0);
        return bytes.toByteArray();
    }
    private static void currentEncoding(VaultManifest manifest) {
        byte[] bytes = null;
        try { bytes = manifest.encode(); assertEquals(2, ByteBuffer.wrap(bytes).getInt()); }
        catch (VaultException failure) { throw new AssertionError(failure); }
        finally { VaultCodec.wipe(bytes); }
    }
    private static void legacyEntry(VaultManifest manifest, String label) {
        assertEquals(1, manifest.entries.size());
        VaultManifest.Entry saved = entry(manifest, "legacy-totp");
        assertEquals(VaultManifest.EntryType.TOTP, saved.type); assertEquals("Issuer:legacy", saved.identity);
        assertEquals(label, saved.label); assertEquals("SHA1", saved.algorithm);
        assertEquals(6, saved.digits); assertEquals(30, saved.period); assertArrayEquals(LEGACY_SEED, saved.secret);
        assertTrue(manifest.files.isEmpty()); assertTrue(manifest.keyBindings.isEmpty()); assertTrue(manifest.accounts.isEmpty());
    }
    private static void unchangedLoan(VaultManifest loan, byte[] authenticated) throws Exception {
        Method check = AppSecrets.class.getDeclaredMethod("requireUnchangedLoan", VaultManifest.class, byte[].class);
        check.setAccessible(true);
        try { check.invoke(null, loan, authenticated); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw (Error) cause;
        }
    }
    private static VaultManifest decode(byte[] ciphertext) throws VaultException {
        byte[] plaintext = VaultEnvelope.parse(ciphertext).decrypt(KEY);
        try { return VaultManifest.decode(plaintext); }
        finally { VaultCodec.wipe(plaintext); }
    }
    private static AppSecrets vault(MemoryStore store, Clock clock) {
        AppSecrets vault = new AppSecrets(store, clock); vault.session.foreground(true); return vault;
    }
    private static MemoryStore installMixed() throws Exception {
        MemoryStore store = new MemoryStore();
        try (VaultManifest source = fixture(true, 2)) { store.bytes = encrypt(source, KEY, new byte[16]); }
        return store;
    }
    private static void graph(VaultManifest manifest) {
        currentEncoding(manifest);
        assertEquals(1, manifest.keyBindings.size()); assertEquals(2, manifest.accounts.size());
        VaultSshMetadata.KeyBinding binding = manifest.keyBindings.get(0);
        assertEquals("binding", binding.id); assertEquals("private", binding.privateFileId);
        assertEquals("public", binding.publicFileId); assertEquals(List.of("certificate"), binding.certificateFileIds);
        assertEquals("phrase", binding.passphraseEntryId);
        VaultSshMetadata.Account password = manifest.accounts.get(0), key = manifest.accounts.get(1);
        assertEquals("password-account", password.id); assertEquals(TARGET, password.targetIdentity);
        assertEquals("EXAMPLE.invalid.", password.host); assertEquals(2200, password.port);
        assertEquals(" Person \u03bb ", password.username); assertEquals(VaultSshMetadata.AuthKind.PASSWORD, password.authKind);
        assertEquals("password", password.credentialId); assertEquals("key-account", key.id);
        assertEquals(KEY_TARGET, key.targetIdentity); assertEquals("[fe80::1%test]", key.host);
        assertEquals(2222, key.port); assertEquals("key user", key.username);
        assertEquals(VaultSshMetadata.AuthKind.KEY, key.authKind); assertEquals("binding", key.credentialId);
    }
    private static VaultManifest.Entry entry(VaultManifest manifest, String id) {
        for (VaultManifest.Entry entry : manifest.entries) if (entry.id.equals(id)) return entry;
        throw new AssertionError("Missing entry " + id);
    }
    private static VaultManifest.FileObject object(VaultManifest manifest, String id) {
        for (VaultManifest.FileObject file : manifest.files) if (file.id.equals(id)) return file;
        throw new AssertionError("Missing object " + id);
    }
    private static void wiped(VaultManifest manifest) {
        for (VaultManifest.Entry entry : manifest.entries) assertArrayEquals(new byte[entry.secret.length], entry.secret);
        for (VaultManifest.FileObject file : manifest.files) assertArrayEquals(new byte[file.content.length], file.content);
    }
    private static void independent(List<VaultManifest> loans) {
        for (int i = 0; i < loans.size(); i++) for (int j = 0; j < i; j++) {
            for (VaultManifest.Entry entry : loans.get(i).entries) for (VaultManifest.Entry other : loans.get(j).entries)
                assertNotSame(entry.secret, other.secret);
            for (VaultManifest.FileObject file : loans.get(i).files) for (VaultManifest.FileObject other : loans.get(j).files)
                assertNotSame(file.content, other.content);
        }
    }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }
    private static SshVaultAdapter adapter(AppSecrets vault) {
        return new SshVaultAdapter(vault, (connection, target) -> { }, trust -> { }, bundle -> { });
    }
    private static void record(AppSecrets vault, List<VaultManifest> loans) {
        vault.attachSshValidator(loan -> {
            assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
            graph(loan); loans.add(loan);
        });
    }

    @Test public void allTotpMutationKindsRetainGraphInAuthenticatedSourceAndCandidateLoans() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            MemoryStore store = installMixed(); AppSecrets vault = vault(store, new Clock()); vault.session.unlock(KEY);
            List<VaultManifest> loans = new ArrayList<>(); record(vault, loans);
            VaultOperation operation;
            if (kind == 0) {
                operation = vault.beginTotpRename("totp"); vault.renameTotp(operation, "totp", "Renamed", OPEN);
            } else if (kind == 1) {
                operation = vault.beginTotpDelete("totp"); vault.deleteTotp(operation, "totp", OPEN);
            } else {
                String id = kind == 2 ? "totp" : "added-totp";
                operation = vault.beginTotpSave(id);
                if (kind == 2) vault.saveTotp(operation, id, "Issuer:new", "Saved", new byte[20], OPEN);
                else vault.addTotp(operation, id, "Issuer:added", "Added", new byte[20], OPEN);
            }
            assertEquals(3, loans.size()); independent(loans); for (VaultManifest loan : loans) wiped(loan);
            try (VaultManifest result = decode(store.bytes)) {
                graph(result); assertArrayEquals(OLD_PASSWORD, entry(result, "password").secret);
                assertArrayEquals(PRIVATE, object(result, "private").content);
                if (kind == 0) assertEquals("Renamed", entry(result, "totp").label);
                if (kind == 1) assertEquals(2, result.entries.size());
                if (kind == 2) assertEquals("Issuer:new", entry(result, "totp").identity);
                if (kind == 3) assertEquals(4, result.entries.size());
            }
            assertEquals(1, store.writes); assertEquals(1, store.claims); assertTrue(operation.consumed);
            assertArrayEquals(new byte[] {31, 32}, store.quick);
        }
    }

    @Test public void pureTotpAndEmptyUseTheSameWriterDuringTotpWrites() throws Exception {
        for (int sourceKind = 0; sourceKind < 2; sourceKind++) {
            MemoryStore store = new MemoryStore();
            try (VaultManifest source = fixture(false, sourceKind)) { store.bytes = encrypt(source, KEY, new byte[16]); }
            AppSecrets vault = vault(store, new Clock()); vault.session.unlock(KEY);
            vault.attachSshValidator(loan -> { throw new AssertionError("Pure TOTP does not need SSH validation"); });
            VaultOperation operation = vault.beginTotpSave("new-totp");
            vault.addTotp(operation, "new-totp", "Issuer:new", "New", new byte[20], OPEN);
            try (VaultManifest result = decode(store.bytes)) {
                currentEncoding(result); assertTrue(result.keyBindings.isEmpty()); assertTrue(result.accounts.isEmpty());
                assertEquals(sourceKind + 1, result.entries.size());
            }
        }
    }

    @Test public void passwordAndPhraseReplacementPreserveAccountsAndBindingInEveryLoan() throws Exception {
        for (boolean phrase : new boolean[] {false, true}) {
            MemoryStore store = installMixed(); AppSecrets vault = vault(store, new Clock()); vault.session.unlock(KEY);
            List<VaultManifest> loans = new ArrayList<>(); record(vault, loans);
            String id = phrase ? "phrase" : "password", target = phrase ? "key:identity-one" : TARGET;
            SshVaultAdapter adapter = adapter(vault);
            VaultOperation operation = adapter.saveRequest(id, target, "connection");
            adapter.save(operation, id, target, "connection", "Replacement", phrase, NEXT_SECRET, OPEN);
            assertEquals(3, loans.size()); independent(loans); for (VaultManifest loan : loans) wiped(loan);
            try (VaultManifest result = decode(store.bytes)) {
                graph(result); assertArrayEquals(NEXT_SECRET, entry(result, id).secret);
                assertArrayEquals(PRIVATE, object(result, "private").content);
            }
            assertEquals(1, store.writes); assertEquals(1, store.claims);
        }
    }

    @Test public void deletingSelectedPasswordOrPhraseRejectsWholeCandidateBeforeEffect() throws Exception {
        for (boolean phrase : new boolean[] {false, true}) {
            MemoryStore store = installMixed(); byte[] original = store.bytes.clone();
            AppSecrets vault = vault(store, new Clock()); vault.session.unlock(KEY);
            List<VaultManifest> loans = new ArrayList<>(); record(vault, loans);
            SshVaultAdapter adapter = adapter(vault);
            String id = phrase ? "phrase" : "password", target = phrase ? "key:identity-one" : TARGET;
            VaultOperation operation = adapter.deleteRequest(id, target);
            rejects("VAULT_CONTENT", () -> adapter.delete(operation, id, target, OPEN));
            assertEquals(2, loans.size()); for (VaultManifest loan : loans) wiped(loan);
            assertEquals(0, store.writes); assertEquals(0, store.claims); assertArrayEquals(original, store.bytes);
            try (VaultManifest result = decode(store.bytes)) { graph(result); assertNotNull(entry(result, id)); }
        }
    }

    @Test public void trustCasAndBundleReplacementRetainGraphAndIndependentLoans() throws Exception {
        for (boolean trust : new boolean[] {true, false}) {
            MemoryStore store = installMixed(); AppSecrets vault = vault(store, new Clock()); vault.session.unlock(KEY);
            List<VaultManifest> loans = new ArrayList<>(); record(vault, loans);
            if (trust) {
                try (SshTrustProposal proposal = new SshTrustProposal("known_hosts", "hosts", TARGET, "connection",
                        "example.invalid", 22, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        VaultCodec.digest(OLD_HOSTS), NEXT_HOSTS)) {
                    VaultOperation operation = vault.beginTrustUpdate(proposal, vault.newSshTrustContext(proposal), OPEN);
                    vault.updateTrust(operation, proposal, OPEN);
                }
            } else {
                try (VaultManifest source = decode(store.bytes)) {
                    VaultManifest.FileObject next = file(VaultManifest.FileType.PUBLIC_KEY, "public", "public", "identity-one", NEXT_SECRET);
                    try (SshBundleProposal.Change change = SshBundleProposal.Change.replace(object(source, "public"), next);
                            SshBundleProposal proposal = new SshBundleProposal(List.of(change))) {
                        SshVaultAdapter adapter = adapter(vault);
                        adapter.commitBundle(adapter.bundleRequest(proposal), proposal, OPEN);
                    } finally { VaultCodec.wipe(next.content); }
                    assertArrayEquals(PUBLIC, object(source, "public").content);
                }
            }
            assertEquals(trust ? 3 : 4, loans.size()); independent(loans); for (VaultManifest loan : loans) wiped(loan);
            try (VaultManifest result = decode(store.bytes)) {
                graph(result); assertArrayEquals(trust ? NEXT_HOSTS : OLD_HOSTS, object(result, "hosts").content);
                assertArrayEquals(trust ? PUBLIC : NEXT_SECRET, object(result, "public").content);
                assertArrayEquals(OLD_PASSWORD, entry(result, "password").secret);
            }
            assertEquals(1, store.writes); assertEquals(1, store.claims);
        }
    }

    @Test public void referencedFileDeleteRetypeAndPrivateReidentityCannotPruneGraph() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            MemoryStore store = installMixed(); byte[] original = store.bytes.clone();
            AppSecrets vault = vault(store, new Clock()); vault.session.unlock(KEY);
            List<VaultManifest> loans = new ArrayList<>(); record(vault, loans);
            try (VaultManifest source = decode(store.bytes)) {
                VaultManifest.FileObject old = object(source, kind == 2 ? "private" : "public");
                VaultManifest.FileObject next = kind == 0 ? null : file(kind == 1 ? VaultManifest.FileType.CERTIFICATE : old.type,
                        old.id, old.path, kind == 2 ? "different-identity" : old.identity, NEXT_SECRET);
                try (SshBundleProposal.Change change = kind == 0 ? SshBundleProposal.Change.delete(old)
                        : SshBundleProposal.Change.replace(old, next);
                        SshBundleProposal proposal = new SshBundleProposal(List.of(change))) {
                    rejects("VAULT_CONTENT", () -> adapter(vault).bundleRequest(proposal));
                } finally { if (next != null) VaultCodec.wipe(next.content); }
                graph(source); assertArrayEquals(PRIVATE, object(source, "private").content);
                assertArrayEquals(PUBLIC, object(source, "public").content);
            }
            assertEquals(1, loans.size()); wiped(loans.get(0));
            assertEquals(0, store.writes); assertEquals(0, store.claims); assertArrayEquals(original, store.bytes);
        }
    }

    @Test public void failedCandidateOrMutatedLoanWipesLoansAndKeepsOriginalBytes() throws Exception {
        MemoryStore store = installMixed(); byte[] original = store.bytes.clone();
        AppSecrets vault = vault(store, new Clock()); vault.session.unlock(KEY);
        List<VaultManifest> retained = new ArrayList<>(); AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
        vault.attachSshValidator(loan -> {
            graph(loan); retained.add(loan);
            if (armed.get() && calls.incrementAndGet() == 2) entry(loan, "password").secret[0] ^= 1;
        });
        VaultOperation operation = vault.beginTotpRename("totp"); armed.set(true);
        rejects("VAULT_CONTENT", () -> vault.renameTotp(operation, "totp", "Not saved", OPEN));
        assertEquals(3, retained.size()); independent(retained); for (VaultManifest loan : retained) wiped(loan);
        assertEquals(0, store.writes); assertEquals(0, store.claims); assertArrayEquals(original, store.bytes);
        try (VaultManifest source = decode(store.bytes)) { graph(source); assertEquals("Original TOTP", entry(source, "totp").label); }
    }

    @Test public void exportAndPasswordChangePreserveExactCurrentAuthenticatedPlaintext() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = GRAPH.clone();
        AppSecrets vault = vault(store, new Clock());
        assertArrayEquals(GRAPH, vault.export(PASSWORD, OPEN)); assertEquals(0, store.writes);
        byte[] plaintext;
        VaultEnvelope before;
        try (AppSecrets.Verified verified = AppSecrets.verify(store.bytes, PASSWORD)) {
            graph(verified.manifest); plaintext = verified.plaintext.clone(); before = verified.envelope;
        }
        try {
            vault.changePassword(PASSWORD, NEXT_PASSWORD, NEXT_PASSWORD, OPEN);
            try (AppSecrets.Verified changed = AppSecrets.verify(store.bytes, NEXT_PASSWORD)) {
                graph(changed.manifest); assertArrayEquals(plaintext, changed.plaintext);
                assertEquals(before.id, changed.envelope.id); assertEquals(before.generation + 1, changed.envelope.generation);
                assertFalse(Arrays.equals(before.salt, changed.envelope.salt));
            }
            rejects("VAULT_AUTHENTICATION", () -> AppSecrets.verify(store.bytes, PASSWORD));
            assertArrayEquals(store.bytes, vault.export(NEXT_PASSWORD, OPEN));
            assertEquals(1, store.writes); assertEquals(1, store.claims);
        } finally { VaultCodec.wipe(plaintext); }
    }

    @Test public void normalCreateUsesTheOnlyCurrentLayoutWithoutImplicitMetadata() throws Exception {
        MemoryStore store = new MemoryStore(); AppSecrets vault = vault(store, new Clock());
        vault.create(PASSWORD, PASSWORD, OPEN);
        try (AppSecrets.Verified created = AppSecrets.verify(store.bytes, PASSWORD)) {
            currentEncoding(created.manifest);
            assertTrue(created.manifest.keyBindings.isEmpty()); assertTrue(created.manifest.accounts.isEmpty());
            assertArrayEquals(new byte[] {0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, created.plaintext);
        }
    }

    private static void callbacks(AppSecrets vault, AtomicInteger validators, AtomicInteger reviewers) throws VaultException {
        vault.attachSshValidator(loan -> {
            currentEncoding(loan); assertTrue(loan.keyBindings.isEmpty()); assertTrue(loan.accounts.isEmpty());
            validators.incrementAndGet();
        });
        vault.attachSshRestoreReviewer("synthetic-content-gate", "1", (source, target) -> {
            reviewers.incrementAndGet(); return SshRestoreTest.syntheticReview(source, target);
        }, (review, consent) -> { });
    }

    @Test public void sourceWithUnreviewedMetadataRejectsBeforeCallbacksAndTargetAuthentication() throws Exception {
        for (byte[] source : UNREVIEWED) for (boolean hooks : new boolean[] {false, true}) {
            MemoryStore store = new MemoryStore(); store.bytes = CURRENT[2].clone();
            AppSecrets vault = vault(store, new Clock()); AtomicInteger validators = new AtomicInteger(), reviewers = new AtomicInteger();
            if (hooks) callbacks(vault, validators, reviewers);
            rejects("VAULT_SSH_REVIEW_REQUIRED", () -> vault.preview(source, PASSWORD, null, "metadata-source", OPEN));
            assertEquals(0, validators.get()); assertEquals(0, reviewers.get());
            assertEquals(0, store.writes); assertEquals(0, store.claims); assertArrayEquals(CURRENT[2], store.bytes);
        }
        for (byte[] source : UNREVIEWED) {
            MemoryStore store = new MemoryStore(); AppSecrets vault = vault(store, new Clock());
            rejects("VAULT_SSH_REVIEW_REQUIRED", () -> vault.preview(source, PASSWORD, null, "missing-target", OPEN));
            assertNull(store.bytes); assertEquals(0, store.writes);
        }
    }

    @Test public void targetWithUnreviewedMetadataPreservesSourceFirstCallbackPrecedence() throws Exception {
        for (byte[] target : UNREVIEWED) for (int sourceKind = 0; sourceKind < 3; sourceKind++)
                for (boolean hooks : new boolean[] {false, true}) {
            MemoryStore store = new MemoryStore(); store.bytes = target.clone();
            AppSecrets vault = vault(store, new Clock()); AtomicInteger validators = new AtomicInteger(), reviewers = new AtomicInteger();
            if (hooks) callbacks(vault, validators, reviewers);
            final byte[] source = CURRENT[sourceKind];
            String expected = sourceKind == 2 && !hooks ? "VAULT_SSH_VALIDATION_REQUIRED" : "VAULT_SSH_REVIEW_REQUIRED";
            rejects(expected, () -> vault.preview(source, PASSWORD, PASSWORD, "metadata-target", OPEN));
            assertEquals(sourceKind == 2 && hooks ? 1 : 0, validators.get()); assertEquals(0, reviewers.get());
            assertEquals(0, store.writes); assertEquals(0, store.claims); assertArrayEquals(target, store.bytes);
        }
    }

    @Test public void currentEmptyAndPureTotpRestoreDoNotRequireSshHooks() throws Exception {
        for (int sourceKind = 0; sourceKind < 2; sourceKind++) for (int targetKind = 0; targetKind < 2; targetKind++) {
            MemoryStore store = new MemoryStore(); store.bytes = CURRENT[targetKind].clone();
            AppSecrets vault = vault(store, new Clock());
            vault.attachSshValidator(loan -> { throw new AssertionError("Pure TOTP validator callback"); });
            AppSecrets.ImportPreview pending = vault.preview(CURRENT[sourceKind], PASSWORD, PASSWORD, "pure-current", OPEN);
            assertNull(pending.reviewMap()); assertEquals(0, store.writes); store.permitRestoreRead();
            vault.importCommit(pending.id, true, OPEN); assertEquals(1, store.writes); assertTrue(store.restoreReads > 0);
            try (AppSecrets.Verified result = AppSecrets.verify(store.bytes, PASSWORD)) {
                currentEncoding(result.manifest); assertEquals(sourceKind, result.manifest.entries.size());
                assertTrue(result.manifest.keyBindings.isEmpty()); assertTrue(result.manifest.accounts.isEmpty());
                assertArrayEquals(pending.source.plaintext, new byte[pending.source.plaintext.length]);
            }
        }
    }

    @Test public void sourceFirstAuthenticationValidationAndTargetPasswordErrorsRetainPrecedence() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = GRAPH.clone();
        AppSecrets vault = vault(store, new Clock());
        rejects("VAULT_SSH_VALIDATION_REQUIRED", () -> vault.preview(CURRENT[2], PASSWORD, PASSWORD, "missing-validator", OPEN));
        rejects("VAULT_AUTHENTICATION", () -> vault.preview(GRAPH, "wrong".toCharArray(), PASSWORD, "wrong-source", OPEN));
        rejects("PARAMETERS", () -> vault.preview(CURRENT[1], PASSWORD, null, "missing-target-password", OPEN));
        rejects("VAULT_AUTHENTICATION", () -> vault.preview(CURRENT[1], PASSWORD, "wrong".toCharArray(), "wrong-target", OPEN));
        vault.attachSshValidator(loan -> { throw new VaultException("SYNTHETIC_SOURCE", "Synthetic source failure"); });
        rejects("SYNTHETIC_SOURCE", () -> vault.preview(CURRENT[2], PASSWORD, PASSWORD, "source-validator", OPEN));
        assertEquals(0, store.writes); assertArrayEquals(GRAPH, store.bytes);
    }

    @Test public void cancellationBeforeContentGateAndDuringEarlierValidationPreventsPublication() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = GRAPH.clone();
        AppSecrets vault = vault(store, new Clock());
        rejects("VAULT_CANCELLED", () -> vault.preview(GRAPH, PASSWORD, PASSWORD, "cancel-before", () -> {
            throw new VaultException("VAULT_CANCELLED", "Synthetic cancellation");
        }));
        AtomicBoolean cancelled = new AtomicBoolean(); AtomicInteger validators = new AtomicInteger(), reviewers = new AtomicInteger();
        callbacks(vault, validators, reviewers);
        vault.attachSshValidator(loan -> { currentEncoding(loan); cancelled.set(true); });
        rejects("VAULT_CANCELLED", () -> vault.preview(CURRENT[2], PASSWORD, PASSWORD, "cancel-source", () -> {
            if (cancelled.get()) throw new VaultException("VAULT_CANCELLED", "Synthetic cancellation");
        }));
        assertEquals(0, reviewers.get()); assertEquals(0, store.writes); assertEquals(0, store.claims);
        assertArrayEquals(GRAPH, store.bytes);
    }

    @Test public void failedMetadataPreviewDoesNotRetireEarlierPreviewOrLeaveBusySlot() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = CURRENT[1].clone();
        AppSecrets vault = vault(store, new Clock());
        AppSecrets.ImportPreview old = vault.preview(CURRENT[1], PASSWORD, PASSWORD, "old-preview", OPEN);
        rejects("VAULT_SSH_REVIEW_REQUIRED", () -> vault.preview(GRAPH, PASSWORD, PASSWORD, "new-metadata", OPEN));
        rejects("VAULT_SSH_REVIEW_REQUIRED", () -> vault.preview(GRAPH, PASSWORD, PASSWORD, "retry-metadata", OPEN));
        assertEquals(0, store.writes); assertArrayEquals(CURRENT[1], store.bytes);
        store.permitRestoreRead(); vault.importCommit(old.id, true, OPEN);
        assertTrue(store.restoreReads > 0); assertEquals(1, store.writes); assertEquals(1, store.claims);
        try (AppSecrets.Verified restored = AppSecrets.verify(store.bytes, PASSWORD)) { currentEncoding(restored.manifest); }
    }

    @Test public void directReviewBindRejectsActualMetadataOnEitherSideAndAcceptsEmptyMetadata() throws Exception {
        for (int metadata = 1; metadata <= 3; metadata++)
                try (VaultManifest pure = fixture(false, 1); VaultManifest graph = fixture(metadata, 2)) {
            assertEquals((metadata & 1) == 0, graph.keyBindings.isEmpty());
            assertEquals((metadata & 2) == 0, graph.accounts.isEmpty());
            try (SshRestoreReview supplied = SshRestoreTest.syntheticReview(graph, null)) {
                rejects("VAULT_SSH_REVIEW_REQUIRED", () -> supplied.bind(graph, null, "synthetic", "1", 1, new byte[32], null));
            }
            try (SshRestoreReview supplied = SshRestoreTest.syntheticReview(pure, graph)) {
                rejects("VAULT_SSH_REVIEW_REQUIRED", () -> supplied.bind(pure, graph, "synthetic", "1", 1, new byte[32], new byte[32]));
            }
            try (SshRestoreReview supplied = SshRestoreTest.syntheticReview(pure, null);
                    SshRestoreReview bound = supplied.bind(pure, null, "synthetic", "1", 1, new byte[32], null)) {
                assertNotNull(bound.toMap());
            }
        }
    }

    @Test public void mixedCurrentRestoreStillRequiresReviewAndCanCommitWithExactSyntheticHooks() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = CURRENT[2].clone();
        AppSecrets vault = vault(store, new Clock()); vault.attachSshValidator(loan -> currentEncoding(loan));
        rejects("VAULT_SSH_REVIEW_REQUIRED", () -> vault.preview(CURRENT[2], PASSWORD, PASSWORD, "no-review", OPEN));
        AtomicInteger validators = new AtomicInteger(), reviewers = new AtomicInteger(); callbacks(vault, validators, reviewers);
        AppSecrets.ImportPreview preview = vault.preview(CURRENT[2], PASSWORD, PASSWORD, "mixed-review", OPEN);
        assertEquals(2, validators.get()); assertEquals(1, reviewers.get());
        assertNotNull(preview.reviewMap()); assertEquals(0, store.writes); store.permitRestoreRead();
        vault.importCommit(preview.id, preview.reviewBinding, true, OPEN);
        assertTrue(store.restoreReads > 0); assertEquals(1, store.writes);
        try (AppSecrets.Verified restored = AppSecrets.verify(store.bytes, PASSWORD)) {
            currentEncoding(restored.manifest); assertTrue(restored.manifest.accounts.isEmpty());
            assertTrue(restored.manifest.keyBindings.isEmpty()); assertArrayEquals(OLD_PASSWORD, entry(restored.manifest, "password").secret);
        }
    }

    @Test public void deliveredTotpInputUnlocksListsCodesAndExportsWithoutWrites() throws Exception {
        for (int kind = 0; kind < 2; kind++) {
            MemoryStore store = new MemoryStore(); store.bytes = LEGACY_TOTP[kind].clone(); byte[] original = store.bytes.clone();
            AppSecrets vault = vault(store, new Clock());
            vault.attachSshValidator(loan -> { throw new AssertionError("Delivered pure TOTP must not enter SSH validation"); });
            vault.unlock(PASSWORD, OPEN);
            List<?> rows = (List<?>) vault.totpList(vault.beginTotpList(), 59).get("entries"); assertEquals(kind, rows.size());
            if (kind == 1) assertEquals("287082", vault.totpCode(vault.beginTotp("legacy-totp"), "legacy-totp", 59));
            assertArrayEquals(original, vault.export(PASSWORD, OPEN));
            try (AppSecrets.Verified verified = AppSecrets.verify(store.bytes, PASSWORD)) {
                assertEquals(1, ByteBuffer.wrap(verified.plaintext).getInt()); currentEncoding(verified.manifest);
                if (kind == 1) legacyEntry(verified.manifest, "Saved account");
            }
            vault.lock(); VaultEnvelope envelope = VaultEnvelope.parse(store.bytes);
            byte[] key = VaultEnvelope.derive(PASSWORD, envelope.salt);
            try { vault.quickUnlock(envelope, key, vault.session.epoch(), OPEN); }
            finally { VaultCodec.wipe(key); }
            assertTrue(vault.session.unlocked());
            assertEquals(0, store.writes); assertEquals(0, store.claims); assertArrayEquals(original, store.bytes);
            assertArrayEquals(new byte[] {31, 32}, store.quick);
        }
    }

    @Test public void deliveredTotpFirstContentWriteUsesCurrentLayoutAndPreservesSeed() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = LEGACY_TOTP[1].clone();
        AppSecrets vault = vault(store, new Clock()); vault.unlock(PASSWORD, OPEN);
        VaultOperation operation = vault.beginTotpRename("legacy-totp");
        vault.renameTotp(operation, "legacy-totp", "Renamed saved account", OPEN);
        try (AppSecrets.Verified changed = AppSecrets.verify(store.bytes, PASSWORD)) {
            assertEquals(2, ByteBuffer.wrap(changed.plaintext).getInt()); legacyEntry(changed.manifest, "Renamed saved account");
        }
        assertEquals(1, store.writes); assertEquals(1, store.claims); assertTrue(operation.consumed);
        assertArrayEquals(new byte[] {31, 32}, store.quick);
    }

    @Test public void deliveredTotpPasswordChangeReencodesSameContentAndRetainsEnvelopeRules() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = LEGACY_TOTP[1].clone();
        VaultEnvelope before = VaultEnvelope.parse(store.bytes); AppSecrets vault = vault(store, new Clock());
        vault.changePassword(PASSWORD, NEXT_PASSWORD, NEXT_PASSWORD, OPEN);
        try (AppSecrets.Verified changed = AppSecrets.verify(store.bytes, NEXT_PASSWORD)) {
            assertEquals(2, ByteBuffer.wrap(changed.plaintext).getInt()); legacyEntry(changed.manifest, "Saved account");
            assertEquals(before.id, changed.envelope.id); assertEquals(before.generation + 1, changed.envelope.generation);
            assertFalse(Arrays.equals(before.salt, changed.envelope.salt));
        }
        rejects("VAULT_AUTHENTICATION", () -> AppSecrets.verify(store.bytes, PASSWORD));
        assertEquals(1, store.writes); assertEquals(1, store.claims); assertNull(store.quick);
    }

    @Test public void deliveredTotpRestoreKeepsRawPreviewBindingAndWritesCurrentContent() throws Exception {
        for (int sourceKind = 0; sourceKind < 2; sourceKind++) for (boolean present : new boolean[] {false, true}) {
            MemoryStore store = new MemoryStore(); store.bytes = present ? LEGACY_TOTP[1].clone() : null;
            AppSecrets vault = vault(store, new Clock());
            AppSecrets.ImportPreview pending = vault.preview(LEGACY_TOTP[sourceKind], PASSWORD, present ? PASSWORD : null, "delivered-totp", OPEN);
            assertEquals(1, ByteBuffer.wrap(pending.source.plaintext).getInt()); assertNull(pending.reviewMap());
            byte[] authenticated = pending.source.plaintext.clone();
            assertArrayEquals(legacyPlaintext(sourceKind != 0), authenticated);
            assertEquals(0, store.writes); store.permitRestoreRead();
            vault.importCommit(pending.id, true, OPEN);
            try (AppSecrets.Verified restored = AppSecrets.verify(store.bytes, PASSWORD)) {
                assertEquals(2, ByteBuffer.wrap(restored.plaintext).getInt()); assertEquals(sourceKind, restored.manifest.entries.size());
                if (sourceKind == 1) legacyEntry(restored.manifest, "Saved account");
                assertNotEquals(VaultEnvelope.parse(LEGACY_TOTP[sourceKind]).id, restored.envelope.id);
                assertEquals(1, restored.envelope.generation);
            }
            assertEquals(1, store.writes); assertEquals(1, store.claims); assertTrue(store.restoreReads > 0);
            assertArrayEquals(new byte[pending.source.plaintext.length], pending.source.plaintext);
            VaultCodec.wipe(authenticated);
        }
    }

    @Test public void deliveredTotpAuthenticationCancellationAndCommitFailureKeepOriginalBytes() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = LEGACY_TOTP[1].clone(); byte[] original = store.bytes.clone();
        AppSecrets vault = vault(store, new Clock());
        rejects("VAULT_AUTHENTICATION", () -> vault.unlock("wrong".toCharArray(), OPEN));
        rejects("VAULT_CANCELLED", () -> vault.changePassword(PASSWORD, NEXT_PASSWORD, NEXT_PASSWORD, () -> {
            throw new VaultException("VAULT_CANCELLED", "Synthetic cancellation");
        }));
        assertArrayEquals(original, store.bytes); assertArrayEquals(new byte[] {31, 32}, store.quick);
        vault.unlock(PASSWORD, OPEN); store.failCommit = true;
        rejects("VAULT_IO", () -> vault.renameTotp(vault.beginTotpRename("legacy-totp"), "legacy-totp", "Not saved", OPEN));
        assertEquals(0, store.writes); assertEquals(0, store.claims); assertArrayEquals(original, store.bytes);
        assertArrayEquals(new byte[] {31, 32}, store.quick);
        try (AppSecrets.Verified unchanged = AppSecrets.verify(store.bytes, PASSWORD)) {
            assertEquals(1, ByteBuffer.wrap(unchanged.plaintext).getInt()); legacyEntry(unchanged.manifest, "Saved account");
        }
    }

    @Test public void restoreEncodingUsesAuthenticatedSourceInsteadOfRetainedMutablePreviewModel() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = CURRENT[1].clone();
        AppSecrets vault = vault(store, new Clock());
        AppSecrets.ImportPreview pending = vault.preview(LEGACY_TOTP[1], PASSWORD, PASSWORD, "source-provenance", OPEN);
        byte[] originalRaw = pending.source.plaintext.clone();
        entry(pending.source.manifest, "legacy-totp").secret[0] ^= 1;
        assertArrayEquals(originalRaw, pending.source.plaintext); store.permitRestoreRead();
        vault.importCommit(pending.id, true, OPEN);
        try (AppSecrets.Verified restored = AppSecrets.verify(store.bytes, PASSWORD)) {
            assertEquals(2, ByteBuffer.wrap(restored.plaintext).getInt()); legacyEntry(restored.manifest, "Saved account");
        } finally { VaultCodec.wipe(originalRaw); }
        assertEquals(1, store.writes); assertEquals(1, store.claims);
    }

    @Test public void restoreRejectsChangedAuthenticatedInputBeforeAnyNormalizationWrite() throws Exception {
        MemoryStore store = new MemoryStore(); store.bytes = CURRENT[1].clone(); byte[] original = store.bytes.clone();
        AppSecrets vault = vault(store, new Clock());
        AppSecrets.ImportPreview pending = vault.preview(LEGACY_TOTP[1], PASSWORD, PASSWORD, "changed-source", OPEN);
        legacyEntry(pending.source.manifest, "Saved account");
        pending.source.plaintext[pending.source.plaintext.length - 1] ^= 1;
        rejects("VAULT_CHANGED", () -> vault.importCommit(pending.id, true, OPEN));
        assertEquals(0, store.writes); assertEquals(0, store.claims); assertArrayEquals(original, store.bytes);
        assertArrayEquals(new byte[] {31, 32}, store.quick);
    }

    @Test public void unchangedLoanComparesIndependentAuthenticatedOriginalAcrossInputAdaptation() throws Exception {
        byte[] raw = legacyPlaintext(true), original = raw.clone();
        try (VaultManifest loan = VaultManifest.decode(raw)) {
            currentEncoding(loan); unchangedLoan(loan, raw);
            entry(loan, "legacy-totp").secret[0] ^= 1;
            rejects("VAULT_CONTENT", () -> unchangedLoan(loan, raw));
        }
        assertArrayEquals(original, raw);
        try (VaultManifest source = fixture(true, 2)) {
            byte[] canonical = source.encode();
            try (VaultManifest loan = VaultManifest.decode(canonical)) {
                unchangedLoan(loan, canonical); entry(loan, "password").secret[0] ^= 1;
                rejects("VAULT_CONTENT", () -> unchangedLoan(loan, canonical));
            } finally { VaultCodec.wipe(canonical); }
        } finally { VaultCodec.wipe(raw); VaultCodec.wipe(original); }
    }
}
