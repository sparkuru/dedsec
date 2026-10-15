package im.majo.ctos;

import static org.junit.Assert.*;
import java.util.UUID;
import org.junit.Test;

public final class VaultOperationTest {
    private static final class Lease implements VaultSession.Revocation {
        boolean active = true;
        int revoked;
        @Override public boolean active() { return active; }
        @Override public void revoke() { if (active) { active = false; revoked++; } }
    }

    private static VaultOperation request() throws Exception {
        VaultEnvelope envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], new byte[32], new byte[0]);
        return new VaultOperation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_FILES,
                "ssh/config", "", "connection-1", envelope, 1, 99);
    }

    @Test public void cancellationAfterPublicationRevokesOnlyItsOwnedLease() throws Exception {
        VaultOperation first = request(), second = request(); Lease a = new Lease(), b = new Lease();
        first.attachRevocation(a); second.attachRevocation(b);
        first.cancel(); first.cancel();
        assertTrue(first.cancelled); assertFalse(a.active); assertEquals(1, a.revoked);
        assertTrue(b.active); assertFalse(second.cancelled);
    }

    @Test public void cancellationBeforeAttachmentRejectsAndPreservesCallerOwnership() throws Exception {
        VaultOperation request = request(); Lease lease = new Lease(); request.cancel();
        try { request.attachRevocation(lease); fail(); }
        catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
        assertTrue(lease.active); lease.revoke(); assertEquals(1, lease.revoked);
    }

    @Test public void requestCannotRebindItsRevocationToAnotherLease() throws Exception {
        VaultOperation request = request(); Lease a = new Lease(), b = new Lease();
        request.attachRevocation(a); a.revoke();
        try { request.attachRevocation(b); fail(); }
        catch (VaultException failure) { assertEquals("BUSY", failure.code); }
        request.cancel(); assertTrue(b.active); assertEquals(1, a.revoked);
    }
}
