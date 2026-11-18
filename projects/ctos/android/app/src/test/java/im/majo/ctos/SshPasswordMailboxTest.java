package im.majo.ctos;

import static org.junit.Assert.*;
import static im.majo.ctos.SshPasswordMailbox.Choice.ONCE;
import static im.majo.ctos.SshPasswordMailbox.Purpose.PASSWORD;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class SshPasswordMailboxTest {
    private static final class Clock implements SshRoleContext.Clock {
        volatile long now = 1000;
        @Override public long now() { return now; }
    }

    private static SshRoleContext role(Clock clock) throws VaultException {
        byte[] nonce = new byte[32]; nonce[0] = 1;
        return SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                123, 0, "connection", "target", "host.invalid", 22, "view", nonce, nonce, 61000),
                () -> true, clock);
    }

    @Test public void onlyMatchingTicketMaySupplyOneOwnedAnswer() throws Exception {
        Clock clock = new Clock(); SshRoleContext role = role(clock);
        AtomicReference<SshPasswordMailbox> box = new AtomicReference<>();
        AtomicReference<SshPasswordMailbox.Ticket> seen = new AtomicReference<>();
        AtomicInteger dismissed = new AtomicInteger();
        byte[] original = "synthetic-password".getBytes(StandardCharsets.UTF_8);
        SshPasswordMailbox mailbox = new SshPasswordMailbox(role, clock, () -> {}, new SshPasswordMailbox.Sink() {
            @Override public void present(SshPasswordMailbox.Ticket ticket) throws VaultException {
                seen.set(ticket);
                assertFalse(box.get().respond("wrong", ticket.binding, original, ONCE));
                assertFalse(box.get().respond(ticket.id, "0".repeat(64), original, ONCE));
                try { box.get().respond(ticket.id, ticket.binding, new byte[0], ONCE); fail("empty input"); }
                catch (VaultException expected) { assertNotNull(expected.code); }
                assertTrue(box.get().respond(ticket.id, ticket.binding, original, ONCE));
                assertFalse(box.get().respond(ticket.id, ticket.binding, original, ONCE));
                original[0] = 0;
            }
            @Override public void dismiss(SshPasswordMailbox.Ticket ticket) { dismissed.incrementAndGet(); }
        }, PASSWORD);
        box.set(mailbox);
        SshPasswordMailbox.Response answer = mailbox.await(() -> fail("ready answer must not poll"), false);
        assertArrayEquals("synthetic-password".getBytes(StandardCharsets.UTF_8), answer.bytes);
        answer.close();
        assertFalse(mailbox.respond(seen.get().id, seen.get().binding, original, ONCE));
        try { mailbox.await(null, false); fail("ticket replay"); }
        catch (VaultException expected) { assertEquals("SSH_AUTH_SELECTION", expected.code); }
        assertEquals(1, dismissed.get()); mailbox.close(); role.close();
    }

    @Test public void crossThreadResponseIsAllowedButOnlyOwnerAwaits() throws Exception {
        Clock clock = new Clock(); SshRoleContext role = role(clock);
        AtomicReference<SshPasswordMailbox> box = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Thread> responder = new AtomicReference<>();
        SshPasswordMailbox mailbox = new SshPasswordMailbox(role, clock, () -> {}, ticket -> {
            Thread thread = new Thread(() -> {
                try { assertTrue(box.get().respond(ticket.id, ticket.binding, new byte[] {65}, ONCE)); }
                catch (Throwable failure) { error.set(failure); }
            });
            responder.set(thread); thread.start();
        }, PASSWORD);
        box.set(mailbox);
        SshPasswordMailbox.Response answer = mailbox.await(() -> {}, false); responder.get().join();
        assertNull(error.get()); assertArrayEquals(new byte[] {65}, answer.bytes); answer.close();
        Thread wrong = new Thread(() -> {
            try { mailbox.await(null, false); } catch (Throwable failure) { error.set(failure); }
        });
        wrong.start(); wrong.join(); assertTrue(error.get() instanceof VaultException);
        assertEquals("SSH_CALLBACK_THREAD", ((VaultException)error.get()).code);
        mailbox.close(); role.close();
    }

    @Test public void savedChoiceRequiresEligibleTicketAndNoBytes() throws Exception {
        for (boolean savedAvailable : new boolean[] {false, true}) {
            Clock clock = new Clock();
            try (SshRoleContext role = role(clock)) {
                AtomicReference<SshPasswordMailbox> box = new AtomicReference<>();
                try (SshPasswordMailbox mailbox = new SshPasswordMailbox(role, clock, () -> {}, ticket -> {
                    assertEquals(savedAvailable, ticket.savedAvailable);
                    assertEquals(SshPasswordMailbox.Purpose.KEY_PASSPHRASE, ticket.purpose);
                    try {
                        box.get().respond(ticket.id, ticket.binding, new byte[] {65}, SshPasswordMailbox.Choice.SAVED);
                        fail("Saved choice carried bytes");
                    } catch (VaultException expected) { assertEquals("PARAMETERS", expected.code); }
                    if (!savedAvailable) {
                        try {
                            box.get().respond(ticket.id, ticket.binding, new byte[0], SshPasswordMailbox.Choice.SAVED);
                            fail("Saved choice used an ineligible ticket");
                        } catch (VaultException expected) { assertEquals("PARAMETERS", expected.code); }
                    }
                    assertTrue(box.get().respond(ticket.id, ticket.binding,
                            savedAvailable ? new byte[0] : new byte[] {65},
                            savedAvailable ? SshPasswordMailbox.Choice.SAVED : SshPasswordMailbox.Choice.SAVE));
                }, SshPasswordMailbox.Purpose.KEY_PASSPHRASE)) {
                    box.set(mailbox);
                    try (SshPasswordMailbox.Response answer = mailbox.await(null, savedAvailable)) {
                        assertEquals(savedAvailable ? SshPasswordMailbox.Choice.SAVED : SshPasswordMailbox.Choice.SAVE,
                                answer.choice);
                        assertArrayEquals(savedAvailable ? new byte[0] : new byte[] {65}, answer.bytes);
                    }
                }
            }
        }
    }

    @Test public void cancelAfterQueueWipesOwnedAnswer() throws Exception {
        Clock clock = new Clock(); SshRoleContext role = role(clock);
        AtomicReference<SshPasswordMailbox> box = new AtomicReference<>();
        AtomicReference<byte[]> borrowed = new AtomicReference<>();
        SshPasswordMailbox mailbox = new SshPasswordMailbox(role, clock, () -> {}, ticket -> {
            assertTrue(box.get().respond(ticket.id, ticket.binding, new byte[] {65}, ONCE));
            try {
                java.lang.reflect.Field field = ticket.getClass().getDeclaredField("response");
                field.setAccessible(true); borrowed.set(((SshPasswordMailbox.Response)field.get(ticket)).bytes);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            box.get().cancel();
        }, PASSWORD);
        box.set(mailbox);
        try { mailbox.await(null, false); fail("cancelled"); }
        catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
        assertArrayEquals(new byte[borrowed.get().length], borrowed.get()); role.close();
    }

    @Test public void expiryOrChangedSessionRejectsAndWipesBeforeHandoff() throws Exception {
        for (boolean expire : new boolean[] {false, true}) {
            Clock clock = new Clock(); SshRoleContext role = role(clock);
            AtomicReference<SshPasswordMailbox> box = new AtomicReference<>();
            AtomicReference<SshPasswordMailbox.Ticket> seen = new AtomicReference<>();
            AtomicBoolean changed = new AtomicBoolean();
            SshPasswordMailbox mailbox = new SshPasswordMailbox(role, clock, () -> {
                if (changed.get()) throw new VaultException("VAULT_CHANGED", "changed");
            }, ticket -> {
                seen.set(ticket);
                if (expire) clock.now = 61000; else changed.set(true);
                try { box.get().respond(ticket.id, ticket.binding, new byte[] {65}, ONCE); fail("invalid source"); }
                catch (VaultException expected) { assertEquals(expire ? "SSH_ROLE_EXPIRED" : "VAULT_CHANGED", expected.code); }
            }, PASSWORD);
            box.set(mailbox);
            try { mailbox.await(null, false); fail("expired or changed"); }
            catch (VaultException expected) { assertEquals(expire ? "SSH_ROLE_EXPIRED" : "VAULT_CHANGED", expected.code); }
            assertFalse(mailbox.respond(seen.get().id, seen.get().binding, new byte[] {65}, ONCE));
            mailbox.close(); role.close();
        }
    }
}
