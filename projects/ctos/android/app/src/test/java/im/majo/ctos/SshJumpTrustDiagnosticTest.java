package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class SshJumpTrustDiagnosticTest {
    @Test public void exactTrustRefusalUsesOnlyTheJumpSpecificCode() throws Exception {
        VaultException original = new VaultException("SSH_SESSION_CANCELLED", "internal callback cancellation");
        assertEquals("SSH_JUMP_HOST_TRUST_REQUIRED", SshPasswordConnector.jumpTrustDiagnostic(
                original, () -> false, () -> false, () -> { }, () -> true).code);
        assertSame(original, SshPasswordConnector.jumpTrustDiagnostic(
                original, () -> false, () -> false, () -> { }, () -> false));
        VaultException changed = new VaultException("SSH_HOST_CHANGED_POLICY", "changed");
        assertEquals("SSH_JUMP_HOST_TRUST_REQUIRED", SshPasswordConnector.jumpTrustDiagnostic(
                changed, () -> false, () -> false, () -> { }, () -> true).code);
        VaultException unrelated = new VaultException("SSH_SESSION_STEP", "unrelated");
        assertSame(unrelated, SshPasswordConnector.jumpTrustDiagnostic(
                unrelated, () -> false, () -> false, () -> { }, () -> true));
        VaultException unlocated = new VaultException("SSH_HOST_KEY_CHANGED", "unlocated host key");
        assertEquals("SSH_JUMP_HOST_TRUST_FAILED", SshPasswordConnector.jumpTrustDiagnostic(
                unlocated, () -> false, () -> false, () -> { }, () -> false).code);
    }

    @Test public void realCancellationAndUncertainWritesOutrankTrustDiagnostic() throws Exception {
        VaultException original = new VaultException("SSH_SESSION_CANCELLED", "cancelled");
        assertEquals("SSH_CANCELLED", SshPasswordConnector.jumpTrustDiagnostic(
                original, () -> true, () -> false, () -> { }, () -> true).code);
        assertEquals("VAULT_COMMIT_UNCERTAIN", SshPasswordConnector.jumpTrustDiagnostic(
                original, () -> false, () -> true, () -> { }, () -> true).code);
        VaultException uncertain = new VaultException("VAULT_COMMIT_UNCERTAIN", "uncertain");
        assertSame(uncertain, SshPasswordConnector.jumpTrustDiagnostic(
                uncertain, () -> false, () -> false, () -> { }, () -> true));
    }

    @Test public void lifecycleFailureBeforePublicationOutranksTrustDiagnostic() throws Exception {
        for (String code : new String[] {"VAULT_LOCKED", "VAULT_CHANGED", "VAULT_EXPIRED", "SSH_TIMED_OUT"}) {
            try {
                SshPasswordConnector.jumpTrustDiagnostic(new VaultException("SSH_SESSION_CANCELLED", "cancelled"),
                        () -> false, () -> false, () -> { throw new VaultException(code, "lifecycle"); }, () -> true);
                fail("lifecycle failure was replaced");
            } catch (VaultException expected) { assertEquals(code, expected.code); }
        }
    }

    @Test public void cancellationOrUncertaintyAppearingDuringEvidenceCheckWins() throws Exception {
        VaultException original = new VaultException("SSH_SESSION_CANCELLED", "cancelled");
        AtomicBoolean cancelled = new AtomicBoolean();
        assertEquals("SSH_CANCELLED", SshPasswordConnector.jumpTrustDiagnostic(original, cancelled::get, () -> false,
                () -> { }, () -> { cancelled.set(true); return true; }).code);
        AtomicBoolean uncertain = new AtomicBoolean();
        AtomicInteger checks = new AtomicInteger();
        assertEquals("VAULT_COMMIT_UNCERTAIN", SshPasswordConnector.jumpTrustDiagnostic(original,
                () -> false, uncertain::get, () -> { }, () -> {
                    if (checks.incrementAndGet() == 1) uncertain.set(true);
                    return true;
                }).code);
    }

    @Test public void actualOwnerCancellationWinsBothRootAndChildTrustFailures() throws Exception {
        for (String code : new String[] {"SSH_HOST_CHANGED_POLICY", "SSH_HOSTKEY_UNKNOWN", "SSH_SESSION_CANCELLED",
                "SSH_CALLBACK_CANCELLED", "SSH_HOST_KEY_CHANGED", "SSH_JUMP_EFFECT_GUARD"}) {
            AtomicBoolean cancelled = new AtomicBoolean();
            CountDownLatch readingEvidence = new CountDownLatch(1), stopCompleted = new CountDownLatch(1);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread stopper = new Thread(() -> {
                try {
                    if (!readingEvidence.await(5, TimeUnit.SECONDS)) throw new AssertionError("evidence not reached");
                    cancelled.set(true);
                } catch (Throwable failure) { workerFailure.set(failure); }
                finally { stopCompleted.countDown(); }
            });
            stopper.start();
            try {
                VaultException result = SshPasswordConnector.jumpTrustDiagnostic(
                        new VaultException(code, "trust failure"), cancelled::get, () -> false, () -> { }, () -> {
                            readingEvidence.countDown();
                            try {
                                if (!stopCompleted.await(5, TimeUnit.SECONDS)) throw new AssertionError("stop timed out");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt(); throw new AssertionError(failure);
                            }
                            return false;
                        });
                assertEquals("SSH_CANCELLED", result.code);
            } finally { stopper.join(5_000); }
            if (workerFailure.get() != null) throw new AssertionError(workerFailure.get());
        }
    }

    @Test public void refusedEvidenceStillChecksForLifecycleFailure() throws Exception {
        for (int failAt = 1; failAt <= 2; failAt++) {
            AtomicBoolean locked = new AtomicBoolean();
            AtomicInteger attempts = new AtomicInteger();
            final int selectedAttempt = failAt;
            try {
                SshPasswordConnector.jumpTrustDiagnostic(new VaultException("SSH_SESSION_CANCELLED", "internal"),
                        () -> false, () -> false,
                        () -> { if (locked.get()) throw new VaultException("VAULT_LOCKED", "locked"); }, () -> {
                            if (attempts.incrementAndGet() == selectedAttempt) { locked.set(true); return false; }
                            return true;
                        });
                fail("a lock during evidence validation was hidden");
            } catch (VaultException expected) { assertEquals("VAULT_LOCKED", expected.code); }
            assertEquals(selectedAttempt, attempts.get());
        }
    }

    @Test public void unrelatedLifecycleFailureIsNeverReplacedByCancellation() throws Exception {
        for (String code : new String[] {"VAULT_LOCKED", "VAULT_CHANGED", "VAULT_EXPIRED", "SSH_TIMED_OUT"}) {
            VaultException original = new VaultException(code, "original lifecycle failure");
            assertSame(original, SshPasswordConnector.jumpTrustDiagnostic(
                    original, () -> true, () -> false, () -> { }, () -> true));
        }
    }

    @Test public void uncertainPublicationOutranksLifecycleFailureDuringLiveCheck() throws Exception {
        AtomicBoolean uncertain = new AtomicBoolean();
        try {
            SshPasswordConnector.jumpTrustDiagnostic(new VaultException("SSH_HOSTKEY_UNKNOWN", "unknown"),
                    () -> false, uncertain::get, () -> {
                        uncertain.set(true); throw new VaultException("VAULT_LOCKED", "locked");
                    }, () -> true);
            fail("uncertain publication was hidden");
        } catch (VaultException expected) { assertEquals("VAULT_COMMIT_UNCERTAIN", expected.code); }
    }
}
