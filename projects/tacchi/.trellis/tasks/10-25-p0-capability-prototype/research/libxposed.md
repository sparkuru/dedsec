# libxposed API baseline research

User decision (2026-10-25): build against modern libxposed API 101+, without waiting for device/ROM/framework details. API 101 is the minimum contract; dependency versions must be pinned separately. No legacy API 82 compatibility implementation is requested.

Primary sources inspected:

- https://libxposed.github.io/api/io/github/libxposed/api/package-summary.html — current documentation is explicitly API 102.0.0, not a frozen API 101 reference. It documents XposedModule entry registration under META-INF/xposed, module properties and package/process filtering; APIs marked 102+ must not be assumed available on 101.
- https://github.com/libxposed/example — official example repository, usable for further pinned-source inspection. Do not copy its mutable current dependency choices as the API 101 contract without verification.

An attempted release URL ending `/releases/tag/101.0.1` returned 404; this does not establish that the Maven artifact is absent. Subsequent immutable-source and Maven verification in `research/vector-contract.md` resolved the baseline to API `101.0.1` and service `101.0.0` from Vector v2.1. That report supersedes the initial pending-version assessment here.

Device test evidence is deferred, not waived. Build success cannot establish hardware-backed authentication, actual framework loading or target-host IPC behavior.

## Vector-specific source of truth (user correction)

The user directed API research to Vector's manual. Prefer these framework-specific primary sources over the generic upstream's mutable latest documentation:

- https://jingmatrix.github.io/libxposed-api/io/github/libxposed/api/package-summary.html
- https://jingmatrix.github.io/libxposed-api/io/github/libxposed/api/XposedModule.html
- https://jingmatrix.github.io/libxposed-api/io/github/libxposed/api/XposedInterface.html
- https://github.com/JingMatrix/Vector (Developer Resources)
- https://github.com/JingMatrix/Vector/blob/master/.gitmodules
- https://github.com/JingMatrix/libxposed-service

Vector's developer guidance recommends matching the module and service API commits pinned by a successfully built Vector revision. Moving master points to JingMatrix forks, whereas the selected v2.1 gitlinks resolve to upstream libxposed repositories. The exact immutable revisions and published artifact metadata are now recorded in `research/vector-contract.md`; use that report rather than moving master for implementation.

The current Vector-hosted manual identifies itself as API 102.0.0. It documents module entries derived from XposedModule, registration under META-INF/xposed, initialization from onModuleLoaded, and scope filtering by package/process. The API exposes runtime API/framework information and capability flags including remote preference/file support. Use the API 101 subset required by the user; API 102-only behavior such as auto hot reload is not an implicit dependency.

Configuration planning must inspect Vector's service interface before inventing a parallel transport. Framework-to-module-app service connectivity is not by itself proof of an authenticated arbitrary host-to-manager command channel. Preserve the design's identity, revision, expiry and no-secret-crossing contracts when comparing available transports.

This research corrects the source selection; it neither adds a new approval requirement nor restores the rejected device-version prerequisite.
