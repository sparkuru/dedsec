# Android capability contracts

## Lifecycle and boundaries

The requested client works on demand while open; do not introduce a persistent app service, background keepalive or automatic app-install monitor as a prerequisite. Model Flutter-facing commands and results separately from Kotlin's Android, Thanox and optional Root interactions. Use a small extension seam justified by the requested future capabilities; plugin packaging and dynamic loading are not yet decided.

Treat direct Android APIs, Thanox Binder and Root as different authorities. Report which one performed an operation. A missing service, unsupported method, denied Root request, incompatible interface, partial batch or dead Binder is an explicit outcome; do not conceal it behind a success-shaped default or an unannounced privileged fallback. Do not expose an unrestricted Root or privileged Binder passthrough to other applications.

## Identity and permissions

Identify an app by package and Android user/profile, not package name alone; retain UID and effective identity where relevant. Permission enumeration and changes must distinguish declared/runtime permissions, AppOps and other platform controls rather than treating their state names as interchangeable. Determine actual availability per Android/ROM/Thanox version; list unsupported operations separately.

Root does not by itself prove that a probe has the target app's UID, SELinux domain, process context, attribution or effective permission. For each supported access test, establish the actual execution identity and observable action, compare expected and observed outcomes, and report unsupported, denied or inconclusive conditions. Do not equate a successful Root action, permission flag, mock or `checkSelfPermission` result with proof that the target application can exercise the underlying capability.

## Groups, templates and changes

Permission profiles describe selected apps and desired allow/deny states. Preserve the distinction between these user-requested batch presets and Thanox's event-driven Profile engine until an implementation is actually verified. Capture selected targets and prior states before mutation where the backend supports it; return per-app/per-permission success, failure and skipped results, including rollback failures. Do not claim atomicity or full reversibility without evidence.

One-click new-app templates are explicitly invoked; investigate Thanox's existing template APIs before duplicating behavior, and document version/capability gaps. Future extension features must state their authority, inputs, per-user targeting and failure semantics rather than gaining arbitrary ambient Root access.

## Observed baseline

`.trellis/tasks/11-20-verify-thanox-pro-api/research/device-proof.md` demonstrates read and reversible cross-app component changes from a separate ordinary UID on Android 16 with Thanox Pro 9.1-row and Xposed. This observation does not guarantee a stable SDK, full permissions coverage, other devices, Root identity simulation or native plugin loading. The local `archive/thanox-repo` is a reference checkout, not a tracked product dependency.
