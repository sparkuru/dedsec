# Development principles

## Scope and state

Treat the product as unreleased until evidence establishes a specific released interface, real user, external consumer or data-retention obligation. Record exceptions for the affected surface rather than assuming all development data is disposable. The attached phone is a real device: preserve existing apps, settings, credentials and data regardless of project maturity.

Implement the current accepted task and concrete near-term needs. Reuse existing execution paths, models and tests once present; change obsolete unreleased code and its callers together instead of adding speculative compatibility layers. The user's requested extension boundary is in scope, but a dynamic plugin loader, provider framework or extra dependency needs an actual task requirement. Keep unrelated changes, renames, formatting and dependency upgrades out of the diff.

## Evidence and failures

Before fixing a defect, establish the real behavior and a plausible cause through reproduction, source or logs; mark unavailable reproduction as a limitation. Verify observable behavior, not only implementation mechanics. Do not suppress errors, relax assertions, disguise an unsupported capability as success, or silently use Root as a fallback when Thanox access fails. Record per-operation backend, identity and result when those affect meaning.

Prefer the smallest useful checks and add durable regression tests when they protect meaningful behavior. Failed or unavailable checks remain visible. Use controlled fixtures for missing dependencies and state exactly what real-device or privileged behavior remains unverified.

## Workspace and documentation

Treat existing work and the enclosing monorepo's changes as user-owned. Inspect affected paths and Git status before editing; clean only identified temporary artifacts from the current task. Do not modify root `README` without explicit request. Keep shared conventions in this layer and task-specific evidence in the normal Trellis task. Do not customize protected Trellis runtime files or copy third-party text without its applicable notice.

At task start and after interruption, read mainline, acceptance criteria and relevant shared details. Update mainline only from accepted changes and actual evidence. Development-only access conveniences must not reuse production secrets, weaken production defaults, expose a service beyond the permitted network, or bypass the permission behavior under test.
