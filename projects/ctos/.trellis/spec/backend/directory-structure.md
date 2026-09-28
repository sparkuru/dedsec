# Runtime Directory Structure

Use the existing layer that owns the behavior. Flutter owns presentation and
interaction; Android Java owns platform APIs, permissions, and long-lived
service lifecycle; Python owns portable tool cores and its HTTP implementation;
C is limited to the JNI PTY and the two bundled native executables.

| Path | Ownership |
| --- | --- |
| `lib/` | Flutter application, view models, and UI-facing APIs; see the [frontend index](../frontend/index.md) |
| `android/app/src/main/java/im/majo/ctos/` | Android host, MethodChannel routing, snapshots, Root adapter, file broker, and HFTP service |
| `android/app/src/main/cpp/` | `pty.c`, `python_exec.c`, and `hftp_relay.c`; do not move service policy here |
| `android/app/src/main/python/` | Workbench protocol, SDK, portable cores, CLI, and HFTP server |
| `android/app/src/main/assets/portable/` | APK-bundled package manifest and runtime data, not user-installed plugins |
| `test/` | Flutter unit and widget tests |
| `android/app/src/androidTest/` | Android host and device instrumentation tests |

Keep a cross-layer feature's public contract close to its owning layer and
document the shared wire shape in the relevant design/spec guide. Do not add a
second catalogue or copy a core implementation into another layer.

Reference files:

- `lib/workbench.dart`, `lib/workbench/api.dart`
- `android/app/src/main/java/im/majo/ctos/MainActivity.java`
- `android/app/src/main/python/ctos_workbench.py`, `android/app/src/main/python/ctos_tools/`
- `design/architecture.md`, `design/portable-workbench.md`, `design/portable-tools.md`
