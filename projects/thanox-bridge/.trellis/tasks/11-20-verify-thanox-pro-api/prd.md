# Verify Thanox Pro API on device

## Goal

Validate the previously identified Thanox Pro service and determine whether an independent Android application can use its API on the designated test device.

## Known facts

- The user designated the connected `product:PLR110` Android device and authorized connecting to it for this investigation.
- The device runs Android 16 and has Thanox Pro `9.1-row` (`versionCode=3380725`) installed.
- The local official source checkout is `archive/thanox-repo`, at commit `053888f56b8d07b964e848ce1ccb5f2b979bc969`.
- Android's service registry exposes `tv_input` with the `github.tornaco.android.thanos.core.IThanos` descriptor. ADB shell calls returned `9.1`, `I am Thanox!!!`, and a package-manager Binder.
- ADB shell uses UID 2000, so its access does not establish access from an ordinary third-party app UID.
- A target-SDK-35 ordinary app obtained the Binder and queried package, activity, and profile interfaces. Its direct Android cross-app component-setting call was denied, while the Thanox interface changed and restored a separately signed temporary target's component state.

## Requirements

- Investigate the actual service discovery and calling path using the designated device and the local source.
- Distinguish source definitions, observed shell behavior, ordinary-app behavior, and capabilities or versions that remain unverified.
- Prefer read-only calls and limit any temporary probe to a dedicated test application and clearly reversible operations.
- Record concrete evidence and limitations without exposing unrelated device data.

## Acceptance criteria

- [x] Identify the installed version, activation/service state, and the relevant API entry point.
- [x] Demonstrate at least one read-only API operation with clearly identified caller identity.
- [x] Test independent ordinary-app access where feasible, or provide the exact observed blocker without extrapolating shell privileges to an app.
- [x] Explain which parts of the prior assessment are confirmed, corrected, or still unknown.
- [x] Remove any temporary device-side probe artifacts created by this investigation and report the cleanup result.

The observations, limitations, cleanup checks, and probe source are retained in `research/`.

## Development-container initialization

The user requested reusing this task to initialize `dev-it-in-docker`. The product stack is Flutter and Kotlin for Android, but this repository does not yet contain a product manifest, build command, or development service. The locally available `ghcr.io/cirruslabs/flutter:3.35.7` image supplies a concrete initial Flutter and Android toolchain; it is a development baseline, not a product compatibility decision.

- [x] Provide an executable, ephemeral `hako` wrapper that runs commands as the host UID/GID with a project-local, ignored HOME and writable Flutter and Android SDK copies.
- [x] Verify the actual container toolchain and UID behavior, including repeated execution, and record any Android SDK or device-access limitations.
- [x] Do not invent a product command, server, port, or `dev.sh` before such a service exists.
- [x] Preserve the existing Codex sandbox and approval policy, and document the established toolchain in the development-environment specification.

## Out of scope

- Building the product wrapper or changing Thanox configuration and managed applications.
- Accessing other connected devices or unrelated device data.
