package im.majo.ctos;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Actual encrypted source/candidate and guarded store path; native parser is the injected boundary. */
public final class SshPasswordProfileTest {
    private static final byte[] KEY = new byte[32];
    private static final byte[] OTP = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    private static final class Clock implements VaultSession.Clock, SshPasswordManifestValidator.Clock {
        long now = 1000;
        @Override public long now() { return now; }
    }
    private static final class Store implements AppSecrets.Store {
        byte[] bytes;
        int writes;
        Runnable beforeCommit;
        @Override public byte[] read() { return bytes.clone(); }
        @Override public byte[] quickMaterial() { return new byte[0]; }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard) throws VaultException {
            if (beforeCommit != null) beforeCommit.run();
            guard.beforeCommit(); bytes = next.clone(); writes++;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Clock clock = new Clock();
        final Store store = new Store();
        final AppSecrets vault = new AppSecrets(store, clock);
        final AtomicInteger nativeChecks = new AtomicInteger();
        Fixture(VaultManifest manifest) throws Exception {
            byte[] plain = manifest.encode();
            try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], KEY, plain).encode(); }
            finally { VaultCodec.wipe(plain); manifest.close(); }
            vault.attachSshValidator(new SshPasswordManifestValidator(clock, (source, profile, deadline) -> {
                assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
                assertTrue(deadline > clock.now); nativeChecks.incrementAndGet();
            }));
            vault.session.foreground(true); vault.session.unlock(KEY);
        }
        void save(SshPasswordProfile profile, AppSecrets.EffectGuard guard) throws Exception {
            AppSecrets.BundleContext context = vault.newBundleContext(60000, guard);
            try (SshPasswordProfile.Snapshot source = vault.passwordProfile(context);
                    SshBundleProposal proposal = source.changes(profile)) {
                if (proposal == null) return;
                SshVaultAdapter adapter = new SshVaultAdapter(vault, (c, t) -> { throw VaultException.invalid(); },
                        p -> { throw VaultException.invalid(); }, exact -> {
                            guard.check(); if (exact != proposal) throw VaultException.invalid();
                        });
                VaultOperation operation = adapter.bundleRequest(proposal, context);
                try { adapter.commitBundle(operation, proposal, guard); }
                finally { adapter.cancel(operation); }
            } finally { vault.cancelBundleContext(context); }
        }
        VaultManifest read() throws Exception {
            byte[] plain = VaultEnvelope.parse(store.bytes).decrypt(KEY);
            try { return VaultManifest.decode(plain); } finally { VaultCodec.wipe(plain); }
        }
        @Override public void close() { vault.session.lock(); VaultCodec.wipe(store.bytes); }
    }
    private interface Action { void run() throws Exception; }
    private static void code(String expected, Action action) throws Exception {
        try { action.run(); fail("Expected " + expected); }
        catch (VaultException failure) { assertEquals(expected, failure.code); }
    }
    private static VaultManifest totp() throws Exception {
        return new VaultManifest(List.of(new VaultManifest.Entry(VaultManifest.EntryType.TOTP,
                "otp", "otp:fixture", "Fixture", "SHA1", 6, 30, OTP)), List.of());
    }
    private static VaultManifest profile(SshPasswordProfile profile, byte[] known) throws Exception {
        return new VaultManifest(List.of(), List.of(
                new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, SshPasswordProfile.CONFIG_ID,
                        SshPasswordProfile.CONFIG_PATH, SshPasswordProfile.CONFIG_IDENTITY, profile.configBytes(),
                        List.of(SshPasswordProfile.HOSTS_PATH)),
                new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, SshPasswordProfile.HOSTS_ID,
                        SshPasswordProfile.HOSTS_PATH, SshPasswordProfile.HOSTS_IDENTITY, known, List.of())));
    }

    @Test public void canonicalQuotedUsernameRoundTripsWithoutTrimmingOrShellEscapes() throws Exception {
        SshPasswordProfile value = new SshPasswordProfile("fixture.invalid", 2222, " user \\\"雪 ");
        SshPasswordProfile decoded = SshPasswordProfile.parse(value.configBytes());
        assertTrue(value.same(decoded)); assertEquals(" user \\\"雪 ", decoded.username);
        code("SSH_PROFILE_UNSUPPORTED", () -> SshPasswordProfile.parse(
                new String(value.configBytes(), StandardCharsets.UTF_8).replace("Port 2222", "Port 02222")
                        .getBytes(StandardCharsets.UTF_8)));
        code("SSH_PROFILE_UNSUPPORTED", () -> SshPasswordProfile.parse(
                (new String(value.configBytes(), StandardCharsets.UTF_8) + "ProxyCommand anything\n")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void pureTotpDefaultsDoNotRunNativeAndExactBundlePreservesSeedWithoutPasswordEntry() throws Exception {
        try (Fixture f = new Fixture(totp())) {
            AppSecrets.BundleContext defaults = f.vault.newBundleContext(60000, () -> { });
            try (SshPasswordProfile.Snapshot value = f.vault.passwordProfile(defaults)) {
                assertNull(value.profile); assertNull(value.config); assertNull(value.knownHosts);
            } finally { f.vault.cancelBundleContext(defaults); }
            assertEquals(0, f.nativeChecks.get());
            long epoch = f.vault.session.epoch();
            SshPasswordProfile next = new SshPasswordProfile("fixture.invalid", 2222, "actual user");
            f.save(next, () -> { });
            assertEquals(1, f.store.writes); assertEquals(epoch + 1, f.vault.session.epoch());
            assertTrue(f.nativeChecks.get() >= 2);
            try (VaultManifest saved = f.read()) {
                assertEquals(1, saved.entries.size()); assertArrayEquals(OTP, saved.entries.get(0).secret);
                assertEquals(VaultManifest.EntryType.TOTP, saved.entries.get(0).type);
                assertTrue(next.same(SshPasswordProfile.checkShape(saved)));
                assertEquals(2, saved.files.size());
            }
            f.save(next, () -> { }); assertEquals(1, f.store.writes);
        }
    }

    @Test public void endpointUpdateRetainsCompleteKnownHostsBodyAndFixedIdentities() throws Exception {
        byte[] known = "first.invalid ssh-ed25519 synthetic-native-validated-body\n# retained comment\n"
                .getBytes(StandardCharsets.US_ASCII);
        try (Fixture f = new Fixture(profile(new SshPasswordProfile("first.invalid", 22, "first"), known))) {
            SshPasswordProfile next = new SshPasswordProfile("second.invalid", 2200, "second");
            f.save(next, () -> { });
            try (VaultManifest saved = f.read()) {
                assertTrue(next.same(SshPasswordProfile.checkShape(saved)));
                VaultManifest.FileObject hosts = saved.files.stream()
                        .filter(file -> file.type == VaultManifest.FileType.KNOWN_HOSTS).findFirst().get();
                assertArrayEquals(known, hosts.content);
                assertEquals(SshPasswordProfile.HOSTS_ID, hosts.id);
                assertEquals(SshPasswordProfile.HOSTS_IDENTITY, hosts.identity);
            }
        }
    }

    @Test public void unsupportedSourceAndLateCancellationKeepOriginalCiphertext() throws Exception {
        VaultManifest unknown = new VaultManifest(List.of(new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "foreign", "target:foreign", "Foreign", "", 0, 0, new byte[]{1})), List.of());
        try (Fixture f = new Fixture(unknown)) {
            byte[] original = f.store.bytes.clone();
            code("SSH_PROFILE_UNSUPPORTED", () -> f.save(new SshPasswordProfile("fixture.invalid", 22, "user"), () -> { }));
            assertArrayEquals(original, f.store.bytes); assertEquals(0, f.store.writes);
            assertEquals(0, f.nativeChecks.get());
        }
        try (Fixture f = new Fixture(totp())) {
            byte[] original = f.store.bytes.clone(); AtomicBoolean cancel = new AtomicBoolean();
            f.store.beforeCommit = () -> cancel.set(true);
            code("SSH_CANCELLED", () -> f.save(new SshPasswordProfile("fixture.invalid", 22, "user"), () -> {
                if (cancel.get()) throw new VaultException("SSH_CANCELLED", "Synthetic cancellation");
            }));
            assertArrayEquals(original, f.store.bytes); assertEquals(0, f.store.writes);
        }
    }

    @Test public void wholeValidatorRejectsForeignObjectAndNoncanonicalTupleBeforeNativeBoundary() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        SshPasswordManifestValidator validator = new SshPasswordManifestValidator(() -> 1000,
                (m, p, d) -> checks.incrementAndGet());
        try (VaultManifest good = profile(new SshPasswordProfile("fixture.invalid", 22, "user"), new byte[0])) {
            validator.validate(good); assertEquals(1, checks.get());
            VaultManifest.FileObject config = good.files.get(0);
            VaultManifest.FileObject extra = new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                    "extra", "ssh/extra", "extra", new byte[]{1}, List.of());
            try (VaultManifest foreign = new VaultManifest(List.of(), Arrays.asList(config, good.files.get(1), extra))) {
                code("SSH_PROFILE_UNSUPPORTED", () -> validator.validate(foreign));
            }
            assertEquals(1, checks.get());
        }
    }
}
