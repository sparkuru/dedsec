# Bootstrap ctOS Trellis Specifications

## Goal

Replace Trellis's generic backend/frontend scaffolding with source-backed
guidance for ctOS's Flutter UI, Android host, JNI/C helpers, and bundled Python
runtime.

## Scope

- `.trellis/spec/backend/`
- `.trellis/spec/frontend/`
- Source examples from `lib/`, `android/app/src/main/`, `test/`, and
  `android/app/src/androidTest/`
- No product-code changes or claims of new device validation

## Acceptance Criteria

- [x] Relevant guides state concrete ctOS ownership, contracts, anti-patterns,
  source examples, and verification commands.
- [x] Inapplicable React hooks and generic database templates are removed;
  current persistence behavior and Android host boundaries are documented.
- [x] Spec indexes link to the current file sets.
- [x] No template placeholders remain under `.trellis/spec/`.
- [x] Claims are grounded in source, tests, or project design documents.

## Result

Completed 2026-09-28. See [review and validation](check.md),
[backend specs](../../spec/backend/index.md), and
[Flutter specs](../../spec/frontend/index.md).
