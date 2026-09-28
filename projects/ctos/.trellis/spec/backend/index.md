# Runtime and Android Host Guidelines

ctOS is an Android application with a Flutter UI, a Java host, a small JNI/C
layer, and an APK-bundled Python runtime. There is no off-device backend or
general database. Use these guides for work below the Flutter presentation layer.

| Guide | Scope |
| --- | --- |
| [Directory Structure](./directory-structure.md) | Ownership of Dart, Java, C, Python, and tests |
| [Host Bridge](./host-bridge.md) | MethodChannel, capability declarations, workers, and native ownership |
| [Persistence](./persistence.md) | Current preferences/files and limits on adding durable state |
| [Error Handling](./error-handling.md) | Typed snapshot states, channel errors, and runtime failures |
| [Logging](./logging-guidelines.md) | Protocol stdout, bounded HFTP diagnostics, and secret-safe logs |
| [Quality](./quality-guidelines.md) | Layer-specific checks and tests |
| [Python Workbench](./python-workbench.md) | SDK, bundled tools, subprocess, file, and HFTP contracts |
