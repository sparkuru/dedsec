# Native UI Design and UUPM

## Local Integration

The product is an Android native APK. Archived `MainActivity.kt`, `HeadsetPanel.kt`, settings Activities and TileServices establish the UI reference; there is no browser product surface. On 2026-10-17 the user explicitly approved UUPM initialization for Codex. `npx --yes uipro-cli@latest init --ai codex` installed CLI 2.2.3's bundled templates at `.codex/skills/ui-ux-pro-max/`. The local skill, scripts and CSV data exist; `python3 .codex/skills/ui-ux-pro-max/scripts/search.py --help` succeeds. Keep these generated assistant files ignored/untracked. Reinitialize another clone only when authorized and absent; do not overwrite a complete installation or initialize every platform.

The native prototype uses Android Views. Kotlin is approved; Compose is only proposed. UUPM's web defaults, cursor/hover rules and CSS examples are not Android implementation requirements. Consult its `jetpack-compose` stack only if Compose is chosen. Retain only reviewed project decisions; see `third_party/index.md` before retaining generated raw material for distribution.

## Plan → Implement → Check → Update Spec

For an authorized UI change, load this policy, the local UUPM skill and relevant application/validation rules. Generate research before implementation using the verified local command shape:

`python3 .codex/skills/ui-ux-pro-max/scripts/search.py "Sony headphone controller Android Bluetooth permissions device state" --design-system -p Suoni -f markdown`

When Compose is selected, consult stack guidance with `python3 .codex/skills/ui-ux-pro-max/scripts/search.py "state accessibility touch targets" --stack jetpack-compose`. These are planning tools, not approval of their recommendations. Initialization runs only help/data checks; no visual design has been approved or saved.

In a future authorized task, retain permitted raw research under `<task-directory>/research/ui-ux-pro-max.md` and selected product decisions in its `design.md`. Define loading, empty, permission denied, disconnected, unsupported, error, disabled, confirmed success and background/tile states, accessible labels, touch targets, screen/font adaptation and motion expectations. Include approved design/research and these spec details explicitly in both implement/check manifests. Do not create a task or a competing editable `design-system/MASTER.md` merely to store design output when tasks are declined.

Implement against the approved decisions; if a technical constraint invalidates them, reconcile the design and both contexts first. Check user interactions and final device state, typography/contrast, TalkBack/focus, touch feedback, layouts, motion and lifecycle. Use native device testing, not Web Playwright, for APK behavior. Feed only residual subjective or hardware risks into the review gate. If a browser surface is added, use the browser validation profile instead of a generic manual smoke request.

After verification, promote only stable original project decisions into this file; leave feature-specific research/design with its authorized task and preserve licensing boundaries. No palette, font, component library, Compose migration or new screen has been selected by this bootstrap.
