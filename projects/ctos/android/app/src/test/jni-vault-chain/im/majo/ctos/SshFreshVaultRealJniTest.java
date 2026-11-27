package im.majo.ctos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Host-only: real Java/vault/JNI, with in-memory storage and synthetic SSH peers. */
public final class SshFreshVaultRealJniTest {
    private static final String ROOT = "ssh/config";
    private static final String HOSTS = "ssh/known_hosts";
    private static final String CONNECTION = "fixture-connection";
    private static final String USER = " 用户😀 ";

    private static native long now();
    private static native int pid();
    private static native int openPeers() throws IOException;
    private static native byte[] hostfile(int mode) throws IOException;
    private static native String targetFingerprint() throws IOException;
    private static native void pumpPeers() throws IOException;
    private static native int[] counters();
    private static native long wireChannelBytes();
    private static native long earlyReplyDenials();
    private static native long pendingKexDrains();
    private static native void earlyTargetReply(boolean success) throws IOException;
    private static native void captureChildOpen() throws IOException;
    private static native int[] finishChildOpen() throws IOException;
    private static native boolean fdClosed(int fd);
    private static native void armEffectCancellation(long callback, int phase) throws IOException;
    private static native void targetNoneSuccess() throws IOException;
    private static native void trackTargetCallback(long callback) throws IOException;
    private static native void armNativeInvalidation(long token, int phase, int kind) throws IOException;
    private static native void armJavaInvalidation(int phase) throws IOException;
    private static native void armEffectDeadline(long deadline) throws IOException;
    private static native void armJumpDeadline(long deadline) throws IOException;
    private static native void armProvisionalFixture(Object wrongReceiver) throws IOException;
    private static native void armPendingInputFixture() throws IOException;
    private static native int queuePendingFixtureExtension(int chunk) throws IOException;
    private static native boolean pendingFixturePeerReady() throws IOException;
    private static native int pendingFixtureBytes() throws IOException;
    private static native void armPendingFixturePeekFailure() throws IOException;
    private static native void armProvisionalConsumption(int stage) throws IOException;
    private static native void armEffectInvalidationFailure() throws IOException;
    private static native void armLeaseDestroyFailure(int mode) throws IOException;
    private static native int leaseDestroyCalls();
    private static native void armLegacyEffectInvalidationFailure() throws IOException;
    private static native void armCleanupBeginFailure(int countdown) throws IOException;
    private static native void armCleanupBeginUnknown(int when) throws IOException;
    private static native void armCleanupEndFailure(int when) throws IOException;
    private static native void armCleanupEndFailureAfter(int when, int countdown) throws IOException;
    private static native void beginOtherInvalidator();
    private static native void endOtherInvalidator();
    private static native void acquireSharedTestEffect() throws IOException;
    private static native void releaseSharedTestEffect() throws IOException;
    private static native boolean effectAvailable();
    private static native int activeEffects();
    private static native void closeFd(int fd) throws IOException;
    private static native void closePeers();
    private static AppSecrets effectVault;
    private static SshSharedPublicView effectShared;
    private static SshSessionOwner.Cancellation effectCancellation;
    private static int javaInvalidation;
    private static SshManagedKnownHostsProvider consumeProvider;
    private static SshManagedKnownHostsProvider.ChildTrustSnapshot consumeSource;
    private static SshNativeCallbackAdapter consumeCallback;
    private static boolean preconsumeDone;
    private static boolean replaceCandidate;
    private static int provisionalCleanupFault;
    private static Object mutatedCandidate;
    private static final byte[][] consumedOwned = new byte[4][];
    private static Runnable transferRaceClockHook;
    private static Thread transferRaceClockOwner;

    private static boolean provisionalMode(int mode) {
        return mode >= 27 && mode <= 42 || mode >= 60 && mode <= 109;
    }

    private static boolean isolatedUncertainMode(int mode) {
        return mode >= 46 && mode <= 54 || mode >= 56 && mode <= 59
                || mode == 72 || mode == 73 || mode == 75 || mode == 76 || mode == 77 || mode == 78
                || mode >= 111 && mode <= 115 || mode >= 118 && mode <= 120
                || mode == 127 || mode == 128;
    }

    private static void invalidateAtEffect() throws Exception {
        if (javaInvalidation == 1) effectVault.session.lock();
        else if (javaInvalidation == 2) effectVault.attachSshValidator(manifest -> { });
        else if (javaInvalidation == 3) {
            java.lang.reflect.Field field = AppSecrets.class.getDeclaredField("sshSecretReadWork");
            field.setAccessible(true);
            Object work = field.get(effectVault);
            require(work != null, "selected credential read is unavailable");
            java.lang.reflect.Field operationField = work.getClass().getDeclaredField("operation");
            operationField.setAccessible(true);
            VaultOperation target = (VaultOperation)operationField.get(work);
            require(target != null && target.purpose == VaultOperation.Purpose.SSH_CREDENTIAL && !target.cancelled,
                    "selected credential operation is unavailable");
            target.cancel();
        } else if (javaInvalidation == 4) effectShared.cancel();
        else if (javaInvalidation == 5) publishStop(effectCancellation);
        else throw new AssertionError("unknown Java invalidator");
    }

    /* Exercise the real first STOP operation before native invalidation begins. */
    private static void publishStop(SshSessionOwner.Cancellation cancellation) throws Exception {
        java.lang.reflect.Method mark = SshSessionOwner.Cancellation.class.getDeclaredMethod("markStop", int.class);
        mark.setAccessible(true);
        require(cancellation != null && !cancellation.stopRequested() && effectAvailable(),
                "STOP fixture did not start with a live native generation");
        mark.invoke(cancellation, 0);
        require(cancellation.stopRequested() && effectAvailable(),
                "STOP was not published before native invalidation");
    }

    private static final class Store implements AppSecrets.Store {
        final byte[] encrypted;
        int commits;
        AppSecrets observedVault;
        Runnable beforeReceiver;
        int releaseReads;
        boolean cancelledBeforeReceiver;
        Store(byte[] encrypted) { this.encrypted = encrypted; }
        @Override public byte[] read() {
            if (beforeReceiver != null) {
                try {
                    java.lang.reflect.Field field = AppSecrets.class.getDeclaredField("sshSecretReadWork");
                    field.setAccessible(true);
                    Object work = field.get(observedVault);
                    if (work != null) {
                        java.lang.reflect.Field operation = work.getClass().getDeclaredField("operation");
                        operation.setAccessible(true);
                        if (operation.get(work) != null && ++releaseReads == 2) {
                            Runnable action = beforeReceiver;
                            beforeReceiver = null;
                            action.run();
                            cancelledBeforeReceiver = true;
                        }
                    }
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
            return encrypted.clone();
        }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] bytes, byte[] quick, AppSecrets.EffectGuard guard) {
            commits++;
            throw new AssertionError("unexpected durable trust write");
        }
    }

