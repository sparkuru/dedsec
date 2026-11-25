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

## First product feature: app permissions

The user selected application permission viewing and individual operations as the first product work after initialization. Build the required Flutter Android and Kotlin foundation and provide an on-demand flow for choosing an installed app, inspecting its requested Android permissions and applicable AppOps separately, and changing one supported control through the verified Thanox interface. The initial scope is the current Android user; package and user identity must remain explicit.

- [x] Show installed apps and their declared Android permission grant states, including readable labels or names and explicit unavailable or immutable states. Use the Android-visible app list rather than silently substituting a Thanox catalog when an independently controlled app-list prompt may have been declined. Explain that device-specific app-list access may affect coverage, without inferring a grant or denial from the returned count. Keep explicit Android exact-package lookup and validate a selected target's identity before operations.
- [x] On first opening the app-list screen, let the user explicitly start the initial list read after a brief explanation that the device may ask for access. The tested device's startup-triggered prompt disappeared before the user could choose; the list must not be queried merely by constructing the screen. Keep exact-package search available without first reading the full list. The user successfully chose Allow after the explicit read action on the designated device.
- [x] Show applicable AppOps separately, including their observed Thanox state and whether an operation is associated with a runtime permission.
- [x] Offer individual changes only for verified supported operations; display errors or unsupported states explicitly and read the resulting state back instead of assuming success.
- [x] Report a missing, incompatible, or disconnected Thanox service without pretending that Android, Thanox, and Root have equivalent authority. Connected-service behavior was tested on the phone; unavailable-service presentation was tested with a controlled channel response.
- [x] Validate the interface and changes on `product:PLR110` using a dedicated disposable application and independent state checks. Effective states were restored where possible and fixture cleanup was verified; exact initial flags/default AppOps entries were not reconstructed.
- [x] Establish Flutter analysis, tests, Android build, and native-device validation; document failures and unverified capabilities.

The initial phone profile's writable slice is limited to verified CAMERA, RECORD_AUDIO and VIBRATE modes for user-0, target-SDK-35 non-system apps on Android 16 with Thanox 9.1. After security review, its compatibility check also requires the recorded `PLR110` product and `github.tornaco.android.thanos.pro` version `9.1-row` (`3380725`); the tighter check has not been physically retested on the phone. Android-only details remain available for other current users when readable. On 2026-11-25 the user explicitly requested an independent sub-agent security review in place of human review for this task and delegated handling its results. The agent review and residual limits are recorded in `research/security-review-agent-20261125.md`; it was not a human review.

The subsequent shared-UID investigation observed that a CAMERA write aimed at one disposable target also changed the permission and UID AppOps mode of a second target sharing its UID. Until shared-UID effects are designed and verified explicitly, packages declaring a shared user ID must remain readable but must not expose or accept permission writes. See `research/shared-uid-proof.md`.

On 2026-11-23 a task-owned probe reproduced a separate phone prompt asking whether the app could read the installed-app list; the user provided a screenshot and then chose Allow for a reinstalled probe. The initial unselected probe saw one entry; after reinstall and Allow, a different UID saw 567 and the dedicated target. This supports the user's hypothesis, but reinstall and UID changes prevent isolating the prompt choice as the sole cause. Do not infer denial from list size or use a privileged list to work around a device access choice. Product access remains a separate user choice from the temporary probe.

The user selected `im.majo.thanox_bridge` as the Android application ID after confirming that the originally requested `im.majo.thanox-bridge` is not valid for Android packages. Historical device probes retain their original package names in the evidence.

Batch changes, profiles, templates, Root impersonation, and polished visual design remain future product work. An Android permission without a verified corresponding Thanox operation must remain visible without presenting a fabricated write capability.

## Development-board continuation

The user subsequently designated the non-production `product:rk3568_firefly_aioj` development board and authorized independent testing. The board reports Android 11 / API 30 and Thanox `9.6-prc` (`versionCode=3404474`); the earlier Android 16 / Thanox 9.1 bridge initially rejected this combination. Separately signed ordinary-app probes verified specific CAMERA, RECORD_AUDIO and VIBRATE states for target SDK 30 and 35. A narrowly matched board profile now enables only those modes, preserves the previously demonstrated phone modes and shared-UID restriction, and has been exercised through the product on the board with independent readback and verified cleanup. The evidence and limits are in `research/board-compatibility.md`.

## Out of scope for the original device investigation

- Building the product wrapper or changing Thanox configuration and managed applications.
- Accessing other connected devices or unrelated device data.
