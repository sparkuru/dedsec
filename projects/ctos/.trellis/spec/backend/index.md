# Runtime and Android Host Guidelines

ctOS is an Android application with a Flutter UI, a Java host, a small JNI/C
layer, and an APK-bundled Python runtime. There is no off-device backend or
general database. Use these guides for work below the Flutter presentation layer.

| Guide | Scope |
| --- | --- |
| [Directory Structure](./directory-structure.md) | Ownership of Dart, Java, C, Python, and tests |
| [Host Bridge](./host-bridge.md) | MethodChannel, capability declarations, workers, and native ownership |
| [Persistence](./persistence.md) | Current preferences/files and limits on adding durable state |
| [App Secrets](./app-secrets.md) | Shared vault, detached preview/bundle/TOTP/public/typed SSH reads and SSH entry mutation/trust CAS/TOTP mutation, original contexts/owned candidates, final guarded copy, metadata-only AtomicFile guards and atomic/uncertain effects |
| [Authenticator](./authenticator.md) | Strict TOTP input, host-only add tickets with original-budget handoff, scoped image/camera, read admission and guarded publication |
| [SSH Native Gates](./ssh-native.md) | Actual supplied-manifest material inspection/CSMI and shared key policy, partial-only outcomes; declaration catalog/CSCD and separate effective readconf/Options; opaque registry; actual offered-host/NEW review and19sessionJNI; SUCCESS-only PTY/shell/auth, QEMU and separate Android evidence |
| [Detached SSH Config](./ssh-detached-config.md) | Native supplied file collection, owned CONFIG copies and selected roots; original lifetime, unchanged live adapter, partial-only semantic scope |
| [Managed Detached Config](./ssh-detached-config-managed.md) | Two-phase Java/JNI owner, CSDI single protected source, all actual CONFIG roots and aggregate budgets; retryable cleanup and partial-only output |
| [CONFIG Audit](./ssh-config-audit.md) | Explicit AUDIT mode, finite all-branch policy, real static Include edges/CSCA and actual parent direct-reference checks; partial-only |
| [CONFIG Resource References](./ssh-config-resources.md) | Explicit resource declarations/CSCR, static typed parent references and unresolved context/cwd/key-loading obligations |
| [Explicit Key Associations](./ssh-key-association.md) | Exact supplied-pair IDs, fixed local sign/verify, certificate plain subject, strict CSKA and partial-only owner/lifetime |
| [All Private Material](./ssh-private-material.md) | Full PRIVATE/nonTOTP visits, private self-signing proof, v1 phrase-owner ambiguity and opaque password targets |
| [Certificate Facts](./ssh-certificate-facts.md) | AllCERT numeric/public facts, embedded-signature policy, strict CSCF/unsigned u64 and explicit trust/context limits |
| [SSH Manifest](./ssh-manifest.md) | One latest model/writer, explicit key/account metadata, protected TOTP input and content-based restore review |
| [Password SSH Connection](./ssh-password-connect.md) | Real managed trust, transient selected password, TCP/FD handoff, shell-only continuation and operable UI |
| [Error Handling](./error-handling.md) | Typed snapshot states, channel errors, and runtime failures |
| [Logging](./logging-guidelines.md) | Protocol stdout, bounded HFTP diagnostics, and secret-safe logs |
| [Quality](./quality-guidelines.md) | Layer-specific checks and tests |
| [Python Workbench](./python-workbench.md) | SDK, bundled tools, subprocess, file, and HFTP contracts |
