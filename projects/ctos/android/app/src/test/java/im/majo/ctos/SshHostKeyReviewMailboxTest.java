package im.majo.ctos;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Exact review decisions only; no network, native provider or real secret is involved. */
public final class SshHostKeyReviewMailboxTest {
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);
    private static final class Clock implements SshRoleContext.Clock {
        volatile long value = 1000;
        @Override public long now() { return value; }
    }
    private static final class View implements SshRoleContext.PublicView {
        volatile boolean active = true;
        @Override public boolean active() { return active; }
    }
    private static final class Fixture implements AutoCloseable {
        final Clock clock = new Clock();
        final View view = new View();
        final SshRoleContext role;
        final SshHostKeyAuthority.Evidence evidence;
        final SshTrustProposal proposal;
        Fixture(int nonce) throws Exception {
            this(nonce, 0);
        }
        Fixture(int nonce, int hop) throws Exception {
            byte[] roleNonce = new byte[32], connection = new byte[32];
            Arrays.fill(roleNonce, (byte) nonce);
            Arrays.fill(connection, (byte) 7);
            role = SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                    42, hop, "mailbox-fixture", "target:test@fixture.invalid:22", "fixture.invalid", 22,
                    "public-view", roleNonce, connection, 60000), view, clock);
            byte[] base = VaultCodec.digest(new byte[0]);
            byte[] replacement = "fixture.invalid ssh-ed25519 AAAA\n".getBytes(StandardCharsets.US_ASCII);
            evidence = SshHostKeyAuthority.Evidence.fromNative(role, SshHostKeyAuthority.Classification.NEW,
                    "ssh-ed25519", FINGERPRINT, base, VaultCodec.digest(replacement));
            proposal = new SshTrustProposal(role, "ssh/known_hosts", "trust", role.targetIdentity,
                    role.connectionId, role.host, role.port, "ssh-ed25519", FINGERPRINT, base, replacement);
        }
        SshHostKeyAuthority.Proof run(SshHostKeyAuthority.UnknownKeyIssuer issuer) throws Exception {
            return new SshHostKeyAuthority(issuer).requireVerified(role, evidence, proposal);
        }
        @Override public void close() { proposal.close(); role.close(); }
    }
    private interface Action { void run() throws Exception; }
    private static void code(String expected, Action action) throws Exception {
        try { action.run(); fail("Expected " + expected); }
        catch (VaultException failure) { assertEquals(expected, failure.code); }
    }

    private static boolean await(FutureTask<Boolean> task) {
        try { return task.get(2, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test public void sharedStopAfterApprovalPreventsProofHandoff() throws Exception {
        try (Fixture fixture = new Fixture(19)) {
            SshSessionOwner.Cancellation cancellation = new SshSessionOwner.Cancellation();
            AtomicReference<SshHostKeyReviewMailbox> owner = new AtomicReference<>();
            AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(fixture.role, fixture.clock,
                    () -> { }, ticket -> {
                        shown.set(ticket);
                        assertTrue(owner.get().resolve(ticket, true));
                        cancellation.cancel();
                        assertFalse(owner.get().resolve(ticket, true));
                    }, cancellation)) {
                owner.set(box);
                code("SSH_HOST_KEY_REVIEW_CANCELLED", () -> fixture.run(box));
                assertNotNull(shown.get());
                code("SSH_HOST_KEY_REVIEW_CANCELLED", () -> box.pending(shown.get()));
            }
        }
    }

    @Test public void pendingPollRunsOnOwnerOutsideMonitorUntilExactDecision() throws Exception {
        try (Fixture f = new Fixture(20, 1)) {
            Thread owner = Thread.currentThread();
            AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
            AtomicInteger polls = new AtomicInteger(), dismissed = new AtomicInteger();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { },
                    new SshHostKeyReviewMailbox.Sink() {
                        @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) { shown.set(ticket); }
                        @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) { dismissed.incrementAndGet(); }
                    })) {
                assertEquals(FINGERPRINT, f.run(review -> box.requireApproved(review, f.role.deadline, () -> {
                    assertSame(owner, Thread.currentThread());
                    assertTrue(box.pending(shown.get()));
                    if (polls.incrementAndGet() == 2) {
                        FutureTask<Boolean> answer = new FutureTask<>(() -> box.resolve(shown.get(), true));
                        Thread ui = new Thread(answer, "synthetic-review-poll-answer");
                        ui.setDaemon(true);
                        ui.start();
                        assertTrue(await(answer));
                    }
                })).fingerprint);
                assertEquals(2, polls.get());
                assertEquals(1, dismissed.get());
                assertFalse(box.pending(shown.get()));
                assertFalse(box.resolve(shown.get(), true));
            }
        }
    }

    @Test public void pendingPollCancellationAfterConcurrentApprovalWins() throws Exception {
        try (Fixture f = new Fixture(21, 1)) {
            AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
            AtomicInteger dismissed = new AtomicInteger();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { },
                    new SshHostKeyReviewMailbox.Sink() {
                        @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) { shown.set(ticket); }
                        @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) { dismissed.incrementAndGet(); }
                    })) {
                code("SSH_HOST_KEY_REVIEW_CANCELLED", () -> f.run(review -> box.requireApproved(review, f.role.deadline, () -> {
                    FutureTask<Boolean> answerThenCancel = new FutureTask<>(() -> {
                        boolean approved = box.resolve(shown.get(), true);
                        box.cancel();
                        return approved;
                    });
                    Thread ui = new Thread(answerThenCancel, "synthetic-review-poll-cancel");
                    ui.setDaemon(true);
                    ui.start();
                    assertTrue(await(answerThenCancel));
                })));
                assertEquals(1, dismissed.get());
                assertFalse(box.resolve(shown.get(), true));
            }
        }
    }

    @Test public void pendingPollRechecksAlreadyApprovedDeadlineAndView() throws Exception {
        for (boolean expire : new boolean[] { true, false }) {
            try (Fixture f = new Fixture(expire ? 22 : 23, 1)) {
                AtomicReference<SshHostKeyReviewMailbox> owner = new AtomicReference<>();
                AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
                AtomicInteger polls = new AtomicInteger();
                try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket -> {
                    shown.set(ticket);
                    assertTrue(owner.get().resolve(ticket, true));
                })) {
                    owner.set(box);
                    code(expire ? "SSH_HOST_KEY_REVIEW_EXPIRED" : "SSH_FILE_VIEW",
                            () -> f.run(review -> box.requireApproved(review, f.role.deadline, () -> {
                                polls.incrementAndGet();
                                if (expire) f.clock.value = shown.get().deadline;
                                else f.view.active = false;
                            })));
                    assertEquals(1, polls.get());
                    assertFalse(box.resolve(shown.get(), true));
                }
            }
        }
    }

    @Test public void pendingPollFailureDismissesAndRejectsLateApproval() throws Exception {
        try (Fixture f = new Fixture(24, 1)) {
            AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
            AtomicInteger dismissed = new AtomicInteger();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { },
                    new SshHostKeyReviewMailbox.Sink() {
                        @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) { shown.set(ticket); }
                        @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) { dismissed.incrementAndGet(); }
                    })) {
                code("SSH_PENDING_TRANSPORT", () -> f.run(review -> box.requireApproved(review, f.role.deadline, () -> {
                    throw new VaultException("SSH_PENDING_TRANSPORT", "Synthetic poll failure");
                })));
                assertEquals(1, dismissed.get());
                assertFalse(box.resolve(shown.get(), true));
                code("SSH_HOST_KEY_REVIEW", () -> f.run(box));
            }
        }
    }

    @Test public void pendingPollDoesNotRunAfterImmediateRejectCancelOrExpiry() throws Exception {
        for (int change = 0; change < 3; change++) {
            try (Fixture f = new Fixture(25 + change, 1)) {
                int mode = change;
                AtomicReference<SshHostKeyReviewMailbox> owner = new AtomicReference<>();
                AtomicInteger polls = new AtomicInteger();
                try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket -> {
                    if (mode == 0) assertTrue(owner.get().resolve(ticket, false));
                    if (mode == 1) owner.get().cancel();
                    if (mode == 2) f.clock.value = ticket.deadline;
                })) {
                    owner.set(box);
                    code(new String[] { "SSH_HOST_KEY_REVIEW", "SSH_HOST_KEY_REVIEW_CANCELLED",
                            "SSH_HOST_KEY_REVIEW_EXPIRED" }[mode],
                            () -> f.run(review -> box.requireApproved(review, f.role.deadline, polls::incrementAndGet)));
                    assertEquals(0, polls.get());
                }
            }
        }
    }

    @Test public void pendingPollUsesEarliestTransportRoleAndReviewDeadline() throws Exception {
        for (long[] deadlines : new long[][] {
                { 1_000, 1_500, 1_500 },
                { 1_000, 50_000, 31_000 },
                { 35_000, 70_000, 60_000 },
        }) {
            try (Fixture f = new Fixture(28, 1)) {
                f.clock.value = deadlines[0];
                AtomicReference<SshHostKeyReviewMailbox> owner = new AtomicReference<>();
                StringBuilder order = new StringBuilder();
                try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket -> {
                    assertEquals(deadlines[2], ticket.deadline);
                    order.append('P');
                    assertTrue(owner.get().resolve(ticket, true));
                })) {
                    owner.set(box);
                    assertEquals(FINGERPRINT, f.run(review -> box.requireApproved(review, deadlines[1],
                            () -> order.append('R'))).fingerprint);
                    assertEquals("PR", order.toString());
                }
            }
        }
    }

    @Test public void expiredTransportDeadlineIsRejectedBeforePresentation() throws Exception {
        for (long deadline : new long[] { 0, 999, 1_000 }) {
            try (Fixture f = new Fixture(29, 1)) {
                AtomicInteger shown = new AtomicInteger(), dismissed = new AtomicInteger(), polls = new AtomicInteger();
                try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { },
                        new SshHostKeyReviewMailbox.Sink() {
                            @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) { shown.incrementAndGet(); }
                            @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) { dismissed.incrementAndGet(); }
                        })) {
                    code("SSH_TIMED_OUT", () -> f.run(review -> box.requireApproved(review, deadline,
                            polls::incrementAndGet)));
                    assertEquals(0, shown.get());
                    assertEquals(0, dismissed.get());
                    assertEquals(0, polls.get());
                }
            }
        }
    }

    @Test public void transportDeadlineAfterInitialSamplePreventsPresentation() throws Exception {
        try (Fixture f = new Fixture(32, 1)) {
            AtomicInteger samples = new AtomicInteger();
            // checkLive samples the role clock four times; the fifth sample constructs the ticket.
            SshRoleContext.Clock clock = () -> samples.incrementAndGet() <= 5 ? 1_000 : 1_500;
            AtomicInteger shown = new AtomicInteger(), dismissed = new AtomicInteger(), polls = new AtomicInteger();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, clock, () -> { },
                    new SshHostKeyReviewMailbox.Sink() {
                        @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) { shown.incrementAndGet(); }
                        @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) { dismissed.incrementAndGet(); }
                    })) {
                code("SSH_HOST_KEY_REVIEW_EXPIRED", () -> f.run(review -> box.requireApproved(review, 1_500,
                        polls::incrementAndGet)));
                assertEquals(0, shown.get());
                assertEquals(0, dismissed.get());
                assertEquals(0, polls.get());
            }
        }
    }

    @Test public void transportDeadlineDuringPollRejectsApprovalAndCleansTicket() throws Exception {
        for (boolean approveBeforeExpiry : new boolean[] { false, true }) {
            try (Fixture f = new Fixture(30, 1)) {
                AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
                AtomicInteger polls = new AtomicInteger(), dismissed = new AtomicInteger();
                try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { },
                        new SshHostKeyReviewMailbox.Sink() {
                            @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) { shown.set(ticket); }
                            @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) { dismissed.incrementAndGet(); }
                        })) {
                    code("SSH_HOST_KEY_REVIEW_EXPIRED", () -> f.run(review -> box.requireApproved(review, 1_500, () -> {
                        polls.incrementAndGet();
                        if (approveBeforeExpiry) assertTrue(box.resolve(shown.get(), true));
                        f.clock.value = 1_500;
                        if (!approveBeforeExpiry) assertFalse(box.resolve(shown.get(), true));
                    })));
                    assertEquals(1, polls.get());
                    assertEquals(1, dismissed.get());
                    assertFalse(box.resolve(shown.get(), true));
                }
            }
        }
    }

    @Test public void transportBudgetBoundsWaitingEvenIfRoleClockStalls() throws Exception {
        try (Fixture f = new Fixture(31, 1)) {
            AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
            AtomicInteger polls = new AtomicInteger(), dismissed = new AtomicInteger();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { },
                    new SshHostKeyReviewMailbox.Sink() {
                        @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) { shown.set(ticket); }
                        @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) { dismissed.incrementAndGet(); }
                    })) {
                code("SSH_HOST_KEY_REVIEW_EXPIRED", () -> f.run(review -> box.requireApproved(review, 1_400,
                        polls::incrementAndGet)));
                assertTrue(polls.get() > 0);
                assertEquals(1_000, f.clock.value);
                assertEquals(1, dismissed.get());
                assertFalse(box.resolve(shown.get(), true));
            }
        }
    }

    @Test public void bridgeSerializesEachReviewFromTheExactRoleTicket() throws Exception {
        for (int hop : new int[] { 0, 1 }) {
            try (Fixture f = new Fixture(hop + 1, hop)) {
                AtomicReference<SshHostKeyReviewMailbox> owner = new AtomicReference<>();
                AtomicReference<FutureTask<Boolean>> answer = new AtomicReference<>();
                try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket -> {
                    java.util.Map<String, Object> event = SshPasswordConnectBridge.hostReviewEvent("attempt", ticket, 30_000);
                    java.util.Map<String, Object> dismissal = SshPasswordConnectBridge.hostReviewDismissal("attempt", ticket);
                    assertEquals("hostKey", event.get("type"));
                    assertEquals(hop, event.get("hop"));
                    assertEquals(f.role.host, event.get("host"));
                    assertEquals(f.role.port, event.get("port"));
                    assertEquals(ticket.id, event.get("ticketId"));
                    assertEquals(ticket.binding, event.get("binding"));
                    assertEquals("hostKeyDismissed", dismissal.get("type"));
                    assertEquals(hop, dismissal.get("hop"));
                    assertEquals(ticket.id, dismissal.get("ticketId"));
                    assertEquals(ticket.binding, dismissal.get("binding"));
                    FutureTask<Boolean> task = new FutureTask<>(() -> owner.get().resolve(ticket, true));
                    answer.set(task);
                    new Thread(task, "synthetic-review-ui").start();
                })) {
                    owner.set(mailbox);
                    assertEquals(FINGERPRINT, f.run(mailbox).fingerprint);
                    assertTrue(answer.get().get(2, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test public void postedNewReviewRechecksConsumerAndExactPendingWithoutTransportDeadline() throws Exception {
        for (String outcome : new String[] { "live", "cancelled", "revoked", "consumer" }) {
            try (Fixture f = new Fixture(10)) {
                AtomicReference<SshHostKeyReviewMailbox> owner = new AtomicReference<>();
                AtomicInteger published = new AtomicInteger(), liveChecks = new AtomicInteger();
                AtomicInteger transportChecks = new AtomicInteger(), consumerChecks = new AtomicInteger();
                AtomicReference<String> postedFailure = new AtomicReference<>();
                try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket -> {
                    Runnable posted = () -> {
                        try {
                            SshPasswordConnectBridge.publishPendingHostReview(ticket, () -> {
                                consumerChecks.incrementAndGet();
                                if (outcome.equals("consumer")) throw new VaultException("SSH_CANCELLED", "consumer ended");
                            }, candidate -> SshPasswordConnector.hostKeyPending(candidate, owner.get(),
                                    liveChecks::incrementAndGet, () -> {
                                        transportChecks.incrementAndGet();
                                        SshPasswordConnector.requireTransportDeadline(f.clock.now(), 1_500);
                                    }), published::incrementAndGet);
                        } catch (VaultException failure) {
                            postedFailure.set(failure.code);
                            owner.get().cancel();
                        }
                    };
                    // The runnable was queued at t=1000, but executes after the shorter transport deadline.
                    f.clock.value = 2_000;
                    if (outcome.equals("cancelled")) owner.get().cancel();
                    if (outcome.equals("revoked")) f.view.active = false;
                    posted.run();
                    if (outcome.equals("live")) assertTrue(owner.get().resolve(ticket, true));
                })) {
                    owner.set(mailbox);
                    if (outcome.equals("live")) {
                        assertEquals(FINGERPRINT, f.run(mailbox).fingerprint);
                        assertEquals(1, published.get());
                    } else {
                        try { f.run(mailbox); fail("The stale review must not succeed"); }
                        catch (VaultException expected) { assertNotNull(expected.code); }
                        assertEquals(0, published.get());
                    }
                    assertEquals(1, consumerChecks.get());
                    assertEquals(0, transportChecks.get());
                    assertEquals(outcome.equals("consumer") ? 0 : 1, liveChecks.get());
                    if (outcome.equals("revoked")) assertNotNull(postedFailure.get());
                    if (outcome.equals("consumer")) assertEquals("SSH_CANCELLED", postedFailure.get());
                }
            }
        }
    }

    @Test public void separateUiThreadApprovesExactMetadataOnceAndTicketCannotBeReused() throws Exception {
        try (Fixture f = new Fixture(1)) {
            AtomicReference<SshHostKeyReviewMailbox> owner = new AtomicReference<>();
            AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
            AtomicReference<FutureTask<Boolean>> answer = new AtomicReference<>();
            AtomicInteger dismissed = new AtomicInteger();
            try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { },
                    new SshHostKeyReviewMailbox.Sink() {
                        @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) throws VaultException {
                            shown.set(ticket);
                            assertTrue(owner.get().pending(ticket));
                            assertEquals(f.role.host, ticket.host);
                            assertEquals(f.role.port, ticket.port);
                            assertEquals(FINGERPRINT, ticket.fingerprint);
                            assertEquals(31000, ticket.deadline);
                            byte[] copy = ticket.baseDigest();
                            copy[0] ^= 1;
                            assertArrayEquals(VaultCodec.digest(new byte[0]), ticket.baseDigest());
                            FutureTask<Boolean> task = new FutureTask<>(() -> owner.get().resolve(ticket, true));
                            answer.set(task);
                            new Thread(task, "synthetic-review-ui").start();
                        }
                        @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) { dismissed.incrementAndGet(); }
                    })) {
                owner.set(mailbox);
                assertEquals(FINGERPRINT, f.run(mailbox).fingerprint);
                assertTrue(answer.get().get(2, TimeUnit.SECONDS));
                assertEquals(1, dismissed.get());
                assertFalse(mailbox.pending(shown.get()));
                assertFalse(mailbox.resolve(shown.get(), true));
                code("SSH_HOST_KEY_REVIEW", () -> f.run(mailbox));
            }
        }
    }

    @Test public void rejectMissingUiAndDuplicateDecisionNeverProduceProof() throws Exception {
        try (Fixture f = new Fixture(2)) {
            code("SSH_HOST_KEY_REVIEW", () -> f.run(new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, null)));
            AtomicReference<SshHostKeyReviewMailbox> ref = new AtomicReference<>();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket -> {
                assertTrue(ref.get().resolve(ticket, false));
                assertFalse(ref.get().resolve(ticket, true));
            })) {
                ref.set(box);
                code("SSH_HOST_KEY_REVIEW", () -> f.run(box));
            }
        }
    }

    @Test public void exactRoleObjectRequiredEvenWhenBindingTextMatches() throws Exception {
        try (Fixture first = new Fixture(3); Fixture other = new Fixture(4)) {
            assertEquals(first.role.binding(), other.role.binding());
            AtomicInteger shown = new AtomicInteger();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(first.role, first.clock,
                    () -> { }, ticket -> shown.incrementAndGet())) {
                code("SSH_HOST_KEY_REVIEW", () -> other.run(box));
                assertEquals(0, shown.get());
            }
        }
    }

    @Test public void crossMailboxTicketAndWrongThreadReviewAreRefused() throws Exception {
        try (Fixture f = new Fixture(5)) {
            AtomicReference<SshHostKeyReviewMailbox> own = new AtomicReference<>();
            AtomicReference<SshHostKeyAuthority.Review> review = new AtomicReference<>();
            try (SshHostKeyReviewMailbox foreign = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, null);
                    SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket -> {
                        assertFalse(foreign.resolve(ticket, true));
                        assertTrue(own.get().resolve(ticket, true));
                    })) {
                own.set(box);
                f.run(value -> { review.set(value); box.requireApproved(value); });
                FutureTask<String> alien = new FutureTask<>(() -> {
                    try { box.requireApproved(review.get()); return "unexpected"; }
                    catch (VaultException failure) { return failure.code; }
                });
                new Thread(alien, "synthetic-alien-review").start();
                assertEquals("SSH_CALLBACK_THREAD", alien.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void approvalFollowedByCancelExpiryOrRevocationFailsBeforeReturn() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            try (Fixture f = new Fixture(6 + mode)) {
                final int change = mode;
                AtomicReference<SshHostKeyReviewMailbox> ref = new AtomicReference<>();
                AtomicBoolean callbackCancelled = new AtomicBoolean();
                try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> {
                    if (callbackCancelled.get()) throw new VaultException("SSH_CALLBACK_CANCELLED", "Synthetic cancel");
                }, ticket -> {
                    assertTrue(ref.get().resolve(ticket, true));
                    if (change == 0) ref.get().cancel();
                    if (change == 1) f.clock.value = f.role.deadline;
                    if (change == 2) f.view.active = false;
                    if (change == 3) callbackCancelled.set(true);
                })) {
                    ref.set(box);
                    code(new String[]{"SSH_HOST_KEY_REVIEW_CANCELLED", "SSH_ROLE_EXPIRED", "SSH_FILE_VIEW",
                            "SSH_CALLBACK_CANCELLED"}[mode], () -> f.run(box));
                }
            }
        }
    }

    @Test public void cancellationWakesOwnerWithoutOwnerQueueOrNativeCalls() throws Exception {
        try (Fixture f = new Fixture(11)) {
            Thread ownerThread = Thread.currentThread();
            AtomicReference<FutureTask<Boolean>> cancel = new AtomicReference<>();
            AtomicReference<SshHostKeyReviewMailbox> ref = new AtomicReference<>();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket -> {
                FutureTask<Boolean> task = new FutureTask<>(() -> {
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (ownerThread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < end)
                        Thread.yield();
                    boolean waiting = ownerThread.getState() == Thread.State.TIMED_WAITING;
                    ref.get().cancel();
                    return waiting;
                });
                cancel.set(task);
                new Thread(task, "synthetic-cancel-ui").start();
            })) {
                ref.set(box);
                code("SSH_HOST_KEY_REVIEW_CANCELLED", () -> f.run(box));
                assertTrue(cancel.get().get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void shortWallBudgetExpiresEvenWhenRoleClockDoesNotAdvance() throws Exception {
        try (Fixture f = new Fixture(12)) {
            AtomicReference<SshHostKeyReviewMailbox.Ticket> ticket = new AtomicReference<>();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket::set, 10)) {
                code("SSH_HOST_KEY_REVIEW_EXPIRED", () -> f.run(box));
                assertEquals(1000, f.clock.value);
                assertFalse(box.resolve(ticket.get(), true));
            }
        }
    }

    @Test public void reentrantPresentationCannotStartASecondReview() throws Exception {
        try (Fixture f = new Fixture(14)) {
            AtomicReference<SshHostKeyReviewMailbox> ref = new AtomicReference<>();
            AtomicReference<SshHostKeyAuthority.Review> exact = new AtomicReference<>();
            try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { }, ticket -> {
                try { code("SSH_HOST_KEY_REVIEW", () -> ref.get().requireApproved(exact.get())); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertTrue(ref.get().resolve(ticket, true));
            })) {
                ref.set(box);
                f.run(review -> { exact.set(review); box.requireApproved(review); });
            }
        }
    }

    @Test public void waitingOwnerObservesRoleViewAndCallbackRevocationWithoutDecision() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            try (Fixture f = new Fixture(15 + mode)) {
                int change = mode;
                Thread ownerThread = Thread.currentThread();
                AtomicBoolean callbackCancelled = new AtomicBoolean();
                AtomicReference<FutureTask<Boolean>> revoke = new AtomicReference<>();
                AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
                try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> {
                    if (callbackCancelled.get()) throw new VaultException("SSH_CALLBACK_CANCELLED", "Synthetic cancel");
                }, ticket -> {
                    shown.set(ticket);
                    FutureTask<Boolean> task = new FutureTask<>(() -> {
                        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (ownerThread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < end)
                            Thread.yield();
                        boolean waiting = ownerThread.getState() == Thread.State.TIMED_WAITING;
                        if (change == 0) f.clock.value = f.role.deadline;
                        if (change == 1) f.view.active = false;
                        if (change == 2) callbackCancelled.set(true);
                        return waiting;
                    });
                    revoke.set(task);
                    new Thread(task, "synthetic-review-revoke").start();
                })) {
                    code(new String[]{"SSH_ROLE_EXPIRED", "SSH_FILE_VIEW", "SSH_CALLBACK_CANCELLED"}[mode],
                            () -> f.run(box));
                    assertTrue(revoke.get().get(2, TimeUnit.SECONDS));
                    assertFalse(box.resolve(shown.get(), true));
                }
            }
        }
    }

    @Test public void interruptionAndSinkErrorInvalidateTicketAndDismissExactlyOnce() throws Exception {
        for (boolean error : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(13)) {
                AtomicInteger dismissed = new AtomicInteger();
                AtomicReference<SshHostKeyReviewMailbox.Ticket> ticket = new AtomicReference<>();
                try (SshHostKeyReviewMailbox box = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { },
                        new SshHostKeyReviewMailbox.Sink() {
                            @Override public void present(SshHostKeyReviewMailbox.Ticket value) {
                                ticket.set(value);
                                if (error) throw new AssertionError("synthetic sink error");
                                Thread.currentThread().interrupt();
                            }
                            @Override public void dismiss(SshHostKeyReviewMailbox.Ticket value) { dismissed.incrementAndGet(); }
                        })) {
                    if (error) {
                        try { f.run(box); fail(); }
                        catch (AssertionError failure) { assertEquals("synthetic sink error", failure.getMessage()); }
                    } else {
                        code("SSH_HOST_KEY_REVIEW_CANCELLED", () -> f.run(box));
                        assertTrue(Thread.interrupted());
                    }
                    assertEquals(1, dismissed.get());
                    assertFalse(box.resolve(ticket.get(), true));
                }
            }
        }
    }
}