    private static final class Clock implements SshRoleContext.Clock, VaultSession.Clock {
        @Override public long now() {
            Runnable hook = transferRaceClockHook;
            if (hook != null && Thread.currentThread() == transferRaceClockOwner) {
                StackTraceElement[] frames = Thread.currentThread().getStackTrace();
                boolean transfer = false, afterCandidateCheck = false;
                for (int index = 0; index < frames.length; index++) {
                    StackTraceElement frame = frames[index];
                    if (frame.getClassName().equals(
                            SshManagedKnownHostsProvider.FixtureCandidateReservation.class.getName())
                            && frame.getMethodName().equals("transferFor")) transfer = true;
                    if (index + 1 < frames.length && frame.getClassName().equals(SshRoleContext.class.getName())
                            && frame.getMethodName().equals("requireActive")
                            && frames[index + 1].getClassName().equals(SshNativeCallbackAdapter.class.getName())
                            && frames[index + 1].getMethodName().equals("requireSignedNewFixtureLive"))
                        afterCandidateCheck = true;
                }
                if (transfer && afterCandidateCheck) {
                    transferRaceClockHook = null;
                    hook.run();
                }
            }
            return SshFreshVaultRealJniTest.now();
        }
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte)value);
        return result;
    }

    private static Store encryptedStore(byte[] hosts, char[] password) throws Exception {
        byte[] salt = Arrays.copyOf(filled(7), 16), key = null, plain = null;
        byte[] secret = "selected-password".getBytes(StandardCharsets.UTF_8);
        SshSavedPassword saved = new SshSavedPassword(new SshPasswordProfile("example.invalid", 22, USER));
        try (VaultManifest manifest = new VaultManifest(List.of(new VaultManifest.Entry(
                VaultManifest.EntryType.SSH_PASSWORD, saved.entryId, saved.target, "fixture", "", 0, 0, secret)), List.of(
                new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, "config", ROOT,
                        "config identity", "Host *\n".getBytes(StandardCharsets.UTF_8), List.of(HOSTS)),
                new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, "hosts", HOSTS,
                        "hosts identity", hosts, List.of())), List.of(), List.of(saved.account()))) {
            key = VaultEnvelope.derive(password, salt);
            plain = manifest.encode();
            return new Store(VaultEnvelope.encrypt(UUID.randomUUID(), 7, salt, key, plain).encode());
        } finally { VaultCodec.wipe(key); VaultCodec.wipe(plain); VaultCodec.wipe(salt); VaultCodec.wipe(secret); }
    }

    private static SshNativeRoleBootstrap.Issued issue(SshRoleRegistry registry, SshPublicFileLease view,
            Clock clock, SshRoleContext.Program program, int hop, String target, String host,
            long deadline, byte[] binding) throws Exception {
        SshRoleContext.Bootstrap bootstrap = new SshRoleContext.Bootstrap(program, pid(), hop,
                CONNECTION, target, host, 22, "fixture-view", filled(hop + 1), binding,
                deadline, "chain:fixture");
        SshNativeRoleBootstrap.Unissued pending = SshNativeRoleBootstrap.prepare(bootstrap);
        try {
            pending.open();
            return pending.issue(registry, view, clock);
        } catch (Exception | Error failure) {
            try { pending.closeUnissued(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static SshNativeCallbackAdapter callback(SshNativeRoleBootstrap.Issued role,
            SshRoleRegistry registry, SshHostKeyAuthority authority, Clock clock, AppSecrets vault,
            SshManagedKnownHostsProvider trust, boolean savedPassword,
            SshSessionOwner.Cancellation cancellation) throws VaultException {
        SshManagedProvider managed = new SshManagedProvider(new SshCredentialBridge(vault, authority, clock),
                clock, trust, trust, null);
        SshNativeCallbackAdapter result = SshNativeCallbackAdapter.bind(role, registry, authority, clock,
                managed, trust.nativeCandidateHandoff(), cancellation);
        SshAuthenticationSelection selection = savedPassword
                ? SshAuthenticationSelection.password(role.role, USER,
                        new SshSavedPassword(new SshPasswordProfile(role.role.host, role.role.port, USER)).entryId, clock)
                : SshAuthenticationSelection.keyboardInteractive(role.role, USER, clock)
                        .withAutomaticDiscovery(SshNativeSession.AuthDiscoveryStatus.KEYBOARD_INTERACTIVE
                                | SshNativeSession.AuthDiscoveryStatus.PASSWORD, clock);
        result.selectAuthentication(selection);
        return result;
    }

    private static void rootKex(SshNativeSession parent) throws Exception {
        for (int count = 0; count < 200_000; count++) {
            pumpPeers();
            if (parent.step().complete) { pumpPeers(); return; }
        }
        throw new AssertionError("root KEX stalled");
    }

    private static void rootAuthentication(SshNativeSession parent) throws Exception {
        parent.beginUserauthService();
        for (int count = 0; !parent.userauthServiceReady(); count++) {
            require(count < 200_000, "root service stalled");
            pumpPeers(); parent.step();
        }
        parent.beginAuthDiscovery();
        while (parent.authDiscoveryResult().result == SshNativeSession.AuthDiscoveryResult.PENDING) {
            pumpPeers(); parent.step();
        }
        require(parent.authDiscoveryResult().result == SshNativeSession.AuthDiscoveryResult.NONE_SUCCESS,
                "synthetic root none authentication did not succeed");
    }

    private static void openJump(SshNativeSession parent, SshNativeSession.JumpChildOwner childOwner, long deadline)
            throws Exception {
        parent.beginJump(childOwner, deadline);
        for (int count = 0; count < 200_000; count++) {
            if (parent.jumpStatus().phase == SshNativeSession.JumpPhase.OPEN) return;
            pumpPeers(); parent.step();
        }
        throw new AssertionError("jump channel stalled");
    }

    private static void childKex(int mode, SshNativeSession parent, SshNativeSession child,
            SshNativeCallbackAdapter callback) throws Exception {
        VaultException rejected = null;
        boolean completed = false;
        for (int count = 0; count < 200_000; count++) {
            pumpPeers(); parent.step(); child.pumpJumpRelay();
            try { completed = child.step().complete; }
            catch (VaultException failure) { rejected = failure; break; }
            if (completed) break;
        }
        if (mode == 0 || mode >= 3) {
            require(completed && rejected == null && callback.hostProof() != null && callback.kexProof() != null,
                    "known target did not complete actual Java/JNI host KEX");
            require(targetFingerprint().equals(callback.hostProof().fingerprint),
                    "known target proof does not match the synthetic peer's offered key");
            if (mode == 0) {
                try { child.beginUserauthService(); throw new AssertionError("fresh authentication was admitted"); }
                catch (VaultException expected) {
                    require("SSH_JUMP_EFFECT_GUARD".equals(expected.code), "wrong fresh auth refusal: " + expected.code);
                }
            }
        } else {
            require(!completed && rejected != null, "new/changed target was not rejected");
            require("SSH_JUMP_EFFECT_GUARD".equals(callback.lastFailureCode()),
                    "wrong candidate refusal: " + callback.lastFailureCode());
            require(callback.reportableJumpTrustRefusal(), "exact child trust refusal was not reportable");
        }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void setField(Object object, String name, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    /* Invoked only by the test-native hook during the exact child dispatch. */
    private static void consumeProvisionalCandidate() throws Exception {
        Object pending = field(consumeCallback, "pendingProvisionalFixture");
        SshHostKeyAuthority.Evidence evidence = (SshHostKeyAuthority.Evidence)field(pending, "evidence");
        Object candidate = field(consumeProvider, "candidate");
        require(candidate != null && field(pending, "candidateIdentity") == field(candidate, "provisionalFixtureIdentity"),
                "the fixture candidate is unavailable for early consumption");
        consumedOwned[0] = (byte[])field(candidate, "content");
        consumedOwned[1] = evidence.baseDigest;
        consumedOwned[2] = evidence.replacementDigest;
        if (replaceCandidate) {
            byte[] replacementBody = consumedOwned[0].clone();
            try {
                SshManagedKnownHostsProvider.KnownHostsCandidate replacement =
                        SshManagedKnownHostsProvider.candidateFor(consumeSource.receipt(), evidence,
                                "candidate-child", replacementBody);
                consumedOwned[3] = (byte[])field(replacement, "content");
                mutatedCandidate = replacement;
                java.lang.reflect.Field field = consumeProvider.getClass().getDeclaredField("candidate");
                field.setAccessible(true);
                synchronized (consumeProvider) { field.set(consumeProvider, replacement); }
                ((SshManagedKnownHostsProvider.KnownHostsCandidate)candidate).close();
            } finally { VaultCodec.wipe(replacementBody); }
        } else {
            SshTrustProposal proposal = consumeProvider.proposalFor(evidence);
            consumedOwned[3] = (byte[])field(proposal, "content");
        }
        require(!zeroed(consumedOwned[3]), "early candidate mutation was not created");
        preconsumeDone = true;
        if (provisionalCleanupFault == 1) armCleanupBeginFailure(1);
        else if (provisionalCleanupFault == 2) armCleanupBeginUnknown(1);
        else if (provisionalCleanupFault == 3) {
            armCleanupBeginFailure(1);
            armLegacyEffectInvalidationFailure();
        } else if (provisionalCleanupFault == 4) {
            armCleanupBeginFailure(1);
            armCleanupEndFailure(1);
        }
    }

    private static boolean zeroed(byte[] bytes) {
        if (bytes == null) return false;
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private interface CheckedAction { void run() throws Exception; }

    /** Keep the first failure while still attempting every independent cleanup. */
    private static final class CleanupFailures {
        private final Throwable primary;
        private Throwable first;

        CleanupFailures(Throwable primary) { this.primary = primary; first = primary; }

        boolean run(CheckedAction action) {
            try { action.run(); return true; }
            catch (Exception | Error failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
                return false;
            }
        }

        void finish() throws Exception {
            if (first == null || primary != null) return;
            if (first instanceof Exception) throw (Exception)first;
            throw (Error)first;
        }
    }

    private static void denied(CheckedAction action, String operation) throws Exception {
        try { action.run(); throw new AssertionError(operation + " was admitted"); }
        catch (VaultException expected) { /* The fixture carries no effect authority. */ }
    }

    private static void cleanupRetry(int mode, SshNativeSession parent,
            SshNativeSession.JumpChildOwner owner, SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshManagedKnownHostsProvider provider) throws Exception {
        SshManagedKnownHostsProvider.SnapshotReceipt receipt = source.receipt();
        byte[] wire = (byte[])field(source, "wire");
        byte[] base = (byte[])field(receipt, "baseDigest");
        byte[] binding = (byte[])field(receipt, "snapshotBinding");
        require(wire != null && !zeroed(wire), "fresh wire missing");
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> concurrentFailure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread concurrent = new Thread(() -> {
            started.countDown();
            try {
                while (!(Boolean)field(source, "closing")) Thread.yield();
                denied(source::requireCurrent, "closing source");
                denied(source::receipt, "closing source receipt");
                denied(source::takeWire, "closing source wire");
                denied(receipt::requireLive, "closing receipt");
                denied(receipt::binding, "closing receipt binding");
                denied(receipt::baseDigest, "closing receipt digest");
                require(!provider.ownsChildSnapshot(source), "closing source remained bound");
            } catch (Throwable failure) { concurrentFailure.set(failure); }
        });
        concurrent.start();
        require(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "concurrent checks did not start");
        armCleanupBeginFailure(mode == 43 ? 1 : 2);
        try { source.close(); throw new AssertionError("cleanup begin failure was confirmed"); }
        catch (UnsatisfiedLinkError expected) { /* Explicitly before the native begin. */ }
        concurrent.join(2_000);
        require(!concurrent.isAlive() && concurrentFailure.get() == null, "closing allowed concurrent use");
        require(!(Boolean)field(owner, "closed")
                        && (Boolean)field(source, "closing") && !(Boolean)field(source, "closed")
                        && (Boolean)field(receipt, "closing") && !(Boolean)field(receipt, "closed")
                        && field(parent, "jumpChildOwner") == owner && (Boolean)field(owner, "effectFenceRegistered"),
                "failed cleanup lost its owner or closing state");
        if (mode == 43) require(!zeroed(wire), "outer begin failure unexpectedly consumed wire");
        else require(zeroed(wire) && field(source, "wire") == null, "inner begin failure retained wire");
        denied(parent::close, "parent with pending child owner");
        java.util.concurrent.atomic.AtomicReference<String> wrong = new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            try { owner.close(); wrong.set("admitted"); }
            catch (VaultException expected) { wrong.set(expected.code); }
        });
        other.start(); other.join(2_000);
        require(!other.isAlive() && "SSH_SESSION_THREAD".equals(wrong.get())
                        && field(parent, "jumpChildOwner") == owner, "wrong owner changed cleanup");
        owner.close();
        require((Boolean)field(owner, "closed") && (Boolean)field(source, "closed")
                        && (Boolean)field(receipt, "closed") && field(parent, "jumpChildOwner") == null
                        && !(Boolean)field(owner, "effectFenceRegistered")
                        && zeroed(wire) && zeroed(base) && zeroed(binding), "same-owner retry did not finish cleanup");
        System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode
                + " cleanup-begin=retry owner=retained concurrent-use=denied");
    }

    private static void cleanupUnknownEnd(int mode, SshNativeSession parent,
            SshNativeSession.JumpChildOwner owner, SshManagedKnownHostsProvider.ChildTrustSnapshot source)
            throws Exception {
        SshManagedKnownHostsProvider.SnapshotReceipt receipt = source.receipt();
        beginOtherInvalidator();
        armCleanupEndFailure(mode == 46 ? 1 : 2);
        try { source.close(); throw new AssertionError("uncertain end was confirmed"); }
        catch (LinkageError expected) { /* Native end was deliberately made ambiguous. */ }
        require((Boolean)field(source, "closing") && (Boolean)field(receipt, "closing")
                        && !(Boolean)field(source, "closed") && !(Boolean)field(receipt, "closed")
                        && field(parent, "jumpChildOwner") == owner && (Boolean)field(owner, "effectFenceRegistered")
                        && !effectAvailable(), "uncertain end released an owner or other invalidator");
        try { owner.close(); throw new AssertionError("uncertain end was retried"); }
        catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong retry error"); }
        require(field(parent, "jumpChildOwner") == owner && (Boolean)field(owner, "effectFenceRegistered")
                        && !(Boolean)field(owner, "closed"), "uncertain receipt cleanup detached its owner");
        require(!effectAvailable(), "retry ended another invalidator");
        endOtherInvalidator();
        require(effectAvailable() == (mode == 47), "unexpected uncertain invalidator count");
        System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode
                + " cleanup-end=unconfirmed other-invalidator=retained isolated=required");
    }

    private static void cleanupDestroyedChild(SshNativeSession parent, SshNativeSession child,
            SshNativeSession.JumpChildOwner owner, SshManagedKnownHostsProvider.ChildTrustSnapshot source)
            throws Exception {
        SshNativeSession.TerminalChain chain = SshNativeSession.terminalChain(child);
        require(!(Boolean)field(source.receipt(), "closed"), "receipt was unexpectedly closed");
        /* Callback provider, receipt, then the destroyed child's source. */
        armCleanupBeginFailure(3);
        try { chain.close(); throw new AssertionError("source cleanup failure was confirmed"); }
        catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong chain error"); }
        require(child.closed() && !(Boolean)field(owner, "closed") && (Boolean)field(owner, "closing")
                        && field(parent, "jumpChildOwner") == owner && !parent.closed(),
                "destroyed child lost its pending exact owner");
        denied(parent::close, "parent before owner cleanup");
        chain.close();
        require((Boolean)field(owner, "closed") && field(parent, "jumpChildOwner") == null
                        && parent.closed(), "terminal chain did not resume owner cleanup");
        System.out.println("SSH_FRESH_VAULT_JNI mode=45 destroyed-child=retry terminal-chain=pass");
    }

    private static void adoptedCallbackCleanup(int mode, SshNativeSession parent, SshNativeSession child,
            SshNativeSession.JumpChildOwner owner, SshNativeCallbackAdapter callback,
            SshManagedKnownHostsProvider provider) throws Exception {
        Object claim = field(owner, "callbackClaim");
        require(field(provider, "candidate") == null && !(Boolean)field(callback, "closed"),
                "adopted cleanup requires an unhanded candidate");
        if (mode == 55) armCleanupBeginFailure(1);
        else {
            beginOtherInvalidator();
            if (mode == 56 || mode == 57) armCleanupBeginUnknown(mode == 56 ? 1 : 2);
            else armCleanupEndFailureAfter(mode == 58 ? 1 : 2, 2);
        }
        try { owner.close(); throw new AssertionError("adopted provider failure was confirmed"); }
        catch (VaultException expected) {
            require("SSH_CALLBACK_CLEANUP".equals(expected.code) && expected.getCause() != null,
                    "adopted callback failure lost its code or cause");
        }
        require((Boolean)field(callback, "closed") && !(Boolean)field(callback, "nativeCloseCleanupComplete")
                        && (Boolean)field(callback, "nativeSessionOwned") && (Boolean)field(claim, "owned")
                        && (Boolean)field(child, "nativeClosed") && !child.closed()
                        && field(parent, "jumpChildOwner") == owner && (Boolean)field(owner, "effectFenceRegistered"),
                "adopted callback failure released its claim, parent or fence");
        denied(callback::releaseRoleAfterNativeClient, "role before callback cleanup");
        if (mode == 55) {
            owner.close();
            require(child.closed() && (Boolean)field(callback, "nativeCloseCleanupComplete")
                            && !(Boolean)field(claim, "owned") && field(parent, "jumpChildOwner") == null,
                    "same owner did not finish adopted callback cleanup");
            System.out.println("SSH_FRESH_VAULT_JNI mode=55 adopted-handoff=retry same-owner=pass");
            return;
        }
        try { callback.close(); throw new AssertionError("callback close bypassed unresolved handoff"); }
        catch (VaultException expected) { require("SSH_CALLBACK_CLEANUP".equals(expected.code), "wrong callback error"); }
        try { owner.close(); throw new AssertionError("owner bypassed unresolved handoff"); }
        catch (VaultException expected) { require("SSH_CALLBACK_CLEANUP".equals(expected.code), "wrong owner error"); }
        require((Boolean)field(claim, "owned") && (Boolean)field(callback, "nativeSessionOwned")
                        && !child.closed() && field(parent, "jumpChildOwner") == owner
                        && (Boolean)field(owner, "effectFenceRegistered") && !effectAvailable(),
                "retry released adopted cleanup or another invalidator");
        endOtherInvalidator();
        require(effectAvailable() == (mode == 56 || mode == 59), "unexpected adopted invalidator count");
        System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode
                + " adopted-handoff=unconfirmed claim-and-owner=retained isolated=required");
    }

    private static void providerUnknownEnd(int mode, SshNativeSession parent,
            SshNativeSession.JumpChildOwner owner, SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshManagedKnownHostsProvider provider) throws Exception {
        SshManagedKnownHostsProvider.SnapshotReceipt receipt = source.receipt();
        beginOtherInvalidator();
        armCleanupEndFailure(mode == 48 ? 1 : 2);
        try { provider.close(); throw new AssertionError("provider end uncertainty was confirmed"); }
        catch (LinkageError expected) { /* Native end was deliberately made ambiguous. */ }
        require((Boolean)field(provider, "closed") && !(Boolean)field(provider, "cleanupComplete")
                        && (Boolean)field(receipt, "closing") && !effectAvailable()
                        && field(parent, "jumpChildOwner") == owner,
                "provider failed to retain an uncertain invalidator");
        denied(receipt::binding, "provider-closing receipt");
        try { provider.close(); throw new AssertionError("provider repeated an uncertain end"); }
        catch (IllegalStateException expected) { /* Unknown outcome is cleanup-only. */ }
        try { owner.close(); throw new AssertionError("owner detached an uncertain provider"); }
        catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong owner error"); }
        require(field(parent, "jumpChildOwner") == owner && (Boolean)field(owner, "effectFenceRegistered"),
                "uncertain provider cleanup lost its parent or fence");
        require(!effectAvailable(), "provider retry ended another invalidator");
        endOtherInvalidator();
        require(effectAvailable() == (mode == 49), "unexpected provider invalidator count");
        System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode
                + " provider-end=unconfirmed other-invalidator=retained isolated=required");
    }

    private static void cleanupUnknownBegin(int mode, SshNativeSession parent,
            SshNativeSession.JumpChildOwner owner, SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshManagedKnownHostsProvider provider) throws Exception {
        boolean providerCase = mode >= 52;
        boolean after = (mode & 1) != 0;
        beginOtherInvalidator();
        armCleanupBeginUnknown(after ? 2 : 1);
        if (providerCase) {
            try { provider.close(); throw new AssertionError("uncertain provider begin was confirmed"); }
            catch (LinkageError expected) { /* No Java acknowledgement of the native outcome. */ }
            try { provider.close(); throw new AssertionError("uncertain provider begin was repeated"); }
            catch (IllegalStateException expected) { /* Retain cleanup state. */ }
        } else {
            try { source.close(); throw new AssertionError("uncertain source begin was confirmed"); }
            catch (LinkageError expected) { /* The native outcome is deliberately ambiguous. */ }
        }
        {
            try { owner.close(); throw new AssertionError("uncertain source begin was repeated"); }
            catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong retry error"); }
        }
        require(field(parent, "jumpChildOwner") == owner && (Boolean)field(owner, "effectFenceRegistered")
                        && !effectAvailable(), "begin retry released another invalidator or owner");
        denied(source::requireCurrent, "source after uncertain begin");
        endOtherInvalidator();
        require(effectAvailable() == !after, "unexpected uncertain begin invalidator count");
        System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode
                + " cleanup-begin=unconfirmed other-invalidator=retained isolated=required");
    }

    private static void providerIndependentCleanup(SshNativeSession parent, SshNativeSession.JumpChildOwner owner,
            SshVaultAdapter files, SshNativeCallbackAdapter callback,
            SshManagedKnownHostsProvider provider, SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshNativeRoleBootstrap.Issued role) throws Exception {
        SshManagedKnownHostsProvider.SnapshotReceipt receipt = source.receipt();
        byte[] base = receipt.baseDigest();
        byte[] body = "staged cleanup candidate".getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = VaultCodec.digest(body);
        SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role.role,
                SshHostKeyAuthority.Classification.NEW, "ssh-ed25519", targetFingerprint(), base, replacement);
        SshKnownHostsCandidateHandoff handoff = (SshKnownHostsCandidateHandoff)field(callback, "candidateHandoff");
        handoff.accept(evidence, "candidate-child", body);
        Object candidate = field(provider, "candidate");
        byte[] candidateBody = (byte[])field(candidate, "content");
        byte[] proposed = "staged cleanup proposal".getBytes(StandardCharsets.US_ASCII);
        SshTrustProposal proposal = new SshTrustProposal(HOSTS, "hosts", role.role.targetIdentity,
                CONNECTION, "example.invalid", 22, targetFingerprint(), base, proposed);
        byte[] proposalBody = (byte[])field(proposal, "content");
        AppSecrets.SshPublicReadContext context = files.fileContext(role.role.deadline, () -> { });
        VaultOperation request = files.fileRequest(ROOT, CONNECTION, context);
        request.bindJumpEffect();
        /* Fault injection deliberately stages independent owned resources in
         * one provider to verify no later wipe is skipped. */
        setField(provider, "activeRequest", request);
        setField(provider, "activeProposal", proposal);
        armLegacyEffectInvalidationFailure();
        try { provider.close(); throw new AssertionError("failed request cancel was confirmed"); }
        catch (UnsatisfiedLinkError expected) { /* First cleanup failure remains visible. */ }
        require(field(provider, "activeRequest") == request && (Boolean)field(provider, "cleanupResourceUnconfirmed")
                        && field(provider, "candidate") == null && field(provider, "activeProposal") == null
                        && (Boolean)field(receipt, "closed") && zeroed(candidateBody) && zeroed(proposalBody),
                "request failure skipped independent sensitive cleanup or lost its owner");
        try { provider.close(); throw new AssertionError("unconfirmed request cleanup was retried"); }
        catch (IllegalStateException expected) { /* Preserve the exact unconfirmed owner. */ }
        try { owner.close(); throw new AssertionError("owner detached an unconfirmed request"); }
        catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong owner error"); }
        require(field(parent, "jumpChildOwner") == owner && (Boolean)field(owner, "effectFenceRegistered"),
                "unconfirmed request lost its parent or fence");
        VaultCodec.wipe(base); VaultCodec.wipe(body); VaultCodec.wipe(replacement); VaultCodec.wipe(proposed);
        VaultCodec.wipe(evidence.baseDigest); VaultCodec.wipe(evidence.replacementDigest);
        System.out.println("SSH_FRESH_VAULT_JNI mode=54 request=unconfirmed candidate-and-proposal=wiped isolated=required");
    }

    private static void handoffAbortRetry(SshKnownHostsCandidateHandoff handoff,
            SshManagedKnownHostsProvider provider, SshHostKeyAuthority.Evidence evidence,
            Object expectedCandidate, byte[] nextBody) throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> result = new java.util.concurrent.atomic.AtomicReference<>();
        Thread concurrentAccept = new Thread(() -> {
            started.countDown();
            try { handoff.accept(evidence, "candidate-child", nextBody); result.set("accepted"); }
            catch (VaultException expected) { result.set("denied"); }
            catch (Throwable failure) { result.set("unexpected " + failure.getClass().getName()); }
        });
        synchronized (handoff) {
            concurrentAccept.start();
            require(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "concurrent accept did not start");
            armEffectInvalidationFailure();
            try { handoff.abort(); throw new AssertionError("injected invalidation failure was not observed"); }
            catch (UnsatisfiedLinkError expected) { /* Test-native one-shot fault. */ }
            require((Boolean)field(handoff, "closing") && !(Boolean)field(handoff, "closed")
                            && field(provider, "candidate") == expectedCandidate && concurrentAccept.isAlive(),
                    "failed abort lost its retry state or concurrent accept escaped the lock");
        }
        concurrentAccept.join(2_000);
        require(!concurrentAccept.isAlive() && "denied".equals(result.get()) && zeroed(nextBody),
                "accept after failed abort was not denied and wiped");
        handoff.abort();
        handoff.close();
        require((Boolean)field(handoff, "closed") && field(provider, "candidate") == null,
                "abort retry did not finish or repeated close was not idempotent");
    }

    private static void childProvisionalFixture(int mode, SshNativeSession parent,
            SshNativeSession child, SshNativeSession.JumpChildOwner childOwner,
            SshNativeCallbackAdapter callback,
            SshManagedKnownHostsProvider provider, SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshNativeRoleBootstrap.Issued role, SshNativeRoleBootstrap.Issued parentRole,
            Store store, byte[][] owned) throws Exception {
        Object pending = null;
        VaultException rejected = null;
        for (int count = 0; count < 200_000; count++) {
            pumpPeers(); parent.step(); child.pumpJumpRelay();
            try {
                SshNativeSession.StepResult step = child.step();
                require(!step.complete, "provisional KEX escaped as ordinary completion");
            } catch (VaultException failure) { rejected = failure; break; }
            pending = field(callback, "pendingProvisionalFixture");
            if (pending != null && (Long)field(pending, "cycle") > 0) break;
        }
        if (mode >= 70 && mode <= 73 || mode == 75) {
            require(rejected != null && preconsumeDone && !(Boolean)field(callback, "inCallback")
                            && callback.hostProof() == null && callback.kexProof() == null && store.commits == 0,
                    "failed fixture cleanup stranded its callback or acquired authority");
            SshKnownHostsCandidateHandoff handoff =
                    (SshKnownHostsCandidateHandoff)field(callback, "candidateHandoff");
            require((Boolean)field(handoff, "closing"), "fixture handoff was not revoked");
            if (mode <= 71) {
                require((Boolean)field(handoff, "closed") && field(provider, "candidate") == null,
                        "confirmed pre-begin failure was not retried by native");
                for (byte[] bytes : consumedOwned)
                    require(zeroed(bytes), "checked cleanup retry did not wipe fixture material");
                childOwner.close();
                require(child.closed() && field(parent, "jumpChildOwner") == null,
                        "checked cleanup retry did not close its exact owner");
            } else {
                Object exactClaim = field(childOwner, "callbackClaim");
                require(!(Boolean)field(handoff, "closed") && field(provider, "candidate") == mutatedCandidate
                                && !zeroed(consumedOwned[3]) && exactClaim != null
                                && (Boolean)field(exactClaim, "owned") && (Boolean)field(childOwner, "effectFenceRegistered"),
                        "unconfirmed cleanup lost its exact candidate");
                require(zeroed(consumedOwned[0]) && zeroed(consumedOwned[1]) && zeroed(consumedOwned[2]),
                        "unconfirmed cleanup retained independent fixture material");
                if (mode == 73 || mode == 75) require(field(callback, "cancellationInvalidationUnconfirmed") != null,
                        "unconfirmed callback cancellation was not retained");
                if (mode == 75) require(!effectAvailable(), "unconfirmed effect end revived the fence");
                try { childOwner.close(); throw new AssertionError("unconfirmed cleanup closed the owner"); }
                catch (VaultException expected) {
                    require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong unconfirmed cleanup failure");
                }
                require(field(parent, "jumpChildOwner") == childOwner && !child.closed()
                                && field(provider, "candidate") == mutatedCandidate && !zeroed(consumedOwned[3])
                                && field(childOwner, "callbackClaim") == exactClaim && (Boolean)field(exactClaim, "owned")
                                && (Boolean)field(childOwner, "effectFenceRegistered")
                                && (Boolean)field(callback, "nativeSessionOwned")
                                && (mode != 75 || !effectAvailable()),
                        "unconfirmed cleanup released the owner, claim, fence or candidate");
            }
            System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode + " provisional-cleanup="
                    + (mode <= 71 ? "retried-and-closed" : "unconfirmed-owner-retained"));
            return;
        }
        if (mode == 30 || mode == 32 || mode >= 33 && mode <= 36) {
            require(rejected != null && field(callback, "pendingProvisionalFixture") == null,
                    "unavailable provisional scope was admitted");
            require(field(provider, "candidate") == null, "a refused scope retained a candidate");
            if (mode >= 33 && mode <= 36) {
                require(preconsumeDone && field(provider, "activeProposal") == null,
                        "early candidate mutation did not reach the intended stage or was retained");
                for (byte[] bytes : consumedOwned)
                    require(zeroed(bytes), "early-consumed candidate or proposal was not wiped");
            }
            require(callback.hostProof() == null && callback.kexProof() == null && store.commits == 0,
                    "a refused scope acquired authority");
            require(counters()[3] == 0 && counters()[4] == 0 && counters()[5] == 0,
                    "a refused scope emitted authentication");
            System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode + " provisional-"
                    + (mode == 30 ? "wrong-receiver" : mode == 32 ? "missing-scope-symbol"
                            : mode == 33 ? "consumed-before-review" : mode == 34 ? "consumed-before-kex"
                            : mode == 35 ? "replaced-before-review" : "replaced-before-kex")
                    + "=denied commits=0");
            return;
        }
        require(rejected == null && pending != null && (Long)field(pending, "cycle") > 0,
                "fixture did not reach signed provisional KEX");
        SshHostKeyAuthority.Evidence evidence = (SshHostKeyAuthority.Evidence)field(pending, "evidence");
        SshManagedKnownHostsProvider.KnownHostsCandidate candidate =
                (SshManagedKnownHostsProvider.KnownHostsCandidate)field(provider, "candidate");
        require(field(pending, "source") == source && evidence.role == role.role
                        && evidence.classification == SshHostKeyAuthority.Classification.NEW
                        && evidence.fingerprint.equals(targetFingerprint())
                        && "candidate-child".equals(field(pending, "candidateId"))
                        && candidate != null && "candidate-child".equals(candidate.candidateId)
                        && field(pending, "candidateIdentity") == field(candidate, "provisionalFixtureIdentity")
                        && field(pending, "candidateIdentity").getClass() == Object.class
                        && field(pending, "candidateIdentity") != candidate
                        && candidate.roleHop == 1 && candidate.fingerprint.equals(evidence.fingerprint)
                        && (Long)field(pending, "reviewSerial") > (Long)field(pending, "candidateSerial"),
                "fixture candidate is not bound to the exact child, source and peer key");
        owned[0] = (byte[])field(candidate, "content");
        owned[1] = (byte[])field(pending, "sessionDigest");
        owned[2] = evidence.baseDigest;
        owned[3] = evidence.replacementDigest;
        byte[] digest = VaultCodec.digest(owned[0]);
        try { require(java.security.MessageDigest.isEqual(digest, evidence.replacementDigest),
                "fixture candidate replacement digest changed"); }
        finally { VaultCodec.wipe(digest); }
        require(owned[1] != null && owned[1].length == 32 && !zeroed(owned[1])
                        && callback.hostProof() == null && callback.kexProof() == null && store.commits == 0,
                "signed fixture milestone acquired ordinary authority");
        denied(child::beginUserauthService, "userauth service");
        denied(child::beginAuthDiscovery, "authentication discovery");
        denied(child::beginPasswordAuth, "password authentication");
        denied(() -> child.beginShell(80, 24), "shell");
        require(counters()[3] == 0 && counters()[4] == 0 && counters()[5] == 0,
                "provisional fixture emitted authentication");
        if (mode >= 60) {
            signedNewReviewFixture(mode, parent, child, childOwner, callback, provider, source,
                    role, parentRole, store, owned);
            return;
        }
        if (mode == 28) {
            java.lang.reflect.Method review = SshNativeCallbackAdapter.class.getDeclaredMethod(
                    "onNativeProvisionalNewReviewForFixture", long.class, int.class, int.class,
                    String.class, String.class, String.class, int.class, String.class, String.class,
                    byte[].class, byte[].class);
            review.setAccessible(true);
            byte[] base = evidence.baseDigest(), replacement = evidence.replacementDigest();
            try {
                replacement[0] ^= 1;
                require(!(Boolean)review.invoke(callback, role.token, role.role.hop, role.role.port,
                        role.role.connectionId, role.role.targetIdentity, role.role.host,
                        SshNativeCallbackAdapter.HOST_NEW, evidence.keyType, evidence.fingerprint,
                        base, replacement), "changed candidate review was admitted");
            } finally { VaultCodec.wipe(base); VaultCodec.wipe(replacement); }
        } else if (mode == 29) {
            java.lang.reflect.Method kex = SshNativeCallbackAdapter.class.getDeclaredMethod(
                    "onNativeProvisionalNewKexForFixture", long.class, byte[].class);
            kex.setAccessible(true);
            byte[] replay = owned[1].clone();
            require(!(Boolean)kex.invoke(callback, (Long)field(pending, "cycle"), replay) && zeroed(replay),
                    "KEX replay outside native dispatch was admitted or not wiped");
        } else if (mode == 37) {
            require(!callback.onNativeCredential(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD),
                    "ordinary credential callback was admitted while provisional");
        } else if (mode == 38) {
            byte[] base = evidence.baseDigest(), replacement = evidence.replacementDigest();
            try {
                require(!callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_NEW, evidence.keyType,
                        evidence.fingerprint, base, replacement),
                        "ordinary host-key callback was admitted while provisional");
            } finally { VaultCodec.wipe(base); VaultCodec.wipe(replacement); }
        } else if (mode == 39) {
            SshKnownHostsCandidateHandoff handoff = (SshKnownHostsCandidateHandoff)field(pending, "handoff");
            handoffAbortRetry(handoff, provider, evidence, candidate, owned[0].clone());
            require(zeroed(owned[0]), "candidate was not wiped after abort retry");
        } else if (mode == 40) {
            java.lang.reflect.Method candidateReplay = SshNativeCallbackAdapter.class.getDeclaredMethod(
                    "onNativeProvisionalNewCandidateForFixture", long.class, int.class, int.class,
                    String.class, String.class, String.class, int.class, String.class, String.class,
                    byte[].class, byte[].class, String.class, byte[].class);
            candidateReplay.setAccessible(true);
            byte[] base = evidence.baseDigest(), replacement = evidence.replacementDigest(), body = owned[0].clone();
            try {
                require(!(Boolean)candidateReplay.invoke(callback, role.token, role.role.hop, role.role.port,
                                role.role.connectionId, role.role.targetIdentity, role.role.host,
                                SshNativeCallbackAdapter.HOST_NEW, evidence.keyType, evidence.fingerprint,
                                base, replacement, "candidate-child", body) && zeroed(body),
                        "candidate replay was admitted or its input was not wiped");
            } finally { VaultCodec.wipe(base); VaultCodec.wipe(replacement); VaultCodec.wipe(body); }
        }
        if (mode == 28 || mode == 29) for (byte[] bytes : owned)
            require(zeroed(bytes), "refused provisional material was not wiped");
        require(store.commits == 0, "provisional fixture committed trust");
        System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode + " provisional=signed-only test="
                + (mode == 27 || mode == 42 ? "baseline" : mode == 39 ? "abort-retry" : "rejected")
                + " credentials=0 commits=0");
    }

    private static void unusedHandoffAbortRetry(SshNativeCallbackAdapter callback,
            SshManagedKnownHostsProvider provider, SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshNativeRoleBootstrap.Issued role, Store store) throws Exception {
        SshKnownHostsCandidateHandoff handoff = (SshKnownHostsCandidateHandoff)field(callback, "candidateHandoff");
        byte[] body = "unused-test-candidate".getBytes(StandardCharsets.US_ASCII);
        byte[] base = source.receipt().baseDigest(), replacement = VaultCodec.digest(body);
        SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role.role,
                SshHostKeyAuthority.Classification.NEW, "ssh-ed25519", targetFingerprint(), base, replacement);
        try {
            require(!(Boolean)field(handoff, "used") && field(provider, "candidate") == null,
                    "unused handoff was already consumed");
            handoffAbortRetry(handoff, provider, evidence, null, body);
            require(store.commits == 0, "unused handoff retry committed trust");
            System.out.println("SSH_FRESH_VAULT_JNI mode=41 unused-abort-retry=pass concurrent-accept=denied");
        } finally {
            VaultCodec.wipe(body); VaultCodec.wipe(base); VaultCodec.wipe(replacement);
            VaultCodec.wipe(evidence.baseDigest); VaultCodec.wipe(evidence.replacementDigest);
        }
    }

    private static void childOwnerAbortRetry(SshNativeSession parent, SshNativeSession child,
            SshNativeSession.JumpChildOwner owner, SshNativeCallbackAdapter callback,
            SshManagedKnownHostsProvider provider, int[] descriptors, byte[][] owned, Store store)
            throws Exception {
        Object candidate = field(provider, "candidate");
        SshKnownHostsCandidateHandoff handoff = (SshKnownHostsCandidateHandoff)field(callback, "candidateHandoff");
        require(candidate != null && !zeroed(owned[0]), "native owner candidate is unavailable");
        armEffectInvalidationFailure();
        try { owner.close(); throw new AssertionError("failed native candidate abort was confirmed"); }
        catch (VaultException expected) {
            require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong native cleanup failure");
        }
        require(field(provider, "candidate") == candidate && (Boolean)field(handoff, "closing")
                        && !(Boolean)field(handoff, "closed") && !child.closed()
                        && field(parent, "jumpChildOwner") == owner && !zeroed(owned[0]),
                "failed native abort did not retain the exact child owner and candidate");
        for (int fd : descriptors) require(!fdClosed(fd), "failed native abort released child descriptor");
        owner.close();
        require(child.closed() && field(parent, "jumpChildOwner") == null
                        && field(provider, "candidate") == null && zeroed(owned[0]) && store.commits == 0,
                "native owner retry did not complete candidate cleanup");
        for (byte[] bytes : owned) require(zeroed(bytes), "native owner retry did not wipe provisional material");
        System.out.println("SSH_FRESH_VAULT_JNI mode=42 native-abort-retry=pass owner=retained-then-closed");
    }

    private static void signedNewReviewFixture(int mode, SshNativeSession parent, SshNativeSession child,
            SshNativeSession.JumpChildOwner owner, SshNativeCallbackAdapter callback,
            SshManagedKnownHostsProvider provider, SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshNativeRoleBootstrap.Issued role, SshNativeRoleBootstrap.Issued parentRole,
            Store store, byte[][] owned) throws Exception {
        if (mode == 67) {
            Object original = field(provider, "candidate");
            beginOtherInvalidator(); endOtherInvalidator();
            armEffectInvalidationFailure();
            denied(child::signedNewFixtureReview, "revoked native readiness");
            require(field(parent, "jumpChildOwner") == owner && !child.closed()
                            && field(provider, "candidate") == original && !zeroed(owned[0]),
                    "failed native review abort discarded its exact owner or candidate");
            owner.close();
            require(child.closed() && field(parent, "jumpChildOwner") == null
                            && field(provider, "candidate") == null && zeroed(owned[0]),
                    "failed native review abort was not retryable by its owner");
            require(store.commits == 0, "failed readiness committed trust");
            System.out.println("SSH_FRESH_VAULT_JNI mode=67 ready-abort=retry-owner");
            return;
        }

        SshNativeCallbackAdapter.SignedNewFixtureReview found = null;
        for (int turn = 0; turn < 200_000 && found == null; turn++) {
            found = child.signedNewFixtureReview();
            if (found == null) { pumpPeers(); parent.step(); child.pumpJumpRelay(); }
        }
        require(found != null, "signed fixture review never reached the real drained child");
        final SshNativeCallbackAdapter.SignedNewFixtureReview review = found;
        owned[4] = (byte[])field(review, "baseDigest");
        owned[5] = (byte[])field(review, "replacementDigest");
        owned[6] = (byte[])field(review, "sessionDigest");
        require(!zeroed(owned[4]) && !zeroed(owned[5]) && !zeroed(owned[6])
                        && owned[4] != owned[2] && owned[5] != owned[3] && owned[6] != owned[1]
                        && java.security.MessageDigest.isEqual(owned[4], owned[2])
                        && java.security.MessageDigest.isEqual(owned[5], owned[3])
                        && java.security.MessageDigest.isEqual(owned[6], owned[1]),
                "issued review did not retain its exact signed evidence");
        require(review.hop == 1 && review.host.equals("example.invalid") && review.port == 22
                        && review.fingerprint.equals(targetFingerprint()) && review.binding.matches("[0-9a-f]{64}")
                        && review.cycle == 1 && callback.hostProof() == null && callback.kexProof() == null,
                "signed fixture review is not bound to the actual offered target");
        denied(child::signedNewFixtureReview, "replayed signed review");
        if (mode >= 88 && mode <= 109) {
            candidateReservationFixture(mode, parent, child, owner, callback, provider, source,
                    role, store, review, owned);
            return;
        }
        if (mode >= 79 && mode <= 87) {
            signedFixturePollIntegration(mode, parent, child, owner, callback, provider, source,
                    role, store, review, owned);
            return;
        }
        if (mode == 78) {
            SshKnownHostsCandidateHandoff handoff =
                    (SshKnownHostsCandidateHandoff)field(callback, "candidateHandoff");
            Object exactClaim = field(owner, "callbackClaim");
            Thread eventLoop = Thread.currentThread();
            java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.FutureTask<Boolean> invalidator = new java.util.concurrent.FutureTask<>(() -> {
                synchronized (handoff) {
                    holding.countDown();
                    java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                    boolean reachedExactMonitor = false;
                    while (System.nanoTime() < deadline) {
                        java.lang.management.ThreadInfo info = threads.getThreadInfo(eventLoop.getId());
                        if (info != null && info.getThreadState() == Thread.State.BLOCKED
                                && info.getLockOwnerId() == Thread.currentThread().getId()
                                && info.getLockInfo() != null
                                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(handoff)) {
                            reachedExactMonitor = true;
                            break;
                        }
                        Thread.yield();
                    }
                    if (!reachedExactMonitor) return false;
                    // The event-loop owner is inside the real JNI abort, after
                    // its first latch check and before handoff.abort returns.
                    armLegacyEffectInvalidationFailure();
                    try { callback.cancel(); return false; }
                    catch (UnsatisfiedLinkError expected) {
                        return field(callback, "cancellationInvalidationUnconfirmed") != null;
                    }
                }
            });
            Thread holder = new Thread(invalidator, "synthetic-abort-cancel-race");
            holder.setDaemon(true); holder.start();
            require(holding.await(2, java.util.concurrent.TimeUnit.SECONDS), "handoff monitor was not held");
            try { owner.close(); throw new AssertionError("racing unconfirmed abort closed the owner"); }
            catch (VaultException expected) {
                require("SSH_SESSION_CLEANUP".equals(expected.code), "native acknowledged racing abort");
            }
            require(invalidator.get(2, java.util.concurrent.TimeUnit.SECONDS),
                    "race never reached the exact real JNI handoff monitor");
            require((Boolean)field(handoff, "closed") && field(provider, "candidate") == null,
                    "the independently guarded handoff cleanup did not complete");
            for (byte[] bytes : owned) require(zeroed(bytes), "racing abort retained fixture material");
            require(field(parent, "jumpChildOwner") == owner && !child.closed()
                            && !(Boolean)field(child, "nativeClosed") && field(owner, "callbackClaim") == exactClaim
                            && (Boolean)field(exactClaim, "owned") && (Boolean)field(owner, "effectFenceRegistered")
                            && (Boolean)field(callback, "nativeSessionOwned"),
                    "racing abort released the owner, claim or fence");
            try { owner.close(); throw new AssertionError("racing abort was acknowledged on retry"); }
            catch (VaultException expected) {
                require("SSH_SESSION_CLEANUP".equals(expected.code) && !(Boolean)field(child, "nativeClosed"),
                        "native did not retain the unconfirmed abort");
            }
            require(callback.hostProof() == null && callback.kexProof() == null && store.commits == 0,
                    "racing abort acquired authority");
            System.out.println("SSH_FRESH_VAULT_JNI mode=78 abort-cancel-race=unconfirmed-owner-retained");
            return;
        }
        if (mode == 76 || mode == 77) {
            Object originalCandidate = field(provider, "candidate");
            Object exactClaim = field(owner, "callbackClaim");
            require(originalCandidate != null && exactClaim != null && !zeroed(owned[0])
                            && !zeroed(owned[4]) && !zeroed(owned[5]) && !zeroed(owned[6]),
                    "signed review material was not present before cancellation");
            if (mode == 76) armLegacyEffectInvalidationFailure();
            else armCleanupEndFailure(1);
            try { callback.cancel(); throw new AssertionError("unconfirmed cancellation succeeded"); }
            catch (UnsatisfiedLinkError expected) { /* Test-native failure at legacy begin/end. */ }
            require(field(callback, "cancellationInvalidationUnconfirmed") != null
                            && !(Boolean)field(callback, "inCallback")
                            && field(callback, "pendingProvisionalFixture") == null,
                    "unconfirmed cancellation did not retire the Java review");
            for (int index = 1; index < owned.length; index++)
                require(zeroed(owned[index]), "unconfirmed cancellation retained an independent digest");
            try { callback.abortNativeKnownHostsCandidate();
                throw new AssertionError("unconfirmed native abort was acknowledged"); }
            catch (IllegalStateException expected) { /* Native must not acknowledge this abort. */ }
            try { owner.close(); throw new AssertionError("unconfirmed cancellation closed the owner"); }
            catch (VaultException expected) {
                require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong unconfirmed cancellation failure");
            }
            require(field(parent, "jumpChildOwner") == owner && !child.closed()
                            && field(provider, "candidate") == originalCandidate && !zeroed(owned[0])
                            && field(owner, "callbackClaim") == exactClaim && (Boolean)field(exactClaim, "owned")
                            && (Boolean)field(owner, "effectFenceRegistered")
                            && (Boolean)field(callback, "nativeSessionOwned") && (mode != 77 || !effectAvailable())
                            && callback.hostProof() == null && callback.kexProof() == null && store.commits == 0,
                    "unconfirmed cancellation released its owner, claim, fence or candidate");
            System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode
                    + " signed-cancel=unconfirmed-owner-retained digests=wiped");
            return;
        }
        if (mode == 74) {
            Object originalCandidate = field(provider, "candidate");
            source.close();
            java.util.concurrent.atomic.AtomicBoolean presented = new java.util.concurrent.atomic.AtomicBoolean();
            try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(
                    role.role, new Clock(), () -> { }, ticket -> presented.set(true))) {
                denied(() -> mailbox.requireSignedNewFixtureApproved(review, child),
                        "review whose source closed before presentation");
            }
            require(!presented.get() && field(provider, "candidate") == originalCandidate
                            && zeroed(owned[4]) && zeroed(owned[5]) && zeroed(owned[6])
                            && !zeroed(owned[1]) && !zeroed(owned[2]) && !zeroed(owned[3]),
                    "pre-presentation failure changed the candidate or pending evidence");
            require(callback.hostProof() == null && callback.kexProof() == null && store.commits == 0,
                    "pre-presentation failure acquired authority");
            System.out.println("SSH_FRESH_VAULT_JNI mode=74 stale-before-presentation=denied digests=wiped");
            return;
        }
        if (mode == 65) {
            denied(() -> review.claim(parentRole.role), "cross-role review claim");
            Class<?> nativeType = Class.forName("im.majo.ctos.SshNativeSession$Native");
            java.lang.reflect.Method ready = nativeType.getDeclaredMethod(
                    "provisionalNewFixtureReady", long.class, long.class);
            ready.setAccessible(true);
            long childHandle = (Long)field(child, "nativeHandle");
            long parentHandle = (Long)field(parent, "nativeHandle");
            try {
                ready.invoke(null, parentHandle, callback.nativeCallbackToken());
                throw new AssertionError("a root session supplied a child readiness observation");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                require(expected.getCause() instanceof IOException, "wrong root-session readiness rejection");
            }
            try {
                ready.invoke(null, childHandle, callback.nativeCallbackToken() + 1);
                throw new AssertionError("another callback token supplied child readiness");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                require(expected.getCause() instanceof IOException, "wrong callback readiness rejection");
            }
            java.util.concurrent.FutureTask<Boolean> wrong = new java.util.concurrent.FutureTask<>(() -> {
                try {
                    child.signedNewFixtureReview();
                    return false;
                } catch (VaultException expected) {
                    if (!"SSH_SESSION_THREAD".equals(expected.code)) return false;
                }
                try { review.claim(role.role); return false; }
                catch (VaultException expected) { /* Wrong-thread claim must not consume it. */ }
                try { ready.invoke(null, childHandle, callback.nativeCallbackToken()); return false; }
                catch (java.lang.reflect.InvocationTargetException expected) {
                    return expected.getCause() instanceof IOException;
                }
            });
            Thread thread = new Thread(wrong, "synthetic-wrong-review-owner");
            thread.setDaemon(true); thread.start();
            require(wrong.get(2, java.util.concurrent.TimeUnit.SECONDS), "cross-thread review was admitted");
        }

        java.util.concurrent.atomic.AtomicReference<SshHostKeyReviewMailbox> ownerBox =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<SshHostKeyReviewMailbox.Ticket> shown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger finalObservation = new java.util.concurrent.atomic.AtomicInteger();
        try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(role.role, new Clock(), () -> { }, ticket -> {
            shown.set(ticket);
            require(ticket.hop == review.hop && ticket.host.equals(review.host)
                            && ticket.fingerprint.equals(review.fingerprint) && ticket.binding.equals(review.binding)
                            && !ticket.changed && ticket.deadline <= review.deadline,
                    "displayed ticket did not use the signed candidate metadata");
            if (mode == 61) callback.cancel();
            else if (mode == 62 || mode == 63) {
                consumeProvider = provider; consumeSource = source; consumeCallback = callback;
                replaceCandidate = mode == 63;
                try { consumeProvisionalCandidate(); }
                catch (Exception failure) { throw new AssertionError(failure); }
            } else if (mode == 64) {
                while (now() < review.deadline) Thread.yield();
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            } else if (mode == 66) owner.close();
            else if (mode == 68) {
                SshManagedKnownHostsProvider.SnapshotReceipt receipt = source.receipt();
                java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
                Thread holder = new Thread(() -> {
                    synchronized (receipt) {
                        locked.countDown();
                        try { release.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                }, "synthetic-held-vault-receipt");
                holder.setDaemon(true); holder.start();
                try {
                    require(locked.await(2, java.util.concurrent.TimeUnit.SECONDS), "fixture receipt was not locked");
                    java.util.concurrent.FutureTask<Boolean> ui = new java.util.concurrent.FutureTask<>(
                            () -> ownerBox.get().pending(ticket) && ownerBox.get().resolve(ticket, true));
                    Thread publisher = new Thread(ui, "synthetic-signed-review-publication");
                    publisher.setDaemon(true); publisher.start();
                    require(ui.get(2, java.util.concurrent.TimeUnit.SECONDS), "UI waited on the vault receipt");
                } catch (Exception failure) { throw new AssertionError(failure); }
                finally { release.countDown(); }
            } else if (mode == 69) {
                // Race publication against revocation of the exact source, without
                // consuming or replacing the candidate held by its owner.
                Object originalCandidate;
                try { originalCandidate = field(provider, "candidate"); }
                catch (Exception failure) { throw new AssertionError(failure); }
                require(originalCandidate != null && !zeroed(owned[0]), "fixture candidate disappeared");
                java.util.concurrent.CountDownLatch observed = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CountDownLatch sourceClosed = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.FutureTask<Boolean> ui = new java.util.concurrent.FutureTask<>(() -> {
                    boolean pending = ownerBox.get().pending(ticket);
                    observed.countDown();
                    return pending && sourceClosed.await(2, java.util.concurrent.TimeUnit.SECONDS)
                            && ownerBox.get().resolve(ticket, true);
                });
                Thread publisher = new Thread(ui, "synthetic-stale-source-publication");
                publisher.setDaemon(true); publisher.start();
                try {
                    require(observed.await(2, java.util.concurrent.TimeUnit.SECONDS), "UI did not sample ticket");
                    source.close();
                    require(field(provider, "candidate") == originalCandidate && !zeroed(owned[0]),
                            "source closure changed the candidate itself");
                } catch (Exception failure) { throw new AssertionError(failure); }
                finally { sourceClosed.countDown(); }
                try { require(ui.get(2, java.util.concurrent.TimeUnit.SECONDS), "UI did not record its decision"); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
            if (mode >= 61 && mode <= 64 || mode == 66) {
                if (mode == 62 || mode == 63)
                    require(ownerBox.get().pending(ticket), "UI ticket sampling unexpectedly acquired the source");
                boolean recorded = ownerBox.get().resolve(ticket, true);
                if (mode == 64) require(!recorded, "an expired ticket recorded a decision");
                else require(recorded, "UI did not record its non-authorizing decision");
                // The UI records a decision without acquiring vault/session locks.
                // The owner must still reject a changed source or closed chain.
            }
        })) {
            ownerBox.set(mailbox);
            if (mode == 60 || mode == 65 || mode == 68) {
                mailbox.requireSignedNewFixtureApprovedForTest(review, child, () -> {
                    try { pumpPeers(); }
                    catch (IOException failure) { throw new VaultException("SSH_SESSION_JUMP", "Fixture pump failed"); }
                    int turn = polls.incrementAndGet();
                    if (mode != 68 && turn == 2) {
                        java.util.concurrent.FutureTask<Boolean> answer = new java.util.concurrent.FutureTask<>(
                                () -> mailbox.resolve(shown.get(), true));
                        Thread ui = new Thread(answer, "synthetic-signed-review-ui");
                        ui.setDaemon(true); ui.start();
                        try { require(answer.get(2, java.util.concurrent.TimeUnit.SECONDS), "review decision failed"); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                    }
                }, finalObservation::set);
                require(polls.get() >= (mode == 68 ? 1 : 2) && (finalObservation.get() & 1) != 0
                                && !mailbox.resolve(shown.get(), true),
                        "signed review did not pump or was replayable");
                denied(() -> review.claim(role.role), "replayed review claim");
                review.requireLive();
            } else {
                denied(() -> mailbox.requireSignedNewFixtureApproved(review, child), "stale signed review");
                require(!mailbox.resolve(shown.get(), true), "expired review was replayed");
                denied(review::requireLive, "stale signed candidate");
            }
        }
        require(callback.hostProof() == null && callback.kexProof() == null && store.commits == 0
                        && counters()[3] == 0 && counters()[4] == 0 && counters()[5] == 0,
                "signed review produced trust or target authentication");
        System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode
                + " signed-review=non-authorizing polls=" + polls.get() + " commits=0");
    }

    private static void signedFixturePollIntegration(int mode, SshNativeSession parent,
            SshNativeSession child, SshNativeSession.JumpChildOwner childOwner,
            SshNativeCallbackAdapter callback, SshManagedKnownHostsProvider provider,
            SshManagedKnownHostsProvider.ChildTrustSnapshot source, SshNativeRoleBootstrap.Issued role,
            Store store, SshNativeCallbackAdapter.SignedNewFixtureReview review, byte[][] owned)
            throws Exception {
        if (mode == 82) {
            denied(() -> parent.pollSignedNewFixture(review), "another session's fixture review");
            try (SshHostKeyReviewMailbox wrongMailbox = new SshHostKeyReviewMailbox(
                    role.role, new Clock(), () -> { }, ticket -> {
                        throw new AssertionError("foreign session presented the fixture review");
                    })) {
                denied(() -> wrongMailbox.requireSignedNewFixtureApproved(review, parent),
                        "another session's fixture mailbox");
            }
            review.requireLive();
            Class<?> nativeType = Class.forName("im.majo.ctos.SshNativeSession$Native");
            java.lang.reflect.Method poll = nativeType.getDeclaredMethod(
                    "provisionalNewFixturePoll", long.class, long.class);
            poll.setAccessible(true);
            long childHandle = (Long)field(child, "nativeHandle");
            long parentHandle = (Long)field(parent, "nativeHandle");
            for (long[] arguments : new long[][] {{parentHandle, callback.nativeCallbackToken()},
                    {childHandle, callback.nativeCallbackToken() + 1}}) {
                try {
                    poll.invoke(null, arguments[0], arguments[1]);
                    throw new AssertionError("foreign session or callback reached fixture poll");
                } catch (java.lang.reflect.InvocationTargetException expected) {
                    require(expected.getCause() instanceof IOException, "wrong fixture poll rejection");
                }
            }
            java.util.concurrent.FutureTask<Boolean> wrongOwner = new java.util.concurrent.FutureTask<>(() -> {
                try { child.pollSignedNewFixture(review); return false; }
                catch (VaultException expected) { return "SSH_SESSION_THREAD".equals(expected.code); }
            });
            Thread wrong = new Thread(wrongOwner, "synthetic-pending-poll-wrong-owner");
            wrong.setDaemon(true); wrong.start();
            require(wrongOwner.get(2, java.util.concurrent.TimeUnit.SECONDS), "wrong owner polled fixture");
            review.requireLive();
        }

        if (mode >= 79 && mode <= 81) {
            boolean peerReady = false;
            for (int turn = 0; turn < 200_000 && !peerReady; turn++) {
                pumpPeers(); parent.step(); child.pumpJumpRelay();
                peerReady = pendingFixturePeerReady();
            }
            require(peerReady, "pending fixture peer did not finish KEX");
            queuePendingFixtureExtension(2);
            int available = 0;
            for (int turn = 0; turn < 200_000 && available == 0; turn++) {
                pumpPeers(); parent.step(); child.pumpJumpRelay();
                available = pendingFixtureBytes();
            }
            require(available > 0 && available <= 2,
                    "first valid extension fragment did not reach the pending child");
        }
        if (mode == 86 || mode == 87) {
            boolean peerReady = false;
            for (int turn = 0; turn < 200_000 && !peerReady; turn++) {
                pumpPeers(); parent.step(); child.pumpJumpRelay();
                peerReady = pendingFixturePeerReady();
            }
            require(peerReady, "pending fixture peer did not finish KEX");
        }
        if (mode == 86) {
            int length = queuePendingFixtureExtension(64);
            int available = 0;
            for (int turn = 0; turn < 200_000 && available < length; turn++) {
                pumpPeers(); parent.step(); child.pumpJumpRelay();
                available = pendingFixtureBytes();
            }
            require(length > 0 && available == length,
                    "full valid extension did not reach the pending child");
        }
        if (mode == 87) {
            Class<?> nativeType = Class.forName("im.majo.ctos.SshNativeSession$Native");
            java.lang.reflect.Method poll = nativeType.getDeclaredMethod(
                    "provisionalNewFixturePoll", long.class, long.class);
            poll.setAccessible(true);
            try {
                poll.invoke(null, (Long)field(child, "nativeHandle"), callback.nativeCallbackToken());
                throw new AssertionError("fixture poll JNI was unexpectedly present");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                require(expected.getCause() instanceof UnsatisfiedLinkError, "wrong missing-poll failure");
            }
        }
        Object originalCandidate = field(provider, "candidate");
        Object exactClaim = field(childOwner, "callbackClaim");
        java.util.concurrent.atomic.AtomicReference<SshHostKeyReviewMailbox> box =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<SshHostKeyReviewMailbox.Ticket> shown =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean decisionRecorded = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger last = new java.util.concurrent.atomic.AtomicInteger();
        try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(role.role, new Clock(), () -> { }, ticket -> {
            shown.set(ticket);
            if (mode == 83) source.close();
            if (mode == 84) childOwner.close();
            require(box.get().resolve(ticket, true), "fixture decision was not recorded");
            decisionRecorded.set(true);
        })) {
            box.set(mailbox);
            if (mode == 85) {
                armPendingFixturePeekFailure();
                armEffectInvalidationFailure();
            }
            SshHostKeyReviewMailbox.PendingPoll beforePoll = () -> {
                int turn = turns.incrementAndGet();
                if (mode != 81 || turn == 1) {
                    try { pumpPeers(); }
                    catch (IOException failure) { throw new VaultException("SSH_SESSION_JUMP", "Fixture peer failed"); }
                }
            };
            SshHostKeyReviewMailbox.SignedFixtureObserver observer = observation -> {
                int turn = turns.get();
                last.set(observation);
                if (turn == 1 && mode >= 79 && mode <= 81)
                    require((observation & 3) == 0, "fragment was reported idle or fully consumed");
                if (mode == 80 && turn == 1) mailbox.cancel();
            };
            if (mode == 79 || mode == 82 || mode == 86) {
                if (mode == 86) mailbox.requireSignedNewFixtureApproved(review, child);
                else mailbox.requireSignedNewFixtureApprovedForTest(review, child, beforePoll, observer);
                require(!mailbox.resolve(shown.get(), true) && (mode == 86 || (last.get() & 1) != 0),
                        "fixture approval returned before an idle observation");
                if (mode == 79) require(turns.get() > 1 && (last.get() & 2) != 0,
                        "valid extension was not consumed by the native fixture poll");
                if (mode == 86) {
                    Class<?> nativeType = Class.forName("im.majo.ctos.SshNativeSession$Native");
                    java.lang.reflect.Method seen = nativeType.getDeclaredMethod(
                            "provisionalNewFixtureExtSeen", long.class, long.class);
                    seen.setAccessible(true);
                    require(pendingFixtureBytes() == 0 && (Boolean)seen.invoke(null,
                                    (Long)field(child, "nativeHandle"), callback.nativeCallbackToken()),
                            "fixture entry did not consume the full extension");
                }
                review.requireLive();
            } else {
                if (mode == 87) {
                    try {
                        mailbox.requireSignedNewFixtureApproved(review, child);
                        throw new AssertionError("missing fixture poll JNI was admitted");
                    } catch (VaultException expected) {
                        require("SSH_HOST_KEY_REVIEW".equals(expected.code), "wrong missing-poll failure code");
                    }
                    require(shown.get() != null && decisionRecorded.get(),
                            "missing-poll test did not present and approve the ticket");
                } else denied(() -> mailbox.requireSignedNewFixtureApprovedForTest(review, child, beforePoll, observer),
                        "invalid pending fixture review");
                require(!mailbox.resolve(shown.get(), true), "failed fixture review was replayed");
                if (mode == 80 || mode == 81)
                    require(turns.get() > 0 && (last.get() & 1) == 0,
                            "cancelled or expired fragmented input was called idle");
                if (mode == 81) require(turns.get() > 1, "expiry did not wait across bounded turns");
                if (mode == 85) {
                    require(turns.get() == 1 && field(parent, "jumpChildOwner") == childOwner
                                    && !child.closed() && field(provider, "candidate") == originalCandidate
                                    && field(childOwner, "callbackClaim") == exactClaim
                                    && (Boolean)field(exactClaim, "owned")
                                    && (Boolean)field(childOwner, "effectFenceRegistered"),
                            "failed native poll cleanup lost its exact owner or candidate");
                    childOwner.close();
                    require(child.closed() && field(parent, "jumpChildOwner") == null
                                    && field(provider, "candidate") == null && zeroed(owned[0]),
                            "failed native poll cleanup did not retry on its owner");
                }
                if (mode == 87) require(field(parent, "jumpChildOwner") == childOwner && !child.closed()
                                && originalCandidate != null && field(provider, "candidate") == originalCandidate
                                && !zeroed(owned[0]) && field(childOwner, "callbackClaim") == exactClaim
                                && (Boolean)field(exactClaim, "owned")
                                && (Boolean)field(childOwner, "effectFenceRegistered"),
                        "missing fixture poll lost its owner or candidate");
            }
        }
        require(callback.hostProof() == null && callback.kexProof() == null && store.commits == 0
                        && counters()[3] == 0 && counters()[4] == 0 && counters()[5] == 0,
                "fixture poll produced trust or target authentication");
        System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode + " pending-poll="
                + (mode == 79 ? "fragmented-extension" : mode == 85 ? "abort-retry"
                        : mode == 86 ? "entry-extension" : mode == 87 ? "missing-jni-denied" : "guarded")
                + " turns=" + turns.get() + " status=" + last.get() + " commits=0");
    }

    private static void awaitTransferMonitor(Thread blocked, Object monitor, Thread holder,
            Class<?> expectedClass, String expectedMethod) {
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            java.lang.management.ThreadInfo info = threads.getThreadInfo(blocked.getId(), 64);
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == holder.getId() && info.getLockInfo() != null
                    && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)) {
                if (expectedClass == null) return;
                for (StackTraceElement frame : info.getStackTrace()) {
                    if (frame.getClassName().equals(expectedClass.getName())
                            && frame.getMethodName().equals(expectedMethod)) return;
                }
            }
            Thread.yield();
        }
        throw new AssertionError("the expected transfer monitor ordering was not reached");
    }

    private static void awaitTransferLatch(java.util.concurrent.CountDownLatch latch, String detail) {
        try { require(latch.await(5, java.util.concurrent.TimeUnit.SECONDS), detail); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void candidateTransferRaceFixture(int mode, SshNativeSession child,
            SshNativeCallbackAdapter callback, SshNativeCallbackAdapter.SignedNewFixtureReview review,
            SshManagedKnownHostsProvider.KnownHostsCandidate candidate,
            SshManagedKnownHostsProvider.FixtureCandidateReservation reservation, byte[] exactBody)
            throws Exception {
        Thread owner = Thread.currentThread();
        Object stateLock = field(callback, "stateLock");
        Object blockedMonitor = mode == 106 ? candidate : reservation;
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch reviewLocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch revokeDone = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.FutureTask<Boolean> controller = new java.util.concurrent.FutureTask<>(() -> {
            java.util.concurrent.FutureTask<Boolean> closer = null, gate = null;
            synchronized (blockedMonitor) {
                holding.countDown();
                awaitTransferMonitor(owner, blockedMonitor, Thread.currentThread(), mode == 106
                        ? SshManagedKnownHostsProvider.class
                        : SshManagedKnownHostsProvider.KnownHostsCandidate.class, "transferFixtureReservation");
                if (mode == 106) {
                    candidate.close();
                    return zeroed(exactBody) && field(reservation, "transfer") == null;
                }
                if (mode == 107) {
                    review.close();
                    return !zeroed(exactBody) && field(candidate, "content") == exactBody
                            && field(reservation, "transfer") == null;
                }
                if (mode == 108) {
                    gate = new java.util.concurrent.FutureTask<>(() -> {
                        synchronized (stateLock) {
                            reviewLocked.countDown();
                            awaitTransferLatch(revokeDone, "candidate close did not finish");
                        }
                        return true;
                    });
                    Thread gateThread = new Thread(gate, "fixture-transfer-final-review-gate");
                    gateThread.setDaemon(true); gateThread.start();
                    awaitTransferLatch(reviewLocked, "final review lock was not acquired");
                    closer = new java.util.concurrent.FutureTask<>(() -> {
                        try {
                            synchronized (candidate) {
                                Object moved = field(reservation, "transfer");
                                require(moved != null && field(candidate, "content") == null
                                                && field(moved, "content") == exactBody && !zeroed(exactBody),
                                        "candidate close did not observe the exact moved body");
                                candidate.close();
                                return zeroed(exactBody);
                            }
                        } finally { revokeDone.countDown(); }
                    });
                } else {
                    closer = new java.util.concurrent.FutureTask<>(() -> {
                        synchronized (stateLock) {
                            reviewLocked.countDown();
                            synchronized (candidate) {
                                Object moved = field(reservation, "transfer");
                                require(moved != null && field(candidate, "content") == null
                                                && field(moved, "content") == exactBody && !zeroed(exactBody),
                                        "review close did not observe the exact moved body");
                                review.close();
                                return zeroed(exactBody);
                            }
                        }
                    });
                }
                Thread closerThread = new Thread(closer, "fixture-transfer-racing-revocation");
                closerThread.setDaemon(true); closerThread.start();
                if (mode == 109) awaitTransferLatch(reviewLocked, "racing review lock was not acquired");
                awaitTransferMonitor(closerThread, candidate, owner, null, null);
            }
            require(closer.get(5, java.util.concurrent.TimeUnit.SECONDS), "racing revocation did not wipe the body");
            return gate == null || gate.get(5, java.util.concurrent.TimeUnit.SECONDS);
        });
        Thread racer = new Thread(controller, "fixture-transfer-race-controller");
        racer.setDaemon(true);
        require(transferRaceClockHook == null && transferRaceClockOwner == null, "transfer race hook was already armed");
        transferRaceClockOwner = owner;
        transferRaceClockHook = () -> {
            racer.start();
            awaitTransferLatch(holding, "transfer competitor did not hold its monitor");
        };
        try {
            denied(() -> reservation.transferFor(child), "racing fixture transfer");
            require(controller.get(5, java.util.concurrent.TimeUnit.SECONDS), "transfer race did not reach its outcome");
            require((Boolean)field(reservation, "revoked"), "racing revocation left a live reservation");
            Object moved = field(reservation, "transfer");
            if (mode <= 107) {
                require(moved == null && !(Boolean)field(candidate, "consumed"),
                        "earlier revocation allowed the body to move");
            } else {
                require(moved != null && (Boolean)field(candidate, "consumed")
                                && field(candidate, "content") == null && (Boolean)field(moved, "closed")
                                && field(moved, "content") == null && zeroed(exactBody),
                        "post-transfer revocation did not erase the exact body before return");
            }
        } finally {
            transferRaceClockHook = null;
            transferRaceClockOwner = null;
        }
    }

    private static void candidateReservationFixture(int mode, SshNativeSession parent,
            SshNativeSession child, SshNativeSession.JumpChildOwner owner,
            SshNativeCallbackAdapter callback, SshManagedKnownHostsProvider provider,
            SshManagedKnownHostsProvider.ChildTrustSnapshot source, SshNativeRoleBootstrap.Issued role,
            Store store, SshNativeCallbackAdapter.SignedNewFixtureReview review, byte[][] owned) throws Exception {
        Object pending = field(review, "pending");
        SshHostKeyAuthority.Evidence evidence = (SshHostKeyAuthority.Evidence)field(pending, "evidence");
        String candidateId = (String)field(pending, "candidateId");
        Object candidateIdentity = field(pending, "candidateIdentity");
        SshManagedKnownHostsProvider.KnownHostsCandidate candidate =
                (SshManagedKnownHostsProvider.KnownHostsCandidate)field(provider, "candidate");
        require(candidate != null && !zeroed(owned[0]), "fixture candidate is unavailable");
        denied(() -> provider.reserveProvisionalFixture(source, evidence, candidateId,
                candidateIdentity, review, child, null), "unapproved provider reservation");
        denied(() -> review.reserveFixtureCandidate(null, child), "unapproved fixture reservation");
        require(field(provider, "fixtureReservation") == null && field(provider, "candidate") == candidate,
                "unapproved reservation changed the candidate");
        review.requireLive();

        java.util.concurrent.atomic.AtomicReference<SshHostKeyReviewMailbox> box =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean cancelledAfterReservation = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean competedAfterReservation = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean revokedAtFinalTimeCheck = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<byte[]> consumedAlias = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<SshTrustProposal> earlierProposal = new java.util.concurrent.atomic.AtomicReference<>();
        SshHostKeyReviewMailbox.LiveCheck live = () -> {
            try {
                if (mode == 94 && field(provider, "fixtureReservation") != null
                        && cancelledAfterReservation.compareAndSet(false, true)) {
                    Thread cancel = new Thread(() -> box.get().cancel(), "fixture-reservation-cancel");
                    cancel.start(); cancel.join(2_000L);
                    require(!cancel.isAlive(), "mailbox cancellation did not complete");
                }
                if (mode == 97 && field(provider, "fixtureReservation") != null
                        && competedAfterReservation.compareAndSet(false, true)) {
                    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
                    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
                    java.util.concurrent.FutureTask<Boolean> proposal = new java.util.concurrent.FutureTask<>(() -> {
                        ready.countDown(); start.await();
                        try { SshTrustProposal value = provider.proposalFor(evidence); value.close(); return false; }
                        catch (VaultException expected) { return true; }
                    });
                    java.util.concurrent.FutureTask<Boolean> alias = new java.util.concurrent.FutureTask<>(() -> {
                        ready.countDown(); start.await();
                        try { byte[] value = candidate.consume(); VaultCodec.wipe(value); return false; }
                        catch (VaultException expected) { return true; }
                    });
                    new Thread(proposal, "fixture-proposal-competitor").start();
                    new Thread(alias, "fixture-alias-competitor").start();
                    require(ready.await(2, java.util.concurrent.TimeUnit.SECONDS), "competitors did not reach barrier");
                    start.countDown();
                    require(proposal.get(2, java.util.concurrent.TimeUnit.SECONDS)
                                    && alias.get(2, java.util.concurrent.TimeUnit.SECONDS),
                            "reserved candidate was consumed by a competitor");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        SshHostKeyReviewMailbox.Sink sink = new SshHostKeyReviewMailbox.Sink() {
            @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) throws VaultException {
                if (mode == 89) {
                    consumedAlias.set(candidate.consume());
                } else if (mode == 90) candidate.close();
                else if (mode == 91) provider.close();
                else if (mode == 98) {
                    java.util.concurrent.FutureTask<SshTrustProposal> proposal =
                            new java.util.concurrent.FutureTask<>(() -> provider.proposalFor(evidence));
                    new Thread(proposal, "fixture-earlier-proposal").start();
                    try { earlierProposal.set(proposal.get(2, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    } catch (Exception failure) { throw new AssertionError(failure); }
                }
                require(box.get().resolve(ticket, mode != 96), "fixture decision was not recorded");
            }
            @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) {
                if (mode == 93) throw new IllegalStateException("fixture dismissal failed");
            }
        };
        SshRoleContext.Clock mailboxClock = () -> {
            try {
                boolean atTimeCheck = false;
                if (mode == 99) for (StackTraceElement frame : Thread.currentThread().getStackTrace())
                    if (frame.getClassName().equals(SshHostKeyReviewMailbox.class.getName())
                            && frame.getMethodName().equals("checkTime")) atTimeCheck = true;
                if (mode == 99 && atTimeCheck && field(provider, "fixtureReservation") != null
                        && revokedAtFinalTimeCheck.compareAndSet(false, true)) {
                    Thread closer = new Thread(candidate::close, "fixture-revoke-before-completion");
                    closer.start(); closer.join(2_000L);
                    require(!closer.isAlive() && zeroed(owned[0]), "candidate revocation did not complete");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } catch (Exception failure) { throw new AssertionError(failure); }
            return now();
        };
        try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(role.role, mailboxClock, live, sink)) {
            box.set(mailbox);
            SshHostKeyReviewMailbox.PendingPoll pump = () -> {
                try { pumpPeers(); }
                catch (IOException failure) { throw new VaultException("SSH_SESSION_JUMP", "Fixture pump failed"); }
            };
            if (mode == 93) {
                try {
                    mailbox.requireSignedNewFixtureApprovedForTest(review, child, pump, null);
                    throw new AssertionError("failed dismissal issued a reservation");
                } catch (IllegalStateException expected) {
                    require("fixture dismissal failed".equals(expected.getMessage()), "wrong dismissal failure");
                }
                require(field(provider, "fixtureReservation") == null,
                        "failed dismissal left a reservation");
            } else if (mode == 89 || mode == 90 || mode == 91 || mode == 94 || mode == 96 || mode == 98
                    || mode == 99) {
                if (mode == 99) {
                    try {
                        mailbox.requireSignedNewFixtureApprovedForTest(review, child, pump, null);
                        throw new AssertionError("pre-completion revocation returned a reservation");
                    } catch (VaultException expected) {
                        require("SSH_HOST_KEY_REVIEW_CANCELLED".equals(expected.code),
                                "wrong pre-completion revocation failure");
                    }
                } else denied(() -> mailbox.requireSignedNewFixtureApprovedForTest(review, child, pump, null),
                        "invalid fixture candidate reservation");
                if (mode == 94 || mode == 99) {
                    Object reservation = field(provider, "fixtureReservation");
                    require((mode == 94 ? cancelledAfterReservation.get() : revokedAtFinalTimeCheck.get())
                                    && reservation != null
                                    && (Boolean)field(reservation, "revoked"),
                            "pre-completion cancellation or revocation left a live reservation");
                } else require(field(provider, "fixtureReservation") == null,
                        "failed approval left a reservation");
                if (mode == 89) require(consumedAlias.get() != null && !zeroed(owned[0])
                                && consumedAlias.get() != owned[0]
                                && java.security.MessageDigest.isEqual(consumedAlias.get(), owned[0]),
                        "earlier alias consumption lost its independent bytes");
                if (mode == 98) require(earlierProposal.get() != null && field(provider, "candidate") == null
                                && field(provider, "activeProposal") == earlierProposal.get()
                                && zeroed(owned[0]) && !zeroed((byte[])field(earlierProposal.get(), "content")),
                        "earlier provider proposal lost its independent content");
            } else {
                SshManagedKnownHostsProvider.FixtureCandidateReservation reservation =
                        mailbox.requireSignedNewFixtureApprovedForTest(review, child, pump, null);
                require(reservation != null && field(provider, "fixtureReservation") == reservation,
                        "approved candidate was not reserved");
                if (mode == 97) require(competedAfterReservation.get(), "reservation competitors did not run");
                reservation.requireHeldFor(child);
                denied(() -> reservation.requireHeldFor(parent), "foreign reservation session");
                java.util.concurrent.FutureTask<Boolean> wrongOwner = new java.util.concurrent.FutureTask<>(() -> {
                    try { reservation.close(); return false; }
                    catch (IllegalStateException expected) { return true; }
                });
                Thread wrong = new Thread(wrongOwner, "fixture-reservation-wrong-owner");
                wrong.start();
                require(wrongOwner.get(2, java.util.concurrent.TimeUnit.SECONDS), "wrong owner closed reservation");
                reservation.requireHeldFor(child);
                java.util.concurrent.FutureTask<Boolean> aliasConsume = new java.util.concurrent.FutureTask<>(() -> {
                    try { byte[] value = candidate.consume(); VaultCodec.wipe(value); return false; }
                    catch (VaultException expected) { return true; }
                });
                Thread alias = new Thread(aliasConsume, "fixture-candidate-alias");
                alias.start();
                require(aliasConsume.get(2, java.util.concurrent.TimeUnit.SECONDS), "candidate alias consumed reservation");
                denied(() -> { SshTrustProposal proposal = provider.proposalFor(evidence); proposal.close(); },
                        "reserved candidate proposal");
                reservation.requireHeldFor(child);
                if (mode >= 106 && mode <= 109) {
                    candidateTransferRaceFixture(mode, child, callback, review, candidate, reservation, owned[0]);
                } else if (mode >= 100 && mode <= 105) {
                    denied(() -> reservation.transferFor(parent), "foreign transfer session");
                    java.util.concurrent.FutureTask<Boolean> wrongTransferOwner = new java.util.concurrent.FutureTask<>(() -> {
                        try { reservation.transferFor(child); return false; }
                        catch (VaultException expected) { return true; }
                    });
                    Thread transferWrong = new Thread(wrongTransferOwner, "fixture-transfer-wrong-owner");
                    transferWrong.start();
                    require(wrongTransferOwner.get(2, java.util.concurrent.TimeUnit.SECONDS),
                            "wrong owner moved the candidate");
                    reservation.requireHeldFor(child);
                    if (mode == 104) {
                        reservation.close();
                        denied(() -> reservation.transferFor(child), "closed reservation transfer");
                        require(!zeroed(owned[0]), "closed reservation destroyed the original candidate");
                    } else if (mode == 105) {
                        Thread closer = new Thread(candidate::close, "fixture-close-before-transfer");
                        closer.start(); closer.join(2_000L);
                        require(!closer.isAlive() && zeroed(owned[0]), "earlier candidate close did not wipe");
                        denied(() -> reservation.transferFor(child), "closed candidate transfer");
                    } else {
                        SshManagedKnownHostsProvider.FixtureCandidateTransfer transfer = reservation.transferFor(child);
                        require(field(candidate, "content") == null && (Boolean)field(candidate, "consumed")
                                        && field(transfer, "content") == owned[0] && !zeroed(owned[0]),
                                "transfer did not move the exact candidate body");
                        transfer.requireHeldFor(child);
                        denied(() -> transfer.requireHeldFor(parent), "foreign fixture transfer session");
                        java.util.concurrent.FutureTask<Boolean> wrongTransferClose =
                                new java.util.concurrent.FutureTask<>(() -> {
                                    try { transfer.close(); return false; }
                                    catch (IllegalStateException expected) { return true; }
                                });
                        Thread wrongClose = new Thread(wrongTransferClose, "fixture-transfer-close-wrong-owner");
                        wrongClose.start();
                        require(wrongTransferClose.get(2, java.util.concurrent.TimeUnit.SECONDS),
                                "wrong owner closed the fixture transfer");
                        transfer.requireHeldFor(child);
                        denied(() -> reservation.transferFor(child), "duplicate fixture transfer");
                        denied(() -> reservation.requireHeldFor(child), "already transferred reservation");
                        denied(() -> { byte[] value = candidate.consume(); VaultCodec.wipe(value); },
                                "transferred candidate alias");
                        denied(() -> { SshTrustProposal proposal = provider.proposalFor(evidence); proposal.close(); },
                                "transferred candidate ordinary proposal");
                        if (mode == 100) transfer.close();
                        else {
                            Runnable revoke = mode == 101 ? candidate::close : mode == 102 ? review::close : provider::close;
                            Thread closer = new Thread(revoke, "fixture-transferred-candidate-revoke");
                            closer.start(); closer.join(2_000L);
                            require(!closer.isAlive(), "fixture transfer revocation did not complete");
                        }
                        require(zeroed(owned[0]), "transfer revocation did not wipe the exact body");
                        denied(() -> transfer.requireHeldFor(child), "revoked fixture transfer");
                    }
                } else if (mode == 92) {
                    Thread closer = new Thread(candidate::close, "fixture-candidate-close");
                    closer.start(); closer.join(2_000L);
                    require(!closer.isAlive() && zeroed(owned[0]), "candidate close did not wipe content");
                    denied(() -> reservation.requireHeldFor(child), "closed candidate reservation");
                } else if (mode == 95) {
                    mailbox.cancel();
                    reservation.requireHeldFor(child);
                    Thread revoke = new Thread(review::close, "fixture-review-revoke");
                    revoke.start(); revoke.join(2_000L);
                    require(!revoke.isAlive(), "review revocation did not complete");
                    denied(() -> reservation.requireHeldFor(child), "revoked fixture reservation");
                    require(!zeroed(owned[0]), "review revocation lost candidate ownership");
                } else {
                    reservation.close();
                    denied(() -> reservation.requireHeldFor(child), "closed fixture reservation");
                    require(!zeroed(owned[0]), "reservation close destroyed its candidate");
                }
                denied(() -> { byte[] value = candidate.consume(); VaultCodec.wipe(value); },
                        "cleanup-only candidate consumption");
            }
        } finally { VaultCodec.wipe(consumedAlias.get()); }
        int[] effects = counters();
        require(callback.hostProof() == null && callback.kexProof() == null && store.commits == 0
                        && effects[3] == 0 && effects[4] == 0 && effects[5] == 0
                        && effects[11] == 0 && effects[12] == 0,
                "fixture reservation acquired authority");
        System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode + " candidate-reservation=non-authorizing commits=0");
    }

    private static void missingScopeReflection(SshNativeCallbackAdapter callback,
            SshManagedKnownHostsProvider provider, SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshNativeRoleBootstrap.Issued role, Store store) throws Exception {
        java.lang.reflect.Method scope = SshNativeCallbackAdapter.class.getDeclaredMethod(
                "nativeProvisionalNewFixtureScope", long.class, int.class);
        scope.setAccessible(true);
        try {
            scope.invoke(callback, callback.nativeCallbackToken(), 1);
            throw new AssertionError("the fixture scope JNI symbol was unexpectedly available");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            require(expected.getCause() instanceof UnsatisfiedLinkError, "unexpected missing-symbol failure");
        }
        java.lang.reflect.Method candidate = SshNativeCallbackAdapter.class.getDeclaredMethod(
                "onNativeProvisionalNewCandidateForFixture", long.class, int.class, int.class,
                String.class, String.class, String.class, int.class, String.class, String.class,
                byte[].class, byte[].class, String.class, byte[].class);
        candidate.setAccessible(true);
        byte[] base = source.receipt().baseDigest();
        byte[] body = "test-only-candidate".getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = VaultCodec.digest(body);
        try {
            require(!(Boolean)candidate.invoke(callback, role.token, role.role.hop, role.role.port,
                            role.role.connectionId, role.role.targetIdentity, role.role.host,
                            SshNativeCallbackAdapter.HOST_NEW, "ssh-ed25519", targetFingerprint(),
                            base, replacement, "candidate-child", body),
                    "reflection without the native scope was admitted");
            require(zeroed(body) && field(callback, "pendingProvisionalFixture") == null
                            && field(provider, "candidate") == null && callback.hostProof() == null
                            && callback.kexProof() == null && store.commits == 0,
                    "missing-scope reflection retained a candidate or authority");
        } finally { VaultCodec.wipe(base); VaultCodec.wipe(replacement); VaultCodec.wipe(body); }
        System.out.println("SSH_FRESH_VAULT_JNI mode=31 missing-scope-reflection=denied pending=0 candidate=0");
    }

    private static void childTurn(SshNativeSession parent, SshNativeSession child) throws Exception {
        pumpPeers(); parent.step(); child.pumpJumpRelay(); child.step(); child.pumpJumpRelay();
    }

    private static void childKexPendingDeadline(SshNativeSession parent, SshNativeSession child,
            SshNativeCallbackAdapter callback, long hopDeadline) throws Exception {
        long before = pendingKexDrains();
        for (int count = 0; count < 200_000; count++) {
            pumpPeers(); parent.step(); child.pumpJumpRelay();
            SshNativeSession.StepResult step = child.step();
            if (callback.kexProof() != null && !step.complete) {
                require(pendingKexDrains() > before, "KEX did not wait for an outbound buffer");
                while (now() < hopDeadline) Thread.yield();
                try { child.pumpJumpRelay(); throw new AssertionError("expired KEX bytes advanced"); }
                catch (VaultException expected) { /* A new relay permit is refused. */ }
                pumpPeers();
                require(counters()[3] == 0 && counters()[5] == 0,
                        "target authentication began after KEX timeout");
                return;
            }
            require(!step.complete, "KEX completed before the test observed its pending output");
        }
        throw new AssertionError("KEX did not reach a pending-output state");
    }

    private static void childSavedAuthentication(int mode, SshNativeSession parent, SshNativeSession child,
            SshNativeCallbackAdapter parentCallback, SshNativeCallbackAdapter childCallback,
            SshNativeRoleBootstrap.Issued parentRole, SshPasswordConnector.TrustCommitState childState,
            AppSecrets vault, SshPublicFileLease view, SshSharedPublicView shared, Store store, long deadline) throws Exception {
        child.beginUserauthService();
        for (int count = 0; !child.userauthServiceReady(); count++) {
            require(count < 200_000, "child service stalled");
            childTurn(parent, child);
        }
        child.beginAuthDiscovery();
        SshNativeSession.AuthDiscoveryStatus discovery;
        for (int count = 0; (discovery = child.authDiscoveryResult()).result
                == SshNativeSession.AuthDiscoveryResult.PENDING; count++) {
            require(count < 200_000, "child discovery stalled");
            childTurn(parent, child);
        }
        if (mode == 7) {
            require(discovery.result == SshNativeSession.AuthDiscoveryResult.NONE_SUCCESS,
                    "none authentication should succeed without a password prompt");
            try { child.selectedPasswordPromptStage(); throw new AssertionError("none success allowed a password prompt"); }
            catch (VaultException expected) { return; }
        }
        require(discovery.result == SshNativeSession.AuthDiscoveryResult.METHODS
                        && (discovery.methods & SshNativeSession.AuthDiscoveryStatus.PASSWORD) != 0,
                "target did not offer password");
        Object stage = child.selectedPasswordPromptStage();
        child.requireSelectedPasswordPromptStageLive(stage);
        child.pollSelectedPasswordPrompt(stage);
        if (mode == 4 || mode == 5) armEffectCancellation(parentCallback.nativeCallbackToken(), mode == 4 ? 1 : 2);
        if (mode == 6) childState.cancellation.cancel();
        if (mode == 8) armNativeInvalidation(parentRole.token, 1, 2);
        if (mode == 9) armNativeInvalidation(view.nativeToken(), 1, 3);
        if (mode == 10 || mode == 11 || mode == 12 || mode == 17) {
            effectVault = vault; effectShared = shared;
            javaInvalidation = mode == 10 ? 1 : mode == 11 ? 2 : mode == 12 ? 3 : 4;
            armJavaInvalidation(1);
        }
        if (mode == 13) armNativeInvalidation(parentCallback.nativeCallbackToken(), 3, 1);
        if (mode == 14) armNativeInvalidation(parentCallback.nativeCallbackToken(), 4, 1);
        if (mode == 15) armEffectDeadline(deadline);
        if (mode == 16) armNativeInvalidation(childCallback.nativeCallbackToken(), 1, 1);
        if (mode == 23) {
            store.observedVault = vault;
            store.beforeReceiver = childState.cancellation::cancel;
        }
        if (mode >= 122 && mode <= 124) {
            effectCancellation = childState.cancellation;
            javaInvalidation = 5;
            armJavaInvalidation(mode == 122 ? 3 : mode == 123 ? 4 : 2);
        }
        try {
            child.beginSelectedSavedPassword(stage);
            if (mode != 3 && (mode < 20 || mode == 23 || mode >= 122 && mode <= 124))
                throw new AssertionError("cancelled selected password was admitted");
        } catch (VaultException denied) {
            if (mode == 3 || mode >= 20 && mode != 23 && (mode < 122 || mode > 124)) throw denied;
            if (mode == 23) require(store.cancelledBeforeReceiver && store.releaseReads == 2,
                    "internal credential clone cancellation was not reached");
            if (mode >= 122 && mode <= 124) {
                int[] actual = counters();
                require(childState.cancellation.stopRequested() && effectAvailable() && activeEffects() == 0
                                && actual[9] == 1 && actual[5] == 0 && actual[13] == 1,
                        "shared STOP did not deny the child effect or release its native permit");
                childState.cancellation.cancel();
                effectCancellation = null;
                System.out.println("SSH_SHARED_STOP_JNI mode=" + mode
                        + " child-effect=denied generation=live permits=0 password-peer=0 array=wiped");
            }
            return;
        }
        if (mode >= 20) {
            if (mode >= 21) require(child.step().bytesWritten > 0,
                    "password ciphertext did not reach the child socketpair");
            if (mode == 22) {
                long before = wireChannelBytes();
                child.pumpJumpRelay();
                require(wireChannelBytes() > before,
                        "password ciphertext did not reach the parent output queue");
            }
            if (mode == 24 || mode == 25) {
                long before = earlyReplyDenials();
                earlyTargetReply(mode == 24);
                try { child.step(); throw new AssertionError("early authentication reply was accepted"); }
                catch (VaultException expected) { /* The outbound password remains queued. */ }
                require(earlyReplyDenials() == before + 1, "the early reply missed the drain fence");
                try { parent.step(); throw new AssertionError("parent reused the unsent password output"); }
                catch (VaultException expected) { /* The reply itself failed the parent before cancel. */ }
            }
            childState.cancellation.cancel();
            try {
                if (mode == 20) child.step();
                else if (mode == 21) child.pumpJumpRelay();
                else parent.step();
                throw new AssertionError("cancelled queued password advanced");
            } catch (VaultException expected) { /* Closed before this send boundary. */ }
            pumpPeers();
            require(counters()[5] == 0, "target received a password after cancellation");
            return;
        }
        SshNativeSession.PasswordAuthResult result;
        for (int count = 0; (result = child.passwordAuthResult())
                == SshNativeSession.PasswordAuthResult.PENDING; count++) {
            require(count < 200_000, "child password authentication stalled");
            childTurn(parent, child);
        }
        require(result == SshNativeSession.PasswordAuthResult.SUCCESS,
                "known child saved password was not authenticated");
    }

    private static void verifyViewCanReopen(AppSecrets vault) throws Exception {
        SshVaultAdapter files = new SshVaultAdapter(vault, (connection, target) -> { });
        AppSecrets.SshPublicReadContext context = files.fileContext(now() + 20_000, () -> { });
        VaultOperation operation = files.fileRequest(ROOT, CONNECTION, context);
        SshPublicFileLease probe;
        try { probe = files.openPublicFiles(operation, ROOT, CONNECTION); }
        catch (Exception | Error failure) { files.cancel(operation); throw failure; }
        try { require(probe.active() && probe.nativeToken() > 0, "native view could not reopen"); }
        finally { probe.close(); }
    }

    private static void sessionCancellationFailure(int mode, SshNativeSession session, int descriptor)
            throws Exception {
        int fault = mode == 117 ? 110 : mode == 118 ? 112 : mode == 119 ? 115 : mode == 120 ? 113 : mode;
        boolean retryable = fault == 110;
        boolean beginBefore = fault == 111, beginAfter = fault == 112;
        boolean endBefore = fault == 113, endAfter = fault == 114;
        boolean callback = fault == 115;
        if (retryable) armCleanupBeginFailure(1);
        else if (beginBefore || beginAfter) armCleanupBeginUnknown(beginBefore ? 1 : 2);
        else if (endBefore || endAfter) armCleanupEndFailure(endBefore ? 1 : 2);
        else if (callback) armLegacyEffectInvalidationFailure();
        else throw new IllegalArgumentException("unknown session cancellation fixture");
        try { session.cancel(); throw new AssertionError("injected session cancellation succeeded"); }
        catch (LinkageError expected) { /* The session retains an unconfirmed native outcome. */ }
        require(session.cancellationRequested() && !session.closed() && !fdClosed(descriptor),
                "session cancellation lost its stop state or native owner");
        try { session.step(); throw new AssertionError("unconfirmed session advanced its native step"); }
        catch (VaultException expected) { require("SSH_SESSION_CANCELLED".equals(expected.code), "wrong stopped step error"); }
        try { session.close(); throw new AssertionError("unconfirmed session closed"); }
        catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong session close error"); }
        require(!session.closed() && !fdClosed(descriptor), "failed cancellation released its descriptor");
        if (retryable) {
            session.cancel();
            session.close();
            require(session.closed() && fdClosed(descriptor) && effectAvailable(),
                    "explicitly unstarted session invalidation did not retry and close");
        } else {
            if (endBefore || endAfter) beginOtherInvalidator();
            else if (callback) armLegacyEffectInvalidationFailure();
            else armCleanupBeginUnknown(1);
            try { session.cancel(); throw new AssertionError("unknown session invalidation retried"); }
            catch (IllegalStateException expected) { /* Do not repeat an uncertain native operation. */ }
            if (endBefore || endAfter) {
                require(!effectAvailable(), "session retry released another invalidator");
                endOtherInvalidator();
                require(effectAvailable() != endBefore, "session end was repeated");
            } else if (callback) {
                try {
                    SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                    throw new AssertionError("session retry consumed the callback failure probe");
                } catch (LinkageError expected) { /* The legacy probe remained armed. */ }
            } else {
                try {
                    SshNativeCallbackAdapter.beginJumpCleanupInvalidation();
                    throw new AssertionError("session retry consumed the checked failure probe");
                } catch (LinkageError expected) { /* The checked probe remained armed. */ }
            }
            try { session.close(); throw new AssertionError("unknown session cleanup was released"); }
            catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong retained error"); }
            require(!session.closed() && !fdClosed(descriptor), "session owner or descriptor was released");
        }
        System.out.println("SSH_SESSION_CANCEL_JNI mode=" + mode + " ownership=pass");
    }

    private static void sessionInvalidationDoesNotHoldMonitor(SshNativeSession session, int descriptor)
            throws Exception {
        Object lock = field(session, "stateLock");
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch observed = new java.util.concurrent.CountDownLatch(1);
        Thread cancel = new Thread(() -> {
            try { session.cancel(); }
            catch (Throwable error) { failure.set(error); }
        });
        Thread observer = new Thread(() -> {
            synchronized (lock) { observed.countDown(); }
        });
        acquireSharedTestEffect();
        boolean observedWhileDraining = false;
        try {
            cancel.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (effectAvailable() && System.nanoTime() < deadline) Thread.yield();
            require(!effectAvailable(), "session invalidation did not start while effect was held");
            observer.start();
            observedWhileDraining = observed.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally { releaseSharedTestEffect(); }
        cancel.join(5000);
        observer.join(5000);
        require(!cancel.isAlive() && !observer.isAlive() && failure.get() == null,
                "session invalidation or observer did not finish");
        require(observedWhileDraining && session.cancellationRequested(),
                "session monitor blocked while native effects drained");
        session.close();
        require(session.closed() && fdClosed(descriptor) && effectAvailable(),
                "session did not close after confirmed cancellation");
        System.out.println("SSH_SESSION_CANCEL_JNI mode=116 monitor=available ownership=pass");
    }

    private static void terminalSessionRetry(int mode, SshNativeSession session, SshNativeSession parent,
            int descriptor, int parentDescriptor, SshSessionOwner.Cancellation cancellation) throws Exception {
        StopScheduler scheduler = new StopScheduler();
        int[] releases = {0}, pending = {0}, unknown = {0};
        SshTerminalSession terminal = new SshTerminalSession(session, () -> { }, scheduler,
                new SshTerminalSession.Sink() {
                    @Override public void data(byte[] bytes, Runnable consumed) { throw new AssertionError("output"); }
                    @Override public void state(String state, Long exit, String reason) {
                        if ("SSH_TERMINAL_CLEANUP_PENDING".equals(reason)) pending[0]++;
                    }
                    @Override public void cancellationUnconfirmed() { unknown[0]++; }
                }, () -> releases[0]++, cancellation);
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            armCleanupBeginFailure(1);
            if (mode == 128) armCleanupBeginUnknown(1);
            if (mode == 131) {
                // Test-only fault: fail the real parent JNI cancel without changing its native owner.
                cancellation.cancel();
                SshNativeCallbackAdapter callback = (SshNativeCallbackAdapter)field(parent, "callback");
                long token = (Long)field(callback, "nativeCallback");
                require(token > 0, "parent callback token unavailable");
                try {
                    setField(callback, "nativeCallback", 0L);
                    terminal.requestCancel();
                } finally { setField(callback, "nativeCallback", token); }
                require((Long)field(callback, "nativeCallback") == token,
                        "fault injection did not restore the parent callback token");
            } else {
                try {
                    terminal.requestCancel();
                    if (mode == 128) throw new AssertionError("mixed unknown cancellation was accepted");
                } catch (LinkageError expected) {
                    if (mode != 128) throw expected;
                    require(expected.getSuppressed().length == 1, "mixed cancellation lost its second failure");
                }
            }
            require(!terminal.closed() && !session.closed() && !fdClosed(descriptor) && releases[0] == 0,
                    "terminal released its session before cancellation resolved");
            if (mode == 131) {
                require((Boolean)field(session, "cancellationExplicitlyUnstarted")
                                && (Boolean)field(parent, "callbackCancelCleanupOnly")
                                && field(parent, "cancellationUnconfirmed") == null,
                        "mixed chain did not preserve checked refusal and callback-only outcomes");
                try { parent.close(); throw new AssertionError("parent closed before its child"); }
                catch (VaultException expected) {
                    require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong reverse-close refusal");
                }
                require(!parent.closed() && !session.closed() && !fdClosed(parentDescriptor),
                        "refused parent close released a chain owner");
                scheduler.next();
                require(terminal.closed() && session.closed() && parent.closed()
                                && fdClosed(descriptor) && fdClosed(parentDescriptor)
                                && field(parent, "jumpChildOwner") == null
                                && releases[0] == 1 && unknown[0] == 0,
                        "mixed callback-only retry did not close the child before its parent");
                System.out.println("SSH_TERMINAL_SESSION_RETRY_JNI mode=131 "
                        + "mixed=checked-unstarted+callback-only reverse-close=pass");
                return;
            }
            if (mode == 128) {
                require((Boolean)field(session, "cancellationExplicitlyUnstarted")
                                && field(parent, "cancellationUnconfirmed") != null,
                        "mixed chain did not retain distinct cancellation outcomes");
                scheduler.next();
                require(unknown[0] == 1 && pending[0] == 1, "mixed chain did not retain an unconfirmed owner");
                armCleanupBeginFailure(1);
                terminal.requestCancel();
                require(scheduler.turns.isEmpty() && (Boolean)field(session, "cancellationExplicitlyUnstarted")
                                && SshNativeCallbackAdapter.beginJumpCleanupInvalidation() == -1,
                        "mixed unknown outcome retried a session");
                System.out.println("SSH_TERMINAL_SESSION_RETRY_JNI mode=128 mixed=retained unknown=no-retry");
                return;
            }
            if (mode == 127) {
                armCleanupBeginUnknown(1);
                try { scheduler.next(); throw new AssertionError("unknown retry was accepted"); }
                catch (LinkageError expected) {
                    require(expected.getSuppressed().length == 1, "retry lost its original failure");
                }
                scheduler.next();
                require(unknown[0] == 1 && pending[0] == 1 && !terminal.closed(),
                        "unknown retry did not retain its owner");
                armCleanupBeginFailure(1);
                terminal.requestCancel();
                require(scheduler.turns.isEmpty() && SshNativeCallbackAdapter.beginJumpCleanupInvalidation() == -1,
                        "unknown retry was attempted again");
                System.out.println("SSH_TERMINAL_SESSION_RETRY_JNI mode=127 retry-unknown=retained no-retry=pass");
                return;
            }
            if (mode == 129) {
                java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Thread concurrent = new Thread(() -> {
                    try { session.cancel(); } catch (Throwable error) { failure.set(error); }
                }, "fixture-concurrent-session-cancel");
                acquireSharedTestEffect();
                try {
                    concurrent.start();
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while (effectAvailable() && System.nanoTime() < deadline) Thread.yield();
                    require(!effectAvailable() && concurrent.isAlive(), "concurrent invalidation did not wait");
                    for (int count = 0; count < 5; count++) scheduler.next();
                    require(!terminal.closed() && releases[0] == 0 && pending[0] == 0 && unknown[0] == 0
                                    && (Integer)field(terminal, "cancelRetryAttempts") == 0,
                            "waiting for concurrent cancellation consumed retry budget or released owner");
                } finally {
                    releaseSharedTestEffect();
                    concurrent.join(5000);
                }
                require(!concurrent.isAlive() && failure.get() == null,
                        "concurrent session cancellation did not complete");
            }
            if (mode == 126) {
                for (int count = 0; count < 3; count++) {
                    armCleanupBeginFailure(1);
                    scheduler.next();
                }
                require(scheduler.turns.isEmpty() && pending[0] == 1 && unknown[0] == 0
                                && !terminal.closed() && !session.closed() && !fdClosed(descriptor),
                        "retry exhaustion did not retain its exact owner without busy scheduling");
                terminal.requestCancel();
            }
            if (mode == 130) {
                // Match requestStop's immediate request, then its queued owner stop().
                armCleanupBeginFailure(1);
                scheduler.next();
                require((Integer)field(terminal, "cancelRetryAttempts") == 1,
                        "first automatic retry was not recorded");
                armCleanupBeginFailure(1);
                terminal.stop();
                require((Integer)field(terminal, "cancelRetryAttempts") == 2,
                        "owner stop reset the original Stop retry budget");
                armCleanupBeginFailure(1);
                scheduler.next();
                require(scheduler.turns.isEmpty() && pending[0] == 1 && unknown[0] == 0
                                && (Integer)field(terminal, "cancelRetryAttempts") == 3
                                && !terminal.closed() && !session.closed() && !fdClosed(descriptor),
                        "owner stop did not preserve bounded retries and the original session");
                terminal.requestCancel();
            }
            scheduler.next();
            require(terminal.closed() && session.closed() && fdClosed(descriptor) && releases[0] == 1
                            && unknown[0] == 0,
                    "confirmed same-session retry did not close and release");
            System.out.println("SSH_TERMINAL_SESSION_RETRY_JNI mode=" + mode
                    + " retry=confirmed owner=released pending=" + pending[0]);
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static void runCase(int mode) throws Exception {
        if (mode >= 79 && mode <= 87) armPendingInputFixture();
        int descriptor = openPeers();
        char[] password = "synthetic local vault password".toCharArray();
        byte[] hosts = null, binding = filled(9);
        byte[][] provisionalOwned = new byte[(mode >= 60 && mode <= 69 && mode != 67)
                || mode == 74 || mode == 76 || mode == 77 || mode == 78
                || mode >= 79 && mode <= 109 ? 7 : 4][];
        Store store;
        try {
            hosts = hostfile(provisionalMode(mode) ? 1 : mode <= 2 ? mode : 0);
            store = encryptedStore(hosts, password);
        } catch (Exception | Error failure) {
            try { closeFd(descriptor); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            finally {
                closePeers(); Arrays.fill(password, '\0'); VaultCodec.wipe(hosts); VaultCodec.wipe(binding);
            }
            throw failure;
        }
        Clock clock = new Clock();
        AppSecrets vault = new AppSecrets(store, clock);
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        SshSocketOwnership socket = new SshSocketOwnership(descriptor, SshFreshVaultRealJniTest::closeFd);
        SshPublicFileLease view = null;
        SshSharedPublicView shared = null;
        SshManagedKnownHostsProvider parentTrust = null, childTrust = null;
        SshManagedKnownHostsProvider.ChildTrustSnapshot childSource = null;
        SshNativeCallbackAdapter parentCallback = null, childCallback = null;
        SshNativeSession parent = null;
        SshNativeSession.JumpChildOwner childOwner = null;
        SshNativeRoleBootstrap.Issued parentRole = null, childRole = null;
        int[] childDescriptors = null;
        Throwable primaryFailure = null;
        try {
            vault.attachSshValidator(manifest -> { });
            vault.session.foreground(true);
            vault.unlock(password, () -> { });
            long deadline = now() + (mode == 15 ? 5_000 : 55_000);
            SshVaultAdapter files = new SshVaultAdapter(vault, (connection, target) -> { }, proposal -> { });
            AppSecrets.SshPublicReadContext context = files.fileContext(deadline, () -> { });
            VaultOperation read = files.fileRequest(ROOT, CONNECTION, context);
            try { view = files.openPublicFiles(read, ROOT, CONNECTION); }
            catch (Exception | Error failure) { files.cancel(read); throw failure; }
            shared = new SshSharedPublicView(view);
            parentRole = issue(registry, view, clock,
                    SshRoleContext.Program.JUMP, 0, "target:jump", "jump.invalid", deadline, binding);
            SshPasswordConnector.TrustCommitState parentState = new SshPasswordConnector.TrustCommitState();
            parentState.bindIssued(parentRole);
            SshManagedKnownHostsProvider.SnapshotReceipt receipt = SshManagedKnownHostsProvider.snapshotFor(
                    files, parentRole.role, clock, ROOT, HOSTS, "hosts", "hosts identity");
            parentTrust = SshManagedKnownHostsProvider.bindForNativeHandoff(files, receipt, parentState);
            parentCallback = callback(parentRole, registry, new SshHostKeyAuthority(), clock, vault, parentTrust,
                    false, parentState.cancellation);
            parent = SshNativeSession.openManaged(parentCallback, shared, socket, HOSTS, "candidate-root", 4096);
            rootKex(parent);
            if (mode == 121) {
                parent.beginUserauthService();
                for (int count = 0; !parent.userauthServiceReady(); count++) {
                    require(count < 200_000, "root service stalled");
                    pumpPeers(); parent.step();
                }
                publishStop(parentState.cancellation);
                SshNativeSession.NativeBinding nativeBinding =
                        (SshNativeSession.NativeBinding)field(parent, "nativeBinding");
                try {
                    nativeBinding.beginAuthDiscovery((Long)field(parent, "nativeHandle"));
                    throw new AssertionError("native root discovery crossed STOP");
                } catch (IOException expected) { /* The native client rejected the shared STOP. */ }
                pumpPeers();
                require(counters()[0] == 1 && counters()[1] == 0 && activeEffects() == 0,
                        "root admitted a post-STOP authentication effect");
                parentState.cancellation.cancel();
                System.out.println("SSH_SHARED_STOP_JNI mode=121 root-service=admitted next-discovery=denied peer=0");
                return;
            }
            rootAuthentication(parent);
            if (mode >= 125 && mode <= 127 || mode == 129 || mode == 130) {
                terminalSessionRetry(mode, parent, null, descriptor, -1, parentState.cancellation);
                return;
            }
            if (mode >= 110 && mode <= 116) {
                SshNativeCallbackAdapter.enableJumpEffectFence();
                try {
                    if (mode == 116) sessionInvalidationDoesNotHoldMonitor(parent, descriptor);
                    else sessionCancellationFailure(mode, parent, descriptor);
                }
                finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
                return;
            }
            SshSavedPassword saved = new SshSavedPassword(new SshPasswordProfile("example.invalid", 22, USER));
            childRole = issue(registry, view, clock, SshRoleContext.Program.CLIENT, 1,
                    mode >= 3 && mode <= 26 || mode >= 122 && mode <= 124
                            ? saved.target : "target:child", "example.invalid", deadline, binding);
            SshNativeSession.JumpAdmission admission = parent.issueJumpAdmission(parentRole, childRole, shared, view);
            SshPasswordConnector.TrustCommitState childState = parentState.forNextHop(childRole, view, admission);
            childSource = SshManagedKnownHostsProvider.snapshotForChild(files, childRole.role, clock,
                    view, ROOT, HOSTS, "hosts", "hosts identity");
            childTrust = SshManagedKnownHostsProvider.bindForNativeHandoff(files, childSource.receipt(), childState);
            if (provisionalMode(mode)) armProvisionalFixture(mode == 30 ? parentCallback : null);
            childCallback = callback(childRole, registry, new SshHostKeyAuthority(), clock, vault, childTrust,
                    mode >= 3 && mode <= 26 || mode >= 122 && mode <= 124, childState.cancellation);
            trackTargetCallback(childCallback.nativeCallbackToken());
            childOwner = SshNativeSession.prepareJumpChildFresh(parent, childCallback, shared, HOSTS,
                    "candidate-child", 4096, childSource, childState);
            if (mode == 43 || mode == 44) {
                cleanupRetry(mode, parent, childOwner, childSource, childTrust);
                return;
            }
            if (mode == 46 || mode == 47) {
                cleanupUnknownEnd(mode, parent, childOwner, childSource);
                return;
            }
            if (mode == 48 || mode == 49) {
                providerUnknownEnd(mode, parent, childOwner, childSource, childTrust);
                return;
            }
            if (mode >= 50 && mode <= 53) {
                cleanupUnknownBegin(mode, parent, childOwner, childSource, childTrust);
                return;
            }
            if (mode == 54) {
                providerIndependentCleanup(parent, childOwner, files, childCallback, childTrust, childSource, childRole);
                return;
            }
            if (mode == 18) {
                long hopDeadline = Math.min(deadline, now() + 250);
                armJumpDeadline(hopDeadline);
                try {
                    parent.beginJump(childOwner, hopDeadline);
                    throw new AssertionError("expired hop deadline allowed a channel open");
                } catch (VaultException expected) {
                    int[] state = counters();
                    require(state[2] == 0 && state[3] == 0 && state[9] == 1,
                            "expired hop deadline emitted a channel open");
                    System.out.println("SSH_FRESH_VAULT_JNI mode=18 jump-deadline=denied channel-open=0");
                    return;
                }
            }
            if (mode == 19) {
                long hopDeadline = Math.min(deadline, now() + 250);
                parent.beginJump(childOwner, hopDeadline);
                while (now() < hopDeadline) Thread.yield();
                try { parent.step(); throw new AssertionError("expired queued channel open was sent"); }
                catch (VaultException expected) { /* Native socket send must reject. */ }
                pumpPeers();
                require(counters()[2] == 0, "expired channel open reached the peer");
                System.out.println("SSH_FRESH_VAULT_JNI mode=19 expired-queued-channel=denied peer=0");
                return;
            }
            long hopDeadline = mode == 26 || mode == 64 || mode == 81
                    ? Math.min(deadline, now() + 1_500) : deadline;
            openJump(parent, childOwner, hopDeadline);
            captureChildOpen();
            SshNativeSession child = childOwner.open();
            childDescriptors = finishChildOpen();
            if (mode == 128 || mode == 131) {
                terminalSessionRetry(mode, child, parent, childDescriptors[0], descriptor, childState.cancellation);
                return;
            }
            if (mode >= 117 && mode <= 120) {
                sessionCancellationFailure(mode, child, childDescriptors[0]);
                if (mode == 117) {
                    require(child.closed() && field(parent, "jumpChildOwner") == null,
                            "retryable child cancellation retained its completed link");
                    for (int fd : childDescriptors) require(fdClosed(fd), "completed child retained a descriptor");
                } else {
                    try { childOwner.close(); throw new AssertionError("unconfirmed child owner closed"); }
                    catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong child error"); }
                    try { parent.close(); throw new AssertionError("parent closed before its child"); }
                    catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong parent error"); }
                    require(!child.closed() && !parent.closed() && field(parent, "jumpChildOwner") == childOwner,
                            "unconfirmed child lost its parent or owner");
                    for (int fd : childDescriptors) require(!fdClosed(fd), "unconfirmed child released a descriptor");
                }
                return;
            }
            if (mode >= 60 && mode <= 109)
                denied(child::signedNewFixtureReview, "unsigned fixture review");
            if (mode == 45) {
                cleanupDestroyedChild(parent, child, childOwner, childSource);
                return;
            }
            if (mode >= 55 && mode <= 59) {
                adoptedCallbackCleanup(mode, parent, child, childOwner, childCallback, childTrust);
                return;
            }
            if (mode >= 33 && mode <= 36 || mode >= 70 && mode <= 73 || mode == 75) {
                consumeProvider = childTrust;
                consumeSource = childSource;
                consumeCallback = childCallback;
                preconsumeDone = false;
                replaceCandidate = mode >= 35;
                mutatedCandidate = null;
                provisionalCleanupFault = mode == 70 || mode == 71 ? 1
                        : mode == 72 ? 2 : mode == 73 ? 3 : mode == 75 ? 4 : 0;
                Arrays.fill(consumedOwned, null);
                armProvisionalConsumption(mode == 33 || mode == 35 || mode == 70
                        || mode == 72 || mode == 73 || mode == 75 ? 2 : 3);
            }
            if (mode == 26) {
                childKexPendingDeadline(parent, child, childCallback, hopDeadline);
                System.out.println("SSH_FRESH_VAULT_JNI mode=26 kex-outbound=wait expired-relay=denied");
                return;
            }
            if (mode == 31) {
                missingScopeReflection(childCallback, childTrust, childSource, childRole, store);
                return;
            }
            if (mode == 41) {
                unusedHandoffAbortRetry(childCallback, childTrust, childSource, childRole, store);
                return;
            }
            if (provisionalMode(mode)) {
                childProvisionalFixture(mode, parent, child, childOwner, childCallback, childTrust, childSource,
                        childRole, parentRole, store, provisionalOwned);
                if (mode == 42) childOwnerAbortRetry(parent, child, childOwner, childCallback,
                        childTrust, childDescriptors, provisionalOwned, store);
                return;
            }
            childKex(mode, parent, child, childCallback);
            if (mode == 7) targetNoneSuccess();
            if (mode >= 3) childSavedAuthentication(mode, parent, child, parentCallback, childCallback,
                    parentRole, childState, vault, view, shared, store, deadline);
            if (mode >= 122 && mode <= 124) return;
            int[] actual = counters();
            require(actual[0] == 1 && actual[1] == 1 && actual[2] == 3,
                    "root did not authenticate and open the forwarding channel");
            require(actual[3] == (mode >= 3 ? 1 : 0) && actual[4] == (mode >= 3 ? 1 : 0)
                            && actual[5] == (mode == 3 ? 1 : 0), "wrong target authentication requests");
            require(actual[6] == (mode == 1 ? 1 : 0) && actual[7] == (mode == 2 ? 1 : 0)
                            && actual[8] == (mode == 1 || mode == 2 ? 1 : 0),
                    "wrong native candidate classification or target peer key");
            if (mode == 4 || mode == 5)
                require(actual[9] == 1 && actual[10] == 1, "effect cancellation or native password wiping failed");
            if (mode == 3) require(actual[10] == 1, "native password buffer was not wiped");
            if (mode >= 8 && mode <= 17) require(actual[9] == 1 && actual[10] == 1,
                    "exact invalidator or native password wiping failed");
            if (mode >= 20 && mode != 23)
                require(actual[11] == 1 && actual[12] == 1 && actual[13] == 1,
                        "selected password callback did not complete and wipe its array");
            if (mode == 23) require(actual[11] == 1 && actual[12] == 0 && actual[5] == 0,
                    "vault cancellation handed a password out of the vault");
            if (mode == 3 || mode == 5 || mode == 14)
                require(actual[11] == 1 && actual[12] == 1 && actual[13] == 1,
                        "selected callback or returned Java array cleanup failed");
            if (mode == 13) require(actual[11] == 1 && actual[12] == 0 && actual[13] == 1,
                    "cancelled JNI copy did not clear its returned Java array");
            require(store.commits == 0, "unexpected trust commit");
            System.out.println("SSH_FRESH_VAULT_JNI mode=" + mode + " root=none-auth target="
                    + (mode == 0 ? "known-kex-guarded" : mode == 1 ? "new-denied" : mode == 2
                            ? "changed-denied" : mode == 3 ? "saved-success" : mode == 7 ? "none-success" : "cancel-denied")
                    + " target-password=" + actual[5] + " commits=0 peer=synthetic store=encrypted-memory");
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            final Throwable bodyFailure = primaryFailure;
            final SshNativeSession.JumpChildOwner cleanupOwner = childOwner;
            final SshNativeSession cleanupParent = parent;
            final int[] cleanupDescriptors = childDescriptors;
            final SshNativeCallbackAdapter cleanupChildCallback = childCallback, cleanupParentCallback = parentCallback;
            final SshNativeRoleBootstrap.Issued cleanupChildRole = childRole, cleanupParentRole = parentRole;
            final SshManagedKnownHostsProvider cleanupChildTrust = childTrust, cleanupParentTrust = parentTrust;
            final SshManagedKnownHostsProvider.ChildTrustSnapshot cleanupSource = childSource;
            final SshSharedPublicView cleanupShared = shared;
            final SshPublicFileLease cleanupView = view;
            final byte[] cleanupHosts = hosts;
            CleanupFailures cleanup = new CleanupFailures(bodyFailure);
            /* Isolated uncertainty leaves its owner and dependencies intact;
             * the test process exits without pretending cleanup succeeded. */
            if (!isolatedUncertainMode(mode)) {
                boolean childClosed = cleanup.run(() -> { if (cleanupOwner != null) cleanupOwner.close(); });
                boolean parentClosed = false;
                if (childClosed) {
                    cleanup.run(() -> {
                        if (mode >= 27 && mode <= 29 || mode >= 37 && mode <= 40 || mode == 42
                        || mode >= 60 && mode <= 69 || mode == 74 || mode >= 79 && mode <= 109)
                            for (byte[] bytes : provisionalOwned) {
                                if (bytes == null && bodyFailure != null) continue;
                                require(zeroed(bytes), "closed provisional material was not wiped");
                            }
                    });
                    cleanup.run(() -> {
                        if (cleanupDescriptors != null) for (int fd : cleanupDescriptors)
                            require(fdClosed(fd), "child view/socket descriptor remains open: " + fd);
                    });
                    parentClosed = cleanup.run(() -> {
                        if (cleanupParent != null) cleanupParent.close(); else socket.close();
                        require(fdClosed(descriptor), "root descriptor remains open");
                    });
                }
                if (childClosed && parentClosed) {
                    boolean childCallbackClosed = cleanup.run(() -> {
                        if (cleanupChildCallback != null) cleanupChildCallback.close();
                    });
                    boolean parentCallbackClosed = cleanup.run(() -> {
                        if (cleanupParentCallback != null) cleanupParentCallback.close();
                    });
                    boolean childRoleClosed = childCallbackClosed && cleanup.run(() -> {
                        if (cleanupChildCallback == null && cleanupChildRole != null)
                            SshNativeRoleBootstrap.release(cleanupChildRole, registry,
                                    SshNativeRoleBootstrap.productionRelease());
                    });
                    boolean parentRoleClosed = parentCallbackClosed && cleanup.run(() -> {
                        if (cleanupParentCallback == null && cleanupParentRole != null)
                            SshNativeRoleBootstrap.release(cleanupParentRole, registry,
                                    SshNativeRoleBootstrap.productionRelease());
                    });
                    boolean childTrustClosed = childRoleClosed && cleanup.run(() -> {
                        if (cleanupChildTrust != null) cleanupChildTrust.close();
                    });
                    boolean parentTrustClosed = parentRoleClosed && cleanup.run(() -> {
                        if (cleanupParentTrust != null) cleanupParentTrust.close();
                    });
                    boolean sourceClosed = childTrustClosed && cleanup.run(() -> {
                        if (cleanupSource != null) cleanupSource.close();
                    });
                    if (childTrustClosed && parentTrustClosed && sourceClosed) {
                        boolean sharedSealed = cleanup.run(() -> { if (cleanupShared != null) cleanupShared.seal(); });
                        boolean viewClosed = sharedSealed && cleanup.run(() -> {
                            if (cleanupShared != null) cleanupShared.close();
                            else if (cleanupView != null) cleanupView.close();
                        });
                        if (viewClosed) {
                            cleanup.run(() -> {
                                if (cleanupShared != null) {
                                    if (mode == 10) vault.unlock(password, () -> { });
                                    verifyViewCanReopen(vault);
                                }
                            });
                            boolean registryClosed = cleanup.run(registry::close);
                            boolean vaultLocked = registryClosed && cleanup.run(() -> vault.session.lock());
                            if (vaultLocked) {
                                cleanup.run(SshFreshVaultRealJniTest::closePeers);
                                cleanup.run(() -> {
                                    consumeProvider = null; consumeSource = null; consumeCallback = null;
                                    provisionalCleanupFault = 0; mutatedCandidate = null;
                                    VaultCodec.wipe(store.encrypted);
                                });
                            }
                        }
                    }
                }
            }
            cleanup.run(() -> { Arrays.fill(password, '\0'); VaultCodec.wipe(cleanupHosts); VaultCodec.wipe(binding); });
            cleanup.finish();
        }
    }

    private static final class StopScheduler implements SshTerminalSession.Scheduler {
        private final java.util.ArrayDeque<Runnable> turns = new java.util.ArrayDeque<>();
        @Override public void later(Runnable action, long delay) { turns.add(action); }
        void next() { turns.remove().run(); }
    }

    private static final class StopPort implements SshTerminalSession.Port {
        int writes, steps, resizes, cancels, closes;
        boolean rejected;
        String cancellationFault;
        @Override public void begin(int columns, int rows) { }
        @Override public SshTerminalSession.Status status() {
            return new SshTerminalSession.Status(rejected ? SshNativeSession.ShellPhase.REJECTED
                    : SshNativeSession.ShellPhase.RUNNING, 0, null);
        }
        @Override public void step() { steps++; }
        @Override public int write(byte[] bytes) { writes++; return bytes.length; }
        @Override public byte[] read(int maximum) { return new byte[0]; }
        @Override public boolean resize(int columns, int rows) { resizes++; return true; }
        @Override public boolean publicationLive() { return true; }
        @Override public void cancel() throws IOException {
            cancels++;
            if (cancellationFault == null) return;
            if ("cancel-begin".equals(cancellationFault)) armLegacyEffectInvalidationFailure();
            else armCleanupEndFailure("cancel-end-before".equals(cancellationFault) ? 1 : 2);
            boolean begun = SshNativeCallbackAdapter.beginJumpEffectInvalidation();
            SshNativeCallbackAdapter.endJumpEffectInvalidation(begun);
        }
        @Override public void close() { closes++; }
    }

    /** Each failure can leave a process-wide invalidator outstanding. */
    private static void terminalCancellationFailure(String mode) throws Exception {
        boolean shared = mode.startsWith("cancel-shared-");
        boolean portFailure = mode.startsWith("cancel-port-");
        boolean duringFailure = "cancel-terminal-failure".equals(mode);
        String fault = shared ? "cancel-" + mode.substring("cancel-shared-".length())
                : portFailure ? "cancel-" + mode.substring("cancel-port-".length()) : mode;
        boolean begin = "cancel-begin".equals(fault) || duringFailure;
        boolean endBefore = "cancel-end-before".equals(fault);
        if (!begin && !endBefore && !"cancel-end-after".equals(fault))
            throw new IllegalArgumentException("unknown terminal cancellation fixture");
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            StopScheduler scheduler = new StopScheduler();
            StopPort port = new StopPort();
            SshSessionOwner.Cancellation cancellation = new SshSessionOwner.Cancellation();
            SshTerminalState state = new SshTerminalState();
            state.begin(1, "ssh", cancellation);
            state.state(1, "running", null, null);
            state.data(1, new byte[] {9});
            require(((byte[])state.snapshot().get("bytes")).length == 1, "fixture replay was not retained");
            int[] releases = {0};
            String[] reason = {null};
            SshTerminalSession terminal = new SshTerminalSession(port, scheduler,
                    new SshTerminalSession.Sink() {
                        @Override public void data(byte[] bytes, Runnable consumed) { consumed.run(); }
                        @Override public void state(String value, Long exit, String code) { reason[0] = code; }
                    }, () -> releases[0]++, cancellation);
            terminal.start(80, 24);
            scheduler.next();
            terminal.write(new byte[] {1});
            terminal.resize(100, 40);
            int stepsBefore = port.steps;
            if (portFailure) port.cancellationFault = fault;
            else if (begin) armLegacyEffectInvalidationFailure();
            else armCleanupEndFailure(endBefore ? 1 : 2);
            if (duringFailure) port.rejected = true;
            try {
                if (duringFailure) scheduler.next();
                else if (shared) cancellation.cancel();
                else terminal.requestCancel();
                throw new AssertionError("injected cancellation succeeded");
            }
            catch (LinkageError expected) { /* The exact native outcome is unknown to Java. */ }
            require(cancellation.stopRequested() && !cancellation.admitOutput() && !terminal.publicationLive()
                            && cancellation.invalidationUnconfirmed() != portFailure
                            && cancellation.invalidationFailed() != portFailure
                            && cancellation.getAsBoolean(),
                    "cancellation failure did not retain its stop and unknown state");
            require(((byte[])state.snapshot().get("bytes")).length == 0,
                    "cancelled replay remained visible");
            if (!duringFailure) scheduler.next();
            require(port.writes == 0 && port.steps == stepsBefore && port.resizes == 0
                            && port.cancels == (portFailure ? 1 : 0) && port.closes == 0 && releases[0] == 0
                            && terminal.queuedInputBytes() == 0 && !terminal.closed()
                            && "SSH_TERMINAL_CLEANUP_PENDING".equals(reason[0]),
                    "unconfirmed cancellation advanced I/O or released its terminal owner");
            if (begin) armLegacyEffectInvalidationFailure();
            else beginOtherInvalidator();
            if (portFailure) terminal.requestCancel();
            else {
                try {
                    if (shared) cancellation.cancel();
                    else terminal.requestCancel();
                    throw new AssertionError("uncertain cancellation retried");
                }
                catch (IllegalStateException expected) { /* No second JNI invalidation. */ }
            }
            if (begin) {
                try {
                    SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                    throw new AssertionError("retry consumed the JNI failure probe");
                } catch (LinkageError expected) { /* The probe was still armed. */ }
            } else {
                require(!effectAvailable(), "a second end released another invalidator");
                endOtherInvalidator();
                require(effectAvailable() != endBefore, "uncertain end was repeated");
            }
            require(port.cancels == (portFailure ? 1 : 0) && port.closes == 0 && releases[0] == 0,
                    "repeated cancellation released its original owner");
            System.out.println("SSH_TERMINAL_CANCEL_JNI " + mode + " stopped=pass owner=retained");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static final class SharedViewProbe implements SshRoleContext.PublicView, SshNativeSession.ViewBinding {
        boolean live = true;
        boolean failRevoke, failClose;
        int revokes, closes;
        @Override public boolean active() { return live; }
        @Override public long token() { return 1; }
        void revoke() {
            revokes++;
            if (failRevoke) failLegacyInvalidation();
            live = false;
        }
        @Override public void close() {
            closes++;
            if (failClose) failLegacyInvalidation();
            live = false;
        }
    }

    private static void failLegacyInvalidation() {
        try { armLegacyEffectInvalidationFailure(); }
        catch (IOException failure) { throw new AssertionError(failure); }
        boolean begun = SshNativeCallbackAdapter.beginJumpEffectInvalidation();
        SshNativeCallbackAdapter.endJumpEffectInvalidation(begun);
    }

    /** Use isolated JVMs when an unknown native invalidator may remain outstanding. */
    private static void sharedViewCancellationFailure(String mode) throws Exception {
        boolean retryable = "shared-retryable".equals(mode);
        boolean beginBefore = "shared-begin-before".equals(mode);
        boolean beginAfter = "shared-begin-after".equals(mode);
        boolean endBefore = "shared-end-before".equals(mode);
        boolean endAfter = "shared-end-after".equals(mode);
        boolean revoke = "shared-revoke".equals(mode);
        boolean close = "shared-close".equals(mode);
        if (!retryable && !beginBefore && !beginAfter && !endBefore && !endAfter && !revoke && !close)
            throw new IllegalArgumentException("unknown shared view fixture");
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            SharedViewProbe source = new SharedViewProbe();
            SshSharedPublicView shared = new SshSharedPublicView(source, source, source::revoke);
            java.lang.reflect.Constructor<SshSharedPublicView.Binding> constructor =
                    SshSharedPublicView.Binding.class.getDeclaredConstructor(
                            SshSharedPublicView.class, SshNativeCallbackAdapter.class);
            constructor.setAccessible(true);
            SshSharedPublicView.Binding binding = constructor.newInstance(shared, null);
            require(binding.active(), "shared fixture binding was not live");
            if (retryable) armCleanupBeginFailure(1);
            else if (beginBefore || beginAfter) armCleanupBeginUnknown(beginBefore ? 1 : 2);
            else if (endBefore || endAfter) armCleanupEndFailure(endBefore ? 1 : 2);
            else if (revoke) source.failRevoke = true;
            else { source.failClose = true; shared.seal(); }
            try {
                if (close) shared.close(); else shared.cancel();
                throw new AssertionError("injected shared view failure succeeded");
            } catch (LinkageError expected) { /* The native result is not inferred from the exception. */ }
            if (!close) require(!binding.active(), "cancelled shared binding remained active");
            require(source.live && source.revokes == (revoke ? 1 : 0)
                            && source.closes == (close ? 1 : 0),
                    "shared view lost or prematurely released its source");
            try { shared.close(); throw new AssertionError("unconfirmed shared view closed"); }
            catch (VaultException expected) { require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong close error"); }
            if (retryable) {
                shared.cancel();
                require(!source.live && source.revokes == 1, "confirmed pre-begin retry did not revoke");
                shared.close();
                require(source.closes == 1 && effectAvailable(), "retry did not complete exact owner cleanup");
                shared.cancel();
            } else {
                if (endBefore || endAfter) beginOtherInvalidator();
                else if (beginBefore || beginAfter) armCleanupBeginUnknown(1);
                else armLegacyEffectInvalidationFailure();
                try { shared.cancel(); throw new AssertionError("unknown shared cancellation retried"); }
                catch (IllegalStateException expected) { /* No repeated revoke or native invalidation. */ }
                if (endBefore || endAfter) {
                    require(!effectAvailable(), "a repeated end released another invalidator");
                    endOtherInvalidator();
                    require(effectAvailable() != endBefore, "unknown end was repeated");
                } else if (beginBefore || beginAfter) {
                    try {
                        SshNativeCallbackAdapter.beginJumpCleanupInvalidation();
                        throw new AssertionError("retry consumed the checked JNI failure probe");
                    } catch (LinkageError expected) { /* The checked probe remained armed. */ }
                } else {
                    try {
                        SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                        throw new AssertionError("retry consumed the legacy JNI failure probe");
                    } catch (LinkageError expected) { /* The legacy probe remained armed. */ }
                }
                require(source.live && source.revokes == (revoke ? 1 : 0)
                                && source.closes == (close ? 1 : 0),
                        "unknown shared operation was retried or released");
            }
            System.out.println("SSH_SHARED_VIEW_JNI " + mode + " ownership=pass");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static void sharedViewInvalidationDoesNotHoldMonitor() throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            SharedViewProbe source = new SharedViewProbe();
            SshSharedPublicView shared = new SshSharedPublicView(source, source, source::revoke);
            java.lang.reflect.Constructor<SshSharedPublicView.Binding> constructor =
                    SshSharedPublicView.Binding.class.getDeclaredConstructor(
                            SshSharedPublicView.class, SshNativeCallbackAdapter.class);
            constructor.setAccessible(true);
            SshSharedPublicView.Binding binding = constructor.newInstance(shared, null);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Boolean> active = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch observed = new java.util.concurrent.CountDownLatch(1);
            Thread cancel = new Thread(() -> {
                try { shared.cancel(); }
                catch (Throwable error) { failure.set(error); }
            });
            Thread observer = new Thread(() -> {
                try { active.set(binding.active()); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { observed.countDown(); }
            });
            acquireSharedTestEffect();
            boolean observedWhileDraining = false;
            try {
                cancel.start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (effectAvailable() && System.nanoTime() < deadline) Thread.yield();
                require(!effectAvailable(), "shared invalidation did not start while effect was held");
                observer.start();
                observedWhileDraining = observed.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally { releaseSharedTestEffect(); }
            cancel.join(5000);
            observer.join(5000);
            require(!cancel.isAlive() && !observer.isAlive() && failure.get() == null,
                    "shared invalidation or observer did not finish");
            require(observedWhileDraining && Boolean.FALSE.equals(active.get()),
                    "shared monitor blocked a binding check while native effects drained");
            require(source.revokes == 1 && !source.live && effectAvailable(),
                    "shared cancellation did not finish after the effect released");
            shared.close();
            require(source.closes == 1, "shared owner did not close after confirmed cancellation");
            System.out.println("SSH_SHARED_VIEW_JNI shared-drain monitor=available ownership=pass");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static final class VaultLeaseProbe implements VaultSession.Revocation {
        boolean live = true;
        boolean confirmed;
        boolean throwAfterRevoke;
        boolean returnUnconfirmed;
        int revokes;

        @Override public boolean active() { return live; }
        @Override public boolean revocationConfirmed() { return confirmed; }
        @Override public void revoke() {
            revokes++;
            live = false;
            if (throwAfterRevoke) throw new UnsatisfiedLinkError("synthetic unconfirmed lease revoke");
            confirmed = !returnUnconfirmed;
        }
    }

    private static Object vaultField(VaultSession session, String name) throws Exception {
        java.lang.reflect.Field field = VaultSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(session);
    }

    private static VaultSession vaultWithLease(VaultLeaseProbe lease) throws Exception {
        VaultSession session = new VaultSession(() -> 1L);
        session.foreground(true);
        session.unlock(filled(9));
        session.attachRevocation(session.epoch(), lease);
        return session;
    }

    private static SshPublicFileLease realVaultLease(long epoch) throws Exception {
        VaultEnvelope envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], new byte[32],
                new byte[0]);
        VaultOperation operation = new VaultOperation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_FILES,
                ROOT, "", CONNECTION, envelope, epoch, now() + 55_000);
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "root", ROOT, "root identity", "Host *\n".getBytes(StandardCharsets.UTF_8), List.of());
        return SshPublicFileLease.create(new SshFileSnapshot(operation, List.of(config)));
    }

    private static long leaseToken(SshPublicFileLease lease) throws Exception {
        java.lang.reflect.Field field = SshPublicFileLease.class.getDeclaredField("token");
        field.setAccessible(true);
        return field.getLong(lease);
    }

    /** Exercise the production Java lease and its actual native handle, not the probe above. */
    private static void vaultManagedLeaseFailure(String mode) throws Exception {
        boolean normal = "vault-lease-normal".equals(mode);
        boolean retryable = "vault-lease-retryable".equals(mode);
        boolean beginBefore = "vault-lease-begin-before".equals(mode);
        boolean beginAfter = "vault-lease-begin-after".equals(mode);
        boolean endBefore = "vault-lease-end-before".equals(mode);
        boolean endAfter = "vault-lease-end-after".equals(mode);
        boolean nestedRetryable = "vault-session-lease-retryable".equals(mode);
        boolean nestedEndBefore = "vault-session-lease-end-before".equals(mode);
        boolean nestedEndAfter = "vault-session-lease-end-after".equals(mode);
        boolean nested = nestedRetryable || nestedEndBefore || nestedEndAfter;
        if (!normal && !retryable && !beginBefore && !beginAfter && !endBefore && !endAfter && !nested)
            throw new IllegalArgumentException("unknown managed vault lease fixture");
        VaultSession session = new VaultSession(() -> now());
        session.foreground(true);
        session.unlock(filled(9));
        long epoch = session.epoch();
        byte[] key = (byte[])vaultField(session, "key");
        SshPublicFileLease lease = realVaultLease(epoch);
        session.attachRevocation(epoch, lease);
        long token = lease.nativeToken();
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            if (normal) {
                session.lock();
                requireVaultLocked(session, key, epoch);
                require(!lease.active() && lease.revocationConfirmed() && leaseToken(lease) == 0,
                        "native lease did not acknowledge revocation");
                SshPublicFileLease next = realVaultLease(session.epoch());
                next.close();
                require(next.revocationConfirmed(), "native handle did not reopen after confirmed cleanup");
            } else {
                if (retryable || nestedRetryable) armCleanupBeginFailure(nested ? 2 : 1);
                else if (beginBefore || beginAfter) armCleanupBeginUnknown(beginBefore ? 1 : 2);
                else armCleanupEndFailure(endBefore || nestedEndBefore ? 1 : 2);
                boolean failed = false;
                try { if (nested) session.lock(); else lease.revoke(); }
                catch (RuntimeException | LinkageError expected) { failed = true; }
                require(failed, "injected native lease failure succeeded");
                require(!lease.active() && !lease.revocationConfirmed() && leaseToken(lease) == token,
                        "unconfirmed native lease did not retain its original token");
                try { lease.nativeToken(); throw new AssertionError("unconfirmed lease exposed its token"); }
                catch (VaultException expected) { /* Java use is denied independently of native state. */ }
                if (nested) {
                    requireVaultLocked(session, key, epoch);
                    require(vaultField(session, "revocation") == lease, "vault discarded its native lease");
                }
                boolean uncertainEnd = endBefore || endAfter || nestedEndBefore || nestedEndAfter;
                if (uncertainEnd) beginOtherInvalidator();
                else armCleanupBeginUnknown(1);
                try { lease.revoke(); } catch (RuntimeException | LinkageError expected) { /* Sticky unknown. */ }
                try { lease.close(); } catch (VaultException | RuntimeException | LinkageError expected) {
                    /* An unconfirmed lease cannot be released by close. */
                }
                require(!lease.revocationConfirmed() && leaseToken(lease) == token,
                        "repeated cleanup discarded the native token");
                if (nested) {
                    try { session.lock(); } catch (RuntimeException | LinkageError expected) { /* Sticky unknown. */ }
                    require(vaultField(session, "revocation") == lease, "vault lost its retained native lease");
                }
                if (uncertainEnd) {
                    require(!effectAvailable(), "lease repeated an end owned by another invalidator");
                    endOtherInvalidator();
                    require(effectAvailable() != (endBefore || nestedEndBefore), "lease repeated its unknown end");
                } else {
                    try {
                        SshNativeCallbackAdapter.beginJumpCleanupInvalidation();
                        throw new AssertionError("lease repeated the checked JNI begin");
                    } catch (LinkageError expected) { /* The test fault remained armed. */ }
                }
            }
            System.out.println("SSH_VAULT_LEASE_JNI " + mode + " ownership=pass");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static void vaultSharedLeaseDestroyFailure(String mode) throws Exception {
        int injected;
        switch (mode) {
            case "vault-shared-destroy-false-before": injected = 1; break;
            case "vault-shared-destroy-false-after": injected = 2; break;
            case "vault-shared-destroy-throw-before": injected = 3; break;
            case "vault-shared-destroy-throw-after": injected = 4; break;
            default: throw new IllegalArgumentException("unknown shared lease destroy fixture");
        }
        VaultSession session = new VaultSession(() -> now());
        session.foreground(true);
        session.unlock(filled(9));
        long epoch = session.epoch();
        byte[] key = (byte[])vaultField(session, "key");
        SshPublicFileLease lease = realVaultLease(epoch);
        session.attachRevocation(epoch, lease);
        long token = lease.nativeToken();
        SshSharedPublicView shared = new SshSharedPublicView(lease);
        shared.seal();
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            armLeaseDestroyFailure(injected);
            try { shared.cancel(); throw new AssertionError("unconfirmed destroy reported shared cancellation"); }
            catch (RuntimeException | LinkageError expected) { /* Native result is not confirmed. */ }
            require(!lease.active() && !lease.revocationConfirmed() && leaseToken(lease) == token
                            && leaseDestroyCalls() == 1,
                    "unconfirmed destroy did not retain its exact lease token");
            try { shared.cancel(); throw new AssertionError("unconfirmed shared cancellation was repeated"); }
            catch (IllegalStateException expected) { /* Original operation remains unknown. */ }
            try { shared.close(); throw new AssertionError("unconfirmed shared lease was released"); }
            catch (VaultException expected) {
                require("SSH_SESSION_CLEANUP".equals(expected.code), "wrong shared cleanup error");
            }
            try { session.lock(); throw new AssertionError("vault accepted an unconfirmed lease"); }
            catch (RuntimeException | LinkageError expected) { /* Preserve the original lease. */ }
            requireVaultLocked(session, key, epoch);
            require(vaultField(session, "revocation") == lease && leaseToken(lease) == token
                            && leaseDestroyCalls() == 1,
                    "repeated cleanup lost or retried the native lease");
            System.out.println("SSH_VAULT_SHARED_DESTROY_JNI " + mode + " uncertainty=retained");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static void requireVaultLocked(VaultSession session, byte[] originalKey, long previousEpoch)
            throws Exception {
        require(Arrays.equals(originalKey, new byte[originalKey.length]), "vault key was not wiped");
        require(vaultField(session, "key") == null && session.epoch() == previousEpoch + 1,
                "vault did not invalidate its original key and epoch");
        try { session.key(); throw new AssertionError("locked vault returned key material"); }
        catch (VaultException expected) {
            require("VAULT_LOCKED".equals(expected.code) || "VAULT_FOREGROUND_REQUIRED".equals(expected.code),
                    "wrong error for locked vault");
        }
    }

    /** Fault cases run in separate JVMs because an unknown invalidator may remain live. */
    private static void vaultLockFailure(String mode) throws Exception {
        boolean retryable = "vault-lock-retryable".equals(mode);
        boolean beginBefore = "vault-lock-begin-before".equals(mode);
        boolean beginAfter = "vault-lock-begin-after".equals(mode);
        boolean endBefore = "vault-lock-end-before".equals(mode);
        boolean endAfter = "vault-lock-end-after".equals(mode);
        boolean revokeThrow = "vault-lock-revoke-throw".equals(mode);
        boolean revokeUnconfirmed = "vault-lock-revoke-unconfirmed".equals(mode);
        boolean background = "vault-background-begin-before".equals(mode);
        if (!retryable && !beginBefore && !beginAfter && !endBefore && !endAfter
                && !revokeThrow && !revokeUnconfirmed && !background)
            throw new IllegalArgumentException("unknown vault lock fixture");
        VaultLeaseProbe lease = new VaultLeaseProbe();
        VaultSession session = vaultWithLease(lease);
        long epoch = session.epoch();
        byte[] key = (byte[])vaultField(session, "key");
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            if (retryable) armCleanupBeginFailure(1);
            else if (beginBefore || beginAfter || background) armCleanupBeginUnknown(beginAfter ? 2 : 1);
            else if (endBefore || endAfter) armCleanupEndFailure(endBefore ? 1 : 2);
            else if (revokeThrow) lease.throwAfterRevoke = true;
            else lease.returnUnconfirmed = true;
            boolean failed = false;
            try { if (background) session.foreground(false); else session.lock(); }
            catch (RuntimeException | LinkageError expected) { failed = true; }
            require(failed, "unconfirmed vault lock returned successfully");
            requireVaultLocked(session, key, epoch);
            require(vaultField(session, "revocation") == lease, "unconfirmed lease owner was discarded");
            if (background) require(!session.foreground(), "failed invalidation kept vault in foreground");
            require(lease.revokes == (revokeThrow || revokeUnconfirmed || endBefore || endAfter ? 1 : 0),
                    "vault called the original lease unexpectedly");
            if (retryable) {
                session.lock();
                require(lease.revokes == 1 && lease.confirmed, "explicitly unstarted lock did not retry");
                session.unlock(filled(8));
                require(session.unlocked(), "vault could not unlock after confirmed retry");
                session.lock();
            } else {
                if (endBefore || endAfter) beginOtherInvalidator();
                else armCleanupBeginUnknown(1);
                try { session.lock(); } catch (RuntimeException | LinkageError expected) { /* Sticky unknown. */ }
                require(vaultField(session, "revocation") == lease, "repeated lock discarded its owner");
                require(lease.revokes == (revokeThrow || revokeUnconfirmed || endBefore || endAfter ? 1 : 0),
                        "repeated lock retried the unconfirmed lease");
                try { session.unlock(filled(8)); throw new AssertionError("unconfirmed vault unlocked"); }
                catch (VaultException | RuntimeException | LinkageError expected) { /* Retain unknown state. */ }
                if (endBefore || endAfter) {
                    require(!effectAvailable(), "repeated lock ended another invalidator");
                    endOtherInvalidator();
                    require(effectAvailable() != endBefore, "unknown end was repeated");
                } else {
                    try {
                        SshNativeCallbackAdapter.beginJumpCleanupInvalidation();
                        throw new AssertionError("repeated lock consumed the JNI fault probe");
                    } catch (LinkageError expected) { /* Repeated lock did not retry JNI. */ }
                }
            }
            System.out.println("SSH_VAULT_LOCK_JNI " + mode + " key=erased result=guarded");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static void vaultLockWhileNativeEffectDrains() throws Exception {
        VaultLeaseProbe lease = new VaultLeaseProbe();
        VaultSession session = vaultWithLease(lease);
        long epoch = session.epoch();
        byte[] key = (byte[])vaultField(session, "key");
        java.util.concurrent.atomic.AtomicReference<Throwable> lockFailure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> readFailure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch readerEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch readerFinished = new java.util.concurrent.CountDownLatch(1);
        Thread lock = new Thread(() -> {
            try { session.lock(); } catch (Throwable failure) { lockFailure.set(failure); }
        });
        Thread reader = new Thread(() -> {
            readerEntered.countDown();
            try { session.key(); readFailure.set(new AssertionError("draining lock returned key material")); }
            catch (Throwable failure) { readFailure.set(failure); }
            finally { readerFinished.countDown(); }
        });
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            acquireSharedTestEffect();
            boolean readerBlocked;
            try {
                lock.start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (effectAvailable() && System.nanoTime() < deadline) Thread.yield();
                require(!effectAvailable(), "vault invalidation did not start draining the effect");
                reader.start();
                require(readerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "vault reader did not start");
                readerBlocked = !readerFinished.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } finally { releaseSharedTestEffect(); }
            lock.join(5000);
            reader.join(5000);
            require(readerBlocked && !lock.isAlive() && !reader.isAlive() && lockFailure.get() == null,
                    "vault monitor did not order the reader after native drain");
            require(readFailure.get() instanceof VaultException
                            && "VAULT_LOCKED".equals(((VaultException)readFailure.get()).code),
                    "reader did not observe the completed lock");
            requireVaultLocked(session, key, epoch);
            require(lease.revokes == 1 && lease.confirmed && effectAvailable(),
                    "vault did not finish revocation after the admitted effect drained");
            System.out.println("SSH_VAULT_LOCK_JNI vault-lock-drain monitor=ordered key=erased");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static void trustConfirmationAfterInvalidationFailure() throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            for (int kind = 0; kind < 2; kind++) {
                SshPasswordConnector.TrustCommitState trust = new SshPasswordConnector.TrustCommitState();
                if (kind == 0) trust.newPersisted(); else trust.persisted();
                armLegacyEffectInvalidationFailure();
                try { trust.cancellation.cancel(); throw new AssertionError("injected invalidation succeeded"); }
                catch (LinkageError expected) { /* Java cannot establish the native outcome. */ }
                require(trust.cancellation.stopRequested() && trust.cancellation.getAsBoolean()
                                && trust.cancellation.invalidationUnconfirmed() && trust.publicationUncertain(),
                        "fixture did not retain its stop request and unconfirmed write");
                try {
                    if (kind == 0) trust.newProofPublished(); else trust.proofPublished();
                    throw new AssertionError("a stop request was followed by trust confirmation");
                } catch (VaultException expected) {
                    require("VAULT_COMMIT_UNCERTAIN".equals(expected.code), "wrong trust confirmation failure");
                }
                require(trust.publicationUncertain(), "a possibly committed write was reported confirmed");
            }
            SshPasswordConnector.TrustCommitState accepted = new SshPasswordConnector.TrustCommitState();
            accepted.newPersisted();
            accepted.newProofPublished();
            armLegacyEffectInvalidationFailure();
            try { accepted.cancellation.cancel(); throw new AssertionError("injected invalidation succeeded"); }
            catch (LinkageError expected) { /* Confirmation preceded the stop request. */ }
            require(!accepted.publicationUncertain(), "an earlier confirmation was undone");
            System.out.println("SSH_TRUST_STOP_JNI trust-failure synthetic-receipt=guarded");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static void trustConfirmationWhileInvalidationDrains() throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            SshPasswordConnector.TrustCommitState trust = new SshPasswordConnector.TrustCommitState();
            trust.newPersisted();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread cancel = new Thread(() -> {
                try { trust.cancellation.cancel(); }
                catch (Throwable error) { failure.set(error); }
            });
            acquireSharedTestEffect();
            try {
                cancel.start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (effectAvailable() && System.nanoTime() < deadline) Thread.yield();
                require(!effectAvailable() && trust.cancellation.stopRequested()
                                && trust.cancellation.getAsBoolean() && trust.cancellation.invalidationUnconfirmed(),
                        "cancellation did not wait for the admitted native effect");
                try { trust.newProofPublished(); throw new AssertionError("draining stop allowed confirmation"); }
                catch (VaultException expected) {
                    require("VAULT_COMMIT_UNCERTAIN".equals(expected.code), "wrong draining trust failure");
                }
                require(trust.publicationUncertain(), "draining invalidation confirmed trust");
            } finally { releaseSharedTestEffect(); }
            cancel.join(5000);
            require(!cancel.isAlive() && failure.get() == null && trust.cancellation.getAsBoolean()
                            && trust.publicationUncertain() && effectAvailable(),
                    "cancellation did not finish after draining the effect");
            System.out.println("SSH_TRUST_STOP_JNI trust-drain synthetic-receipt=guarded");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static final class CreationCancellationFactory implements SshSessionOwner.Factory {
        final SshSessionOwner.Cancellation cancellation = new SshSessionOwner.Cancellation();
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch checked = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch closeEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch allowClose = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean sawStop;
        boolean blockClose;
        boolean failClose;
        boolean writesTrust;
        boolean tracksSave;
        String creationFailureCode;

        @Override public SshSessionOwner.Cancellation trustCancellation() { return cancellation; }
        @Override public boolean mayWriteTrust() { return writesTrust; }
        @Override public boolean tracksCredentialSave() { return tracksSave; }
        @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) throws Exception {
            entered.countDown();
            require(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS), "creation fixture timed out");
            sawStop = cancelled.getAsBoolean();
            checked.countDown();
            if (creationFailureCode != null) throw new VaultException(creationFailureCode, "synthetic factory result");
            throw new IOException("synthetic creation failure");
        }
        @Override public void close() throws Exception {
            closes.incrementAndGet();
            closeEntered.countDown();
            if (blockClose)
                require(allowClose.await(5, java.util.concurrent.TimeUnit.SECONDS), "close fixture timed out");
            if (failClose) throw new IOException("synthetic close failure");
        }
    }

    private static void requireConnectionFailure(SshSessionOwner owner, String expected) throws Exception {
        try {
            owner.connected.get(5, java.util.concurrent.TimeUnit.SECONDS);
            throw new AssertionError("cancelled owner unexpectedly connected");
        } catch (java.util.concurrent.ExecutionException failure) {
            require(failure.getCause() instanceof VaultException
                            && expected.equals(((VaultException)failure.getCause()).code),
                    "wrong connection failure after cancellation");
        }
    }

    private static void creationStopOwnership(String mode) throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        CreationCancellationFactory factory = new CreationCancellationFactory();
        boolean lockFailure = "creation-lock-failure".equals(mode);
        boolean lockUncertain = "creation-lock-uncertain".equals(mode);
        boolean lockSave = "creation-lock-save".equals(mode);
        boolean serviceFirst = "creation-service-lock-failure".equals(mode);
        boolean ordinaryScreen = "creation-ordinary-then-screen".equals(mode);
        boolean ordinaryService = "creation-ordinary-then-service".equals(mode);
        boolean ordinarySave = "creation-ordinary-save-then-service".equals(mode);
        boolean ordinarySaveScreen = "creation-ordinary-save-then-screen".equals(mode);
        boolean ordinaryUncertain = "creation-ordinary-uncertain-then-service".equals(mode);
        if (lockFailure || lockUncertain || lockSave || serviceFirst
                || ordinaryScreen || ordinaryService || ordinarySave || ordinarySaveScreen || ordinaryUncertain
                || "creation-lock-idle".equals(mode) || "creation-service-idle".equals(mode)) {
            factory.writesTrust = true;
            factory.tracksSave = lockSave || ordinarySave || ordinarySaveScreen;
            factory.creationFailureCode = lockUncertain || ordinaryUncertain ? "VAULT_COMMIT_UNCERTAIN"
                    : lockSave || ordinarySave || ordinarySaveScreen ? "VAULT_IO" : "SSH_CANCELLED";
        }
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch pending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger pendingEvents = new java.util.concurrent.atomic.AtomicInteger();
        SshTerminalState state = new SshTerminalState();
        state.subscribe(event -> {
            if ("SSH_TERMINAL_CLEANUP_PENDING".equals(event.get("reason"))) {
                pendingEvents.incrementAndGet();
                pending.countDown();
            }
        });
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, state, Runnable::run,
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { released.countDown(); }
                });
        Thread invalidator = null;
        boolean heldEffect = false;
        try {
            if ("creation-lock-idle".equals(mode) || "creation-service-idle".equals(mode)
                    || "creation-stop-idle".equals(mode)) {
                armLegacyEffectInvalidationFailure();
                try {
                    if ("creation-lock-idle".equals(mode)) owner.stopForScreenLock(owner.id);
                    else if ("creation-service-idle".equals(mode)) owner.rejectStart();
                    else owner.requestStop();
                    throw new AssertionError("injected invalidation succeeded");
                } catch (LinkageError expected) { /* No factory was admitted. */ }
                requireConnectionFailure(owner, "creation-lock-idle".equals(mode)
                        ? "SSH_SCREEN_LOCKED" : "creation-service-idle".equals(mode)
                        ? "SSH_SERVICE_START" : "SSH_CANCELLED");
                require(factory.cancellation.invalidationUnconfirmed()
                                && factory.closes.get() == 0 && released.getCount() == 1,
                        "idle failed cancellation released its owner");
                System.out.println("SSH_CREATION_STOP_JNI " + mode + " result=pass owner=retained");
                return;
            }
            owner.begin();
            require(factory.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "factory did not start");
            require(!factory.cancellation.getAsBoolean(), "new factory is already cancelled");
            if (ordinaryScreen || ordinaryService || ordinarySave || ordinarySaveScreen || ordinaryUncertain) {
                factory.cancellation.cancel();
                if (ordinaryScreen || ordinarySaveScreen) owner.stopForScreenLock(owner.id); else owner.rejectStart();
                require(!owner.connected.isDone(), "factory result was published before its outcome");
                factory.proceed.countDown();
                require(factory.checked.await(5, java.util.concurrent.TimeUnit.SECONDS) && factory.sawStop,
                        "factory did not observe the ordinary first stop");
                requireConnectionFailure(owner, ordinaryUncertain ? "VAULT_COMMIT_UNCERTAIN"
                        : ordinarySave || ordinarySaveScreen ? "VAULT_IO" : "SSH_CANCELLED");
                require(released.await(5, java.util.concurrent.TimeUnit.SECONDS) && factory.closes.get() == 1,
                        "confirmed ordinary stop did not finish cleanup");
            } else if ("creation-pending-once".equals(mode)) {
                armLegacyEffectInvalidationFailure();
                try { owner.requestStop(); throw new AssertionError("injected invalidation succeeded"); }
                catch (LinkageError expected) { /* The original result is unknown. */ }
                factory.proceed.countDown();
                require(factory.checked.await(5, java.util.concurrent.TimeUnit.SECONDS), "factory did not finish");
                java.util.concurrent.ScheduledExecutorService executor = (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
                executor.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
                require(pending.await(5, java.util.concurrent.TimeUnit.SECONDS), "cleanup pending was not published");
                armLegacyEffectInvalidationFailure();
                try { owner.requestStop(); throw new AssertionError("unknown invalidation retried"); }
                catch (IllegalStateException expected) { /* Keep the original unknown result. */ }
                executor.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
                require(pendingEvents.get() == 1 && "SSH_TERMINAL_CLEANUP_PENDING".equals(state.snapshot().get("reason"))
                                && factory.closes.get() == 0 && released.getCount() == 1,
                        "cleanup pending was duplicated, superseded, or its owner released");
                try { SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                    throw new AssertionError("repeated stop entered native invalidation");
                } catch (LinkageError expected) { /* The probe remained armed. */ }
                java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(4);
                java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<Throwable> concurrentFailure = new java.util.concurrent.atomic.AtomicReference<>();
                Thread[] stops = new Thread[4];
                for (int index = 0; index < stops.length; index++) {
                    stops[index] = new Thread(() -> {
                        try {
                            armLegacyEffectInvalidationFailure();
                            ready.countDown();
                            require(start.await(5, java.util.concurrent.TimeUnit.SECONDS), "repeated stop barrier timed out");
                            try { owner.requestStop(); throw new AssertionError("unknown invalidation retried"); }
                            catch (IllegalStateException expected) { /* No repeated native operation. */ }
                            try { SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                                throw new AssertionError("repeated stop consumed native failure probe");
                            } catch (LinkageError expected) { /* This thread's probe remained armed. */ }
                        } catch (Throwable failure) { concurrentFailure.compareAndSet(null, failure); }
                    });
                    stops[index].start();
                }
                require(ready.await(5, java.util.concurrent.TimeUnit.SECONDS), "repeated stops did not start");
                start.countDown();
                for (Thread stop : stops) stop.join(5000);
                for (Thread stop : stops) require(!stop.isAlive(), "repeated stop did not finish");
                require(concurrentFailure.get() == null && pendingEvents.get() == 1
                                && "SSH_TERMINAL_CLEANUP_PENDING".equals(state.snapshot().get("reason")),
                        "concurrent stops duplicated or superseded cleanup pending");
            } else if (lockFailure || lockUncertain || lockSave || serviceFirst) {
                armLegacyEffectInvalidationFailure();
                try {
                    if (serviceFirst) owner.rejectStart(); else owner.stopForScreenLock(owner.id);
                    throw new AssertionError("injected invalidation succeeded");
                } catch (LinkageError expected) { /* The shared native result is unknown. */ }
                if (serviceFirst) {
                    try { owner.stopForScreenLock(owner.id); throw new AssertionError("unknown invalidation retried"); }
                    catch (IllegalStateException expected) { /* Preserve the first service result. */ }
                }
                require(!owner.connected.isDone(), "trust-capable factory result was published too soon");
                factory.proceed.countDown();
                require(factory.checked.await(5, java.util.concurrent.TimeUnit.SECONDS) && factory.sawStop,
                        "factory did not observe failed stop");
                requireConnectionFailure(owner, lockUncertain ? "VAULT_COMMIT_UNCERTAIN" : lockSave ? "VAULT_IO"
                        : serviceFirst ? "SSH_SERVICE_START" : "SSH_SCREEN_LOCKED");
                require(pending.await(5, java.util.concurrent.TimeUnit.SECONDS)
                                && factory.cancellation.invalidationUnconfirmed()
                                && factory.closes.get() == 0 && released.getCount() == 1,
                        "failed stop did not retain its creation owner");
            } else if ("creation-drain".equals(mode)) {
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                acquireSharedTestEffect();
                heldEffect = true;
                invalidator = new Thread(() -> {
                    try { factory.cancellation.cancel(); }
                    catch (Throwable error) { failure.set(error); }
                });
                invalidator.start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (effectAvailable() && System.nanoTime() < deadline) Thread.yield();
                require(!effectAvailable() && factory.cancellation.getAsBoolean()
                                && factory.cancellation.invalidationUnconfirmed(),
                        "stop was not visible while native invalidation drained");
                factory.proceed.countDown();
                require(factory.checked.await(5, java.util.concurrent.TimeUnit.SECONDS) && factory.sawStop,
                        "factory did not observe stop");
                require(!factory.closeEntered.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                                && released.getCount() == 1,
                        "unconfirmed drain released creation owner");
                releaseSharedTestEffect();
                heldEffect = false;
                invalidator.join(5000);
                require(!invalidator.isAlive() && failure.get() == null, "invalidation did not finish");
                require(released.await(5, java.util.concurrent.TimeUnit.SECONDS) && factory.closes.get() == 1,
                        "confirmed cancellation did not resume original cleanup");
            } else if ("creation-unconfirmed".equals(mode) || "creation-end-before".equals(mode)
                    || "creation-end-after".equals(mode)) {
                boolean beginFailure = "creation-unconfirmed".equals(mode);
                boolean endBefore = "creation-end-before".equals(mode);
                if (beginFailure) armLegacyEffectInvalidationFailure();
                else armCleanupEndFailure(endBefore ? 1 : 2);
                try { owner.requestStop(); throw new AssertionError("injected invalidation succeeded"); }
                catch (LinkageError expected) { /* The native outcome is unknown to Java. */ }
                factory.proceed.countDown();
                require(factory.checked.await(5, java.util.concurrent.TimeUnit.SECONDS) && factory.sawStop,
                        "factory did not observe failed stop");
                require(pending.await(5, java.util.concurrent.TimeUnit.SECONDS)
                                && factory.cancellation.getAsBoolean() && factory.cancellation.invalidationUnconfirmed()
                                && factory.cancellation.invalidationFailed()
                                && factory.closes.get() == 0 && released.getCount() == 1,
                        "unconfirmed invalidation released creation owner");
                if (beginFailure) armLegacyEffectInvalidationFailure();
                else {
                    require(effectAvailable() != endBefore, "unexpected native invalidation outcome");
                    beginOtherInvalidator();
                }
                try { factory.cancellation.cancel(); throw new AssertionError("unknown invalidation retried"); }
                catch (IllegalStateException expected) { /* The unknown operation is not repeated. */ }
                if (beginFailure) {
                    try { SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                        throw new AssertionError("retry consumed the native failure probe");
                    } catch (LinkageError expected) { /* The probe remained armed. */ }
                } else {
                    require(!effectAvailable(), "a repeated end released another invalidator");
                    endOtherInvalidator();
                    require(effectAvailable() != endBefore, "unknown native end was repeated");
                }
                require(factory.closes.get() == 0 && released.getCount() == 1,
                        "retry released its original creation owner");
            } else if ("creation-prior-close".equals(mode) || "creation-prior-failed-close".equals(mode)) {
                factory.blockClose = true;
                factory.failClose = "creation-prior-failed-close".equals(mode);
                factory.proceed.countDown();
                require(factory.closeEntered.await(5, java.util.concurrent.TimeUnit.SECONDS) && !factory.sawStop,
                        "the first close was not admitted before stop");
                armLegacyEffectInvalidationFailure();
                try { owner.requestStop(); throw new AssertionError("injected invalidation succeeded"); }
                catch (LinkageError expected) { /* The earlier close may still finish. */ }
                factory.allowClose.countDown();
                if (factory.failClose) {
                    require(pending.await(5, java.util.concurrent.TimeUnit.SECONDS), "cleanup failure not reported");
                    require(!released.await(350, java.util.concurrent.TimeUnit.MILLISECONDS)
                                    && factory.closes.get() == 1,
                            "retry crossed an unconfirmed stop");
                } else {
                    require(released.await(5, java.util.concurrent.TimeUnit.SECONDS) && factory.closes.get() == 1,
                            "an admitted close could not complete");
                }
            } else throw new IllegalArgumentException("unknown creation cancellation fixture");
            System.out.println("SSH_CREATION_STOP_JNI " + mode + " ownership=pass");
        } finally {
            factory.proceed.countDown();
            factory.allowClose.countDown();
            if (heldEffect) releaseSharedTestEffect();
            if (invalidator != null) invalidator.join(5000);
            // A deliberately retained fake owner must not keep this isolated test JVM alive.
            java.lang.reflect.Field field = SshSessionOwner.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((java.util.concurrent.ScheduledExecutorService)field.get(owner)).shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void publishedServiceFailureRetainsFuture() throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        CreationCancellationFactory factory = new CreationCancellationFactory();
        factory.writesTrust = true;
        factory.creationFailureCode = "VAULT_COMMIT_UNCERTAIN";
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { released.countDown(); }
                }, true);
        try {
            owner.begin();
            require(factory.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "factory was not admitted");
            armLegacyEffectInvalidationFailure();
            java.util.concurrent.CompletableFuture<String> result = owner.startPublished(() -> {
                throw new IllegalStateException("synthetic service startup failure");
            });
            require(result == owner.connected && !result.isDone()
                            && factory.cancellation.invalidationUnconfirmed()
                            && "SSH_SERVICE_START".equals(factory.cancellation.stopCode()),
                    "published owner did not retain its pending result");
            // Rearming proves that the first injected native failure was consumed.
            armLegacyEffectInvalidationFailure();
            factory.proceed.countDown();
            require(factory.checked.await(5, java.util.concurrent.TimeUnit.SECONDS) && factory.sawStop,
                    "the original factory did not observe STOP");
            requireConnectionFailure(owner, "VAULT_COMMIT_UNCERTAIN");
            java.util.concurrent.ScheduledExecutorService executor =
                    (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
            executor.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            require(field(owner, "factory") == factory && factory.closes.get() == 0 && released.getCount() == 1,
                    "unconfirmed published owner was released");
            try {
                SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                throw new AssertionError("calling thread retried unknown JNI");
            } catch (LinkageError expected) {
                require("test effect invalidation failure".equals(expected.getMessage()), "unexpected JNI failure");
            }
            System.out.println("SSH_PUBLISHED_OWNER_JNI service-unknown receipt=retained owner=retained");
        } finally {
            factory.proceed.countDown();
            java.util.concurrent.ScheduledExecutorService executor =
                    (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
            executor.shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void publishedCallbackFailureRetainsOwner() throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        SshSessionOwner.Cancellation cancellation = new SshSessionOwner.Cancellation();
        java.util.concurrent.BlockingQueue<Runnable> posted = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicReference<SshSessionOwner> published = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public SshSessionOwner.Cancellation trustCancellation() { return cancellation; }
            @Override public void cancellationAction(Runnable action) {
                require(published.get() != null, "cancellation callback preceded publication");
                try { armLegacyEffectInvalidationFailure(); }
                catch (IOException failure) { throw new AssertionError(failure); }
                action.run();
            }
            @Override public SshNativeSession create(java.util.function.BooleanSupplier stopped) {
                throw new AssertionError("stopped factory entered");
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(), posted::add,
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { releases.incrementAndGet(); }
                }, true);
        published.set(owner);
        try {
            java.util.concurrent.CompletableFuture<String> result = owner.startPublished(() -> {
                throw new AssertionError("service start continued after callback failure");
            });
            require(result == owner.connected && cancellation.invalidationUnconfirmed()
                            && "SSH_CANCELLED".equals(cancellation.stopCode()),
                    "callback failure lost its exact owner or first reason");
            armLegacyEffectInvalidationFailure();
            java.util.concurrent.atomic.AtomicBoolean begun = (java.util.concurrent.atomic.AtomicBoolean)field(owner, "begun");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!begun.get() && System.nanoTime() < deadline) {
                Runnable action = posted.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (action != null) action.run();
            }
            require(begun.get(), "published callback failure did not schedule cleanup");
            java.util.concurrent.ScheduledExecutorService executor =
                    (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
            executor.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            requireConnectionFailure(owner, "SSH_CANCELLED");
            require(field(owner, "factory") == factory && closes.get() == 0 && releases.get() == 0,
                    "unconfirmed callback owner was released");
            try {
                SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                throw new AssertionError("calling thread retried unknown JNI");
            } catch (LinkageError expected) {
                require("test effect invalidation failure".equals(expected.getMessage()), "unexpected JNI failure");
            }
            System.out.println("SSH_PUBLISHED_OWNER_JNI callback-unknown reason=preserved owner=retained");
        } finally {
            java.util.concurrent.ScheduledExecutorService executor =
                    (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
            executor.shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void terminalSharedCancellationUnconfirmed() throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        try {
            StopScheduler scheduler = new StopScheduler();
            StopPort port = new StopPort();
            SshSessionOwner.Cancellation cancellation = new SshSessionOwner.Cancellation();
            int[] releases = {0};
            String[] reason = {null};
            SshTerminalSession terminal = new SshTerminalSession(port, scheduler,
                    new SshTerminalSession.Sink() {
                        @Override public void data(byte[] bytes, Runnable consumed) { consumed.run(); }
                        @Override public void state(String value, Long exit, String code) { reason[0] = code; }
                    }, () -> releases[0]++, cancellation);
            armLegacyEffectInvalidationFailure();
            try { cancellation.cancel(); throw new AssertionError("injected invalidation succeeded"); }
            catch (LinkageError expected) { /* The shared native result is unknown. */ }
            armLegacyEffectInvalidationFailure();
            try { terminal.stop(); throw new AssertionError("unknown invalidation retried"); }
            catch (IllegalStateException expected) { /* The native call must not be repeated. */ }
            scheduler.next();
            require("SSH_TERMINAL_CLEANUP_PENDING".equals(reason[0]) && port.cancels == 0
                            && port.closes == 0 && releases[0] == 0 && !terminal.closed(),
                    "terminal did not publish and retain its unconfirmed owner");
            try { SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                throw new AssertionError("terminal repeated the unknown native begin");
            } catch (LinkageError expected) { /* The probe remained armed. */ }
            System.out.println("SSH_TERMINAL_CANCEL_JNI terminal-cancel-unconfirmed owner=retained");
        } finally { SshNativeCallbackAdapter.disableJumpEffectFence(); }
    }

    private static void terminalOwnerCancellationUnconfirmed(String mode) throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        CreationCancellationFactory factory = new CreationCancellationFactory();
        StopScheduler scheduler = new StopScheduler();
        StopPort port = new StopPort();
        int[] releases = {0};
        String[] terminalReason = {null};
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24,
                new SshTerminalState(), Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { releases[0]++; }
                });
        SshTerminalSession terminal = new SshTerminalSession(port, scheduler,
                new SshTerminalSession.Sink() {
                    @Override public void data(byte[] bytes, Runnable consumed) { consumed.run(); }
                    @Override public void state(String value, Long exit, String code) { terminalReason[0] = code; }
                }, () -> releases[0]++, factory.cancellation);
        try {
            setField(owner, "terminal", terminal);
            armLegacyEffectInvalidationFailure();
            try {
                if ("terminal-owner-stop-unconfirmed".equals(mode)) owner.requestStop();
                else if ("terminal-owner-lock-unconfirmed".equals(mode)) owner.stopForScreenLock(owner.id);
                else if ("terminal-owner-reject-unconfirmed".equals(mode)) owner.rejectStart();
                else throw new IllegalArgumentException("unknown owner cancellation fixture");
                throw new AssertionError("injected invalidation succeeded");
            } catch (LinkageError expected) { /* The owner must still schedule its terminal. */ }
            scheduler.next();
            require("SSH_TERMINAL_CLEANUP_PENDING".equals(terminalReason[0])
                            && factory.cancellation.invalidationUnconfirmed() && port.cancels == 0
                            && port.closes == 0 && releases[0] == 0 && !terminal.closed(),
                    "owner failed to retain and report its existing terminal");
            armLegacyEffectInvalidationFailure();
            try { owner.requestStop(); throw new AssertionError("unknown invalidation retried"); }
            catch (IllegalStateException expected) { /* Keep the original unknown operation. */ }
            scheduler.next();
            try { SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                throw new AssertionError("owner repeated the native begin");
            } catch (LinkageError expected) { /* The probe remained armed. */ }
            require(port.cancels == 0 && port.closes == 0 && releases[0] == 0,
                    "repeated stop released its existing terminal");
            System.out.println("SSH_TERMINAL_CANCEL_JNI " + mode + " owner=retained");
        } finally {
            java.lang.reflect.Field field = SshSessionOwner.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((java.util.concurrent.ScheduledExecutorService)field.get(owner)).shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void terminalRunningStopUnconfirmed(String mode) throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        CreationCancellationFactory factory = new CreationCancellationFactory();
        factory.tracksSave = mode.startsWith("terminal-running-save-");
        StopScheduler scheduler = new StopScheduler();
        StopPort port = new StopPort();
        java.util.concurrent.CountDownLatch runningAdmitted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        int[] releases = {0};
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24,
                new SshTerminalState(), Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { releases[0]++; }
                });
        java.lang.reflect.Method terminalState = SshSessionOwner.class.getDeclaredMethod(
                "terminalState", String.class, Long.class, String.class);
        terminalState.setAccessible(true);
        SshTerminalSession terminal = new SshTerminalSession(port, scheduler,
                new SshTerminalSession.Sink() {
                    @Override public void data(byte[] bytes, Runnable consumed) { consumed.run(); }
                    @Override public void state(String value, Long exit, String reason) {
                        try {
                            if ("running".equals(value)) {
                                runningAdmitted.countDown();
                                require(resume.await(5, java.util.concurrent.TimeUnit.SECONDS), "running handoff timed out");
                            }
                            terminalState.invoke(owner, value, exit, reason);
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                }, () -> releases[0]++, factory.cancellation);
        Thread worker = null;
        Thread stopper = null;
        boolean heldEffect = false;
        try {
            setField(owner, "terminal", terminal);
            terminal.start(80, 24);
            worker = new Thread(() -> {
                try { scheduler.next(); }
                catch (Throwable failure) { workerFailure.set(failure); }
            });
            worker.start();
            require(runningAdmitted.await(5, java.util.concurrent.TimeUnit.SECONDS), "running was not admitted");
            boolean drain = mode.endsWith("-first");
            java.util.concurrent.atomic.AtomicReference<Throwable> stopperFailure = new java.util.concurrent.atomic.AtomicReference<>();
            if (drain) {
                ((java.util.concurrent.atomic.AtomicBoolean)field(owner, "begun")).set(true);
                acquireSharedTestEffect();
                heldEffect = true;
                stopper = new Thread(() -> {
                    try {
                        if ("terminal-running-save-service-first".equals(mode)) owner.rejectStart();
                        else if ("terminal-running-save-screen-first".equals(mode)) owner.stopForScreenLock(owner.id);
                        else owner.requestStop();
                    } catch (Throwable failure) { stopperFailure.set(failure); }
                });
                stopper.start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (effectAvailable() && System.nanoTime() < deadline) Thread.yield();
                require(!effectAvailable() && factory.cancellation.getAsBoolean() && stopper.isAlive(),
                        "stop did not reach the native drain barrier");
            } else {
                armLegacyEffectInvalidationFailure();
                try { owner.requestStop(); throw new AssertionError("injected invalidation succeeded"); }
                catch (LinkageError expected) { /* Stop precedes the owner's running handoff. */ }
                armLegacyEffectInvalidationFailure();
            }
            resume.countDown();
            worker.join(5000);
            require(!worker.isAlive() && workerFailure.get() == null
                            && port.steps == 0 && port.cancels == 0 && port.closes == 0 && releases[0] == 0,
                    "stop did not prevent successful running delivery or retained cleanup");
            String expected = "terminal-running-save-service-first".equals(mode) ? "SSH_SERVICE_START"
                    : "terminal-running-save-screen-first".equals(mode) ? "SSH_SCREEN_LOCKED" : "SSH_CANCELLED";
            if (drain) {
                require(!owner.connected.isDone(), "in-progress native invalidation completed the connection result");
                releaseSharedTestEffect();
                heldEffect = false;
                stopper.join(5000);
                require(!stopper.isAlive() && stopperFailure.get() == null, "stop did not finish after drain");
                requireConnectionFailure(owner, expected);
            } else {
                requireConnectionFailure(owner, expected);
                try { SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                    throw new AssertionError("running stop repeated native invalidation");
                } catch (LinkageError expectedFailure) { /* The probe remained armed. */ }
            }
            System.out.println("SSH_TERMINAL_CANCEL_JNI " + mode + " result=denied");
        } finally {
            resume.countDown();
            if (heldEffect) releaseSharedTestEffect();
            if (worker != null) worker.join(5000);
            if (stopper != null) stopper.join(5000);
            java.lang.reflect.Field field = SshSessionOwner.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((java.util.concurrent.ScheduledExecutorService)field.get(owner)).shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void terminalSaveStatusFailure(String mode) throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        CreationCancellationFactory factory = new CreationCancellationFactory();
        factory.tracksSave = !mode.contains("-untracked-");
        StopScheduler scheduler = new StopScheduler();
        java.util.concurrent.CountDownLatch statusEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        boolean drain = mode.startsWith("terminal-save-status-drain-");
        boolean drainFailure = mode.startsWith("terminal-save-status-drain-fail-");
        boolean portUnknown = "terminal-save-status-port-unknown".equals(mode);
        boolean closeRetry = mode.endsWith("-close-retry");
        String code = mode.contains("-uncertain") ? "VAULT_COMMIT_UNCERTAIN"
                : mode.contains("-io") || drain || portUnknown ? "VAULT_IO" : "SSH_CANCELLED";
        int[] cancels = {0}, closes = {0}, releases = {0};
        SshTerminalState events = new SshTerminalState();
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, events, Runnable::run,
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { releases[0]++; }
                });
        java.lang.reflect.Method terminalState = SshSessionOwner.class.getDeclaredMethod(
                "terminalState", String.class, Long.class, String.class);
        terminalState.setAccessible(true);
        SshTerminalSession.Port port = new SshTerminalSession.Port() {
            @Override public void begin(int columns, int rows) { }
            @Override public SshTerminalSession.Status status() throws Exception {
                statusEntered.countDown();
                require(resume.await(5, java.util.concurrent.TimeUnit.SECONDS), "status fixture timed out");
                throw new VaultException(code, "synthetic status failure");
            }
            @Override public void step() { }
            @Override public int write(byte[] bytes) { return bytes.length; }
            @Override public byte[] read(int maximum) { return new byte[0]; }
            @Override public boolean resize(int columns, int rows) { return true; }
            @Override public boolean publicationLive() { return true; }
            @Override public void cancel() throws IOException {
                cancels[0]++;
                if (portUnknown) throw new IOException("synthetic port cancellation failure");
            }
            @Override public void close() throws IOException {
                closes[0]++;
                if (closeRetry && closes[0] == 1) throw new IOException("synthetic retryable close");
            }
        };
        SshTerminalSession terminal = new SshTerminalSession(port, scheduler,
                new SshTerminalSession.Sink() {
                    @Override public void data(byte[] bytes, Runnable consumed) { consumed.run(); }
                    @Override public void state(String value, Long exit, String reason) {
                        try { terminalState.invoke(owner, value, exit, reason); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                    }
                    public void startupFailure(String reason) {
                        try {
                            java.lang.reflect.Method method = SshSessionOwner.class.getDeclaredMethod(
                                    "terminalStartupFailure", String.class);
                            method.setAccessible(true);
                            method.invoke(owner, reason);
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                    @Override public void cancellationUnconfirmed() {
                        try {
                            java.lang.reflect.Method method = SshSessionOwner.class.getDeclaredMethod(
                                    "terminalCancellationUnconfirmed");
                            method.setAccessible(true);
                            method.invoke(owner);
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                }, () -> releases[0]++, factory.cancellation);
        Thread worker = null;
        Thread stopper = null;
        boolean heldEffect = false;
        java.util.concurrent.CountDownLatch releaseExecutor = new java.util.concurrent.CountDownLatch(1);
        try {
            setField(owner, "terminal", terminal);
            terminal.start(80, 24);
            worker = new Thread(() -> {
                try { scheduler.next(); }
                catch (Throwable failure) { workerFailure.set(failure); }
            });
            worker.start();
            require(statusEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "status was not entered");
            java.util.concurrent.atomic.AtomicReference<Throwable> stopperFailure = new java.util.concurrent.atomic.AtomicReference<>();
            if (drain) {
                acquireSharedTestEffect();
                heldEffect = true;
                stopper = new Thread(() -> {
                    try {
                        if (drainFailure) armCleanupEndFailure(1);
                        factory.cancellation.cancel();
                    } catch (Throwable failure) { stopperFailure.set(failure); }
                });
                stopper.start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (effectAvailable() && System.nanoTime() < deadline) Thread.yield();
                require(!effectAvailable() && factory.cancellation.getAsBoolean() && stopper.isAlive(),
                        "direct connector cancellation did not enter native drain");
            } else if (portUnknown || closeRetry && !factory.tracksSave && !mode.contains("-screen-")
                    && !mode.contains("-service-")) terminal.requestCancel();
            else if (closeRetry) {
                ((java.util.concurrent.atomic.AtomicBoolean)field(owner, "begun")).set(true);
                java.util.concurrent.ScheduledExecutorService executor = (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
                java.util.concurrent.CountDownLatch executorEntered = new java.util.concurrent.CountDownLatch(1);
                executor.execute(() -> {
                    executorEntered.countDown();
                    try { require(releaseExecutor.await(5, java.util.concurrent.TimeUnit.SECONDS), "executor fixture timed out"); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                });
                require(executorEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "owner executor did not pause");
                if (mode.contains("-service-")) owner.rejectStart();
                else require(owner.stopForScreenLock(owner.id), "screen stop was not accepted");
            } else {
                armLegacyEffectInvalidationFailure();
                try {
                    if (mode.startsWith("terminal-save-status-service-")) owner.rejectStart();
                    else if ("terminal-save-status-screen-io".equals(mode)) owner.stopForScreenLock(owner.id);
                    else owner.requestStop();
                    throw new AssertionError("injected invalidation succeeded");
                } catch (LinkageError expected) { /* The fake status call is still in progress. */ }
            }
            require(!owner.connected.isDone(), "result was completed before the status call exited");
            resume.countDown();
            worker.join(5000);
            require(!worker.isAlive() && workerFailure.get() == null, "status failure was not handled");
            String expected = "terminal-save-status-service-io".equals(mode) ? "SSH_SERVICE_START" : code;
            if (drain) {
                require(!owner.connected.isDone() && closes[0] == 0,
                        "in-progress invalidation was mistaken for sticky failure");
                releaseSharedTestEffect();
                heldEffect = false;
                stopper.join(5000);
                require(!stopper.isAlive() && (drainFailure ? stopperFailure.get() instanceof LinkageError
                                : stopperFailure.get() == null), "native drain resolved unexpectedly");
                if (!drainFailure) terminal.requestCancel();
                scheduler.next();
            } else if (closeRetry) {
                require(!owner.connected.isDone() && closes[0] == 1,
                        "retryable close completed the result too early");
                scheduler.next();
            }
            requireConnectionFailure(owner, expected);
            if (drainFailure || portUnknown || !drain && !closeRetry) {
                require((portUnknown || factory.cancellation.invalidationUnconfirmed())
                                && cancels[0] == (portUnknown ? 1 : 0) && closes[0] == 0
                                && releases[0] == 0 && !terminal.closed()
                                && "SSH_TERMINAL_CLEANUP_PENDING".equals(events.snapshot().get("reason")),
                        "status failure did not preserve pending owner and state");
            } else require(terminal.closed() && closes[0] == (closeRetry ? 2 : 1),
                    "confirmed cleanup did not finish");
            System.out.println("SSH_TERMINAL_CANCEL_JNI " + mode + " status=fake result=" + expected);
        } finally {
            resume.countDown();
            releaseExecutor.countDown();
            if (heldEffect) releaseSharedTestEffect();
            if (worker != null) worker.join(5000);
            if (stopper != null) stopper.join(5000);
            java.lang.reflect.Field field = SshSessionOwner.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((java.util.concurrent.ScheduledExecutorService)field.get(owner)).shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void terminalFinalFirstStop(String mode) throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        CreationCancellationFactory factory = new CreationCancellationFactory();
        factory.writesTrust = true;
        factory.tracksSave = true;
        boolean running = mode.contains("-running-");
        boolean serviceFirst = mode.endsWith("-service-screen");
        boolean screenFirst = mode.endsWith("-screen-first");
        String statusFailure = mode.contains("-uncertain-") ? "VAULT_COMMIT_UNCERTAIN"
                : mode.contains("-save-") ? "VAULT_IO" : "SSH_SESSION_CANCELLED";
        java.util.concurrent.BlockingQueue<Runnable> turns = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.BlockingQueue<Runnable> posted = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.CountDownLatch boundary = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch executorEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseExecutor = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        int[] closes = {0};
        SshTerminalState events = new SshTerminalState();
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, events, posted::add,
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { }
                });
        java.lang.reflect.Method terminalState = SshSessionOwner.class.getDeclaredMethod(
                "terminalState", String.class, Long.class, String.class);
        java.lang.reflect.Method startupFailure = SshSessionOwner.class.getDeclaredMethod(
                "terminalStartupFailure", String.class);
        terminalState.setAccessible(true);
        startupFailure.setAccessible(true);
        SshTerminalSession.Port port = new SshTerminalSession.Port() {
            @Override public void begin(int columns, int rows) { }
            @Override public SshTerminalSession.Status status() throws Exception {
                if (!running) {
                    boundary.countDown();
                    require(resume.await(5, java.util.concurrent.TimeUnit.SECONDS), "status barrier timed out");
                    throw new VaultException(statusFailure, "synthetic status failure");
                }
                return new SshTerminalSession.Status(SshNativeSession.ShellPhase.RUNNING, 0, null);
            }
            @Override public void step() { }
            @Override public int write(byte[] bytes) { return bytes.length; }
            @Override public byte[] read(int maximum) { return new byte[0]; }
            @Override public boolean resize(int columns, int rows) { return true; }
            @Override public boolean publicationLive() { return true; }
            @Override public void cancel() { }
            @Override public void close() { closes[0]++; }
        };
        SshTerminalSession terminal = new SshTerminalSession(port, (turn, delay) -> turns.add(turn),
                new SshTerminalSession.Sink() {
                    @Override public void data(byte[] bytes, Runnable consumed) { consumed.run(); }
                    @Override public void state(String value, Long exit, String reason) {
                        try {
                            if ("running".equals(value)) {
                                boundary.countDown();
                                require(resume.await(5, java.util.concurrent.TimeUnit.SECONDS), "running barrier timed out");
                            }
                            terminalState.invoke(owner, value, exit, reason);
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                    @Override public void startupFailure(String reason) {
                        try { startupFailure.invoke(owner, reason); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                    }
                }, () -> { }, factory.cancellation);
        Thread worker = null;
        try {
            setField(owner, "terminal", terminal);
            ((java.util.concurrent.atomic.AtomicBoolean)field(owner, "begun")).set(true);
            java.util.concurrent.ScheduledExecutorService executor = (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
            executor.execute(() -> {
                executorEntered.countDown();
                try { require(releaseExecutor.await(5, java.util.concurrent.TimeUnit.SECONDS), "owner barrier timed out"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            });
            require(executorEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "owner executor did not pause");
            terminal.start(80, 24);
            worker = new Thread(() -> {
                try { turns.remove().run(); }
                catch (Throwable failure) { workerFailure.set(failure); }
            });
            worker.start();
            require(boundary.await(5, java.util.concurrent.TimeUnit.SECONDS), "terminal did not reach the barrier");
            if (screenFirst) {
                require(owner.stopForScreenLock(owner.id), "screen stop was rejected");
                owner.requestStop();
            } else {
                if (serviceFirst) owner.rejectStart(); else owner.requestStop();
                require(owner.stopForScreenLock(owner.id), "screen stop was rejected");
            }
            require(!owner.connected.isDone(), "connection completed before terminal exited its barrier");
            resume.countDown();
            worker.join(5000);
            require(!worker.isAlive() && workerFailure.get() == null && terminal.closed() && closes[0] == 1,
                    "terminal did not finish its confirmed close");
            String expected = mode.contains("-uncertain-") ? "VAULT_COMMIT_UNCERTAIN"
                    : mode.contains("-save-") && !serviceFirst ? "VAULT_IO"
                    : serviceFirst ? "SSH_SERVICE_START" : screenFirst ? "SSH_SCREEN_LOCKED" : "SSH_CANCELLED";
            requireConnectionFailure(owner, expected);
            Runnable action;
            while ((action = posted.poll()) != null) action.run();
            require("closed".equals(events.snapshot().get("state"))
                            && "SSH_SCREEN_LOCKED".equals(events.snapshot().get("reason")),
                    "screen-locked terminal state was not preserved");
            System.out.println("SSH_TERMINAL_CANCEL_JNI " + mode + " result=" + expected);
        } finally {
            resume.countDown();
            releaseExecutor.countDown();
            if (worker != null) worker.join(5000);
            java.lang.reflect.Field field = SshSessionOwner.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((java.util.concurrent.ScheduledExecutorService)field.get(owner)).shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void constructorCancellationUnconfirmed() throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        SshSessionOwner.Cancellation cancellation = new SshSessionOwner.Cancellation();
        java.util.concurrent.BlockingQueue<Runnable> posted = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public SshSessionOwner.Cancellation trustCancellation() { return cancellation; }
            @Override public void cancellationAction(Runnable action) {
                try { armLegacyEffectInvalidationFailure(); }
                catch (IOException failure) { throw new AssertionError(failure); }
                try { action.run(); throw new AssertionError("injected invalidation succeeded"); }
                catch (LinkageError expected) { /* Constructor-time signal stays with the original owner. */ }
            }
            @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) {
                throw new AssertionError("stopped factory was admitted");
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        SshTerminalState state = new SshTerminalState();
        state.subscribe(event -> {
            if ("SSH_TERMINAL_CLEANUP_PENDING".equals(event.get("reason"))) pending.incrementAndGet();
        });
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, state, posted::add,
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { releases.incrementAndGet(); }
                });
        try {
            java.util.concurrent.atomic.AtomicBoolean begun = (java.util.concurrent.atomic.AtomicBoolean)field(owner, "begun");
            require(!begun.get() && !owner.connected.isDone() && cancellation.invalidationUnconfirmed()
                            && closes.get() == 0 && releases.get() == 0,
                    "constructor-time stop outran owner publication");
            armLegacyEffectInvalidationFailure();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!begun.get() && System.nanoTime() < deadline) {
                Runnable action = posted.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (action != null) action.run();
            }
            require(begun.get(), "published owner did not begin deferred cleanup");
            java.util.concurrent.ScheduledExecutorService executor = (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
            executor.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            Runnable action;
            while ((action = posted.poll()) != null) action.run();
            requireConnectionFailure(owner, "SSH_CANCELLED");
            require(pending.get() == 1 && closes.get() == 0 && releases.get() == 0,
                    "constructor-time pending was duplicated or owner released");
            try { SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                throw new AssertionError("constructor cleanup repeated native invalidation");
            } catch (LinkageError expected) { /* The probe remained armed. */ }
            System.out.println("SSH_CREATION_STOP_JNI creation-constructor-stop-unconfirmed owner=retained");
        } finally {
            java.lang.reflect.Field field = SshSessionOwner.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((java.util.concurrent.ScheduledExecutorService)field.get(owner)).shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void latePendingAfterReleasedOwner() throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        CreationCancellationFactory factory = new CreationCancellationFactory();
        factory.blockClose = true;
        java.util.concurrent.BlockingQueue<Runnable> posted = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicInteger pendingEvents = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        SshTerminalState state = new SshTerminalState();
        state.subscribe(event -> {
            if ("SSH_TERMINAL_CLEANUP_PENDING".equals(event.get("reason"))) pendingEvents.incrementAndGet();
        });
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, state, posted::add,
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { releases.incrementAndGet(); }
                });
        try {
            owner.begin();
            require(factory.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "factory did not start");
            factory.proceed.countDown();
            require(factory.closeEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "close was not admitted");
            armLegacyEffectInvalidationFailure();
            try { owner.requestStop(); throw new AssertionError("injected invalidation succeeded"); }
            catch (LinkageError expected) { /* The earlier close remains admitted. */ }
            require(factory.cancellation.invalidationUnconfirmed() && releases.get() == 0 && posted.size() >= 2,
                    "pending publication was not queued before close finished");
            factory.allowClose.countDown();
            java.util.concurrent.ScheduledExecutorService executor = (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
            require(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                            && Boolean.TRUE.equals(field(owner, "released")) && factory.closes.get() == 1,
                    "the previously admitted close did not actually release");
            Runnable action;
            while ((action = posted.poll()) != null) action.run();
            java.util.Map<String, Object> snapshot = state.snapshot();
            require(pendingEvents.get() == 0 && releases.get() == 1
                            && (snapshot == null || !"SSH_TERMINAL_CLEANUP_PENDING".equals(snapshot.get("reason"))),
                    "late cleanup pending was published after owner release");
            armLegacyEffectInvalidationFailure();
            owner.requestStop();
            try { SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                throw new AssertionError("released owner repeated native invalidation");
            } catch (LinkageError expected) { /* The probe remained armed. */ }
            System.out.println("SSH_CREATION_STOP_JNI creation-prior-close-delayed-pending released=pass");
        } finally {
            factory.proceed.countDown();
            factory.allowClose.countDown();
            java.lang.reflect.Field field = SshSessionOwner.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((java.util.concurrent.ScheduledExecutorService)field.get(owner)).shutdownNow();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    private static void transferredSessionUnconfirmed() throws Exception {
        SshNativeCallbackAdapter.enableJumpEffectFence();
        SshPasswordConnector.TrustCommitState trust = new SshPasswordConnector.TrustCommitState();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch returnSession = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch pending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger pendingEvents = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger descriptor = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<SshNativeSession> session = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> setupFailure = new java.util.concurrent.atomic.AtomicReference<>();
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public SshSessionOwner.Cancellation trustCancellation() { return trust.cancellation; }
            @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) throws Exception {
                char[] password = "synthetic local vault password".toCharArray();
                byte[] hosts = null, binding = filled(9);
                try {
                    int fd = openPeers();
                    descriptor.set(fd);
                    hosts = hostfile(0);
                    Store store = encryptedStore(hosts, password);
                    Clock clock = new Clock();
                    AppSecrets vault = new AppSecrets(store, clock);
                    SshRoleRegistry registry = new SshRoleRegistry(clock);
                    SshSocketOwnership socket = new SshSocketOwnership(fd, SshFreshVaultRealJniTest::closeFd);
                    vault.attachSshValidator(manifest -> { });
                    vault.session.foreground(true);
                    vault.unlock(password, () -> { });
                    long deadline = now() + 55_000;
                    SshVaultAdapter files = new SshVaultAdapter(vault, (connection, target) -> { }, proposal -> { });
                    AppSecrets.SshPublicReadContext context = files.fileContext(deadline, () -> { });
                    VaultOperation read = files.fileRequest(ROOT, CONNECTION, context);
                    SshPublicFileLease view;
                    try { view = files.openPublicFiles(read, ROOT, CONNECTION); }
                    catch (Exception | Error failure) { files.cancel(read); throw failure; }
                    SshSharedPublicView shared = new SshSharedPublicView(view);
                    SshNativeRoleBootstrap.Issued role = issue(registry, view, clock,
                            SshRoleContext.Program.JUMP, 0, "target:jump", "jump.invalid", deadline, binding);
                    trust.bindIssued(role);
                    SshManagedKnownHostsProvider.SnapshotReceipt receipt = SshManagedKnownHostsProvider.snapshotFor(
                            files, role.role, clock, ROOT, HOSTS, "hosts", "hosts identity");
                    SshManagedKnownHostsProvider provider = SshManagedKnownHostsProvider.bindForNativeHandoff(files, receipt, trust);
                    SshNativeCallbackAdapter callback = callback(role, registry, new SshHostKeyAuthority(),
                            clock, vault, provider, false, trust.cancellation);
                    SshNativeSession current = SshNativeSession.openManaged(callback, shared, socket,
                            HOSTS, "candidate-root", 4096);
                    session.set(current);
                    rootKex(current);
                    rootAuthentication(current);
                    ready.countDown();
                    require(returnSession.await(10, java.util.concurrent.TimeUnit.SECONDS), "session return timed out");
                    require(cancelled.getAsBoolean(), "factory did not observe the failed stop");
                    return current;
                } catch (Exception | Error failure) {
                    setupFailure.set(failure);
                    ready.countDown();
                    throw failure;
                } finally {
                    Arrays.fill(password, '\0');
                    VaultCodec.wipe(hosts);
                    VaultCodec.wipe(binding);
                }
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        SshTerminalState state = new SshTerminalState();
        state.subscribe(event -> {
            if ("SSH_TERMINAL_CLEANUP_PENDING".equals(event.get("reason"))) {
                pendingEvents.incrementAndGet();
                pending.countDown();
            }
        });
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, state, Runnable::run,
                new SshSessionOwner.Lifecycle() {
                    @Override public void state(String ignored) { }
                    @Override public void released() { released.countDown(); }
                });
        try {
            owner.begin();
            require(ready.await(30, java.util.concurrent.TimeUnit.SECONDS), "synthetic native session timed out");
            if (setupFailure.get() != null)
                throw new AssertionError("synthetic native session setup failed", setupFailure.get());
            require(session.get() != null && descriptor.get() >= 0,
                    "synthetic native session did not reach the return barrier");
            armLegacyEffectInvalidationFailure();
            try { owner.requestStop(); throw new AssertionError("injected invalidation succeeded"); }
            catch (LinkageError expected) { /* The session is still inside its factory. */ }
            armLegacyEffectInvalidationFailure();
            returnSession.countDown();
            require(pending.await(5, java.util.concurrent.TimeUnit.SECONDS), "transferred terminal did not report pending");
            java.util.concurrent.ScheduledExecutorService executor = (java.util.concurrent.ScheduledExecutorService)field(owner, "executor");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean terminalChecked = false;
            while (!terminalChecked && System.nanoTime() < deadline) {
                terminalChecked = executor.submit(() -> {
                    Object terminal = field(owner, "terminal");
                    return terminal != null && Boolean.TRUE.equals(field(terminal, "cleanupReported"));
                }).get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!terminalChecked) Thread.yield();
            }
            require(terminalChecked, "transferred terminal did not run its own cleanup check");
            requireConnectionFailure(owner, "SSH_FAILED");
            require(trust.cancellation.invalidationUnconfirmed() && !session.get().closed()
                            && !fdClosed(descriptor.get()) && closes.get() == 0 && released.getCount() == 1
                            && pendingEvents.get() == 1 && "SSH_TERMINAL_CLEANUP_PENDING".equals(state.snapshot().get("reason")),
                    "transferred session or descriptor was released after an unknown invalidation");
            try { SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                throw new AssertionError("transferred terminal repeated the native begin");
            } catch (LinkageError expected) { /* The probe remained armed. */ }
            System.out.println("SSH_CREATION_STOP_JNI creation-transferred-unconfirmed native-session=retained");
        } finally {
            returnSession.countDown();
            java.lang.reflect.Field field = SshSessionOwner.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((java.util.concurrent.ScheduledExecutorService)field.get(owner)).shutdownNow();
            closePeers();
            SshNativeCallbackAdapter.disableJumpEffectFence();
        }
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary("ctos_ssh_files");
        if (args.length == 1 && args[0].startsWith("vault-")) {
            if ("vault-lock-drain".equals(args[0])) vaultLockWhileNativeEffectDrains();
            else if (args[0].startsWith("vault-shared-destroy-")) vaultSharedLeaseDestroyFailure(args[0]);
            else if (args[0].startsWith("vault-lease-") || args[0].startsWith("vault-session-lease-"))
                vaultManagedLeaseFailure(args[0]);
            else vaultLockFailure(args[0]);
            return;
        }
        if (args.length == 1 && "published-service-unknown".equals(args[0])) {
            publishedServiceFailureRetainsFuture();
            return;
        }
        if (args.length == 1 && "published-callback-unknown".equals(args[0])) {
            publishedCallbackFailureRetainsOwner();
            return;
        }
        if (args.length == 1 && "terminal-cancel-unconfirmed".equals(args[0])) {
            terminalSharedCancellationUnconfirmed();
            return;
        }
        if (args.length == 1 && ("terminal-running-stop-unconfirmed".equals(args[0])
                || args[0].startsWith("terminal-running-save-"))) {
            terminalRunningStopUnconfirmed(args[0]);
            return;
        }
        if (args.length == 1 && args[0].startsWith("terminal-save-status-")) {
            terminalSaveStatusFailure(args[0]);
            return;
        }
        if (args.length == 1 && args[0].startsWith("terminal-final-")) {
            terminalFinalFirstStop(args[0]);
            return;
        }
        if (args.length == 1 && "creation-constructor-stop-unconfirmed".equals(args[0])) {
            constructorCancellationUnconfirmed();
            return;
        }
        if (args.length == 1 && "creation-prior-close-delayed-pending".equals(args[0])) {
            latePendingAfterReleasedOwner();
            return;
        }
        if (args.length == 1 && args[0].startsWith("terminal-owner-")) {
            terminalOwnerCancellationUnconfirmed(args[0]);
            return;
        }
        if (args.length == 1 && "creation-transferred-unconfirmed".equals(args[0])) {
            transferredSessionUnconfirmed();
            return;
        }
        if (args.length == 1 && args[0].startsWith("creation-")) {
            creationStopOwnership(args[0]);
            return;
        }
        if (args.length == 1 && args[0].startsWith("trust-")) {
            if ("trust-failure".equals(args[0])) trustConfirmationAfterInvalidationFailure();
            else if ("trust-drain".equals(args[0])) trustConfirmationWhileInvalidationDrains();
            else throw new IllegalArgumentException("unknown trust cancellation fixture");
            return;
        }
        if (args.length == 1 && args[0].startsWith("cancel-")) {
            terminalCancellationFailure(args[0]);
            return;
        }
        if (args.length == 1 && args[0].startsWith("shared-")) {
            if ("shared-drain".equals(args[0])) sharedViewInvalidationDoesNotHoldMonitor();
            else sharedViewCancellationFailure(args[0]);
            return;
        }
        if (args.length == 1 && args[0].startsWith("session-")) {
            int mode;
            switch (args[0]) {
                case "session-retryable": mode = 110; break;
                case "session-begin-before": mode = 111; break;
                case "session-begin-after": mode = 112; break;
                case "session-end-before": mode = 113; break;
                case "session-end-after": mode = 114; break;
                case "session-callback": mode = 115; break;
                case "session-drain": mode = 116; break;
                case "session-child-retryable": mode = 117; break;
                case "session-child-begin-after": mode = 118; break;
                case "session-child-callback": mode = 119; break;
                case "session-child-end-before": mode = 120; break;
                case "session-terminal-retry": mode = 125; break;
                case "session-terminal-retry-exhausted": mode = 126; break;
                case "session-terminal-retry-unknown": mode = 127; break;
                case "session-terminal-mixed-unknown": mode = 128; break;
                case "session-terminal-retry-wait": mode = 129; break;
                case "session-terminal-owner-stop-budget": mode = 130; break;
                case "session-terminal-mixed-callback": mode = 131; break;
                default: throw new IllegalArgumentException("unknown session cancellation fixture");
            }
            runCase(mode);
            return;
        }
        if (args.length == 0) {
            for (int mode = 0; mode < 31; mode++) runCase(mode);
            runCase(33);
            runCase(34);
            runCase(35);
            runCase(36);
            runCase(37);
            runCase(38);
            runCase(39);
            runCase(40);
            runCase(41);
            runCase(42);
            runCase(43);
            runCase(44);
            runCase(45);
            runCase(55);
            for (int mode = 60; mode <= 71; mode++) runCase(mode);
            runCase(74);
            for (int mode = 79; mode <= 86; mode++) runCase(mode);
            for (int mode = 88; mode <= 109; mode++) runCase(mode);
            for (int mode = 121; mode <= 124; mode++) runCase(mode);
            runCase(125);
            runCase(126);
            runCase(129);
            runCase(130);
            runCase(131);
        } else {
            if (args.length != 1) for (String argument : args) {
                int mode = Integer.parseInt(argument);
                if (isolatedUncertainMode(mode))
                    throw new IllegalArgumentException("uncertain cleanup requires an isolated process");
            }
            for (String argument : args) runCase(Integer.parseInt(argument));
        }
        if (args.length == 1 && isolatedUncertainMode(Integer.parseInt(args[0])))
            System.out.println("SSH_FRESH_VAULT_JNI isolated-uncertain-cleanup=observed");
        else System.out.println("SSH_FRESH_VAULT_JNI cleanup-and-reopen=pass");
    }
}
