package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class SshJumpFailurePriorityTest {
    @Test public void onlyCancelledRevokedViewBecomesCancellation() {
        VaultException revoked = new VaultException("SSH_FILE_VIEW", "revoked");
        assertEquals("SSH_CANCELLED", SshPasswordConnector.jumpCancellationDiagnostic(
                revoked, () -> true, () -> false).code);
        assertSame(revoked, SshPasswordConnector.jumpCancellationDiagnostic(revoked, () -> false, () -> false));
        for (String code : new String[] {"VAULT_LOCKED", "VAULT_CHANGED", "VAULT_EXPIRED", "SSH_TIMED_OUT"}) {
            VaultException original = new VaultException(code, "original lifecycle failure");
            assertSame(original, SshPasswordConnector.jumpCancellationDiagnostic(original, () -> true, () -> false));
        }
    }

    @Test public void unconfirmedTrustOutranksCancellation() {
        VaultException revoked = new VaultException("SSH_FILE_VIEW", "revoked");
        assertEquals("VAULT_COMMIT_UNCERTAIN", SshPasswordConnector.jumpCancellationDiagnostic(
                revoked, () -> true, () -> true).code);
        VaultException uncertain = new VaultException("VAULT_COMMIT_UNCERTAIN", "uncertain");
        assertSame(uncertain, SshPasswordConnector.jumpCancellationDiagnostic(uncertain, () -> true, () -> false));
    }

    @Test public void uncertaintyAppearingDuringCancellationIsNotReportedAsCancelled() throws Exception {
        AtomicBoolean pending = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch checkingCancellation = new CountDownLatch(1);
        CountDownLatch uncertaintyRecorded = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                if (!checkingCancellation.await(5, TimeUnit.SECONDS)) return;
                pending.set(true);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { uncertaintyRecorded.countDown(); }
        });
        writer.start();
        try {
            VaultException result = SshPasswordConnector.jumpCancellationDiagnostic(
                    new VaultException("SSH_FILE_VIEW", "revoked"), () -> {
                        checkingCancellation.countDown();
                        try {
                            if (!uncertaintyRecorded.await(5, TimeUnit.SECONDS)) throw new AssertionError("writer timed out");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt(); throw new AssertionError(failure);
                        }
                        return true;
                    }, () -> { reads.incrementAndGet(); return pending.get(); });
            assertEquals("VAULT_COMMIT_UNCERTAIN", result.code);
            assertEquals(2, reads.get());
        } finally { writer.join(5_000); }
    }
}
