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

/** Real encrypted-store/typed-release tests; native decrypt policy has a separate ARM matrix. */
public final class SshKeyPassphraseTest {
    private static final byte[] KEY = new byte[32], OTP = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    private static final byte[] PHRASE = "synthetic key phrase 雪".getBytes(StandardCharsets.UTF_8);
    private static final String PATH = ".ssh/id_phrase";
    private static final class Clock implements VaultSession.Clock, SshRoleContext.Clock, SshPasswordManifestValidator.Clock {
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
    private static byte[] encryptedBody() {
        byte[] magic = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII), cipher = "aes256-ctr".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = ByteBuffer.allocate(magic.length + 4 + cipher.length).put(magic).putInt(cipher.length).put(cipher).array();
        return ("-----BEGIN OPENSSH PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(raw)
                + "\n-----END OPENSSH PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);
    }
    private static final class Fixture implements AutoCloseable {
        final Clock clock = new Clock(); final Store store = new Store(); final AppSecrets vault = new AppSecrets(store, clock);
        final SshPasswordProfile profile = new SshPasswordProfile("key.invalid", 2200, "key-user");
        final SshSavedPassword login = new SshSavedPassword(new SshPasswordProfile("password.invalid", 22, "password-user"));
        final AtomicBoolean cancelled = new AtomicBoolean();
        Fixture() throws Exception {
            byte[] name = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
            byte[] blob = ByteBuffer.allocate(4 + name.length + 4 + 32).putInt(name.length).put(name).putInt(32).put(new byte[32]).array();
            String identity = "ctos-key-" + SshConnectionMaterial.hex(VaultCodec.digest(blob));
            byte[] publicLine = ("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + "\n").getBytes(StandardCharsets.US_ASCII);
            List<VaultManifest.FileObject> files = new ArrayList<>(); List<VaultSshMetadata.KeyBinding> keys = new ArrayList<>();
            for (String path : List.of(PATH, PATH + "_second")) {
                files.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                        SshConnectionMaterial.id("private", path), path, identity, encryptedBody(), List.of()));
                files.add(new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                        SshConnectionMaterial.id("public", path), path + ".pub", identity, publicLine, List.of()));
                keys.add(new VaultSshMetadata.KeyBinding(SshConnectionMaterial.bindingId(path, identity),
                        SshConnectionMaterial.id("private", path), SshConnectionMaterial.id("public", path), List.of(), null));
            }
            files.add(SshConnectionMaterial.emptyHosts());
            SshConnectionMaterial.Key key = new SshConnectionMaterial.Key(new VaultManifest(List.of(), files, keys, List.of()), keys.get(0));
            try (VaultManifest initial = new VaultManifest(List.of(
                    new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "otp", "otp:fixture", "OTP", "SHA1", 6, 30, OTP),
                    new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, login.entryId, login.target, "Password", "", 0, 0, PHRASE)),
                    files, keys, List.of(login.account(), new SshConnectionMaterial.KeyAccount(profile, key).declaration()))) {
                byte[] plain = initial.encode();
                try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], KEY, plain).encode(); }
                finally { VaultCodec.wipe(plain); }
            }
            vault.attachSshValidator(new SshPasswordManifestValidator(clock, (m, p, d) -> {
                assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
                SshConnectionMaterial.checkShape(m);
            }));
            vault.session.foreground(true); vault.session.unlock(KEY);
        }
        VaultManifest read() throws Exception {
            byte[] plain = VaultEnvelope.parse(store.bytes).decrypt(KEY);
            try { return VaultManifest.decode(plain); } finally { VaultCodec.wipe(plain); }
        }
        SshConnectionMaterial.Key key(VaultManifest manifest, int index) throws Exception {
            return new SshConnectionMaterial.Key(manifest, manifest.keyBindings.get(index));
        }
        void mutate(int index, boolean all, boolean save) throws Exception {
            AppSecrets.SshEntryMutationContext context;
            try (VaultManifest source = read()) {
                context = vault.newKeyPassphraseMutationContext(vault.session.epoch(), 60000,
                        () -> { if (cancelled.get()) throw new VaultException("VAULT_CANCELLED", "Synthetic cancellation"); },
                        all ? null : key(source, index), all);
            }
            VaultOperation operation = vault.beginKeyPassphraseMutation(context, save ? "save-key-phrase" : "", save);
            try {
                if (save) vault.saveKeyPassphrase(operation, PHRASE, () -> { });
                else vault.forgetKeyPassphrase(operation, () -> { });
            } finally { vault.cancelOperation(operation); context.cancel(); }
        }
        void unrelated(VaultManifest manifest) throws Exception {
            assertArrayEquals(OTP, manifest.entries.stream().filter(e -> e.type == VaultManifest.EntryType.TOTP).findFirst().get().secret);
            assertArrayEquals(PHRASE, manifest.entries.stream().filter(e -> e.type == VaultManifest.EntryType.SSH_PASSWORD).findFirst().get().secret);
            assertTrue(login.present(manifest.accounts)); assertEquals(5, manifest.files.size()); assertEquals(2, manifest.keyBindings.size());
        }
        @Override public void close() { vault.session.lock(); VaultCodec.wipe(store.bytes); }
    }
    private interface Action { void run() throws Exception; }
    private static void code(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); } catch (VaultException failure) { assertEquals(code, failure.code); }
    }
    private static byte[] nonce() { byte[] value = new byte[32]; value[0] = 1; return value; }
    private static SshRoleContext role(Fixture f, SshConnectionMaterial.Key key) throws Exception {
        SshConnectionMaterial.KeyAccount account = new SshConnectionMaterial.KeyAccount(f.profile, key);
        return SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT, 42, 0,
                "key-connection", account.target, f.profile.host, f.profile.port, "key-view", nonce(), nonce(), 31000), () -> true, f.clock);
    }
    private static SshHostKeyAuthority.Proof known(SshHostKeyAuthority authority, SshRoleContext role) throws Exception {
        return authority.requireVerified(role, SshHostKeyAuthority.Evidence.fromNative(role,
                SshHostKeyAuthority.Classification.KNOWN, "ssh-ed25519", "SHA256:" + "A".repeat(43), new byte[32], new byte[32]), null);
    }

    @Test public void noSaveGraphNeedsNoPhraseAndAtomicSaveOneAllForgetPreserveOtherBusinesses() throws Exception {
        try (Fixture f = new Fixture()) {
            try (VaultManifest source = f.read()) {
                SshConnectionMaterial.checkShape(source); f.unrelated(source);
                assertTrue(f.key(source, 0).encrypted); assertFalse(f.key(source, 0).hasSavedPassphrase);
                assertEquals(2, source.entries.size());
            }
            f.mutate(0, false, true); f.mutate(1, false, true);
            try (VaultManifest saved = f.read()) {
                f.unrelated(saved); SshConnectionMaterial.checkShape(saved);
                assertNotEquals(saved.keyBindings.get(0).passphraseEntryId, saved.keyBindings.get(1).passphraseEntryId);
                assertEquals(4, saved.entries.size());
            }
            f.mutate(0, false, false);
            try (VaultManifest one = f.read()) {
                assertFalse(f.key(one, 0).hasSavedPassphrase); assertTrue(f.key(one, 1).hasSavedPassphrase); f.unrelated(one);
            }
            f.mutate(0, true, false);
            try (VaultManifest none = f.read()) {
                assertEquals(2, none.entries.size()); for (VaultSshMetadata.KeyBinding key : none.keyBindings) assertNull(key.passphraseEntryId);
                f.unrelated(none); SshConnectionMaterial.checkShape(none);
            }
            assertEquals(4, f.store.writes);
        }
    }

    @Test public void cancelledFinalMutationCannotPersistUnlinkedPhraseOrChangeOldCiphertext() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] before = f.store.bytes.clone(); f.store.before = () -> f.cancelled.set(true);
            code("VAULT_CANCELLED", () -> f.mutate(0, false, true));
            assertArrayEquals(before, f.store.bytes); assertEquals(0, f.store.writes);
        }
    }

    @Test public void savedBundleRequiresExactBindingAndSelectionAndIsOneUse() throws Exception {
        try (Fixture f = new Fixture()) {
            f.mutate(0, false, true);
            try (VaultManifest source = f.read()) {
                SshConnectionMaterial.Key key = f.key(source, 0); byte[] blob = key.publicBlob();
                try (SshRoleContext role = role(f, key)) {
                    SshAuthenticationSelection selected = SshAuthenticationSelection.privateKey(role, f.profile.username,
                            key.path, key.identity, blob, key.binding.passphraseEntryId, f.clock);
                    SshHostKeyAuthority authority = new SshHostKeyAuthority(); SshHostKeyAuthority.Proof host = known(authority, role);
                    SshKexProof kex = SshKexProof.fromNative(role, host, 1, nonce());
                    SshCredentialBridge bridge = new SshCredentialBridge(f.vault, authority, f.clock);
                    SshCredentialBridge.Request request = bridge.openSelection(role, host, selected, kex, () -> { });
                    byte[][] loan = new byte[1][];
                    bridge.release(request, kex, selected, bytes -> {
                        loan[0] = bytes; ByteBuffer bundle = ByteBuffer.wrap(bytes);
                        assertEquals(0x43534b42, bundle.getInt()); assertEquals(1, bundle.getInt());
                        byte[] body = new byte[bundle.getInt()]; bundle.get(body); assertArrayEquals(encryptedBody(), body);
                        byte[] phrase = new byte[bundle.getInt()]; bundle.get(phrase); assertArrayEquals(PHRASE, phrase); assertFalse(bundle.hasRemaining());
                    });
                    assertArrayEquals(new byte[loan[0].length], loan[0]);
                    code("VAULT_CANCELLED", () -> bridge.release(request, kex, selected, bytes -> fail("Repeated release")));
                    SshConnectionMaterial.Key other = f.key(source, 1);
                    SshAuthenticationSelection wrong = SshAuthenticationSelection.privateKey(role, f.profile.username,
                            key.path, key.identity, blob, new SshKeyPassphrase(other).entryId, f.clock);
                    code("SSH_AUTH_SELECTION", () -> bridge.openSelection(role, host, wrong, kex, () -> { }));
                } finally { VaultCodec.wipe(blob); }
            }
        }
    }

    @Test public void transientBundleUsesSameNativeSelectionWithoutCreatingAnySavedEntry() throws Exception {
        try (Fixture f = new Fixture(); VaultManifest source = f.read()) {
            SshConnectionMaterial.Key key = f.key(source, 0); byte[] blob = key.publicBlob();
            try (SshRoleContext role = role(f, key);
                    SshTransientKey transientKey = new SshTransientKey(f.vault, f.profile, key, encryptedBody(), PHRASE,
                            31000, () -> { if (f.cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic"); }, f.clock)) {
                SshAuthenticationSelection selected = SshAuthenticationSelection.privateKey(role, f.profile.username,
                        key.path, key.identity, blob, transientKey.entryId, f.clock);
                transientKey.bind(role, selected);
                SshHostKeyAuthority authority = new SshHostKeyAuthority(); SshHostKeyAuthority.Proof host = known(authority, role);
                SshKexProof kex = SshKexProof.fromNative(role, host, 1, nonce());
                SshCredentialBridge bridge = new SshCredentialBridge(f.vault, authority, f.clock, null, transientKey);
                SshManagedProvider provider = new SshManagedProvider(bridge, f.clock, null, null,
                        (b, r, p, k) -> { throw new AssertionError("No legacy bypass"); });
                AppSecrets.SshSecretReadContext unbound = f.vault.newSavedPasswordReadContext(31000, () -> { }, selected);
                try { code("SSH_AUTH_SELECTION", () -> f.vault.beginSshPrivateKey(key.path, key.identity,
                        role.targetIdentity, role.connectionId, unbound)); } finally { unbound.cancel(); }
                byte[] bundle = provider.credentialBytes(role, host, kex, SshCredentialBridge.Kind.PRIVATE_KEY, selected, () -> { });
                try { assertEquals(0x43534b42, ByteBuffer.wrap(bundle).getInt()); }
                finally { VaultCodec.wipe(bundle); }
                code("SSH_CREDENTIAL_PROVIDER", () -> provider.credentialBytes(role, host, kex,
                        SshCredentialBridge.Kind.PRIVATE_KEY, selected, () -> { }));
                try (VaultManifest after = f.read()) { assertEquals(2, after.entries.size()); f.unrelated(after); }
                assertEquals(0, f.store.writes);
            } finally { VaultCodec.wipe(blob); }
        }
    }

    @Test public void malformedPhraseTextAndUtf8ByteLimitsRejectBeforeAnyOperation() throws Exception {
        for (String value : List.of("", "bad\0phrase", "\ud800", "\udc00", "🙂".repeat(2049)))
            code("PARAMETERS", () -> SshKeyPassphrase.bytes(value, false));
        byte[] valid = SshKeyPassphrase.bytes("🙂".repeat(2048), false);
        assertEquals(8192, valid.length); VaultCodec.wipe(valid);
    }

    @Test public void samePublicIdentityReplacementRemovesOldPhraseInTheExactFileTransaction() throws Exception {
        try (Fixture f = new Fixture()) {
            f.mutate(0, false, true);
            try (VaultManifest source = f.read()) {
                SshConnectionMaterial.Key key = f.key(source, 0);
                List<VaultSshMetadata.KeyBinding> bindings = new ArrayList<>(source.keyBindings);
                bindings.set(0, new VaultSshMetadata.KeyBinding(key.id, key.binding.privateFileId,
                        key.binding.publicFileId, List.of(), null));
                VaultManifest.FileObject before = SshConnectionMaterial.file(source, PATH);
                byte[] replacement = Arrays.copyOf(before.content, before.content.length + 1);
                replacement[replacement.length - 1] = '\n';
                VaultManifest.FileObject next = new VaultManifest.FileObject(before.type, before.id, before.path,
                        before.identity, replacement, before.references);
                SshBundleProposal.Change change = SshBundleProposal.Change.replace(before, next);
                try (SshBundleProposal proposal = new SshBundleProposal(List.of(change),
                        new SshBundleProposal.Relations(source, bindings, source.accounts),
                        java.util.Set.of(key.binding.passphraseEntryId))) {
                    AppSecrets.BundleContext context = f.vault.newBundleContext(60000, () -> { });
                    SshVaultAdapter adapter = new SshVaultAdapter(f.vault, (c, t) -> { throw VaultException.invalid(); },
                            p -> { throw VaultException.invalid(); }, approved -> { if (approved != proposal) throw VaultException.invalid(); });
                    VaultOperation operation = adapter.bundleRequest(proposal, context);
                    try { adapter.commitBundle(operation, proposal, () -> { }); }
                    finally { adapter.cancel(operation); f.vault.cancelBundleContext(context); }
                } finally { change.close(); VaultCodec.wipe(replacement); VaultCodec.wipe(next.content); }
                try (VaultManifest actual = f.read()) {
                    assertEquals(key.id, actual.keyBindings.get(0).id); assertNull(actual.keyBindings.get(0).passphraseEntryId);
                    assertEquals(2, actual.entries.size()); f.unrelated(actual);
                }
            }
        }
    }

    @Test public void orphanOrCrossKeyPhraseLinksAndClaimedPublicIdentityAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.mutate(0, false, true);
            try (VaultManifest source = f.read()) {
                SshConnectionMaterial.Key first = f.key(source, 0), second = f.key(source, 1);
                List<VaultSshMetadata.KeyBinding> cross = new ArrayList<>(source.keyBindings);
                cross.set(1, new VaultSshMetadata.KeyBinding(second.id, second.binding.privateFileId,
                        second.binding.publicFileId, List.of(), first.binding.passphraseEntryId));
                code("VAULT_CONTENT", () -> new VaultManifest(source.entries, source.files, cross, source.accounts));
                List<VaultSshMetadata.KeyBinding> orphan = new ArrayList<>(source.keyBindings);
                orphan.set(0, new VaultSshMetadata.KeyBinding(first.id, first.binding.privateFileId,
                        first.binding.publicFileId, List.of(), null));
                VaultManifest unlinked = new VaultManifest(source.entries, source.files, orphan, source.accounts);
                code("SSH_PROFILE_UNSUPPORTED", () -> SshConnectionMaterial.checkShape(unlinked));
                VaultManifest.FileObject publicFile = SshConnectionMaterial.fileId(source, first.binding.publicFileId);
                List<VaultManifest.FileObject> changed = new ArrayList<>();
                for (VaultManifest.FileObject file : source.files) changed.add(file == publicFile
                        ? new VaultManifest.FileObject(file.type, file.id, file.path, "claimed-other-identity", file.content, file.references) : file);
                VaultManifest badPublic = new VaultManifest(source.entries, changed, source.keyBindings, source.accounts);
                code("SSH_PROFILE_UNSUPPORTED", () -> SshConnectionMaterial.checkShape(badPublic));
            }
        }
    }

    @Test public void transientCancellationAndDifferentReceiptCannotPublishAnyPrivateBundle() throws Exception {
        try (Fixture f = new Fixture(); VaultManifest source = f.read()) {
            SshConnectionMaterial.Key key = f.key(source, 0); byte[] blob = key.publicBlob();
            try (SshRoleContext role = role(f, key);
                    SshTransientKey transientKey = new SshTransientKey(f.vault, f.profile, key, encryptedBody(), PHRASE,
                            31000, () -> { if (f.cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic"); }, f.clock)) {
                SshAuthenticationSelection selected = SshAuthenticationSelection.privateKey(role, f.profile.username,
                        key.path, key.identity, blob, transientKey.entryId, f.clock);
                transientKey.bind(role, selected);
                SshAuthenticationSelection copy = SshAuthenticationSelection.privateKey(role, f.profile.username,
                        key.path, key.identity, blob, transientKey.entryId, f.clock);
                code("SSH_CREDENTIAL_PROVIDER", () -> transientKey.requireSelection(copy));
                SshHostKeyAuthority authority = new SshHostKeyAuthority(); SshHostKeyAuthority.Proof host = known(authority, role);
                SshKexProof kex = SshKexProof.fromNative(role, host, 1, nonce());
                f.cancelled.set(true);
                code("SSH_CANCELLED", () -> transientKey.take(role, host, kex, selected, () -> { }));
                assertEquals(0, f.store.writes);
            } finally { VaultCodec.wipe(blob); }
        }
    }
}
