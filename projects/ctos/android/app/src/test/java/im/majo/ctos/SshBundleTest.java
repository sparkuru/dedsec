package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Independent bounded fixture validator only; this does not implement or verify OpenSSH semantics. */
public final class SshBundleTest {
    private static final char[] PASSWORD = "fixture bundle master password".toCharArray();
    private static byte[] fixture;
    private static byte[] fixtureKey;
    private MemoryStore store;
    private AppSecrets vault;
    private FakeClock clock;

    private static final class FakeClock implements VaultSession.Clock {
        long now;
        @Override public long now() { return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        byte[] wrapper = {4, 5, 6};
        boolean fail;
        Runnable afterClaim;
        @Override public byte[] read() { return bytes.clone(); }
        @Override public byte[] quickMaterial() { return wrapper.clone(); }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard) throws IOException, VaultException {
            if (fail) throw new IOException("fixture precommit failure");
            guard.beforeCommit();
            if (afterClaim != null) afterClaim.run();
            bytes = next.clone();
            wrapper = quick.clone();
        }
    }

    @BeforeClass public static void buildFixture() throws Exception {
        byte[] salt = VaultEnvelope.random(16);
        fixtureKey = VaultEnvelope.derive(PASSWORD, salt);
        VaultManifest.Entry totp = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp",
                "Fixture:account", "Fixture", "SHA1", 6, 30, new byte[20]);
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "password",
                "target:fixture@example.invalid:22", "Fixture", "", 0, 0, bytes("fixture password"));
        VaultManifest.Entry passphrase = new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE, "passphrase",
                "key:fixture-existing", "Fixture", "", 0, 0, bytes("fixture passphrase"));
        try (VaultManifest manifest = new VaultManifest(Arrays.asList(totp, password, passphrase), Arrays.asList(
                file(VaultManifest.FileType.CONFIG, "unrelated", "other/config", "fixture unrelated", Collections.emptyList()),
                file(VaultManifest.FileType.PRIVATE_KEY, "existing", "other/key", "fixture-existing", Collections.emptyList())))) {
            byte[] plain = manifest.encode();
            try { fixture = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, fixtureKey, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        }
    }

    @Before public void setup() throws Exception {
        store = new MemoryStore();
        store.bytes = fixture.clone();
        clock = new FakeClock();
        vault = new AppSecrets(store, clock);
        vault.attachSshValidator(SshBundleTest::fixtureValidator);
        vault.session.foreground(true);
        vault.unlock(PASSWORD, () -> { });
    }

    @Test public void completeGraphAddReplaceDeleteUsesSharedEncryptedTransaction() throws Exception {
        List<VaultManifest.FileObject> graph = graph();
        SshVaultAdapter adapter = approved();
        VaultEnvelope original = vault.current();
        TotpVaultAdapter totp = new TotpVaultAdapter(vault);
        VaultOperation staleTotp = totp.request("totp");
        try (SshBundleProposal add = additions(graph)) {
            adapter.commitBundle(adapter.bundleRequest(add), add, () -> { });
            assertUnrelatedPreserved();
            assertArrayEquals(new byte[] {4, 5, 6}, store.wrapper);
            assertArrayEquals(original.materialBinding(), vault.current().materialBinding());
            assertEquals(original.generation, vault.current().generation);
            assertFalse(Arrays.equals(original.nonce, vault.current().nonce));
            assertTrue(vault.session.unlocked());
            rejects("VAULT_OPERATION", () -> totp.currentCode(staleTotp, "totp"));
            assertEquals(6, totp.currentCode(totp.request("totp"), "totp").length());
        }
        VaultManifest.FileObject old = graph.get(0);
        VaultManifest.FileObject replacement = new VaultManifest.FileObject(old.type, old.id, old.path, old.identity,
                bytes("fixture edited config"), old.references);
        try (SshBundleProposal replace = proposal(Arrays.asList(SshBundleProposal.Change.replace(old, replacement)))) {
            adapter.commitBundle(adapter.bundleRequest(replace), replace, () -> { });
            graph.set(0, replacement);
            try (VaultManifest manifest = currentManifest()) { assertArrayEquals(replacement.content, find(manifest, old.path).content); }
            assertUnrelatedPreserved();
        }
        List<SshBundleProposal.Change> deletes = new ArrayList<>();
        for (VaultManifest.FileObject file : graph) deletes.add(SshBundleProposal.Change.delete(file));
        try (SshBundleProposal remove = proposal(deletes)) {
            adapter.commitBundle(adapter.bundleRequest(remove), remove, () -> { });
            try (VaultManifest manifest = currentManifest()) { assertEquals(2, manifest.files.size()); }
            assertUnrelatedPreserved();
        }
    }

    @Test public void rawCallerMutationAndClosedInputsCannotChangeExactProposal() throws Exception {
        VaultManifest.FileObject trust = file(VaultManifest.FileType.KNOWN_HOSTS, "trust", "bundle/trust", "fixture trust", Collections.emptyList());
        byte[] expected = trust.content.clone();
        SshBundleProposal.Change change = SshBundleProposal.Change.add(trust);
        List<SshBundleProposal.Change> input = new ArrayList<>();
        input.add(change);
        try (SshBundleProposal proposal = new SshBundleProposal(input)) {
            String binding = proposal.binding;
            Arrays.fill(trust.content, (byte) 'x');
            change.close();
            input.clear();
            byte[] returned = proposal.knownHostsContent(trust.path);
            Arrays.fill(returned, (byte) 0);
            assertArrayEquals(expected, proposal.knownHostsContent(trust.path));
            assertEquals(binding, proposal.binding);
            try { proposal.summary().clear(); fail("mutable summary"); }
            catch (UnsupportedOperationException expectedFailure) { /* immutable metadata */ }
            SshVaultAdapter adapter = approved();
            adapter.commitBundle(adapter.bundleRequest(proposal), proposal, () -> { });
            try (VaultManifest manifest = currentManifest()) { assertArrayEquals(expected, find(manifest, trust.path).content); }
        }
        rejects("VAULT_EXPIRED", () -> new SshBundleProposal(Arrays.asList(change)));
    }

    @Test public void closedProposalWipesOwnedContentAndRejectsAdmissionOrEffect() throws Exception {
        SshBundleProposal proposal = additions(graph());
        SshVaultAdapter adapter = approved();
        VaultOperation operation = adapter.bundleRequest(proposal);
        // Reflection observes owned storage only in this JVM test; production exposes no private bytes.
        java.lang.reflect.Field changesField = SshBundleProposal.class.getDeclaredField("changes");
        changesField.setAccessible(true);
        List<?> changes = (List<?>) changesField.get(proposal);
        java.lang.reflect.Field contentField = SshBundleProposal.Change.class.getDeclaredField("content");
        contentField.setAccessible(true);
        byte[] owned = (byte[]) contentField.get(changes.get(0));
        assertFalse(Arrays.equals(owned, new byte[owned.length]));
        proposal.close();
        assertArrayEquals(new byte[owned.length], owned);
        rejects("VAULT_EXPIRED", () -> proposal.summary());
        rejects("VAULT_EXPIRED", () -> adapter.bundleRequest(proposal));
        rejects("VAULT_EXPIRED", () -> adapter.commitBundle(operation, proposal, () -> { }));
        assertOriginal();
    }

    @Test public void bindingCoversWholeChangeAndCannotUseReplacementSummary() throws Exception {
        VaultManifest.FileObject first = file(VaultManifest.FileType.CONFIG, "new", "new/config", "fixture config", Collections.emptyList());
        VaultManifest.FileObject other = new VaultManifest.FileObject(first.type, first.id, first.path, first.identity,
                bytes("fixture other content"), first.references);
        try (SshBundleProposal a = additions(Arrays.asList(first)); SshBundleProposal b = additions(Arrays.asList(other))) {
            assertNotEquals(a.binding, b.binding);
            SshVaultAdapter exact = adapter(proposal -> {
                if (!proposal.binding.equals(a.binding)) throw new VaultException("VAULT_TRUST_REVIEW", "fixture unapproved replacement");
            });
            VaultOperation operation = exact.bundleRequest(a);
            rejects("VAULT_TRUST_REVIEW", () -> exact.commitBundle(operation, b, () -> { }));
            rejects("VAULT_OPERATION", () -> approved().commitBundle(operation, b, () -> { }));
            exact.commitBundle(operation, a, () -> { });
            rejects("VAULT_OPERATION", () -> exact.commitBundle(operation, a, () -> { }));
        }
        List<VaultManifest.FileObject> forward = graph();
        List<VaultManifest.FileObject> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        try (SshBundleProposal a = additions(forward); SshBundleProposal b = additions(reverse)) { assertEquals(a.binding, b.binding); }
    }

    @Test public void oldObjectCasCoversTypeIdIdentityReferencesAndContent() throws Exception {
        try (VaultManifest manifest = currentManifest()) {
            VaultManifest.FileObject old = find(manifest, "other/config");
            List<VaultManifest.FileObject> wrong = Arrays.asList(
                    new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY, old.id, old.path, old.identity, old.content, old.references),
                    new VaultManifest.FileObject(old.type, "wrongId", old.path, old.identity, old.content, old.references),
                    new VaultManifest.FileObject(old.type, old.id, old.path, "fixture wrong identity", old.content, old.references),
                    new VaultManifest.FileObject(old.type, old.id, old.path, old.identity, old.content, Arrays.asList("other/key")),
                    new VaultManifest.FileObject(old.type, old.id, old.path, old.identity, bytes("fixture wrong bytes"), old.references));
            for (VaultManifest.FileObject expected : wrong) {
                try (SshBundleProposal proposal = proposal(Arrays.asList(SshBundleProposal.Change.replace(expected, old)))) {
                    rejects("VAULT_CHANGED", () -> approved().bundleRequest(proposal));
                    assertOriginal();
                }
            }
        }
    }

    @Test public void defaultDenyApprovalCannotBeReplacedByVerifiedHost() throws Exception {
        try (SshBundleProposal proposal = additions(graph())) {
            SshVaultAdapter adapter = new SshVaultAdapter(vault, (connection, target) -> { });
            rejects("VAULT_TRUST_REVIEW", () -> adapter.bundleRequest(proposal));
            assertOriginal();
        }
    }

    @Test public void approvalIsCheckedAgainAtEffectAndFinalCommitGuard() throws Exception {
        try (SshBundleProposal proposal = additions(graph())) {
            boolean[] allowed = {true};
            SshVaultAdapter adapter = adapter(value -> { if (!allowed[0]) throw new VaultException("VAULT_TRUST_REVIEW", "fixture revoked"); });
            VaultOperation operation = adapter.bundleRequest(proposal);
            allowed[0] = false;
            rejects("VAULT_TRUST_REVIEW", () -> adapter.commitBundle(operation, proposal, () -> { }));
            assertOriginal();
            allowed[0] = true;
            AppSecrets.EffectGuard revokeAtClaim = new AppSecrets.EffectGuard() {
                @Override public void check() { }
                @Override public void beforeCommit() { allowed[0] = false; }
            };
            rejects("VAULT_TRUST_REVIEW", () -> adapter.commitBundle(operation, proposal, revokeAtClaim));
            assertOriginal();
        }
    }

    @Test public void validatorRequiredForAddAndEvenRemovalOfLastSshFiles() throws Exception {
        try (SshBundleProposal add = additions(graph())) {
            vault.attachSshValidator(null);
            rejects("VAULT_SSH_VALIDATION_REQUIRED", () -> approved().bundleRequest(add));
            vault.attachSshValidator(SshBundleTest::fixtureValidator);
            VaultOperation operation = approved().bundleRequest(add);
            vault.attachSshValidator(null);
            rejects("VAULT_SSH_VALIDATION_REQUIRED", () -> approved().commitBundle(operation, add, () -> { }));
            assertOriginal();
        }
        // A separate TOTP-only vault plus a single file has no SSH credentials after deletion.
        vault.attachSshValidator(SshBundleTest::fixtureValidator);
        try (VaultManifest original = currentManifest(); VaultManifest minimal = new VaultManifest(
                Arrays.asList(original.entries.get(0)), Arrays.asList(find(original, "other/config")))) {
            byte[] plain = minimal.encode();
            try { store.bytes = VaultEnvelope.encrypt(vault.current().id, 1, vault.current().salt, fixtureKey, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        }
        vault.unlock(PASSWORD, () -> { });
        try (VaultManifest original = currentManifest(); SshBundleProposal deletion = proposal(Arrays.asList(
                SshBundleProposal.Change.delete(original.files.get(0))))) {
            VaultOperation operation = approved().bundleRequest(deletion);
            vault.attachSshValidator(null);
            rejects("VAULT_SSH_VALIDATION_REQUIRED", () -> approved().commitBundle(operation, deletion, () -> { }));
            boolean[] rejectEmpty = {false};
            vault.attachSshValidator(manifest -> {
                if (rejectEmpty[0] && manifest.files.isEmpty()) throw new VaultException("VAULT_CONTENT", "fixture rejects empty graph");
            });
            rejects("VAULT_CHANGED", () -> approved().commitBundle(operation, deletion, () -> { }));
            VaultOperation fresh = approved().bundleRequest(deletion);
            rejectEmpty[0] = true;
            rejects("VAULT_CONTENT", () -> approved().commitBundle(fresh, deletion, () -> { }));
        }
    }

    @Test public void malformedOversizedAndDuplicateChangesAreBoundedBeforeAdmission() throws Exception {
        for (String path : Arrays.asList("/absolute", "../escape", "a//b", "content:uri", "a/../b")) {
            rejects("VAULT_CONTENT", () -> SshBundleProposal.Change.add(file(VaultManifest.FileType.CONFIG, "bad", path, "fixture", Collections.emptyList())));
        }
        VaultManifest.FileObject large = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "large", "large",
                "fixture", new byte[VaultManifest.MAX_FILE_BYTES + 1], Collections.emptyList());
        rejects("VAULT_CONTENT", () -> SshBundleProposal.Change.add(large));
        VaultManifest.FileObject file = graph().get(0);
        rejects("VAULT_CONTENT", () -> SshBundleProposal.Change.replace(null, file));
        rejects("VAULT_CONTENT", () -> SshBundleProposal.Change.replace(file, null));
        rejects("VAULT_CONTENT", () -> SshBundleProposal.Change.delete(null));
        rejects("VAULT_CONTENT", () -> additions(Arrays.asList(file, file)));
        List<VaultManifest.FileObject> many = new ArrayList<>();
        for (int index = 0; index < 129; index++) many.add(file(VaultManifest.FileType.CONFIG, "f" + index, "f" + index, "fixture", Collections.emptyList()));
        rejects("VAULT_CONTENT", () -> additions(many));
        many.clear();
        for (int index = 0; index < 9; index++) many.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "f" + index, "f" + index, "fixture", new byte[VaultManifest.MAX_FILE_BYTES], Collections.emptyList()));
        rejects("VAULT_CONTENT", () -> additions(many));
        assertOriginal();
    }

    @Test public void finalLibraryCountAndBytesIncludeUnrelatedFiles() throws Exception {
        List<VaultManifest.FileObject> files = new ArrayList<>();
        for (int index = 0; index < 127; index++) files.add(file(VaultManifest.FileType.CONFIG, "f" + index, "f" + index, "fixture", Collections.emptyList()));
        try (SshBundleProposal proposal = additions(files)) { rejects("VAULT_CONTENT", () -> approved().bundleRequest(proposal)); }
        files.clear();
        for (int index = 0; index < 8; index++) files.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "f" + index, "f" + index, "fixture", new byte[VaultManifest.MAX_FILE_BYTES], Collections.emptyList()));
        try (SshBundleProposal proposal = additions(files)) { rejects("VAULT_CONTENT", () -> approved().bundleRequest(proposal)); }
        assertOriginal();
    }

    @Test public void missingReferencesCyclesAndExcessiveDepthRejectWholeGraph() throws Exception {
        List<List<VaultManifest.FileObject>> invalid = new ArrayList<>();
        invalid.add(Arrays.asList(file(VaultManifest.FileType.CONFIG, "missing", "missing", "fixture", Arrays.asList("absent"))));
        invalid.add(Arrays.asList(file(VaultManifest.FileType.CONFIG, "cycle", "cycle", "fixture", Arrays.asList("cycle"))));
        List<VaultManifest.FileObject> deep = new ArrayList<>();
        for (int index = 0; index < 10; index++) deep.add(file(VaultManifest.FileType.CONFIG, "deep" + index, "deep" + index,
                "fixture", index < 9 ? Arrays.asList("deep" + (index + 1)) : Collections.emptyList()));
        invalid.add(deep);
        for (List<VaultManifest.FileObject> files : invalid) {
            try (SshBundleProposal proposal = additions(files)) { rejects("VAULT_CONTENT", () -> approved().bundleRequest(proposal)); }
            assertOriginal();
        }
    }

    @Test public void noImplicitCascadeForSharedReferencesOrPrivateKeyPassphrases() throws Exception {
        try (VaultManifest manifest = currentManifest(); SshBundleProposal deletion = proposal(Arrays.asList(
                SshBundleProposal.Change.delete(find(manifest, "other/key"))))) {
            rejects("VAULT_CONTENT", () -> approved().bundleRequest(deletion));
            assertOriginal();
        }
        VaultManifest.FileObject shared = file(VaultManifest.FileType.PRIVATE_KEY, "shared", "shared/key", "fixture shared", Collections.emptyList());
        try (VaultManifest manifest = currentManifest()) {
            VaultManifest.FileObject old = find(manifest, "other/config");
            VaultManifest.FileObject referring = new VaultManifest.FileObject(old.type, old.id, old.path, old.identity, old.content, Arrays.asList(shared.path));
            try (SshBundleProposal proposal = proposal(Arrays.asList(SshBundleProposal.Change.replace(old, referring), SshBundleProposal.Change.add(shared)))) {
                approved().commitBundle(approved().bundleRequest(proposal), proposal, () -> { });
            }
        }
        byte[] before = store.bytes.clone();
        try (SshBundleProposal remove = proposal(Arrays.asList(SshBundleProposal.Change.delete(shared)))) {
            rejects("VAULT_CONTENT", () -> approved().bundleRequest(remove));
            assertArrayEquals(before, store.bytes);
        }
        assertUnrelatedEntriesPreserved();
    }

    @Test public void consumerPurposeCancelLockExpiryAndSnapshotBindingReject() throws Exception {
        try (SshBundleProposal proposal = additions(graph())) {
            SshVaultAdapter adapter = approved();
            VaultOperation cancelled = adapter.bundleRequest(proposal);
            rejects("VAULT_OPERATION", () -> new TotpVaultAdapter(vault).save(cancelled, "totp", "Fixture:account", "Fixture", new byte[20], () -> { }));
            rejects("VAULT_OPERATION", () -> adapter.useFiles(cancelled, "other/config", "connection", files -> fail("bundle released files")));
            adapter.cancel(cancelled);
            rejects("VAULT_CANCELLED", () -> adapter.commitBundle(cancelled, proposal, () -> { }));
            assertTrue(vault.session.unlocked());
            final VaultOperation expires = adapter.bundleRequest(proposal);
            clock.now = 120000;
            rejects("VAULT_EXPIRED", () -> adapter.commitBundle(expires, proposal, () -> { }));
            clock.now = 0;
            final VaultOperation changed = adapter.bundleRequest(proposal);
            byte[] plain = decryptPlain(store.bytes);
            try { store.bytes = VaultEnvelope.encrypt(vault.current().id, 1, vault.current().salt, fixtureKey, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
            rejects("VAULT_CHANGED", () -> adapter.commitBundle(changed, proposal, () -> { }));
            store.bytes = fixture.clone();
            final VaultOperation locked = adapter.bundleRequest(proposal);
            vault.session.lock();
            rejects("VAULT_CHANGED", () -> adapter.commitBundle(locked, proposal, () -> { }));
            assertOriginal();
        }
    }

    @Test public void preclaimFailureAndCancelledEffectRetainWholeRecordAndWrapper() throws Exception {
        try (SshBundleProposal proposal = additions(graph())) {
            SshVaultAdapter adapter = approved();
            store.fail = true;
            rejects("VAULT_IO", () -> adapter.commitBundle(adapter.bundleRequest(proposal), proposal, () -> { }));
            assertOriginal();
            store.fail = false;
            VaultOperation operation = adapter.bundleRequest(proposal);
            rejects("VAULT_CANCELLED", () -> adapter.commitBundle(operation, proposal, () -> adapter.cancel(operation)));
            assertOriginal();
            assertTrue(vault.session.unlocked());
        }
    }

    @Test public void proposalClosureOrDeadlineDuringFinalGuardPreventsCommit() throws Exception {
        try (SshBundleProposal proposal = additions(graph())) {
            SshVaultAdapter adapter = approved();
            VaultOperation request = adapter.bundleRequest(proposal);
            rejects("VAULT_EXPIRED", () -> adapter.commitBundle(request, proposal, new AppSecrets.EffectGuard() {
                @Override public void check() { }
                @Override public void beforeCommit() { clock.now = 120000; }
            }));
            assertOriginal();
            clock.now = 0;
            VaultOperation second = adapter.bundleRequest(proposal);
            rejects("VAULT_EXPIRED", () -> adapter.commitBundle(second, proposal, new AppSecrets.EffectGuard() {
                @Override public void check() { }
                @Override public void beforeCommit() { proposal.close(); }
            }));
            assertOriginal();
        }
    }

    @Test public void afterClaimCancellationIsUncertainAndCannotReopenLockedSession() throws Exception {
        try (SshBundleProposal proposal = additions(graph())) {
            SshVaultAdapter adapter = approved();
            VaultOperation operation = adapter.bundleRequest(proposal);
            VaultRequestPhase phase = new VaultRequestPhase();
            CountDownLatch claimed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            store.afterClaim = () -> {
                claimed.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture release timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            };
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { adapter.commitBundle(operation, proposal, new AppSecrets.EffectGuard() {
                    @Override public void check() throws VaultException {
                        if (phase.cancelled()) throw new VaultException("VAULT_CANCELLED", "fixture cancelled");
                    }
                    @Override public void beforeCommit() throws VaultException { phase.claimCommit(this::check); }
                }); } catch (Throwable failure) { error.set(failure); }
            });
            worker.start();
            try {
                assertTrue(claimed.await(5, TimeUnit.SECONDS));
                phase.cancel();
                adapter.cancel(operation);
                vault.session.lock();
                assertEquals("VAULT_COMMIT_UNCERTAIN", phase.interrupted(new VaultException("VAULT_CANCELLED", "fixture cancel")).code);
            } finally { release.countDown(); worker.join(5000); }
            assertFalse(worker.isAlive());
            assertNull(error.get());
            assertFalse(vault.session.unlocked());
            assertFalse(Arrays.equals(fixture, store.bytes));
            assertArrayEquals(new byte[] {4, 5, 6}, store.wrapper);
            try (VaultManifest manifest = currentManifest()) { assertEquals(8, manifest.files.size()); }
            vault.unlock(PASSWORD, () -> { });
            assertTrue(vault.session.unlocked());
        }
    }

    private static void fixtureValidator(VaultManifest manifest) throws VaultException {
        for (VaultManifest.FileObject file : manifest.files) if (!file.identity.startsWith("fixture")) throw VaultException.invalid();
    }
    private SshVaultAdapter approved() { return adapter(proposal -> { }); }
    private SshVaultAdapter adapter(SshVaultAdapter.ExactBundleApproval approval) {
        return new SshVaultAdapter(vault, (connection, target) -> {
            throw new VaultException("VAULT_OPERATION", "Bundle approval never authorizes a connection");
        }, proposal -> { throw new VaultException("VAULT_TRUST_REVIEW", "No live trust proposal approved"); }, approval);
    }
    private static SshBundleProposal additions(List<VaultManifest.FileObject> files) throws Exception {
        List<SshBundleProposal.Change> changes = new ArrayList<>();
        try {
            for (VaultManifest.FileObject file : files) changes.add(SshBundleProposal.Change.add(file));
            return new SshBundleProposal(changes);
        } finally { for (SshBundleProposal.Change change : changes) change.close(); }
    }
    private static SshBundleProposal proposal(List<SshBundleProposal.Change> changes) throws Exception {
        try { return new SshBundleProposal(changes); }
        finally { for (SshBundleProposal.Change change : changes) change.close(); }
    }
    private static List<VaultManifest.FileObject> graph() {
        List<VaultManifest.FileObject> files = new ArrayList<>();
        files.add(file(VaultManifest.FileType.CONFIG, "root", "bundle/config", "fixture config", Arrays.asList("bundle/include")));
        files.add(file(VaultManifest.FileType.CONFIG, "include", "bundle/include", "fixture include",
                Arrays.asList("bundle/key", "bundle/public", "bundle/certificate", "bundle/trust")));
        files.add(file(VaultManifest.FileType.PRIVATE_KEY, "key", "bundle/key", "fixture key", Collections.emptyList()));
        files.add(file(VaultManifest.FileType.PUBLIC_KEY, "public", "bundle/public", "fixture public", Collections.emptyList()));
        files.add(file(VaultManifest.FileType.CERTIFICATE, "certificate", "bundle/certificate", "fixture certificate", Collections.emptyList()));
        files.add(file(VaultManifest.FileType.KNOWN_HOSTS, "trust", "bundle/trust", "fixture trust", Collections.emptyList()));
        return files;
    }
    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id, String path, String identity, List<String> refs) {
        return new VaultManifest.FileObject(type, id, path, identity, bytes("fixture " + path), refs);
    }
    private VaultManifest currentManifest() throws Exception {
        byte[] plain = decryptPlain(store.bytes);
        try { return VaultManifest.decode(plain); } finally { VaultCodec.wipe(plain); }
    }
    private static byte[] decryptPlain(byte[] encrypted) throws Exception { return VaultEnvelope.parse(encrypted).decrypt(fixtureKey); }
    private static VaultManifest.FileObject find(VaultManifest manifest, String path) {
        for (VaultManifest.FileObject file : manifest.files) if (file.path.equals(path)) return file;
        throw new AssertionError("fixture missing file");
    }
    private void assertUnrelatedPreserved() throws Exception {
        assertUnrelatedEntriesPreserved();
        try (VaultManifest actual = currentManifest()) {
            byte[] plain = decryptPlain(fixture);
            try (VaultManifest old = VaultManifest.decode(plain)) {
                for (VaultManifest.FileObject file : old.files) assertEquals(SshBundleProposal.objectBinding(file), SshBundleProposal.objectBinding(find(actual, file.path)));
            } finally { VaultCodec.wipe(plain); }
        }
    }
    private void assertUnrelatedEntriesPreserved() throws Exception {
        byte[] plain = decryptPlain(fixture);
        try (VaultManifest actual = currentManifest(); VaultManifest old = VaultManifest.decode(plain)) {
            assertEquals(old.entries.size(), actual.entries.size());
            for (int index = 0; index < old.entries.size(); index++) {
                VaultManifest.Entry a = old.entries.get(index), b = actual.entries.get(index);
                assertEquals(a.id, b.id);
                assertEquals(a.type, b.type);
                assertEquals(a.identity, b.identity);
                assertEquals(a.label, b.label);
                assertEquals(a.algorithm, b.algorithm);
                assertEquals(a.digits, b.digits);
                assertEquals(a.period, b.period);
                assertArrayEquals(a.secret, b.secret);
            }
        } finally { VaultCodec.wipe(plain); }
    }
    private void assertOriginal() { assertArrayEquals(fixture, store.bytes); assertArrayEquals(new byte[] {4, 5, 6}, store.wrapper); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private interface Throwing { void run() throws Exception; }
    private static void rejects(String code, Throwing operation) throws Exception {
        try { operation.run(); fail("expected " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }
}
