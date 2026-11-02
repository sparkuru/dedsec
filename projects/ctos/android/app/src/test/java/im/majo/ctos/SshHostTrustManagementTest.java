package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Host graph and lifecycle checks with injected native framing, not a fake host-key parser. */
public final class SshHostTrustManagementTest {
    private static final byte[] PASSWORD_BODY = bytes("password-group source\r\n");
    private static final byte[] CONFIG_BODY = bytes("config-group source\n# untouched\n");
    private static final byte[] REMAINING = bytes("# untouched\n");
    private static final byte[] MASTER = new byte[32];
    private static final String HOST = SshHostTrustMaterialTest.HOST;
    private static final class Clock implements VaultSession.Clock, SshPasswordManifestValidator.Clock,
            SshHostTrustMaterial.Clock {
        long now = 1000;
        @Override public long now() { return now; }
    }
    private static final class Inspector implements SshHostTrustManagement.Inspector {
        final Clock clock;
        int calls;
        int[][] rows = {{1, 0, 0, 0}};
        byte[] replacement = REMAINING;
        Runnable during;
        IOException failure;
        Inspector(Clock clock) { this.clock = clock; }
        @Override public SshHostTrustMaterial.Result inspect(byte[] body, String host, int port,
                boolean prepare, long deadline) throws IOException {
            calls++;
            return SshHostTrustMaterial.inspect(body, host, port, prepare, deadline, clock,
                    (input, endpoint, selectedPort, mode, limit) -> {
                        if (during != null) during.run();
                        if (failure != null) throw failure;
                        boolean canForget = rows.length > 0;
                        for (int[] row : rows) canForget &= row[3] == 0;
                        return SshHostTrustMaterialTest.wire(input, mode, mode && canForget ? replacement : null, rows);
                    });
        }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static VaultManifest baseline(boolean passwordGroup, boolean configGroup) throws Exception {
        List<VaultManifest.FileObject> files = new ArrayList<>();
        SshSavedPassword password = new SshSavedPassword(new SshPasswordProfile("saved.invalid", 22, "fixture"));
        try {
            if (passwordGroup) {
                files.add(new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, SshPasswordProfile.CONFIG_ID,
                        SshPasswordProfile.CONFIG_PATH, SshPasswordProfile.CONFIG_IDENTITY,
                        new SshPasswordProfile("password.invalid", 22, "fixture").configBytes(), List.of(SshPasswordProfile.HOSTS_PATH)));
                files.add(new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, SshPasswordProfile.HOSTS_ID,
                        SshPasswordProfile.HOSTS_PATH, SshPasswordProfile.HOSTS_IDENTITY, PASSWORD_BODY, List.of()));
            }
            if (configGroup) {
                files.add(new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                        SshConnectionMaterial.id("hosts", SshConnectionMaterial.HOSTS), SshConnectionMaterial.HOSTS,
                        SshConnectionMaterial.id("hosts-owner", SshConnectionMaterial.HOSTS), CONFIG_BODY, List.of()));
                files.add(new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                        SshConnectionMaterial.id("config", SshConnectionMaterial.ROOT), SshConnectionMaterial.ROOT,
                        SshConnectionMaterial.id("config-owner", SshConnectionMaterial.ROOT), bytes("Host example\n HostName example.invalid\n"),
                        List.of(SshConnectionMaterial.HOSTS)));
            }
            VaultManifest result = new VaultManifest(List.of(
                    new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "otp", "otp:fixture", "OTP", "SHA1", 6, 30, new byte[20]),
                    new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, password.entryId, password.target,
                            "Password", "", 0, 0, bytes("test password"))), files, List.of(), List.of(password.account()));
            files.clear(); return result;
        } finally { for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content); }
    }
    private interface Work { void run() throws Exception; }
    private static void code(String expected, Work work) throws Exception {
        try { work.run(); fail("Expected " + expected); }
        catch (VaultException failure) { assertEquals(expected, failure.code); }
    }

    @Test public void queryAlwaysHasBothOrderedExactGroupsAndNoMutationTicket() throws Exception {
        for (boolean password : List.of(false, true)) for (boolean config : List.of(false, true)) {
            Inspector inspector = new Inspector(new Clock());
            try (VaultManifest source = baseline(password, config)) {
                byte[] before = source.encode();
                Map<String, Object> result = SshHostTrustManagement.query(source, HOST, 2200, 2000, () -> { }, inspector);
                assertEquals(3, result.size()); assertEquals(HOST, result.get("host")); assertEquals(2200, result.get("port"));
                List<?> groups = (List<?>)result.get("groups"); assertEquals(2, groups.size());
                for (int index = 0; index < 2; index++) {
                    Map<?, ?> group = (Map<?, ?>)groups.get(index);
                    boolean exists = index == 0 ? password : config;
                    assertEquals(index == 0 ? "password" : "config", group.get("group"));
                    assertEquals(index == 0 ? SshPasswordProfile.HOSTS_PATH : SshConnectionMaterial.HOSTS, group.get("path"));
                    assertEquals(exists, group.get("exists")); assertEquals(exists, group.get("canForget"));
                    assertEquals(exists ? 1 : 0, ((List<?>)group.get("records")).size());
                }
                assertEquals((password ? 1 : 0) + (config ? 1 : 0), inspector.calls);
                assertArrayEquals(before, source.encode());
            }
        }
    }

    @Test public void singleReplacementPreservesAllOtherObjectsRelationsAndSecrets() throws Exception {
        for (SshHostTrustManagement.Group group : SshHostTrustManagement.Group.values()) {
            Inspector inspector = new Inspector(new Clock());
            try (VaultManifest source = baseline(true, true);
                    SshHostTrustManagement.Forget forgetting = SshHostTrustManagement.prepare(source, HOST, 2200,
                            group, 2000, () -> { }, inspector);
                    VaultManifest candidate = forgetting.proposal.applyTo(source)) {
                assertEquals(1, forgetting.proposal.summary().size());
                assertEquals(SshBundleProposal.Action.REPLACE, forgetting.proposal.summary().get(0).action);
                assertEquals(source.files.size(), candidate.files.size());
                assertEquals(source.accounts, candidate.accounts); assertEquals(source.keyBindings, candidate.keyBindings);
                for (VaultManifest.FileObject old : source.files) {
                    VaultManifest.FileObject next = SshConnectionMaterial.file(candidate, old.path);
                    assertEquals(old.id, next.id); assertEquals(old.identity, next.identity); assertEquals(old.references, next.references);
                    if (old.path.equals(group.path)) assertArrayEquals(REMAINING, next.content);
                    else assertEquals(SshBundleProposal.objectBinding(old), SshBundleProposal.objectBinding(next));
                }
                for (int index = 0; index < source.entries.size(); index++)
                    assertArrayEquals(source.entries.get(index).secret, candidate.entries.get(index).secret);
                assertEquals(7, forgetting.metadata("ticket").size());
                assertEquals(HOST, forgetting.metadata("ticket").get("host"));
            }
        }
    }

    @Test public void finalOrdinaryRecordRemovalRetainsEmptyObjectAndConfigReference() throws Exception {
        Inspector inspector = new Inspector(new Clock()); inspector.replacement = new byte[0];
        try (VaultManifest source = baseline(true, true);
                SshHostTrustManagement.Forget forgetting = SshHostTrustManagement.prepare(source, HOST, 2200,
                        SshHostTrustManagement.Group.CONFIG, 2000, () -> { }, inspector);
                VaultManifest result = forgetting.proposal.applyTo(source)) {
            assertEquals(0, SshConnectionMaterial.file(result, SshConnectionMaterial.HOSTS).content.length);
            assertTrue(SshConnectionMaterial.file(result, SshConnectionMaterial.ROOT).references.contains(SshConnectionMaterial.HOSTS));
            SshPasswordProfile.checkShape(result);
        }
    }

    @Test public void emptyBlockedWrongGroupAndInvalidTargetCannotPrepare() throws Exception {
        Inspector inspector = new Inspector(new Clock());
        try (VaultManifest empty = baseline(false, false); VaultManifest source = baseline(true, true)) {
            code("SSH_HOST_TRUST_EMPTY", () -> SshHostTrustManagement.prepare(empty, HOST, 2200,
                    SshHostTrustManagement.Group.PASSWORD, 2000, () -> { }, inspector));
            inspector.rows = new int[0][0];
            code("SSH_HOST_TRUST_EMPTY", () -> SshHostTrustManagement.prepare(source, HOST, 2200,
                    SshHostTrustManagement.Group.CONFIG, 2000, () -> { }, inspector));
            inspector.rows = new int[][] {{1, 0, 0, 0}, {2, 1, 0, 2}};
            code("SSH_HOST_TRUST_BLOCKED", () -> SshHostTrustManagement.prepare(source, HOST, 2200,
                    SshHostTrustManagement.Group.CONFIG, 2000, () -> { }, inspector));
            code("PARAMETERS", () -> SshHostTrustManagement.Group.parse("other"));
            code("PARAMETERS", () -> SshHostTrustManagement.query(source, "bad host", 22, 2000, () -> { }, inspector));
            code("PARAMETERS", () -> SshHostTrustManagement.query(source, HOST, 0, 2000, () -> { }, inspector));
        }
    }

    @Test public void fullObjectIdentityAndSourceBytesBindTheReplacement() throws Exception {
        Inspector inspector = new Inspector(new Clock());
        try (VaultManifest source = baseline(true, true);
                SshHostTrustManagement.Forget forgetting = SshHostTrustManagement.prepare(source, HOST, 2200,
                        SshHostTrustManagement.Group.CONFIG, 2000, () -> { }, inspector)) {
            VaultManifest.FileObject old = SshConnectionMaterial.file(source, SshConnectionMaterial.HOSTS);
            VaultManifest.FileObject changedFile = new VaultManifest.FileObject(old.type, old.id, old.path,
                    old.identity, bytes("changed bytes"), old.references);
            try (VaultManifest changed = replace(source, old, changedFile)) {
                code("VAULT_CHANGED", () -> forgetting.proposal.applyTo(changed));
            } finally { VaultCodec.wipe(changedFile.content); }
            VaultManifest.FileObject wrongFile = new VaultManifest.FileObject(old.type, "wrong-owner", old.path,
                    old.identity, old.content, old.references);
            try (VaultManifest wrong = replace(source, old, wrongFile)) {
                code("SSH_HOST_TRUST_FAILED", () -> SshHostTrustManagement.Group.CONFIG.select(wrong));
            } finally { VaultCodec.wipe(wrongFile.content); }
            forgetting.close(); code("VAULT_EXPIRED", () -> forgetting.proposal.applyTo(source));
        }
    }

    private static VaultManifest replace(VaultManifest source, VaultManifest.FileObject old,
            VaultManifest.FileObject replacement) throws VaultException {
        try (SshBundleProposal.Change change = SshBundleProposal.Change.replace(old, replacement);
                SshBundleProposal proposal = new SshBundleProposal(List.of(change))) {
            return proposal.applyTo(source);
        }
    }

    @Test public void queryAndPrepareRecheckOriginalCancellationAfterNative() throws Exception {
        Inspector inspector = new Inspector(new Clock()); AtomicBoolean cancelled = new AtomicBoolean();
        SshHostTrustManagement.Guard guard = () -> {
            if (cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic original guard");
        };
        inspector.during = () -> cancelled.set(true);
        try (VaultManifest source = baseline(true, true)) {
            code("SSH_CANCELLED", () -> SshHostTrustManagement.query(source, HOST, 2200, 2000, guard, inspector));
            assertEquals(1, inspector.calls); cancelled.set(false);
            code("SSH_CANCELLED", () -> SshHostTrustManagement.prepare(source, HOST, 2200,
                    SshHostTrustManagement.Group.CONFIG, 2000, guard, inspector));
        }
    }

    @Test public void exactTicketKindBindingCancellationAndCloseRejectCrossUse() throws Exception {
        Inspector inspector = new Inspector(new Clock());
        try (VaultManifest source = baseline(true, true);
                SshHostTrustManagement.Forget forgetting = SshHostTrustManagement.prepare(source, HOST, 2200,
                        SshHostTrustManagement.Group.CONFIG, 2000, () -> { }, inspector)) {
            SshProfilesBridge.Ticket ticket = new SshProfilesBridge.Ticket(new Object(), forgetting);
            assertTrue(ticket.matches(ticket.id, ticket.binding, SshProfilesBridge.TicketKind.HOST_TRUST_FORGET));
            for (SshProfilesBridge.TicketKind kind : List.of(SshProfilesBridge.TicketKind.IMPORT, SshProfilesBridge.TicketKind.KEY_DELETE)) {
                assertFalse(ticket.matches(ticket.id, kind)); assertFalse(ticket.matches(ticket.id, ticket.binding, kind));
            }
            assertFalse(ticket.matches("wrong-ticket", ticket.binding, SshProfilesBridge.TicketKind.HOST_TRUST_FORGET));
            assertFalse(ticket.matches(ticket.id, "wrong-binding", SshProfilesBridge.TicketKind.HOST_TRUST_FORGET));
            ticket.cancelled = true; assertFalse(ticket.matches(ticket.id, SshProfilesBridge.TicketKind.HOST_TRUST_FORGET));
            ticket.close(); code("VAULT_EXPIRED", () -> ticket.proposal().applyTo(source));
        }
    }

    @Test public void establishedServiceRemainsBusyAfterConnectAttemptAndActivityStateClear() throws Exception {
        AtomicBoolean connecting = new AtomicBoolean(), service = new AtomicBoolean();
        SshProfilesBridge.requireTrustSessionIdle(connecting::get, service::get);
        connecting.set(true);
        code("BUSY", () -> SshProfilesBridge.requireTrustSessionIdle(connecting::get, service::get));
        // SUCCESS hands ownership to the service and retires the connection attempt.
        service.set(true); connecting.set(false);
        code("BUSY", () -> SshProfilesBridge.requireTrustSessionIdle(connecting::get, service::get));
        AtomicBoolean recreatedActivityConnecting = new AtomicBoolean();
        code("BUSY", () -> SshProfilesBridge.requireTrustSessionIdle(recreatedActivityConnecting::get, service::get));
        service.set(false);
        SshProfilesBridge.requireTrustSessionIdle(recreatedActivityConnecting::get, service::get);
    }

    private static final class Store implements AppSecrets.Store {
        byte[] bytes; int writes;
        Runnable before, after;
        VaultException postWriteFailure;
        @Override public byte[] read() { return bytes.clone(); }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard) throws VaultException {
            if (before != null) before.run(); guard.beforeCommit(); bytes = next.clone(); writes++;
            if (after != null) after.run();
            if (postWriteFailure != null) throw postWriteFailure;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Clock clock = new Clock(); final Store store = new Store(); final AppSecrets vault = new AppSecrets(store, clock);
        final Inspector inspector = new Inspector(clock);
        Fixture() throws Exception {
            try (VaultManifest source = baseline(true, true)) {
                byte[] plain = source.encode();
                try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], MASTER, plain).encode(); }
                finally { VaultCodec.wipe(plain); }
            }
            vault.attachSshValidator(new SshPasswordManifestValidator(clock, (manifest, profile, deadline) -> {
                assertFalse(Thread.holdsLock(vault)); SshPasswordProfile.checkShape(manifest);
            }));
            vault.session.foreground(true); vault.session.unlock(MASTER);
        }
        SshHostTrustManagement.Forget prepare(AppSecrets.BundleContext context) throws Exception {
            try (SshConnectionMaterial.Snapshot source = vault.sshLibrarySnapshot(context)) {
                SshHostTrustManagement.Forget result = SshHostTrustManagement.prepare(source.manifest, HOST, 2200,
                        SshHostTrustManagement.Group.CONFIG, 60000, () -> { }, inspector);
                try (VaultManifest candidate = result.proposal.applyTo(source.manifest)) { vault.validateSshCandidate(context, candidate); }
                return result;
            }
        }
        SshVaultAdapter adapter(SshHostTrustManagement.Forget forgetting) {
            return new SshVaultAdapter(vault, (connection, target) -> { throw VaultException.invalid(); },
                    proposal -> { throw VaultException.invalid(); }, actual -> assertSame(forgetting.proposal, actual));
        }
        @Override public void close() { vault.session.lock(); VaultCodec.wipe(store.bytes); }
    }

    @Test public void encryptedBundleCommitsExactlyOnceAndKeepsUnrelatedTrust() throws Exception {
        try (Fixture f = new Fixture()) {
            AppSecrets.BundleContext context = f.vault.newBundleContext(60000, () -> { });
            try (SshHostTrustManagement.Forget forgetting = f.prepare(context)) {
                SshVaultAdapter adapter = f.adapter(forgetting);
                VaultOperation operation = adapter.bundleRequest(forgetting.proposal, context);
                try {
                    adapter.commitBundle(operation, forgetting.proposal, () -> { }); assertEquals(1, f.store.writes);
                    byte[] plain = VaultEnvelope.parse(f.store.bytes).decrypt(MASTER);
                    try (VaultManifest result = VaultManifest.decode(plain)) {
                        assertArrayEquals(REMAINING, SshConnectionMaterial.file(result, SshConnectionMaterial.HOSTS).content);
                        assertArrayEquals(PASSWORD_BODY, SshConnectionMaterial.file(result, SshPasswordProfile.HOSTS_PATH).content);
                        assertEquals(2, result.entries.size()); assertEquals(1, result.accounts.size());
                    } finally { VaultCodec.wipe(plain); }
                    try { adapter.commitBundle(operation, forgetting.proposal, () -> { }); fail("Replayed forget"); }
                    catch (VaultException expected) { }
                    assertEquals(1, f.store.writes);
                } finally { adapter.cancel(operation); }
            } finally { f.vault.cancelBundleContext(context); }
        }
    }

    @Test public void finalCancellationExpiryAndSourceChangePreventAnyWrite() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) try (Fixture f = new Fixture()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            AppSecrets.EffectGuard guard = () -> { if (cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic guard"); };
            AppSecrets.BundleContext context = f.vault.newBundleContext(60000, guard);
            try (SshHostTrustManagement.Forget forgetting = f.prepare(context)) {
                SshVaultAdapter adapter = f.adapter(forgetting);
                VaultOperation operation = adapter.bundleRequest(forgetting.proposal, context);
                byte[] before = f.store.bytes.clone();
                try {
                    if (scenario == 0) f.store.before = () -> cancelled.set(true);
                    if (scenario == 1) f.clock.now = 60000;
                    if (scenario == 2) f.store.bytes[f.store.bytes.length - 1] ^= 1;
                    if (scenario == 3) f.vault.session.lock();
                    if (scenario == 4) f.vault.session.foreground(false);
                    try { adapter.commitBundle(operation, forgetting.proposal, guard); fail("Expected invalidated operation"); }
                    catch (VaultException expected) { }
                    assertEquals(0, f.store.writes);
                    if (scenario != 2) assertArrayEquals(before, f.store.bytes);
                } finally { adapter.cancel(operation); }
            } finally { f.vault.cancelBundleContext(context); }
        }
    }

    @Test public void postWriteCancellationReportsUncertainAndLocksVault() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            AppSecrets.EffectGuard guard = () -> { if (cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic guard"); };
            AppSecrets.BundleContext context = f.vault.newBundleContext(60000, guard);
            try (SshHostTrustManagement.Forget forgetting = f.prepare(context)) {
                SshVaultAdapter adapter = f.adapter(forgetting);
                VaultOperation operation = adapter.bundleRequest(forgetting.proposal, context);
                try {
                    f.store.after = () -> cancelled.set(true);
                    adapter.commitBundle(operation, forgetting.proposal, guard);
                    assertEquals("VAULT_COMMIT_UNCERTAIN", SshProfilesBridge.commitReceipt(f.vault, true, null, guard));
                    assertEquals(1, f.store.writes); assertFalse(f.vault.session.unlocked());
                } finally { adapter.cancel(operation); }
            } finally { f.vault.cancelBundleContext(context); }
        }
    }

    @Test public void lateServiceActivationIsRejectedByTheOriginalFinalWriteGuard() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicBoolean service = new AtomicBoolean();
            AppSecrets.EffectGuard guard = () -> SshProfilesBridge.requireTrustSessionIdle(() -> false, service::get);
            AppSecrets.BundleContext context = f.vault.newBundleContext(60000, guard);
            try (SshHostTrustManagement.Forget forgetting = f.prepare(context)) {
                SshVaultAdapter adapter = f.adapter(forgetting);
                VaultOperation operation = adapter.bundleRequest(forgetting.proposal, context);
                byte[] before = f.store.bytes.clone();
                try {
                    f.store.before = () -> service.set(true);
                    code("BUSY", () -> adapter.commitBundle(operation, forgetting.proposal, guard));
                    assertEquals(0, f.store.writes); assertArrayEquals(before, f.store.bytes);
                } finally { adapter.cancel(operation); }
            } finally { f.vault.cancelBundleContext(context); }
        }
    }

    @Test public void persistedButFailedTransportLocksAndOnlyAttemptsOneUncertainReply() throws Exception {
        for (boolean linkage : List.of(false, true)) try (Fixture f = new Fixture()) {
            AppSecrets.BundleContext context = f.vault.newBundleContext(60000, () -> { });
            try (SshHostTrustManagement.Forget forgetting = f.prepare(context)) {
                SshVaultAdapter adapter = f.adapter(forgetting);
                VaultOperation operation = adapter.bundleRequest(forgetting.proposal, context);
                try {
                    adapter.commitBundle(operation, forgetting.proposal, () -> { });
                    assertEquals(1, f.store.writes);
                    List<String> fallbacks = new ArrayList<>();
                    SshProfilesBridge.deliverTrustCommit(f.vault, true, null, () -> {
                        if (linkage) throw new UnsatisfiedLinkError("Synthetic transport linkage failure");
                        throw new IllegalStateException("Synthetic response failure");
                    }, reason -> {
                        fallbacks.add(reason);
                        throw new IllegalStateException("Synthetic partly delivered reply");
                    });
                    assertEquals(List.of("VAULT_COMMIT_UNCERTAIN"), fallbacks);
                    assertFalse(f.vault.session.unlocked()); assertEquals(1, f.store.writes);
                } finally { adapter.cancel(operation); }
            } finally { f.vault.cancelBundleContext(context); }
        }
    }

    @Test public void uncertainStoreOutcomeCannotBeDowngradedWhenItsErrorReplyFails() throws Exception {
        try (Fixture f = new Fixture()) {
            AppSecrets.BundleContext context = f.vault.newBundleContext(60000, () -> { });
            try (SshHostTrustManagement.Forget forgetting = f.prepare(context)) {
                SshVaultAdapter adapter = f.adapter(forgetting);
                VaultOperation operation = adapter.bundleRequest(forgetting.proposal, context);
                try {
                    f.store.postWriteFailure = new VaultException("VAULT_COMMIT_UNCERTAIN",
                            "Synthetic post-write readback failure");
                    String reason;
                    try {
                        adapter.commitBundle(operation, forgetting.proposal, () -> { });
                        fail("Expected uncertain store outcome");
                        return;
                    } catch (VaultException failure) {
                        reason = SshHostTrustManagement.errorCode(failure);
                    }
                    assertEquals("VAULT_COMMIT_UNCERTAIN", reason);
                    assertEquals(1, f.store.writes);
                    List<String> fallbacks = new ArrayList<>();
                    SshProfilesBridge.deliverTrustCommit(f.vault, false, reason, () -> {
                        throw new IllegalStateException("Synthetic undelivered error reply");
                    }, fallbacks::add);
                    assertEquals(List.of("VAULT_COMMIT_UNCERTAIN"), fallbacks);
                    assertFalse(f.vault.session.unlocked());
                    assertEquals(1, f.store.writes);
                } finally { adapter.cancel(operation); }
            } finally { f.vault.cancelBundleContext(context); }
        }
    }
}
