package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Tests the Java receipt contract; native ownership is an explicit test fixture. */
public final class SshTrustReceiptsTest {
    @Test public void freshChildRequiresAnIssuedParentAdmission() throws Exception {
        try (StrictFixture fixture = new StrictFixture()) {
            try (StrictChild unknown = fixture.child(null)) {
                try { fixture.prepare(unknown, fixture.root); fail("missing parent admission"); }
                catch (VaultException expected) { assertEquals("SSH_SESSION_PHASE", expected.code); }
                assertEquals(0, fixture.parent.binding.reserves);
            }
        }
    }

    @Test public void freshChildRequiresTheExactParentAdmissionAndProviders() throws Exception {
        try (StrictFixture fixture = new StrictFixture()) {
            SshNativeSession.JumpAdmission admission = fixture.parent.session.issueJumpAdmission(
                    fixture.rootRole, fixture.childRole, fixture.fixture.shared, fixture.fixture.view);
            SshPasswordConnector.TrustCommitState foreign = new SshPasswordConnector.TrustCommitState();
            foreign.bindIssued(fixture.rootRole);
            try (TrustHop foreignProvider = new TrustHop(fixture.vault, fixture.fixture.clock,
                    fixture.rootRole.role, foreign)) {
                try { foreign.forNextHop(fixture.childRole, fixture.fixture.view, admission);
                    fail("a different parent provider cannot consume the admission"); }
                catch (VaultException expected) { assertEquals("SSH_SESSION_PHASE", expected.code); }
            }

            fixture.root.newPersisted();
            try { fixture.root.forNextHop(fixture.childRole, fixture.fixture.view, admission);
                fail("an unconfirmed parent write blocks the next hop"); }
            catch (VaultException expected) { assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); }
            fixture.root.newProofPublished(); // This Java fixture simulates acknowledgement, not native KEX.
            SshPasswordConnector.TrustCommitState state = fixture.root.forNextHop(
                    fixture.childRole, fixture.fixture.view, admission);
            try { admission.consume(fixture.rootRole, fixture.childRole, fixture.fixture.view);
                fail("the admission is one-shot"); }
            catch (VaultException expected) { assertEquals("SSH_SESSION_PHASE", expected.code); }

            try (StrictChild child = fixture.child(state);
                    SshManagedKnownHostsProvider.ChildTrustSnapshot wrongSource = fixture.snapshot();
                    SshManagedKnownHostsProvider wrongProvider =
                            SshManagedKnownHostsProvider.bindForNativeHandoff(fixture.adapter, wrongSource.receipt())) {
                try { fixture.prepare(child.callback, wrongSource, state);
                    fail("another source cannot borrow the child's provider"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                assertFalse(child.callback.ownsTrustProvider(wrongProvider));
                assertEquals(0, fixture.parent.binding.reserves);
                SshNativeSession.JumpChildOwner owner = fixture.prepare(child, state);
                try {
                    fixture.parent.session.beginJump(owner);
                    SshNativeSession connected = owner.open();
                    assertEquals(1, fixture.parent.binding.reserves);
                    assertEquals(1, fixture.parent.binding.opens);
                    try { connected.beginUserauthService(); fail("the effect guard stays closed"); }
                    catch (VaultException expected) { assertEquals("SSH_JUMP_EFFECT_GUARD", expected.code); }
                } finally { owner.close(); }
                try { state.claimChildAssembly(admission, fixture.parent.callback, child.callback,
                        child.source, ".ssh/known_hosts"); fail("a prepared child cannot replay its admission"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            }
        }
    }

    @Test public void foreignRootCannotClaimTheParentProviderOrConsumeItsAdmission() throws Exception {
        try (StrictFixture fixture = new StrictFixture()) {
            SshNativeSession.JumpAdmission admission = fixture.parent.session.issueJumpAdmission(
                    fixture.rootRole, fixture.childRole, fixture.fixture.shared, fixture.fixture.view);
            SshPasswordConnector.TrustCommitState foreign = new SshPasswordConnector.TrustCommitState();
            foreign.bindIssued(fixture.rootRole);
            byte[] before = bytes("trusted fixture\n"), after = bytes("trusted fixture and next key\n");
            try {
                fixture.rootHop.writeNew(before, after);
                assertTrue(fixture.root.publicationUncertain());
                try { foreign.bindProvider(fixture.rootHop.provider, fixture.rootRole.role);
                    fail("a foreign ledger cannot claim the parent's actual provider"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                try { foreign.forNextHop(fixture.childRole, fixture.fixture.view, admission);
                    fail("a foreign ledger cannot consume the parent's admission"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                try { fixture.root.forNextHop(fixture.childRole, fixture.fixture.view, admission);
                    fail("the actual parent's unconfirmed write still blocks continuation"); }
                catch (VaultException expected) { assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); }
                fixture.rootHop.provider.newProofPublished(); // Synthetic acknowledgement in this JVM fixture.
                SshPasswordConnector.TrustCommitState childState = fixture.root.forNextHop(
                        fixture.childRole, fixture.fixture.view, admission);
                try (StrictChild child = fixture.child(childState)) {
                    SshNativeSession.JumpChildOwner owner = fixture.prepare(child, childState);
                    owner.close();
                }
            } finally { VaultCodec.wipe(before); VaultCodec.wipe(after); }
        }
    }

    @Test public void childStateRejectsAProviderWithANoopObserverWithoutLosingItsClaim() throws Exception {
        try (StrictFixture fixture = new StrictFixture()) {
            SshNativeSession.JumpAdmission admission = fixture.parent.session.issueJumpAdmission(
                    fixture.rootRole, fixture.childRole, fixture.fixture.shared, fixture.fixture.view);
            SshPasswordConnector.TrustCommitState childState = fixture.root.forNextHop(
                    fixture.childRole, fixture.fixture.view, admission);
            try (SshManagedKnownHostsProvider.ChildTrustSnapshot source = fixture.snapshot();
                    SshManagedKnownHostsProvider foreign =
                            SshManagedKnownHostsProvider.bindForNativeHandoff(fixture.adapter, source.receipt())) {
                try { childState.bindProvider(foreign, fixture.childRole.role);
                    fail("the child provider must send commits to the child's exact ledger"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                try { childState.providerFor(fixture.childRole);
                    fail("a rejected provider must not occupy the child's claim"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                assertEquals(0, fixture.parent.binding.reserves);
            }
            try (StrictChild child = fixture.child(childState)) {
                SshNativeSession.JumpChildOwner owner = fixture.prepare(child, childState);
                owner.close();
            }
        }
    }

    @Test public void freshChildRejectsCancelledOrExpiredParentBeforeReservingNativeOwners() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            try (StrictFixture fixture = new StrictFixture()) {
                SshNativeSession.JumpAdmission admission = fixture.parent.session.issueJumpAdmission(
                        fixture.rootRole, fixture.childRole, fixture.fixture.shared, fixture.fixture.view);
                SshPasswordConnector.TrustCommitState childState = fixture.root.forNextHop(
                        fixture.childRole, fixture.fixture.view, admission);
                try (StrictChild child = fixture.child(childState)) {
                    if (mode == 0) fixture.root.cancellation.cancel();
                    else if (mode == 1) fixture.parent.session.cancel();
                    else fixture.fixture.clock.value = fixture.rootRole.role.deadline;
                    try { fixture.prepare(child, childState); fail("the parent or chain is no longer live"); }
                    catch (VaultException expected) {
                        assertEquals(mode == 0 ? "SSH_CANCELLED"
                                : mode == 1 ? "SSH_SESSION_CANCELLED" : "SSH_ROLE_EXPIRED", expected.code);
                    }
                    assertEquals(0, fixture.parent.binding.reserves);
                    fixture.parent.session.close(); // No child owner was retained.
                }
            }
        }
    }

    @Test public void confirmedParentTrustWriteStillBindsTheNextChildToTheSameVault() throws Exception {
        try (StrictFixture fixture = new StrictFixture()) {
            byte[] before = bytes("trusted fixture\n"), after = bytes("trusted fixture and next key\n");
            try {
                long epoch = fixture.vault.session.epoch();
                fixture.rootHop.writeNew(before, after);
                assertTrue(fixture.root.publicationUncertain());
                fixture.rootHop.provider.newProofPublished(); // Synthetic native acknowledgement in this JVM fixture.
                assertEquals(epoch + 1, fixture.vault.session.epoch());
                SshNativeSession.JumpAdmission admission = fixture.parent.session.issueJumpAdmission(
                        fixture.rootRole, fixture.childRole, fixture.fixture.shared, fixture.fixture.view);
                SshPasswordConnector.TrustCommitState state = fixture.root.forNextHop(
                        fixture.childRole, fixture.fixture.view, admission);
                try (StrictChild child = fixture.child(state)) {
                    SshNativeSession.JumpChildOwner owner = fixture.prepare(child, state);
                    owner.close();
                    fixture.parent.session.close();
                }
            } finally { VaultCodec.wipe(before); VaultCodec.wipe(after); }
        }
    }

    @Test public void aForeignVaultCannotClaimTheChildReceipt() throws Exception {
        try (StrictFixture fixture = new StrictFixture()) {
            SshNativeSession.JumpAdmission admission = fixture.parent.session.issueJumpAdmission(
                    fixture.rootRole, fixture.childRole, fixture.fixture.shared, fixture.fixture.view);
            SshPasswordConnector.TrustCommitState state = fixture.root.forNextHop(
                    fixture.childRole, fixture.fixture.view, admission);
            MemoryStore foreignStore = new MemoryStore();
            char[] password = "foreign vault fixture".toCharArray();
            foreignStore.data = encryptedVault(password, bytes("trusted fixture\n"));
            AppSecrets foreign = new AppSecrets(foreignStore, fixture.fixture.clock);
            foreign.attachSshValidator(manifest -> { });
            foreign.session.foreground(true); foreign.unlock(password, () -> { });
            foreign.session.attachRevocation(foreign.session.epoch(), fixture.fixture.view);
            SshVaultAdapter foreignAdapter = new SshVaultAdapter(foreign,
                    (connection, target) -> { }, proposal -> { });
            try (SshManagedKnownHostsProvider.ChildTrustSnapshot wrong =
                    SshManagedKnownHostsProvider.snapshotForChild(foreignAdapter, fixture.childRole.role,
                            fixture.fixture.clock, fixture.fixture.view, ".ssh/config", ".ssh/known_hosts",
                            "trust", "trust-source")) {
                try { SshPasswordConnector.bindOwnedTrustReceipt(foreignAdapter, wrong.receipt(), state);
                    fail("a different AppSecrets owner cannot claim this state"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                try (StrictChild child = fixture.child(state)) {
                    SshNativeSession.JumpChildOwner owner = fixture.prepare(child, state);
                    owner.close();
                }
            } finally { foreign.lock(); Arrays.fill(password, '\0'); }
        }
    }

    @Test public void cancellationAfterViewIssuanceReturnsTheUnadoptedBinding() throws Exception {
        try (StrictFixture fixture = new StrictFixture()) {
            SshNativeSession.JumpAdmission admission = fixture.parent.session.issueJumpAdmission(
                    fixture.rootRole, fixture.childRole, fixture.fixture.shared, fixture.fixture.view);
            SshPasswordConnector.TrustCommitState state = fixture.root.forNextHop(
                    fixture.childRole, fixture.fixture.view, admission);
            try (StrictChild child = fixture.child(state)) {
                fixture.fixture.view.issueHook = fixture.root.cancellation::cancel;
                try { fixture.prepare(child, state); fail("late cancellation prevents owner publication"); }
                catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
                assertEquals(0, fixture.parent.binding.reserves);
                fixture.parent.session.close();
                fixture.fixture.shared.seal();
                fixture.fixture.shared.close(); // No unadopted child binding remains pinned.
            }
        }
    }

    @Test public void eachReceiptIsOneShotAndBelongsToItsCancellationAndScope() {
        SshSessionOwner.Cancellation first = new SshSessionOwner.Cancellation();
        SshSessionOwner.Cancellation second = new SshSessionOwner.Cancellation();
        Object scope = new Object(), other = new Object();
        SshSessionOwner.Cancellation.TrustReceipt one = first.trustReceipt(scope);
        SshSessionOwner.Cancellation.TrustReceipt two = first.trustReceipt(other);
        assertFalse(second.confirmTrust(one, scope));
        assertFalse(first.confirmTrust(one, other));
        assertTrue(first.confirmTrust(one, scope));
        assertFalse(first.confirmTrust(one, scope));
        assertTrue(first.confirmTrust(two, other));
        Object pendingScope = new Object();
        SshSessionOwner.Cancellation.TrustReceipt pending = first.trustReceipt(pendingScope);
        first.cancel();
        assertFalse(first.confirmTrust(pending, pendingScope));
    }

    @Test public void eachHopHasAnIndependentReceiptAndUncertaintyIsAggregated() throws Exception {
        Fixture fixture = new Fixture();
        try {
            SshNativeRoleBootstrap.Issued first = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
            SshNativeRoleBootstrap.Issued second = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
            SshPasswordConnector.TrustCommitState root = new SshPasswordConnector.TrustCommitState();
            root.bindIssued(first);
            SshNativeSession.JumpAdmission admission = fixture.admission(first, second);
            root.requireRole(first.role);
            root.newPersisted();
            assertTrue(root.written());
            assertTrue(root.publicationUncertain());
            try { root.forNextHopForFixture(second, fixture.view, admission); fail("a pending first-hop write blocks continuation"); }
            catch (VaultException expected) { assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); }
            try { root.proofPublished(); fail("a changed-host acknowledgement cannot confirm a new-host write"); }
            catch (VaultException expected) { assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); }
            root.newProofPublished();
            assertFalse(root.publicationUncertain());
            try { root.newProofPublished(); fail("the first receipt is one-shot"); }
            catch (VaultException expected) { assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); }

            SshPasswordConnector.TrustCommitState middle = root.forNextHopForFixture(second, fixture.view, admission);
            middle.requireRole(second.role);
            try { middle.requireRole(first.role); fail("a receipt cannot bind another hop"); }
            catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            middle.persisted();
            middle.persisted(); // An uncertain store return can notify the same pending write twice.
            assertTrue(root.publicationUncertain());
            middle.proofPublished();
            assertFalse(root.publicationUncertain());
            root.cancellation.cancel();
            assertFalse(root.publicationUncertain());
            assertFalse(middle.publicationUncertain());
        } finally { fixture.close(); }
    }

    @Test public void hopCannotBeReusedOrSubstitutedByAnotherRoleOrConnection() throws Exception {
        Fixture fixture = new Fixture();
        Fixture foreign = new Fixture();
        try {
            SshNativeRoleBootstrap.Issued first = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
            SshNativeRoleBootstrap.Issued second = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
            SshNativeRoleBootstrap.Issued foreignRole = foreign.issue(3, 1, 1, "connection", "chain:one", 12, foreign.view);
            SshNativeRoleBootstrap.Issued copiedRoot = foreign.issue(1, 2, 0, "connection", "chain:one", 10, foreign.view);
            SshPasswordConnector.TrustCommitState root = new SshPasswordConnector.TrustCommitState();
            root.bindIssued(first);
            SshNativeSession.JumpAdmission admission = fixture.admission(first, second);
            try { root.requireRole(copiedRoot.role); fail("even a same-hop copy cannot own the receipt"); }
            catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            try { root.forNextHopForFixture(foreignRole, fixture.view, admission); fail("another registry cannot join this connection"); }
            catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            try { root.forNextHopForFixture(second, foreign.view, admission); fail("the exact original view is required"); }
            catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            SshPasswordConnector.TrustCommitState child = root.forNextHopForFixture(second, fixture.view, admission);
            child.requireRole(second.role);
            try { root.forNextHopForFixture(second, fixture.view, admission); fail("an existing hop cannot issue another receipt"); }
            catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            try { admission.consume(first, second, fixture.view); fail("the admission itself is one-shot"); }
            catch (VaultException expected) { assertEquals("SSH_SESSION_PHASE", expected.code); }
        } finally { fixture.close(); foreign.close(); }
    }

    @Test public void wrongChainAndConnectionCannotBorrowTheReceiptLedger() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            Fixture fixture = new Fixture();
            Fixture other = new Fixture();
            try {
                SshNativeRoleBootstrap.Issued first = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
                String connection = mode == 0 ? "other_connection" : "connection";
                String chain = mode == 1 ? "chain:other" : "chain:one";
                int binding = mode == 2 ? 8 : 7;
                long deadline = mode == 3 ? 30_999 : 31_000;
                int uid = mode == 4 ? 43 : 42;
                int pid = mode == 5 ? 1002 : 1001;
                SshNativeRoleBootstrap.Issued second = fixture.issue(2, 1, 1, connection, chain, 11,
                        binding, uid, pid, deadline, fixture.view);
                SshPasswordConnector.TrustCommitState root = new SshPasswordConnector.TrustCommitState();
                root.bindIssued(first);
                SshNativeRoleBootstrap.Issued otherFirst = other.issue(11, 2, 0, "other", "chain:other", 20, other.view);
                SshNativeRoleBootstrap.Issued otherNext = other.issue(12, 1, 1, "other", "chain:other", 21, other.view);
                SshNativeSession.JumpAdmission unrelated = other.admission(otherFirst, otherNext);
                try { root.forNextHopForFixture(second, fixture.view, unrelated); fail("mismatched chain metadata must fail"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                assertFalse(root.written());
                SshPasswordConnector.TrustCommitState otherRoot = new SshPasswordConnector.TrustCommitState();
                otherRoot.bindIssued(otherFirst);
                assertNotNull(otherRoot.forNextHopForFixture(otherNext, other.view, unrelated));
            } finally { fixture.close(); other.close(); }
        }
    }

    @Test public void repeatedOrDifferentWritesCannotReuseAConfirmedReceipt() throws Exception {
        SshPasswordConnector.TrustCommitState first = new SshPasswordConnector.TrustCommitState();
        first.newPersisted(); first.newProofPublished();
        first.newPersisted();
        assertTrue("a second write needs its own receipt", first.publicationUncertain());
        try { first.newProofPublished(); fail("an invalid receipt cannot be confirmed"); }
        catch (VaultException expected) { assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); }

        SshPasswordConnector.TrustCommitState second = new SshPasswordConnector.TrustCommitState();
        second.newPersisted();
        second.persisted();
        assertTrue("another trust type needs a separate receipt", second.publicationUncertain());
        try { second.newProofPublished(); fail("a mixed receipt cannot be confirmed"); }
        catch (VaultException expected) { assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); }
    }

    @Test public void finalClientCannotIssueAJumpReceipt() throws Exception {
        Fixture fixture = new Fixture();
        Fixture other = new Fixture();
        try {
            SshNativeRoleBootstrap.Issued first = fixture.issue(1, 1, 0, "connection", "chain:one", 10, fixture.view);
            SshNativeRoleBootstrap.Issued second = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
            SshPasswordConnector.TrustCommitState root = new SshPasswordConnector.TrustCommitState();
            root.bindIssued(first);
            SshNativeRoleBootstrap.Issued otherFirst = other.issue(11, 2, 0, "other", "chain:other", 20, other.view);
            SshNativeRoleBootstrap.Issued otherNext = other.issue(12, 1, 1, "other", "chain:other", 21, other.view);
            SshNativeSession.JumpAdmission unrelated = other.admission(otherFirst, otherNext);
            try { root.forNextHopForFixture(second, fixture.view, unrelated); fail("a client role cannot become a jump"); }
            catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            SshPasswordConnector.TrustCommitState otherRoot = new SshPasswordConnector.TrustCommitState();
            otherRoot.bindIssued(otherFirst);
            assertNotNull(otherRoot.forNextHopForFixture(otherNext, other.view, unrelated));
        } finally { fixture.close(); other.close(); }
    }

    @Test public void admissionFromAnotherSessionCannotBeConsumedOrSpoilTheActualReceipt()
            throws Exception {
        Fixture first = new Fixture(), second = new Fixture();
        try {
            SshNativeRoleBootstrap.Issued parentA = first.issue(1, 2, 0, "connection", "chain:one", 10, first.view);
            SshNativeRoleBootstrap.Issued nextA = first.issue(2, 1, 1, "connection", "chain:one", 11, first.view);
            SshNativeRoleBootstrap.Issued parentB = second.issue(3, 2, 0, "connection", "chain:one", 12, second.view);
            SshNativeRoleBootstrap.Issued nextB = second.issue(4, 1, 1, "connection", "chain:one", 13, second.view);
            SshPasswordConnector.TrustCommitState stateA = new SshPasswordConnector.TrustCommitState();
            SshPasswordConnector.TrustCommitState stateB = new SshPasswordConnector.TrustCommitState();
            stateA.bindIssued(parentA); stateB.bindIssued(parentB);
            SshNativeSession.JumpAdmission admissionB = second.admission(parentB, nextB);
            try { stateA.forNextHopForFixture(nextA, first.view, null); fail("admission is mandatory"); }
            catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            try { stateA.forNextHopForFixture(nextA, first.view, admissionB); fail("another session cannot admit this hop"); }
            catch (VaultException expected) { assertEquals("SSH_SESSION_PHASE", expected.code); }
            assertNotNull(stateB.forNextHopForFixture(nextB, second.view, admissionB));
            assertNotNull(stateA.forNextHopForFixture(nextA, first.view, first.admission(parentA, nextA)));
        } finally { first.close(); second.close(); }
    }

    @Test public void unstartedAndPartialParentAuthenticationCannotIssueAdmission() throws Exception {
        for (int result : new int[] {0, 4}) {
            Fixture fixture = new Fixture();
            try {
                SshNativeRoleBootstrap.Issued parent = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
                SshNativeRoleBootstrap.Issued next = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
                TestSession session = fixture.session(parent, result);
                try { session.session.issueJumpAdmission(parent, next, fixture.shared, fixture.view);
                    fail("unstarted or partial authentication is not a jump admission"); }
                catch (VaultException expected) { assertEquals("SSH_SESSION_PHASE", expected.code); }
            } finally { fixture.close(); }
        }
    }

    @Test public void cancelledOrExpiredParentCannotConsumeItsAdmission() throws Exception {
        for (boolean expire : new boolean[] {false, true}) {
            Fixture fixture = new Fixture();
            try {
                SshNativeRoleBootstrap.Issued parent = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
                SshNativeRoleBootstrap.Issued next = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
                SshPasswordConnector.TrustCommitState state = new SshPasswordConnector.TrustCommitState();
                state.bindIssued(parent);
                SshNativeSession.JumpAdmission admission = fixture.admission(parent, next);
                if (expire) fixture.clock.value = parent.role.deadline;
                else fixture.sessions.get(parent).session.cancel();
                try { state.forNextHopForFixture(next, fixture.view, admission); fail("stale session must fail closed"); }
                catch (VaultException expected) { assertNotNull(expected.code); }
                assertFalse(state.written());
            } finally { fixture.close(); }
        }
    }

    @Test public void concurrentCancellationAndConfirmationHaveOneConsistentOutcome() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            Fixture fixture = new Fixture();
            try {
                SshPasswordConnector.TrustCommitState state = new SshPasswordConnector.TrustCommitState();
                state.bindIssued(fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view));
                state.newPersisted();
                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<Throwable> unexpected = new AtomicReference<>();
                Thread cancel = new Thread(() -> {
                    try { start.await(); state.cancellation.cancel(); }
                    catch (Throwable failure) { unexpected.set(failure); }
                });
                cancel.start(); start.countDown();
                boolean confirmed;
                try { state.newProofPublished(); confirmed = true; }
                catch (VaultException expected) {
                    assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); confirmed = false;
                }
                cancel.join();
                assertNull(unexpected.get());
                assertTrue(state.cancellation.getAsBoolean());
                assertEquals(!confirmed, state.publicationUncertain());
            } finally { fixture.close(); }
        }
    }

    @Test public void anotherThreadCannotConfirmTheOwnersReceipt() throws Exception {
        Fixture fixture = new Fixture();
        try {
            SshPasswordConnector.TrustCommitState state = new SshPasswordConnector.TrustCommitState();
            state.bindIssued(fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view));
            state.persisted();
            AtomicReference<Throwable> observed = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try { state.proofPublished(); }
                catch (Throwable failure) { observed.set(failure); }
            });
            foreign.start(); foreign.join();
            assertTrue(observed.get() instanceof VaultException);
            assertEquals("VAULT_COMMIT_UNCERTAIN", ((VaultException)observed.get()).code);
            assertTrue(state.publicationUncertain());
            state.proofPublished();
            assertFalse(state.publicationUncertain());
        } finally { fixture.close(); }
    }

    @Test public void screenLockDuringSecondHopPreservesTheUnconfirmedWrite() throws Exception {
        SshPasswordConnector.TrustCommitState state = new SshPasswordConnector.TrustCommitState();
        CountDownLatch secondWrite = new CountDownLatch(1), finishWrite = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicReference<Fixture> ownerFixture = new AtomicReference<>();
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public boolean mayWriteTrust() { return true; }
            @Override public SshSessionOwner.Cancellation trustCancellation() { return state.cancellation; }
            @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) throws Exception {
                Fixture fixture = new Fixture(); ownerFixture.set(fixture);
                SshNativeRoleBootstrap.Issued first = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
                SshNativeRoleBootstrap.Issued next = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
                state.bindIssued(first);
                state.newPersisted(); state.newProofPublished();
                SshPasswordConnector.TrustCommitState child = state.forNextHopForFixture(next, fixture.view,
                        fixture.admission(first, next));
                child.persisted(); secondWrite.countDown();
                assertTrue(finishWrite.await(5, java.util.concurrent.TimeUnit.SECONDS));
                child.proofPublished();
                throw new AssertionError("screen lock must reject the later confirmation");
            }
            @Override public void close() throws Exception { if (ownerFixture.get() != null) ownerFixture.get().close(); }
        };
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24,
                new SshTerminalState(), Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { released.countDown(); }
                });
        owner.begin();
        try {
            assertTrue(secondWrite.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(owner.stopForScreenLock(owner.id));
            assertFalse("the owner waits for the second trust outcome", owner.connected.isDone());
        } finally { finishWrite.countDown(); }
        try {
            owner.connected.get(5, java.util.concurrent.TimeUnit.SECONDS);
            fail("the committed but unconfirmed child write is uncertain");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof VaultException);
            assertEquals("VAULT_COMMIT_UNCERTAIN", ((VaultException)expected.getCause()).code);
        }
        assertTrue(state.publicationUncertain());
        assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test public void twoActualVaultTrustWritesUseSeparateReceiptsAndKeepCancellationUncertain()
            throws Exception {
        actualVaultTrustWrites(true);
    }

    @Test public void twoActualVaultTrustWritesCanBothBeConfirmedBeforeCancellation()
            throws Exception {
        actualVaultTrustWrites(false);
    }

    private static void actualVaultTrustWrites(boolean cancelSecondBeforeConfirmation) throws Exception {
        Fixture fixture = new Fixture();
        MemoryStore store = new MemoryStore();
        char[] password = "two-hop trust fixture".toCharArray();
        byte[] original = bytes("original trusted keys\n"), firstContent = bytes("first approved key\n"),
                secondContent = bytes("first and second approved keys\n");
        try {
            store.data = encryptedVault(password, original);
            AppSecrets vault = new AppSecrets(store, fixture.clock);
            // Hostfile parsing is tested separately. These are synthetic approvals and evidence.
            vault.attachSshValidator(manifest -> { });
            vault.session.foreground(true);
            vault.unlock(password, () -> { });
            vault.session.attachRevocation(vault.session.epoch(), fixture.view);
            long startEpoch = vault.session.epoch();
            SshNativeRoleBootstrap.Issued jump = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
            SshNativeRoleBootstrap.Issued target = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
            SshPasswordConnector.TrustCommitState root = new SshPasswordConnector.TrustCommitState();
            root.bindIssued(jump);

            try (TrustHop first = new TrustHop(vault, fixture.clock, jump.role, root)) {
                first.writeNew(original, firstContent);
                assertEquals(1, store.commits);
                assertEquals(startEpoch + 1, vault.session.epoch());
                assertTrue(root.publicationUncertain());
                first.provider.newProofPublished(); // Synthetic owner acknowledgement, not native KEX.
                assertFalse(root.publicationUncertain());
            }
            SshPasswordConnector.TrustCommitState child = root.forNextHopForFixture(target, fixture.view,
                    fixture.admission(jump, target));
            try (TrustHop second = new TrustHop(vault, fixture.clock, target.role, child)) {
                second.writeNew(firstContent, secondContent);
                assertEquals(2, store.commits);
                assertEquals(startEpoch + 2, vault.session.epoch());
                assertTrue(root.publicationUncertain());
                if (cancelSecondBeforeConfirmation) {
                    root.cancellation.cancel();
                    try { second.provider.newProofPublished(); fail("cancelled child cannot confirm"); }
                    catch (VaultException expected) { assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); }
                    VaultException uncertain = second.provider.invalidateUnacknowledgedNew();
                    assertNotNull(uncertain);
                    assertEquals("VAULT_COMMIT_UNCERTAIN", uncertain.code);
                    assertFalse(vault.session.unlocked());
                    assertTrue(root.publicationUncertain());
                } else {
                    second.provider.newProofPublished();
                    assertFalse(root.publicationUncertain());
                    root.cancellation.cancel();
                    assertFalse(root.publicationUncertain());
                    assertTrue(vault.session.unlocked());
                }
            }
            try (AppSecrets.Verified verified = AppSecrets.verify(store.data, password)) {
                assertArrayEquals(secondContent, verified.manifest.files.stream()
                        .filter(file -> file.path.equals(".ssh/known_hosts"))
                        .findFirst().orElseThrow().content);
            }
        } finally {
            fixture.close();
            Arrays.fill(password, '\0');
            VaultCodec.wipe(original); VaultCodec.wipe(firstContent); VaultCodec.wipe(secondContent);
        }
    }

    @Test public void oneRoleReceiptCanBeClaimedByOnlyOneExactProvider() throws Exception {
        Fixture fixture = new Fixture();
        MemoryStore store = new MemoryStore();
        char[] password = "provider claim fixture".toCharArray();
        byte[] original = bytes("original trusted keys\n"), firstContent = bytes("first approved key\n"),
                secondContent = bytes("first and second approved keys\n");
        try {
            store.data = encryptedVault(password, original);
            AppSecrets vault = new AppSecrets(store, fixture.clock);
            vault.attachSshValidator(manifest -> { });
            vault.session.foreground(true);
            vault.unlock(password, () -> { });
            vault.session.attachRevocation(vault.session.epoch(), fixture.view);
            SshNativeRoleBootstrap.Issued jump = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
            SshNativeRoleBootstrap.Issued target = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
            SshPasswordConnector.TrustCommitState root = new SshPasswordConnector.TrustCommitState();
            root.bindIssued(jump);

            try (TrustHop ignored = new TrustHop(vault, fixture.clock, target.role, root)) {
                fail("a provider for a different role must not claim the receipt");
            } catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            assertFalse(root.written());

            try (TrustHop first = new TrustHop(vault, fixture.clock, jump.role, root)) {
                try (TrustHop ignored = new TrustHop(vault, fixture.clock, jump.role, root)) {
                    fail("a second provider for the same role must not reuse its receipt");
                } catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                try { root.bindProvider(first.provider, jump.role); fail("a provider claim is one-shot"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                first.writeNew(original, firstContent);
                first.provider.newProofPublished();
                assertFalse(root.publicationUncertain());
            }

            SshPasswordConnector.TrustCommitState child = root.forNextHopForFixture(target, fixture.view,
                    fixture.admission(jump, target));
            try (TrustHop second = new TrustHop(vault, fixture.clock, target.role, child)) {
                second.writeNew(firstContent, secondContent);
                second.provider.newProofPublished();
                assertEquals(2, store.commits);
                assertFalse(root.publicationUncertain());
            }
        } finally {
            fixture.close();
            Arrays.fill(password, '\0');
            VaultCodec.wipe(original); VaultCodec.wipe(firstContent); VaultCodec.wipe(secondContent);
        }
    }

    @Test public void failedConnectorReceiptTransferWipesOnlyTheUnownedReceipt() throws Exception {
        Fixture fixture = new Fixture();
        MemoryStore store = new MemoryStore();
        char[] password = "receipt transfer fixture".toCharArray();
        byte[] original = bytes("original trusted keys\n"), replacement = bytes("new trusted key\n");
        try {
            store.data = encryptedVault(password, original);
            AppSecrets vault = new AppSecrets(store, fixture.clock);
            vault.attachSshValidator(manifest -> { });
            vault.session.foreground(true); vault.unlock(password, () -> { });
            vault.session.attachRevocation(vault.session.epoch(), fixture.view);
            SshNativeRoleBootstrap.Issued jump = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
            SshNativeRoleBootstrap.Issued target = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
            SshPasswordConnector.TrustCommitState state = new SshPasswordConnector.TrustCommitState();
            state.bindIssued(jump);
            AtomicReference<SshHostKeyAuthority.Review> approval = new AtomicReference<>();
            SshHostKeyAuthority authority = new SshHostKeyAuthority(approval::set);
            SshVaultAdapter adapter = new SshVaultAdapter(vault, (connection, targetIdentity) -> {
                if (approval.get() == null || !connection.equals(jump.role.connectionId)
                        || !targetIdentity.equals(jump.role.targetIdentity)) throw denied();
            }, proposal -> {
                if (approval.get() == null || !proposal.matchesRole(jump.role)) throw denied();
            });

            SshManagedKnownHostsProvider.SnapshotReceipt wrongRole = snapshot(adapter, target.role, fixture.clock);
            byte[] wrongBase = receiptBytes(wrongRole, "baseDigest");
            byte[] wrongBinding = receiptBytes(wrongRole, "snapshotBinding");
            try { SshPasswordConnector.bindOwnedTrustReceipt(adapter, wrongRole, state); fail("wrong role"); }
            catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
            assertWipedAndClosed(wrongRole, wrongBase, wrongBinding);

            for (Throwable failure : new Throwable[] {
                    new VaultException("SSH_ROLE_REQUIRED", "synthetic failure"),
                    new IllegalStateException("synthetic failure"), new AssertionError("synthetic failure") }) {
                SshManagedKnownHostsProvider.SnapshotReceipt failed = snapshot(adapter, jump.role, fixture.clock);
                byte[] base = receiptBytes(failed, "baseDigest"), binding = receiptBytes(failed, "snapshotBinding");
                SshManagedKnownHostsProvider.TrustCommitObserver reject = new SshManagedKnownHostsProvider.TrustCommitObserver() {
                    @Override public void persisted() { }
                    @Override public void bindProvider(SshManagedKnownHostsProvider provider, SshRoleContext role)
                            throws VaultException {
                        if (failure instanceof VaultException) throw (VaultException)failure;
                        if (failure instanceof RuntimeException) throw (RuntimeException)failure;
                        throw (Error)failure;
                    }
                };
                try { SshPasswordConnector.bindOwnedTrustReceipt(adapter, failed, reject); fail("observer must fail"); }
                catch (VaultException | RuntimeException | Error actual) { assertSame(failure, actual); }
                assertWipedAndClosed(failed, base, binding);
            }

            SshManagedKnownHostsProvider.SnapshotReceipt owned = snapshot(adapter, jump.role, fixture.clock);
            byte[] ownedBase = receiptBytes(owned, "baseDigest"), ownedBinding = receiptBytes(owned, "snapshotBinding");
            try (SshManagedKnownHostsProvider provider = SshPasswordConnector.bindOwnedTrustReceipt(adapter, owned, state)) {
                owned.requireLive();
                assertFalse(Arrays.equals(new byte[ownedBase.length], ownedBase));
                assertFalse(Arrays.equals(new byte[ownedBinding.length], ownedBinding));
                SshManagedKnownHostsProvider.SnapshotReceipt duplicate = snapshot(adapter, jump.role, fixture.clock);
                byte[] duplicateBase = receiptBytes(duplicate, "baseDigest"),
                        duplicateBinding = receiptBytes(duplicate, "snapshotBinding");
                try { SshPasswordConnector.bindOwnedTrustReceipt(adapter, duplicate, state); fail("duplicate claim"); }
                catch (VaultException expected) { assertEquals("SSH_ROLE_REQUIRED", expected.code); }
                assertWipedAndClosed(duplicate, duplicateBase, duplicateBinding);
                owned.requireLive(); // Failure of another receipt cannot close the live provider's source.
                byte[] base = VaultCodec.digest(original), next = VaultCodec.digest(replacement);
                try (SshKnownHostsCandidateHandoff handoff = provider.nativeCandidateHandoff()) {
                    SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(jump.role,
                            SshHostKeyAuthority.Classification.NEW, "ssh-ed25519", "SHA256:" + "A".repeat(43), base, next);
                    handoff.accept(evidence, "candidate-0", replacement.clone());
                    try (SshTrustProposal proposal = provider.proposalFor(evidence)) {
                        SshHostKeyAuthority.Proof proof = authority.requireVerified(jump.role, evidence, proposal);
                        assertNotNull(provider.write(evidence, proof, proposal));
                    }
                    provider.newProofPublished();
                } finally { VaultCodec.wipe(base); VaultCodec.wipe(next); }
                assertEquals(1, store.commits);
            }
            assertWipedAndClosed(owned, ownedBase, ownedBinding);
        } finally {
            fixture.close(); Arrays.fill(password, '\0');
            VaultCodec.wipe(original); VaultCodec.wipe(replacement);
        }
    }

    private static SshManagedKnownHostsProvider.SnapshotReceipt snapshot(SshVaultAdapter adapter,
            SshRoleContext role, Clock clock) throws VaultException {
        return SshManagedKnownHostsProvider.snapshotFor(adapter, role, clock,
                ".ssh/config", ".ssh/known_hosts", "trust", "trust-source");
    }

    private static byte[] receiptBytes(SshManagedKnownHostsProvider.SnapshotReceipt receipt, String field)
            throws Exception {
        java.lang.reflect.Field member = receipt.getClass().getDeclaredField(field);
        member.setAccessible(true);
        return (byte[])member.get(receipt);
    }

    private static void assertWipedAndClosed(SshManagedKnownHostsProvider.SnapshotReceipt receipt,
            byte[] base, byte[] binding) throws Exception {
        assertArrayEquals(new byte[base.length], base);
        assertArrayEquals(new byte[binding.length], binding);
        try { receipt.requireLive(); fail("closed trust receipt must not be reused"); }
        catch (VaultException expected) { assertEquals("VAULT_EXPIRED", expected.code); }
    }

    /** Real encrypted vault and Java owners; transport and native authentication are test doubles. */
    private static final class StrictFixture implements AutoCloseable {
        final Fixture fixture = new Fixture();
        final MemoryStore store = new MemoryStore();
        final char[] password = "fresh admission fixture".toCharArray();
        final AppSecrets vault;
        final SshVaultAdapter adapter;
        final SshNativeRoleBootstrap.Issued rootRole, childRole;
        final SshPasswordConnector.TrustCommitState root = new SshPasswordConnector.TrustCommitState();
        final TrustHop rootHop;
        final TestSession parent;

        StrictFixture() throws Exception {
            store.data = encryptedVault(password, bytes("trusted fixture\n"));
            vault = new AppSecrets(store, fixture.clock);
            vault.attachSshValidator(manifest -> { });
            vault.session.foreground(true);
            vault.unlock(password, () -> { });
            vault.session.attachRevocation(vault.session.epoch(), fixture.view);
            adapter = new SshVaultAdapter(vault, (connection, target) -> { }, proposal -> { });
            rootRole = fixture.issue(1, 2, 0, "connection", "chain:one", 10, fixture.view);
            childRole = fixture.issue(2, 1, 1, "connection", "chain:one", 11, fixture.view);
            root.bindIssued(rootRole);
            rootHop = new TrustHop(vault, fixture.clock, rootRole.role, root);
            parent = fixture.session(rootRole, 2, rootHop);
        }

        SshManagedKnownHostsProvider.ChildTrustSnapshot snapshot() throws VaultException {
            return SshManagedKnownHostsProvider.snapshotForChild(adapter, childRole.role, fixture.clock,
                    fixture.view, ".ssh/config", ".ssh/known_hosts", "trust", "trust-source");
        }

        StrictChild child(SshPasswordConnector.TrustCommitState state) throws Exception {
            return new StrictChild(this, state);
        }

        SshNativeSession.JumpChildOwner prepare(StrictChild child,
                SshPasswordConnector.TrustCommitState state) throws VaultException {
            return prepare(child.callback, child.source, state);
        }

        SshNativeSession.JumpChildOwner prepare(SshNativeCallbackAdapter callback,
                SshManagedKnownHostsProvider.ChildTrustSnapshot source,
                SshPasswordConnector.TrustCommitState state) throws VaultException {
            return SshNativeSession.prepareJumpChildFresh(parent.session, callback, fixture.shared,
                    ".ssh/known_hosts", "candidate_1", 1024, source, state, parent.binding);
        }

        @Override public void close() throws Exception {
            try { fixture.close(); }
            finally {
                rootHop.close(); vault.lock(); Arrays.fill(password, '\0');
            }
        }
    }

    private static final class StrictChild implements AutoCloseable {
        final SshManagedKnownHostsProvider.ChildTrustSnapshot source;
        final SshNativeCallbackAdapter callback;

        StrictChild(StrictFixture fixture, SshPasswordConnector.TrustCommitState state) throws Exception {
            source = fixture.snapshot();
            SshManagedKnownHostsProvider provider = state == null
                    ? SshManagedKnownHostsProvider.bindForNativeHandoff(fixture.adapter, source.receipt())
                    : SshPasswordConnector.bindOwnedTrustReceipt(fixture.adapter, source.receipt(), state);
            SshKnownHostsCandidateHandoff handoff = provider.nativeCandidateHandoff();
            SshHostKeyAuthority authority = new SshHostKeyAuthority();
            SshManagedProvider managed = new SshManagedProvider(
                    new SshCredentialBridge(fixture.vault, authority, fixture.fixture.clock),
                    fixture.fixture.clock, provider, provider, null);
            SshNativeCallbackAdapter.NativeBinding callbackNative = new SshNativeCallbackAdapter.NativeBinding() {
                @Override public long open(SshNativeCallbackAdapter receiver, long role, int uid, long deadline) {
                    return 905;
                }
                @Override public void cancel(long token) { }
                @Override public void close(long token) { }
            };
            callback = SshNativeCallbackAdapter.bind(fixture.childRole, fixture.fixture.registry, authority,
                    fixture.fixture.clock, callbackNative, (token, uid) -> { }, managed, handoff);
            callback.selectAuthentication(SshAuthenticationSelection.password(fixture.childRole.role,
                    "fixture", "password", fixture.fixture.clock));
        }

        @Override public void close() throws Exception { callback.close(); source.close(); }
    }

    private static final class TrustHop implements AutoCloseable {
        final AppSecrets vault;
        final SshRoleContext role;
        final SshHostKeyAuthority authority;
        final SshManagedKnownHostsProvider provider;
        final SshKnownHostsCandidateHandoff handoff;
        final AtomicReference<SshHostKeyAuthority.Review> approval = new AtomicReference<>();

        TrustHop(AppSecrets vault, Clock clock, SshRoleContext role,
                SshPasswordConnector.TrustCommitState observer) throws Exception {
            this.vault = vault;
            this.role = role;
            authority = new SshHostKeyAuthority(review -> {
                if (!review.belongsTo(role)) throw denied();
                approval.set(review);
            });
            SshVaultAdapter adapter = new SshVaultAdapter(vault, (connection, target) -> {
                if (approval.get() == null || !connection.equals(role.connectionId)
                        || !target.equals(role.targetIdentity)) throw denied();
            }, proposal -> {
                if (approval.get() == null || !proposal.matchesRole(role)
                        || !proposal.matchesManagedObject(VaultManifest.FileType.KNOWN_HOSTS,
                                "trust", ".ssh/known_hosts", "trust-source")) throw denied();
            });
            SshManagedKnownHostsProvider.SnapshotReceipt snapshot = SshManagedKnownHostsProvider.snapshotFor(
                    adapter, role, clock, ".ssh/config", ".ssh/known_hosts", "trust", "trust-source");
            SshManagedKnownHostsProvider created = null;
            SshKnownHostsCandidateHandoff receiver;
            try {
                created = SshPasswordConnector.bindOwnedTrustReceipt(adapter, snapshot, observer);
                receiver = created.nativeCandidateHandoff();
            } catch (VaultException | RuntimeException | Error failure) {
                if (created != null) created.close();
                throw failure;
            }
            provider = created;
            handoff = receiver;
        }

        void writeNew(byte[] before, byte[] after) throws Exception {
            byte[] base = VaultCodec.digest(before), replacement = VaultCodec.digest(after);
            try {
                SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                        SshHostKeyAuthority.Classification.NEW, "ssh-ed25519", "SHA256:" + "A".repeat(43),
                        base, replacement);
                handoff.accept(evidence, "candidate-" + role.hop, after.clone());
                try (SshTrustProposal proposal = provider.proposalFor(evidence)) {
                    SshHostKeyAuthority.Proof proof = authority.requireVerified(role, evidence, proposal);
                    assertNotNull(provider.write(evidence, proof, proposal));
                }
            } finally { VaultCodec.wipe(base); VaultCodec.wipe(replacement); }
        }

        @Override public void close() { handoff.close(); provider.close(); }
    }

    private static final class MemoryStore implements AppSecrets.Store {
        byte[] data;
        int commits;
        @Override public byte[] read() { return data.clone(); }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            guard.beforeCommit(); data = next.clone(); commits++;
        }
    }

    private static byte[] encryptedVault(char[] password, byte[] content) throws Exception {
        byte[] salt = new byte[16]; Arrays.fill(salt, (byte)7);
        byte[] key = VaultEnvelope.derive(password, salt);
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "config", ".ssh/config", "config-source", bytes("Host fixture\n"), List.of(".ssh/known_hosts"));
        VaultManifest.FileObject hosts = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "trust", ".ssh/known_hosts", "trust-source", content, List.of());
        try (VaultManifest manifest = new VaultManifest(List.of(), List.of(config, hosts))) {
            byte[] plain = manifest.encode();
            try { return VaultEnvelope.encrypt(UUID.randomUUID(), 7, salt, key, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        } finally { VaultCodec.wipe(key); VaultCodec.wipe(salt); }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static VaultException denied() { return new VaultException("SSH_HOST_KEY_REVIEW", "Invalid fixture approval"); }

    private static final class Fixture implements AutoCloseable {
        final Clock clock = new Clock();
        final View view = new View();
        final SshRoleRegistry registry = new SshRoleRegistry(clock);
        final SshSharedPublicView shared = new SshSharedPublicView(view, view, view::revoke);
        final IdentityHashMap<SshNativeRoleBootstrap.Issued, TestSession> sessions = new IdentityHashMap<>();

        SshNativeRoleBootstrap.Issued issue(int token, int program, int hop, String connection,
                String chain, int nonce, SshRoleContext.PublicView selectedView) throws Exception {
            return issue(token, program, hop, connection, chain, nonce, 7, selectedView);
        }
        SshNativeRoleBootstrap.Issued issue(int token, int program, int hop, String connection,
                String chain, int nonce, int binding, SshRoleContext.PublicView selectedView) throws Exception {
            return issue(token, program, hop, connection, chain, nonce, binding, 42, 1001, 31_000, selectedView);
        }
        SshNativeRoleBootstrap.Issued issue(int token, int program, int hop, String connection,
                String chain, int nonce, int binding, int uid, int pid, long deadline,
                SshRoleContext.PublicView selectedView) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x43535242); out.writeByte(2); out.writeByte(program); out.writeShort(0);
            out.writeLong(token); out.writeInt(pid); out.writeInt(uid); out.writeInt(hop);
            out.writeInt(2222 + hop); out.writeLong(deadline);
            byte[] field = new byte[32]; Arrays.fill(field, (byte)nonce); out.write(field);
            Arrays.fill(field, (byte)binding); out.write(field);
            text(out, connection); text(out, "target:hop" + hop); text(out, "example.invalid");
            text(out, "view_chain"); text(out, chain); out.flush();
            byte[] wire = bytes.toByteArray();
            return SshNativeRoleBootstrap.issue(wire, registry, selectedView, clock,
                    actual -> Arrays.equals(wire, actual.canonicalWire()));
        }

        /** The session is production Java; its authentication result is supplied by fake native. */
        SshNativeSession.JumpAdmission admission(SshNativeRoleBootstrap.Issued parent,
                SshNativeRoleBootstrap.Issued next) throws Exception {
            TestSession session = sessions.get(parent);
            if (session == null) session = session(parent, 2);
            return session.session.issueJumpAdmission(parent, next, shared, view);
        }

        TestSession session(SshNativeRoleBootstrap.Issued parent, int authResult) throws Exception {
            return session(parent, authResult, null);
        }

        TestSession session(SshNativeRoleBootstrap.Issued parent, int authResult, TrustHop trust) throws Exception {
            if (sessions.containsKey(parent)) throw new IllegalStateException("session already exists");
            TestSession result = new TestSession(this, parent, authResult, trust);
            sessions.put(parent, result);
            return result;
        }

        @Override public void close() throws Exception {
            for (TestSession session : new ArrayList<>(sessions.values())) session.close();
            shared.seal(); shared.close(); registry.close();
        }
        private static void text(DataOutputStream out, String value) throws Exception {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            out.writeShort(bytes.length); out.write(bytes);
        }
    }

    private static final class Clock implements SshRoleContext.Clock, VaultSession.Clock {
        volatile long value = 1_000;
        @Override public long now() { return value; }
    }

    private static final class View implements SshRoleContext.PublicView, VaultSession.Revocation,
            SshNativeSession.ViewBinding {
        private boolean active = true;
        Runnable issueHook;
        @Override public synchronized boolean active() {
            if (issueHook != null) {
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (frame.getClassName().equals(SshSharedPublicView.class.getName())
                            && frame.getMethodName().equals("issue")) {
                        Runnable hook = issueHook; issueHook = null; hook.run(); break;
                    }
                }
            }
            return active;
        }
        @Override public synchronized void revoke() { active = false; }
        @Override public synchronized boolean revocationConfirmed() { return !active; }
        @Override public synchronized long token() throws VaultException {
            if (!active) throw new VaultException("SSH_FILE_VIEW", "The test view was revoked");
            return 902;
        }
        @Override public synchronized void close() { active = false; }
    }

    private static final class TestSession implements AutoCloseable {
        final SshNativeCallbackAdapter callback;
        final SshNativeSession session;
        final AuthNative binding;

        TestSession(Fixture fixture, SshNativeRoleBootstrap.Issued issued, int authResult, TrustHop trust)
                throws Exception {
            SshHostKeyAuthority authority = trust == null ? new SshHostKeyAuthority() : trust.authority;
            AppSecrets.Store unavailableStore = new AppSecrets.Store() {
                @Override public byte[] read() { throw new AssertionError("fake native cannot read a vault"); }
                @Override public byte[] quickMaterial() { throw new AssertionError("unexpected secret access"); }
                @Override public void commit(byte[] data, byte[] quick, AppSecrets.EffectGuard guard) {
                    throw new AssertionError("fake native cannot write a vault");
                }
            };
            AppSecrets vault = trust == null ? new AppSecrets(unavailableStore, fixture.clock) : trust.vault;
            SshManagedProvider provider = trust == null
                    ? new SshManagedProvider(new SshCredentialBridge(vault, authority, fixture.clock),
                            fixture.clock, null, (bridge, role, proof, kind) -> {
                                throw new AssertionError("unexpected credential");
                            })
                    : new SshManagedProvider(new SshCredentialBridge(vault, authority, fixture.clock),
                            fixture.clock, trust.provider, trust.provider, null);
            SshNativeCallbackAdapter.NativeBinding callbackNative = new SshNativeCallbackAdapter.NativeBinding() {
                @Override public long open(SshNativeCallbackAdapter receiver, long role, int uid, long deadline) { return 901; }
                @Override public void cancel(long token) { }
                @Override public void close(long token) { }
            };
            callback = SshNativeCallbackAdapter.bind(issued, fixture.registry, authority, fixture.clock,
                    callbackNative, (token, uid) -> { }, provider, trust == null ? null : trust.handoff);
            callback.selectAuthentication(SshAuthenticationSelection.password(issued.role, "fixture", "password", fixture.clock));
            binding = new AuthNative();
            session = SshNativeSession.openManaged(callback, fixture.shared,
                    new SshSocketOwnership(41, ignored -> { }), "ssh/known_hosts", "candidate_1", 1024, binding);
            session.step();
            if (authResult != 0) {
                session.beginUserauthService();
                assertTrue(session.userauthServiceReady());
                session.beginPasswordAuth();
                binding.result = authResult;
                session.step();
            }
        }

        @Override public void close() throws Exception { session.close(); callback.close(); }
    }

    /** Supplies synthetic USERAUTH results; it does not model a native SSH peer. */
    private static final class AuthNative implements SshNativeSession.NativeBinding {
        byte[] receipt;
        int result;
        int reserves, opens, aborts;
        int childState;
        boolean jump;
        @Override public long open(long role, int uid, long callback, long view, int socket,
                long deadline, int budget, byte[] key, byte[] fingerprint, String path,
                String host, String candidate) throws IOException { throw new IOException("unexpected pinned open"); }
        @Override public long openManaged(long role, int uid, long callback, long view, int socket,
                long deadline, int budget, String path, String host, String candidate, byte[] selection) {
            receipt = VaultCodec.digest(selection);
            return 903;
        }
        @Override public SshNativeSession.StepResult step(long handle, boolean cancelled) throws IOException {
            if (cancelled) throw new IOException("test session cancelled");
            return new SshNativeSession.StepResult(0, 0, 0, 0, true, false, false, true);
        }
        @Override public byte[] authenticationSelectionReceipt(long handle) { return receipt.clone(); }
        @Override public void beginUserauthService(long handle) { }
        @Override public boolean userauthServiceReady(long handle) { return true; }
        @Override public void beginPasswordAuth(long handle) { result = 1; }
        @Override public int passwordAuthResult(long handle) { return result; }
        @Override public void beginJump(long handle, long nextRole) { jump = true; }
        @Override public int[] jumpStatus(long handle) { return new int[] { jump ? 2 : 1, 0, 0, 0 }; }
        @Override public long reserveJumpChild(long handle) { reserves++; return 904; }
        @Override public boolean openJumpChildFresh(long handle, long role, int uid, long callback,
                long view, long deadline, int budget, String path, String host, String candidate,
                byte[] selection, byte[] fresh) {
            assertEquals(904, handle);
            assertNotNull(fresh);
            opens++; childState = 4;
            return true;
        }
        @Override public int jumpChildState(long handle) { return childState; }
        @Override public void abortJumpChild(long handle) { aborts++; }
        @Override public void verifyHostKey(long handle, byte[] blob) { }
        @Override public void close(long handle) { }
        @Override public void destroy(long handle) { }
    }
}
