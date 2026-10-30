package im.majo.ctos;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Exact graph/store tests with an injected shape gate; actual native admission runs in the device fixture. */
public final class SshKeyDeletionTest {
    private static final String A = ".ssh/id_delete", B = ".ssh/id_other", CHILD = ".ssh/child";
    private static final byte[] MASTER = new byte[32], OTP = {1,2,3,4,5,6,7,8,9,10};
    private static final byte[] PHRASE = "synthetic phrase".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PRIVATE = ("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n"
            + "synthetic-envelope-shape-only\n-----END RSA PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);

    private interface Work { void run() throws Exception; }
    private static void code(String expected, Work work) throws Exception {
        try { work.run(); fail("Expected " + expected); }
        catch (VaultException failure) { assertEquals(expected, failure.code); }
    }
    private static VaultManifest.FileObject config(String path, String text, String... references) {
        return new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, SshConnectionMaterial.id("config", path),
                path, SshConnectionMaterial.id("config-owner", path), text.getBytes(StandardCharsets.UTF_8),
                Arrays.asList(references));
    }
    private static VaultManifest baseline(boolean manual, boolean inbound, boolean manualOther) throws Exception {
        byte[] name = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        byte[] blob = ByteBuffer.allocate(4 + name.length + 4 + 32).putInt(name.length).put(name).putInt(32).put(new byte[32]).array();
        byte[] publicLine = ("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + "\n").getBytes(StandardCharsets.US_ASCII);
        String identity = "ctos-key-" + SshConnectionMaterial.hex(VaultCodec.digest(blob));
        List<VaultManifest.FileObject> files = new ArrayList<>();
        List<VaultSshMetadata.KeyBinding> bindings = new ArrayList<>();
        for (String path : List.of(A, B)) {
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                    SshConnectionMaterial.id("private", path), path, identity, PRIVATE, List.of()));
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                    SshConnectionMaterial.id("public", path), path + ".pub", identity, publicLine, List.of()));
            bindings.add(new VaultSshMetadata.KeyBinding(SshConnectionMaterial.bindingId(path, identity),
                    SshConnectionMaterial.id("private", path), SshConnectionMaterial.id("public", path), List.of(), null));
        }
        files.add(SshConnectionMaterial.emptyHosts());
        files.add(config(CHILD, "Host active\n IdentityFile ~/" + A + "\n IdentityFile ~/" + A + ".pub\n",
                SshConnectionMaterial.HOSTS, A, A + ".pub"));
        List<String> rootRefs = new ArrayList<>(List.of(SshConnectionMaterial.HOSTS, CHILD, A, B));
        if (inbound) rootRefs.add(SshConnectionMaterial.MANUAL);
        files.add(config(SshConnectionMaterial.ROOT, "Include ~/.ssh/child\nHost inactive\n IdentityFile ~/" + A
                + "\nHost other\n IdentityFile ~/" + B + "\n", rootRefs.toArray(new String[0])));
        if (manual) files.add(config(SshConnectionMaterial.MANUAL, "Host ctos-key\n IdentityFile ~/"
                + (manualOther ? B : A) + "\n", SshConnectionMaterial.HOSTS, manualOther ? B : A));
        SshSavedPassword password = new SshSavedPassword(new SshPasswordProfile("password.invalid", 22, "password"));
        List<VaultManifest.Entry> entries = List.of(
                new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "otp", "otp:fixture", "OTP", "SHA1", 6, 30, OTP),
                new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, password.entryId, password.target,
                        "Password", "", 0, 0, PHRASE));
        VaultManifest current = new VaultManifest(entries, files, bindings, List.of(password.account()));
        try {
            for (int i = 0; i < 2; i++) {
                SshConnectionMaterial.Key key = new SshConnectionMaterial.Key(current, current.keyBindings.get(i));
                SshKeyPassphrase phrase = new SshKeyPassphrase(key);
                VaultManifest.Entry replacement = new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE,
                        phrase.entryId, phrase.identity, "Phrase", "", 0, 0, PHRASE);
                VaultManifest next;
                try { next = SshKeyPassphrase.candidate(current, key.id, false, replacement); }
                finally { VaultCodec.wipe(replacement.secret); }
                current.close(); current = next;
            }
            List<VaultSshMetadata.Account> accounts = new ArrayList<>(current.accounts);
            for (VaultSshMetadata.KeyBinding binding : current.keyBindings) {
                SshConnectionMaterial.Key key = new SshConnectionMaterial.Key(current, binding);
                accounts.add(new SshConnectionMaterial.KeyAccount(new SshPasswordProfile("first.invalid", 22, "user"), key).declaration());
                accounts.add(new SshConnectionMaterial.KeyAccount(new SshPasswordProfile("second.invalid", 2200, "user"), key).declaration());
            }
            try (SshBundleProposal relations = new SshBundleProposal(List.of(),
                    new SshBundleProposal.Relations(current, current.keyBindings, accounts))) {
                VaultManifest result = relations.applyTo(current); current.close(); current = null; return result;
            }
        } finally {
            if (current != null) current.close();
            for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
            VaultCodec.wipe(blob); VaultCodec.wipe(publicLine);
        }
    }
    private static String selected(VaultManifest manifest) { return manifest.keyBindings.get(0).id; }

    @Test public void exactDeletionPrunesOnlySelectedGraphAndPreservesAllConfigBytes() throws Exception {
        try (VaultManifest source = baseline(false, false, false);
                SshKeyDeletion deletion = SshKeyDeletion.create(source, selected(source));
                VaultManifest result = deletion.proposal.applyTo(source)) {
            SshConnectionMaterial.checkShape(result);
            assertEquals(2, deletion.accountCount); assertEquals(List.of(CHILD, SshConnectionMaterial.ROOT), deletion.configPaths);
            assertTrue(deletion.key.hasSavedPassphrase); assertFalse(deletion.removesManualConfig);
            assertNull(SshConnectionMaterial.file(result, A)); assertNull(SshConnectionMaterial.file(result, A + ".pub"));
            assertNotNull(SshConnectionMaterial.file(result, B)); assertEquals(1, result.keyBindings.size());
            assertEquals(3, result.entries.size()); assertEquals(3, result.accounts.size());
            assertEquals(source.keyBindings.get(1).id, result.keyBindings.get(0).id);
            assertEquals(source.keyBindings.get(1).passphraseEntryId, result.keyBindings.get(0).passphraseEntryId);
            assertArrayEquals(OTP, result.entries.stream().filter(e -> e.type == VaultManifest.EntryType.TOTP).findFirst().get().secret);
            assertArrayEquals(PHRASE, result.entries.stream().filter(e -> e.type == VaultManifest.EntryType.SSH_PASSWORD).findFirst().get().secret);
            assertArrayEquals(SshConnectionMaterial.file(source, B).content, SshConnectionMaterial.file(result, B).content);
            for (String path : deletion.configPaths) {
                VaultManifest.FileObject old = SshConnectionMaterial.file(source, path), next = SshConnectionMaterial.file(result, path);
                assertArrayEquals(old.content, next.content); assertEquals(old.id, next.id); assertEquals(old.identity, next.identity);
                assertFalse(next.references.contains(A)); assertFalse(next.references.contains(A + ".pub"));
                assertTrue(next.references.contains(SshConnectionMaterial.HOSTS));
            }
            assertEquals(4, source.entries.size()); assertEquals(2, source.keyBindings.size());
            assertEquals(A + ".pub", deletion.metadata("delete-ticket").get("publicPath"));
        }
    }

    @Test public void unreferencedOwnedManualIsRemovedButInboundIncludeKeepsItsBytes() throws Exception {
        for (boolean inbound : List.of(false, true)) {
            try (VaultManifest source = baseline(true, inbound, false);
                    SshKeyDeletion deletion = SshKeyDeletion.create(source, selected(source));
                    VaultManifest result = deletion.proposal.applyTo(source)) {
                assertEquals(!inbound, deletion.removesManualConfig);
                VaultManifest.FileObject manual = SshConnectionMaterial.file(result, SshConnectionMaterial.MANUAL);
                if (!inbound) assertNull(manual);
                else {
                    assertArrayEquals(SshConnectionMaterial.file(source, SshConnectionMaterial.MANUAL).content, manual.content);
                    assertEquals(List.of(SshConnectionMaterial.HOSTS), manual.references);
                    assertTrue(SshConnectionMaterial.file(result, SshConnectionMaterial.ROOT).references.contains(SshConnectionMaterial.MANUAL));
                }
                SshConnectionMaterial.checkShape(result);
            }
        }
    }

    @Test public void manualForAnotherKeyAndSamePublicAtOtherPathRemainExact() throws Exception {
        try (VaultManifest source = baseline(true, false, true);
                SshKeyDeletion deletion = SshKeyDeletion.create(source, selected(source));
                VaultManifest result = deletion.proposal.applyTo(source)) {
            assertFalse(deletion.removesManualConfig);
            for (String path : List.of(B, B + ".pub", SshConnectionMaterial.MANUAL))
                assertEquals(SshBundleProposal.objectBinding(SshConnectionMaterial.file(source, path)),
                        SshBundleProposal.objectBinding(SshConnectionMaterial.file(result, path)));
        }
    }

    @Test public void noSavedPhraseAndLastKeyRemovalStillLeaveUsableBoundedGraph() throws Exception {
        try (VaultManifest source = baseline(false, false, false);
                VaultManifest noPhrases = SshKeyPassphrase.candidate(source, "", true, null);
                SshKeyDeletion first = SshKeyDeletion.create(noPhrases, selected(noPhrases));
                VaultManifest one = first.proposal.applyTo(noPhrases);
                SshKeyDeletion last = SshKeyDeletion.create(one, selected(one));
                VaultManifest empty = last.proposal.applyTo(one)) {
            assertFalse(first.key.hasSavedPassphrase); assertTrue(empty.keyBindings.isEmpty());
            assertEquals(2, empty.entries.size()); assertEquals(1, empty.accounts.size());
            SshConnectionMaterial.checkShape(empty);
            assertNotNull(SshConnectionMaterial.file(empty, SshConnectionMaterial.ROOT));
        }
    }

    @Test public void exactProposalRejectsChangedPrivateBytesAndRelationsAndClosedOwner() throws Exception {
        try (VaultManifest source = baseline(false, false, false);
                SshKeyDeletion deletion = SshKeyDeletion.create(source, selected(source))) {
            VaultManifest.FileObject old = SshConnectionMaterial.file(source, A);
            VaultManifest.FileObject replacement = new VaultManifest.FileObject(old.type, old.id, old.path,
                    old.identity, new byte[] {9}, old.references);
            try (SshBundleProposal.Change edit = SshBundleProposal.Change.replace(old, replacement);
                    SshBundleProposal change = new SshBundleProposal(List.of(edit));
                    VaultManifest changed = change.applyTo(source)) {
                code("VAULT_CHANGED", () -> deletion.proposal.applyTo(changed));
            } finally { VaultCodec.wipe(replacement.content); }
            try (SshBundleProposal change = new SshBundleProposal(List.of(),
                    new SshBundleProposal.Relations(source, source.keyBindings, List.of(source.accounts.get(0))));
                    VaultManifest changed = change.applyTo(source)) {
                code("VAULT_CHANGED", () -> deletion.proposal.applyTo(changed));
            }
            code("SSH_KEY_MISSING", () -> SshKeyDeletion.create(source, "no-such-key"));
            deletion.close(); code("VAULT_EXPIRED", () -> deletion.proposal.applyTo(source));
        }
    }

    private static final class Clock implements VaultSession.Clock, SshPasswordManifestValidator.Clock {
        long now = 1000;
        @Override public long now() { return now; }
    }
    private static final class Store implements AppSecrets.Store {
        byte[] bytes; int writes; Runnable before;
        @Override public byte[] read() { return bytes.clone(); }
        @Override public byte[] quickMaterial() { return new byte[0]; }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard) throws VaultException {
            if (before != null) before.run(); guard.beforeCommit(); bytes = next.clone(); writes++;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Clock clock = new Clock(); final Store store = new Store(); final AppSecrets vault = new AppSecrets(store, clock);
        Fixture() throws Exception {
            try (VaultManifest source = baseline(false, false, false)) {
                byte[] plain = source.encode();
                try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], MASTER, plain).encode(); }
                finally { VaultCodec.wipe(plain); }
            }
            vault.attachSshValidator(new SshPasswordManifestValidator(clock, (m, p, d) -> {
                assertFalse(Thread.holdsLock(vault)); SshConnectionMaterial.checkShape(m);
            }));
            vault.session.foreground(true); vault.session.unlock(MASTER);
        }
        VaultManifest read() throws Exception {
            byte[] plain = VaultEnvelope.parse(store.bytes).decrypt(MASTER);
            try { return VaultManifest.decode(plain); } finally { VaultCodec.wipe(plain); }
        }
        @Override public void close() { vault.session.lock(); VaultCodec.wipe(store.bytes); }
    }

    @Test public void encryptedStoreDeletionIsOneAtomicBundleAndCannotReplay() throws Exception {
        try (Fixture f = new Fixture(); VaultManifest source = f.read();
                SshKeyDeletion deletion = SshKeyDeletion.create(source, selected(source))) {
            AppSecrets.BundleContext context = f.vault.newBundleContext(60000, () -> { });
            SshVaultAdapter adapter = new SshVaultAdapter(f.vault, (c, t) -> { throw VaultException.invalid(); },
                    p -> { throw VaultException.invalid(); }, actual -> assertSame(deletion.proposal, actual));
            VaultOperation operation = adapter.bundleRequest(deletion.proposal, context);
            try {
                adapter.commitBundle(operation, deletion.proposal, () -> { }); assertEquals(1, f.store.writes);
                try (VaultManifest result = f.read()) {
                    assertEquals(1, result.keyBindings.size()); assertEquals(3, result.entries.size());
                    assertEquals(3, result.accounts.size()); assertNull(SshConnectionMaterial.file(result, A));
                }
                try { adapter.commitBundle(operation, deletion.proposal, () -> { }); fail("Replayed deletion"); }
                catch (VaultException expected) { }
                assertEquals(1, f.store.writes);
            } finally { adapter.cancel(operation); f.vault.cancelBundleContext(context); }
        }
    }

    @Test public void cancellationAtFinalStoreGateKeepsOriginalCiphertextAndGraph() throws Exception {
        try (Fixture f = new Fixture(); VaultManifest source = f.read();
                SshKeyDeletion deletion = SshKeyDeletion.create(source, selected(source))) {
            AtomicBoolean cancelled = new AtomicBoolean(); byte[] before = f.store.bytes.clone();
            AppSecrets.EffectGuard guard = () -> {
                if (cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic original cancellation");
            };
            AppSecrets.BundleContext context = f.vault.newBundleContext(60000, guard);
            SshVaultAdapter adapter = new SshVaultAdapter(f.vault, (c, t) -> { throw VaultException.invalid(); },
                    p -> { throw VaultException.invalid(); }, actual -> assertSame(deletion.proposal, actual));
            VaultOperation operation = adapter.bundleRequest(deletion.proposal, context);
            try {
                f.store.before = () -> cancelled.set(true);
                code("SSH_CANCELLED", () -> adapter.commitBundle(operation, deletion.proposal, guard));
                assertEquals(0, f.store.writes); assertArrayEquals(before, f.store.bytes);
                try (VaultManifest result = f.read()) { assertEquals(2, result.keyBindings.size()); assertEquals(4, result.entries.size()); }
            } finally { adapter.cancel(operation); f.vault.cancelBundleContext(context); }
        }
    }
}
