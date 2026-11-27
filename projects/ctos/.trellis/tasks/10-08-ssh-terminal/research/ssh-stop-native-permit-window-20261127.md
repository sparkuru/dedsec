# Shared STOP and native effect permit ordering

## Follow-up implementation, not verified

The later [client-bound STOP admission](../../../../design/verification.md#20261127-ssh-native-stop-effects) now adds the originating Java cancellation CAS to selected actual client effects, while retaining native generation/deadline checks where they already existed. The standalone hook below still exercises only the raw global generation and does not pass through that client-bound admission. The follow-up has not been tested or built under the user's no-testing instruction and does not establish independent callback, session, vault or persistent-write ordering.

## Observation

`SshSessionOwner.Cancellation.cancel()` publishes the Java `STOP_REQUESTED` bit before it enters the cancellation monitor and invokes the native effect invalidation. A thread holding that monitor can therefore delay native invalidation after STOP is visible. Native `ctos_ssh_effect_acquire()` orders against the native generation and invalidation, but does not itself read that Java bit.

The independent [host probe](StopEffectWindow.java) holds the actual cancellation monitor, starts `cancel()` on a second thread, waits for both the STOP bit and the blocked thread, then uses the existing JNI test hook to acquire and release the native shared effect permit. The current implementation prints `STOP_EFFECT_WINDOW stop=published native-permit=accepted scope=standalone-hook` under `-Xcheck:jni`; the original generation is unchanged until the monitor is released. The stop worker then finishes. This shows a permit-ordering window, not an SSH authentication, a packet sent, or remote receipt.

The probe was run against the previous release Java classes and `build/ctos-vault-jni-result-delivery-20261127/libctos_ssh_files.so`; it accesses test-only JNI hooks from that fixture. With those local artifacts present it can be rerun in an isolated scratch directory:

```sh
mkdir -p /tmp/ctos-stop-effect-probe-classes
java -m jdk.compiler/com.sun.tools.javac.Main -proc:none -cp "build/app/intermediates/javac/release/compileReleaseJavaWithJavac/classes:build/ctos-vault-jni-result-delivery-20261127/classes" -d /tmp/ctos-stop-effect-probe-classes .trellis/tasks/10-08-ssh-terminal/research/StopEffectWindow.java
java -Xcheck:jni -Djava.library.path=build/ctos-vault-jni-result-delivery-20261127 -cp "/tmp/ctos-stop-effect-probe-classes:build/ctos-vault-jni-result-delivery-20261127/classes:build/app/intermediates/javac/release/compileReleaseJavaWithJavac/classes" im.majo.ctos.StopEffectWindow
```

## Source-level implications and limits

Root sessions return early from `SshNativeSession.requireFreshEffectGuard()` when there is no child trust source. `ssh_native_client.c` currently obtains its password-packet effect permit only for a client with an upstream relay and a nonzero generation; `wire_effect_acquire()` permits an unguarded `WIRE_EFFECT_NONE` path. Keyboard-interactive and private-key flows have separate effects and must be reviewed at their own final boundaries. These observations are source inspection, not evidence that a packet crossed the demonstrated window.

For an already guarded child, a previously captured generation can remain current during the demonstrated window. Existing tests that cause native invalidation directly do not by themselves order the earlier Java STOP publication against final native permit acquisition. A fuller test would need a barrier immediately before an actual child credential effect, the same shared cancellation object, an explicit blocked invalidator, owner-thread cleanup and assertions on the precise native effect reached. Do not present that as completed until executed.

A repair needs one defined ordering point shared by STOP and every intended sensitive effect, including root and child paths. A check made before the final effect is not enough. A proposed native-to-Java atomic admission must also be evaluated against native/JNI reentry, lock order, owner identity, in-flight effects admitted earlier, cancellation failure and the exact lifetime of credentials. Do not release retained owners or claim full chain cancellation merely because the Java bit or a native generation changed.
