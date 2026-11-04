package im.majo.ctos;

import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class SshKeyboardMailboxTest {
    private static final class Clock implements SshRoleContext.Clock {
        long now=1000; @Override public long now(){return now;}
    }
    private static SshRoleContext role(Clock clock) throws VaultException {
        byte[] nonce=new byte[32]; nonce[0]=1;
        return SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                123,0,"connection","target","host.invalid",22,"view",nonce,nonce,61000),()->true,clock);
    }

    @Test public void crossRoundCrossTicketReplayAndCountCannotQueueAnotherAnswer() throws Exception {
        Clock clock=new Clock(); SshRoleContext role=role(clock);
        AtomicReference<SshKeyboardMailbox> box=new AtomicReference<>();
        AtomicReference<SshKeyboardMailbox.Ticket> old=new AtomicReference<>();
        AtomicInteger dismissed=new AtomicInteger();
        SshKeyboardMailbox mailbox=new SshKeyboardMailbox(role,clock,()->{},new SshKeyboardMailbox.Sink(){
            @Override public void present(SshKeyboardMailbox.Ticket ticket) throws VaultException {
                assertFalse(box.get().respond("wrong",ticket.binding,List.of("")));
                assertFalse(box.get().respond(ticket.id,"0".repeat(64),List.of("")));
                if(old.get()!=null)assertFalse(box.get().respond(old.get().id,old.get().binding,List.of("")));
                try{box.get().respond(ticket.id,ticket.binding,List.of());fail("Wrong count");}
                catch(VaultException expected){assertEquals("SSH_KEYBOARD_CHALLENGE",expected.code);}
                assertTrue(box.get().respond(ticket.id,ticket.binding,List.of("")));
                assertFalse(box.get().respond(ticket.id,ticket.binding,List.of("replay")));
                old.set(ticket);
            }
            @Override public void dismiss(SshKeyboardMailbox.Ticket ticket){dismissed.incrementAndGet();}
        });box.set(mailbox);
        for(int round=1;round<=2;round++){
            SshKeyboardChallenge challenge=SshKeyboardChallenge.decode(SshKeyboardChallengeTest.frame(round,"","","?"));
            byte[] response=mailbox.await(challenge,()->fail("Ready answer must not poll"));
            challenge.requireResponse(response); VaultCodec.wipe(response);
        }
        assertEquals(2,dismissed.get());
        assertFalse(mailbox.respond(old.get().id,old.get().binding,List.of("")));
        mailbox.close();role.close();
    }

    @Test public void realOtherThreadCanAnswerButOwnerAloneMayAwait() throws Exception {
        Clock clock=new Clock();SshRoleContext role=role(clock);
        AtomicReference<SshKeyboardMailbox> box=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        AtomicReference<Thread> responder=new AtomicReference<>();
        SshKeyboardMailbox mailbox=new SshKeyboardMailbox(role,clock,()->{},ticket->{
            Thread thread=new Thread(()->{
                try{assertTrue(box.get().respond(ticket.id,ticket.binding,List.of("answer")));}
                catch(Throwable failure){error.set(failure);}
            });responder.set(thread);thread.start();
        });box.set(mailbox);
        SshKeyboardChallenge challenge=SshKeyboardChallenge.decode(SshKeyboardChallengeTest.frame(1,"","","?"));
        byte[] response=mailbox.await(challenge,()->{});responder.get().join();assertNull(error.get());
        challenge.requireResponse(response);VaultCodec.wipe(response);
        error.set(null);
        Thread wrong=new Thread(()->{try{mailbox.await(challenge,null);}
            catch(Throwable failure){error.set(failure);}});
        wrong.start();wrong.join();assertTrue(error.get() instanceof VaultException);
        assertEquals("SSH_CALLBACK_THREAD",((VaultException)error.get()).code);mailbox.close();role.close();
    }

    @Test public void cancelAfterQueueWipesPendingOwnedBytesAndNoAnswerEscapes() throws Exception {
        Clock clock=new Clock();SshRoleContext role=role(clock);
        AtomicReference<SshKeyboardMailbox> box=new AtomicReference<>();AtomicReference<byte[]> borrowed=new AtomicReference<>();
        SshKeyboardMailbox mailbox=new SshKeyboardMailbox(role,clock,()->{},ticket->{
            assertTrue(box.get().respond(ticket.id,ticket.binding,List.of("secret")));
            try{java.lang.reflect.Field field=ticket.getClass().getDeclaredField("response");field.setAccessible(true);
                borrowed.set((byte[])field.get(ticket));}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            box.get().cancel();
        });box.set(mailbox);
        try{mailbox.await(SshKeyboardChallenge.decode(SshKeyboardChallengeTest.frame(1,"","","?")),null);fail("Cancelled");}
        catch(VaultException expected){assertEquals("SSH_CANCELLED",expected.code);}
        assertArrayEquals(new byte[borrowed.get().length],borrowed.get());role.close();
    }

    @Test public void originalDeadlineAndLiveGuardAreRecheckedDuringWaitAndAtResponse() throws Exception {
        for(boolean expire:new boolean[]{false,true}){
            Clock clock=new Clock();SshRoleContext role=role(clock);AtomicReference<SshKeyboardMailbox> box=new AtomicReference<>();
            AtomicReference<SshKeyboardMailbox.Ticket> seen=new AtomicReference<>();AtomicInteger checked=new AtomicInteger();
            SshKeyboardMailbox mailbox=new SshKeyboardMailbox(role,clock,()->{
                if(checked.incrementAndGet()>3&&!expire)throw new VaultException("VAULT_CHANGED","changed");
            },seen::set);box.set(mailbox);
            try{mailbox.await(SshKeyboardChallenge.decode(SshKeyboardChallengeTest.frame(1,"","","?")),()->{
                if(expire)clock.now=61000;
            });fail("Expired or changed");}
            catch(VaultException expected){assertEquals(expire?"SSH_ROLE_EXPIRED":"VAULT_CHANGED",expected.code);}
            assertFalse(mailbox.respond(seen.get().id,seen.get().binding,List.of("late")));
            mailbox.close();role.close();
        }
    }

    @Test public void expiredOrForeignSourceResponseCannotBecomeAnOwnedWire() throws Exception {
        for(boolean expire:new boolean[]{false,true}){
            Clock clock=new Clock();SshRoleContext role=role(clock);AtomicReference<SshKeyboardMailbox> box=new AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
            SshKeyboardMailbox mailbox=new SshKeyboardMailbox(role,clock,()->{
                if(changed.get())throw new VaultException("VAULT_CHANGED","changed");
            },ticket->{
                if(expire)clock.now=61000;else changed.set(true);
                try{box.get().respond(ticket.id,ticket.binding,List.of("never encoded"));fail("Changed source must reject before response");}
                catch(VaultException expected){assertEquals(expire?"SSH_ROLE_EXPIRED":"VAULT_CHANGED",expected.code);}
            });box.set(mailbox);
            try{mailbox.await(SshKeyboardChallenge.decode(SshKeyboardChallengeTest.frame(1,"","","?")),null);fail("Changed request");}
            catch(VaultException expected){assertEquals(expire?"SSH_ROLE_EXPIRED":"VAULT_CHANGED",expected.code);}
            mailbox.close();role.close();
        }
    }
}
