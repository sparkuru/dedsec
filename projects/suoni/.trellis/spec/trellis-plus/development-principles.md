# Development Principles

## Scope and Data

Treat Suoni as a development project. No released API, production data set, real-user retention commitment or supported-device promise has been established. Record exceptions with evidence for the affected surface when that changes. Archived APKs, analysis and prototypes are user materials, not disposable build output.

Implement the requested behavior in the real execution path. Search for existing framing, session, device and UI helpers before adding one. Prefer a small direct implementation; add abstractions, dependencies, caching or compatibility layers only for a concrete requirement. For unreleased interfaces, update producers, consumers and tests together instead of retaining discarded prototype APIs. Preserve unknown data; development status does not authorize resetting it.

Keep changes within the accepted scope. Do not combine unrelated cleanup, upgrades, renaming, mass formatting or reorganizing `archive/`. Preserve user edits and pre-existing changes. README is human-owned presentation: edit or create it only on explicit request. Remove only known temporary artifacts created by the current work.

## Diagnosis and Evidence

Before fixing a bug, establish observed behavior, the affected path and root-cause evidence. If hardware or reproduction is unavailable, state the hypothesis and missing evidence. Distinguish source claims, implementation, fixtures and current real-device results.

Validate observable behavior with the smallest useful checks. Do not weaken assertions, swallow failures, supply success-looking defaults, or mock away the acceptance criterion. A successful build proves compilation, an HTTP response proves that response, and a protocol fixture proves only the exercised bytes. Missing material checks remain unresolved. Broaden checks for new failures or risks, not ritual repetition.

## Development Access

For an approved trusted-LAN Flask preview, make listener and publish addresses configurable and normally allow device access through `0.0.0.0`. Preserve narrower network constraints. Never open a firewall or expose unrelated ports as a setup shortcut. No preview is currently implemented or running.

Avoid unnecessary development login friction through an explicit development configuration and separate credentials. Do not weaken production defaults, remove the authentication being tested, reuse private production credentials, or expose Bluetooth control on an untrusted network. A tool wrapper does not grant sandbox, Docker or network permissions.

## Completion

Read mainline and policy at start/resume and before completion. Preserve original requirements while incorporating approved changes. Report the actual checks, failures and unverified portions against the requested behavior, then apply the review gate in `validation.md`. Stage only classified explicit paths under `commit-policy.md`.
