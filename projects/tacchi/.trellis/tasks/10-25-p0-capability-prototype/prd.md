# P0 Android environment and capability prototype

## Goal

Build the Kotlin Android foundation and prove the framework/process capabilities needed by tacchi before payment automation. User authorized P0 planning on 2026-10-25, selected libxposed API 101+, directed research to Vector's manual, and explicitly rejected device-version collection as an engineering prerequisite. On 2026-10-26 the user selected native Kotlin platform Views to replace the original Flutter UI and prioritize a small APK.

## Background and decisions

Source requirements: `archive/design.md` sections 4–8, 11 and 14–15, with the user's subsequent corrections recorded in `.trellis/mainline.md`. Initialization is archived; no application scaffold existed when planning began. Vector documentation and the fixed API/service revisions of Vector v2.1 supply the integration baseline. Reference-project code is evidence only.

Kotlin handles the single-page native configuration/diagnostics UI, Android, module and IPC responsibilities. Remove the superseded Flutter engine, Dart code and method channel. Use the API 101 contract with pinned dependencies, no dynamic `101+` coordinate, no API 102-only requirement and no legacy API 82 layer. Device/ROM/framework versions are recorded alongside actual device results, not demanded to start building.

## Requirements

- R1: A minimal APK builds through a reproducible pinned Android/Gradle development entry, without installing a global host toolchain or creating a web service. Keep product runtime offline.
- R2: Show framework connection/API and actual host acknowledgement separately. Missing framework/host evidence must remain visibly unknown or unavailable.
- R3: Exercise module loading and probe behavior in a controlled development-only host fixture before a real payment-app adapter. The product remains one APK; the fixture is a test artifact.
- R4: Host-local test bytes undergo hardware-backed, per-operation biometric encryption/decryption. Cancellation/failure produces no plaintext output or weaker fallback. Never place payment secrets in the manager/configuration process.
- R5: Bounded IPC authenticates system-derived UID/user/package/signature, rejects wrong/ambiguous identity and stale/replayed requests, and carries only non-sensitive commands/status. Host read-only framework sharing is not treated as an uplink.
- R6: Provide a minimal diagnostics UI with explicit loading/unknown/disconnected/unsupported/failure/success states and accessible narrow-screen behavior, not a polished production configuration page.
- R7: Record exact commands, artifacts and test environments; distinguish build/logic/widget evidence from Binder, framework injection and real hardware evidence.
- R8 (2026-10-26 installed-build feedback and subsequent explicit choice): Measure APK size, compare the user-supplied BBZQ reference (`https://github.com/HSSkyBoy/BBZQ.git`), and replace Flutter with Kotlin platform Views. Use compatible R8/resource shrinking, retain reflective module entry points, and record the actual release size against the 48,345,323-byte Flutter baseline. A sub-4-MB result is a measurement goal rather than a promise before building.
- R9 (2026-10-26): Allow the Vector manager to present editable module scope. Preserve the fixture-only runtime package/process allowlist and do not imply that selecting an arbitrary payment app adds an adapter.
- R10 (2026-10-26): Change the main application identity and Kotlin namespace to `im.majo.tacchi`; keep IPC authorities, module entry, package visibility and native Activity integration consistent. The controlled test fixture remains `dev.tacchi.probe`.

## Acceptance criteria

- [x] R1: Updated native API/toolchain pins and actual build entry are recorded; minimal APK and relevant automated checks pass.
- [ ] R2/R6: Native UI preserves separate framework/host/probe evidence, loading/failure/refresh and lifecycle guards; automated state tests pass. Actual native layout/accessibility tests require Android execution and remain explicitly unverified when unavailable.
- [ ] R3: Controlled host confirms actual module loading under the chosen framework; unexecuted loading remains unverified.
- [ ] R4: Actual hardware-backed per-operation round trip passes using test data, and cancellation/failure/late callbacks produce no output or false success.
- [ ] R5: Authorized IPC works and wrong identity, target, revision, replay and expiry are rejected; secrets do not cross into the manager.
- [x] R7: New native APK static checks preserve offline/privacy/API packaging boundaries; device limitations are explicit and no unsupported compatibility claim is made.
- [x] R8: Actual native APK size and BBZQ source comparison are recorded; Flutter/Dart runtime is absent and optimized release retains metadata, offline permissions and reflective entry points. No native libraries remain, so one architecture-independent APK suffices.
- [x] R9: Packaged scope metadata permits manager selection; runtime still rejects unsupported packages/processes. Actual installed Vector UI behavior remains a device check.
- [x] R10: Final manifest/DEX/module registration and both sides of IPC consistently use the new main identity; tests and signed artifacts pass. Package-name change installs a separate Android application and does not migrate the previous installation automatically.

Current local verification is recorded in `feedback-results.md`; original Flutter checks remain historical in `check-results.md`. R2/R6 native diagnostic state and contrast tests pass, while actual native layout/TalkBack acceptance remains open alongside host/hardware/Binder checks in `device-validation.md`.

## Out of scope and deferred decisions

No real payment input/transfers, production enrollment/replacement/deletion, finished P1 settings page, broad KSU/clone compatibility, legacy API support or reference-code copying. No device installation or mutation is implied by build permission.

The first real payment application/version/scenario, production applicationId/signing and fingerprint-only product UX are deferred to affected tasks; the controlled P0 fixture does not decide them. Development-only identities and strong-biometric test probes are described in design.md for review.

Device-dependent acceptance is retained, not waived. Scaffold implementation may proceed without device details, but P0 cannot be declared fully hardware/host-verified from a successful build. No remaining device-information question blocks planning; actual device execution remains a separately reported validation prerequisite.
