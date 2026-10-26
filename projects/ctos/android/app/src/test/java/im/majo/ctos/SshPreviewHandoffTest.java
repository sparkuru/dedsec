package im.majo.ctos;

import static org.junit.Assert.*;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class SshPreviewHandoffTest {
    @Test public void changedOriginNeverPublishesBusyOwnerOrQueuesBody() throws Exception {
        AtomicBoolean attached = new AtomicBoolean(), queued = new AtomicBoolean();
        byte[] owned = {1, 2, 3};
        try {
            SshPreviewHandoff.submit(
                    () -> { throw new VaultException("VAULT_CHANGED", "Changed origin"); },
                    () -> attached.set(true), () -> queued.set(true), () -> VaultCodec.wipe(owned));
            fail("Changed origin must reject the preview");
        } catch (VaultException failure) { assertEquals("VAULT_CHANGED", failure.code); }
        assertFalse(attached.get()); assertFalse(queued.get());
        assertArrayEquals(new byte[3], owned);
    }

    @Test public void rejectedOwnerSubmissionRetiresPublishedHandoffAndWipesBody() throws Exception {
        AtomicBoolean busy = new AtomicBoolean(), originalHandedOff = new AtomicBoolean();
        AtomicInteger retired = new AtomicInteger();
        byte[] owned = {4, 5, 6};
        try {
            SshPreviewHandoff.submit(() -> { },
                    () -> { busy.set(true); originalHandedOff.set(true); },
                    () -> { throw new RejectedExecutionException("Owner stopped"); },
                    () -> { busy.set(false); retired.incrementAndGet(); VaultCodec.wipe(owned); });
            fail("Rejected owner must fail the preview");
        } catch (RejectedExecutionException expected) { }
        assertFalse(busy.get()); assertTrue(originalHandedOff.get());
        assertEquals(1, retired.get()); assertArrayEquals(new byte[3], owned);
    }

    @Test public void successfulSubmissionTransfersBodyCleanupToOwner() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        AtomicBoolean aborted = new AtomicBoolean();
        byte[] owned = {7, 8, 9};
        SshPreviewHandoff.submit(() -> assertEquals(0, sequence.getAndIncrement()),
                () -> assertEquals(1, sequence.getAndIncrement()),
                () -> assertEquals(2, sequence.getAndIncrement()), () -> aborted.set(true));
        assertEquals(3, sequence.get()); assertFalse(aborted.get());
        assertArrayEquals(new byte[] {7, 8, 9}, owned);
        VaultCodec.wipe(owned);
    }
}
