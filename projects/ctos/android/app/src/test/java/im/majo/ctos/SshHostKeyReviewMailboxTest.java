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
            byte[] roleNonce = new byte[32], connection = new byte[32];
            Arrays.fill(roleNonce, (byte) nonce);
            Arrays.fill(connection, (byte) 7);
            role = SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                    42, 0, "mailbox-fixture", "target:test@fixture.invalid:22", "fixture.invalid", 22,
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

    @Test public void separateUiThreadApprovesExactMetadataOnceAndTicketCannotBeReused() throws Exception {
        try (Fixture f = new Fixture(1)) {
            AtomicReference<SshHostKeyReviewMailbox> owner = new AtomicReference<>();
            AtomicReference<SshHostKeyReviewMailbox.Ticket> shown = new AtomicReference<>();
            AtomicReference<FutureTask<Boolean>> answer = new AtomicReference<>();
            AtomicInteger dismissed = new AtomicInteger();
            try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(f.role, f.clock, () -> { },
                    new SshHostKeyReviewMailbox.Sink() {
                        @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) {
                            shown.set(ticket);
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
