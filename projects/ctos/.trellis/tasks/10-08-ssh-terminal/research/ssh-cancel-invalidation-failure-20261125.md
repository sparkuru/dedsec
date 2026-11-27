# SSH cancellation invalidation failure

Status: this is the historical reproduction before the terminal stop-intent increment. The original source deliberately asserts the former fake-port write and no longer serves as a passing regression against the updated terminal. Local terminal I/O is now fenced separately, but pre-auth creation, trust and cross-hop cancellation remain **not fully fixed**. The currently supported known-host ProxyJump and direct SSH paths must retain their existing gates; do not treat the withdrawn retry prototype as a product implementation.

## Observed failure

`CancelFailurePumpRepro.java` in this directory constructs the actual Java terminal controller with a synthetic scheduler and port. On the same thread it arms the test JNI's legacy effect-invalidation failure before native begin, calls `requestCancel()`, and then advances the scheduler. The current source leaves the shared cancellation state false; a previously queued input reaches the fake port's `write()` and another `step()` runs. A zero exit and `REPRODUCED` mean the known defect occurred, not that cancellation works. It does not exercise Android ART, a real remote connection, or begin/end uncertainty after native mutation.

The fixture requires the project's existing test JNI library and `SshFreshVaultRealJniTest` class containing the private failure hook. Build them with `bash android/ssh/host-jni-vault-chain-test.sh --output <new-/tmp/ctos-vault-jni-directory>` after compiling current release classes. The JNI hook is thread-local: arm it on the thread that calls cancellation. [Verification](../../../../design/verification.md#20261125-ssh-cancel-invalidation-failure) records the result and its coverage limits.

The final-source reproduction was run from the project root after the final host JNI fixture built `/tmp/ctos-vault-jni-resume-20261125-final`:

```sh
mkdir -p /tmp/ctos-cancel-failure-repro-20261125/final-classes
java -m jdk.compiler/com.sun.tools.javac.Main \
  -cp 'build/app/intermediates/javac/release/compileReleaseJavaWithJavac/classes:.devhome/android-sdk/platforms/android-36/android.jar' \
  -d /tmp/ctos-cancel-failure-repro-20261125/final-classes \
  .trellis/tasks/10-08-ssh-terminal/research/CancelFailurePumpRepro.java
java -Xcheck:jni -Djava.library.path=/tmp/ctos-vault-jni-resume-20261125-final \
  -cp '/tmp/ctos-cancel-failure-repro-20261125/final-classes:/tmp/ctos-vault-jni-resume-20261125-final/classes:build/app/intermediates/javac/release/compileReleaseJavaWithJavac/classes:.devhome/android-sdk/platforms/android-36/android.jar:.devhome/gradle-home/caches/modules-2/files-2.1/org.bouncycastle/bcprov-jdk18on/1.86/830c8467bed8e4c1148262386b09fc42aedcf262/bcprov-jdk18on-1.86.jar' \
  im.majo.ctos.CancelFailurePumpRepro
```

## Source boundaries

- `SshSessionOwner.Cancellation.cancel()` invokes native invalidation before recording its logical cancellation; if that call throws, `admitOutput()` and `confirmTrust()` can still see a live owner.
- `SshPasswordConnector.cancel()`, `SshSessionOwner.requestStop()` and `stopForScreenLock()` may exit on that error before signalling the terminal. A later terminal pump may process queued input.
- `SshTerminalSession.fail()` calls `closeTurn()` even when cancellation throws. `NativePort.close()` closes the native chain before connector resources, and `SshSessionOwner.closeUnattached()` can close a session before its factory. An outer failure flag alone cannot establish that no native owner was already released.
- `SshNativeSession.openInternal()` may close a partially opened native session before returning an owner to its caller. Per-hop `SshNativeSession.cancel()` and the shared public view have their own invalidation calls; confirmation of one outer cancellation is not confirmation of every session or view.
- Native effect invalidation has process-wide generation and invalidator counters. A legacy begin/end exception does not reveal whether native changed that counter. Do not retry an unknown end or infer that a subsequent no-op proves completion; the existing cleanup-begin API reports `-1` only when it can confirm no begin occurred.

## Required next work

1. Specify a shared logical stop and short local admission for each new terminal write, step, read and resize. A stop must deny subsequent admission and owner-side queue delivery; an already admitted effect may physically finish later and is not thereby proven drained.
2. Give every relevant owner, session and shared view an exact cancellation/cleanup state, including partial creation. Distinguish known pre-begin failure from unknown begin/end and confirmed completion. Retain the original owners and chain on an unknown outcome; do not invoke a native close or report release merely because Java has stopped output.
3. Coordinate connector cancellation, screen lock, terminal failure, owner creation and service/Activity teardown without native/JNI calls or external callbacks while holding new metadata locks. Preserve the first error and independently clear resources whose cleanup is known safe.
4. Add barrier tests for stop versus each local I/O admission and for terminal publication versus owner creation. Inject JNI failures on the actual execution thread, isolating processes where an invalidator may remain outstanding. Check no repeated uncertain begin/end, no premature native close or release, no extra credentials, and any already-in-flight work separately.
5. Verify JVM state, host JNI, real SSH and Android lifecycle independently. A fake port or a host JVM cannot stand in for Android or a real network. The existing user-approved project-local Docker fixture permission does not authorize device installation or real-account access.

The abandoned `/tmp/ctos-product-retry-review-20261125/` snapshot contains a draft with known lifecycle and cancellation gaps. It was withdrawn from the source tree, and the six corresponding production files were compared against their pre-draft SHA-256 values. It is evidence of an abandoned approach, not an implementation to reapply without review.
