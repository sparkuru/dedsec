# Runtime Quality Checks

Choose checks for the layer changed; a passing Flutter test does not validate
Java, Python, JNI, Root, SAF, or device lifecycle behavior.

From `projects/ctos`, the supported container wrapper is `./hako`:

```sh
./hako flutter analyze
./hako flutter test
./hako bash -lc 'cd android && ./gradlew :app:lintRelease --console=plain'
./hako bash -lc 'cd android && ./gradlew :app:assembleReleaseAndroidTest --console=plain'
```

- No standalone Python test runner is checked in today. A Python core change
  must define runnable unit/integration coverage in its task and keep it
  separate from Flutter coverage; exercise malformed input, bounds,
  cancellation/error paths, and preservation of existing files.
- Java host changes need Android lint and relevant instrumentation coverage.
  Changes to native executables must retain `-Wall -Wextra -Werror` and explicit
  local-test guards; a local relay test does not prove Android network binding
  or Root behavior.
- Keep Flutter, host, Python, and native wire-contract checks aligned when a
  MethodChannel or process protocol changes.
- Device installation, Root authorization, LAN access, SAF grants, ROM
  behavior, and screen-off behavior require current authorization and evidence
  from the named device. Do not promote historical results to current checks.
- `./hako current` publishes/replaces the current APK under `dist/`; use it
  only when the task requires a release artifact, not for routine analysis.

Test examples: `test/hftp_feedback_test.dart`,
`android/app/src/androidTest/java/im/majo/ctos/PortableToolsTest.java`, and
`android/app/src/androidTest/java/im/majo/ctos/DeviceTest.java`.
