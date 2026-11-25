# Research: Android APK and DEX static-analysis route

- Query: Which existing REA/controller and offline tools can examine the installed Thanox 9.6-prc APK and its DEX, and what has actually been checked on this host?
- Scope: local controller source, bundled documentation, and read-only tool/image metadata; no target APK, device operation, dependency installation, or semantic analysis.
- Date: 2026-11-25.

## Findings

### Available tools and observed metadata

- The installed entrypoint resolves to `/home/wkyuu/cargo/repo/21-geass/skills/rea/scripts/rectl`; `rectl --help` and `rectl doctor --json` ran. Doctor observed OpenJDK 21.0.13.1 but reports `java-kotlin` analysis readiness as `unverified`; a runtime version alone is not a DEX analysis result.
- Host `/usr/bin/jadx` resolves to `/home/wkyuu/cargo/bin/jadx/bin/jadx`. `jadx --version` reports 1.5.3, and `jadx --help` advertises APK/DEX input, `--single-class`, `--single-class-output`, `--no-res`, and `--decompilation-mode fallback`. Only help/version were run: the host binary's ability to parse this target, fidelity and isolation are not established. The REA sources lock refers to a different optional JADX release, so the installed version must be identified explicitly if used.
- A sandboxed Docker metadata check returned `environment_permission_denied`; after the controller-prescribed, approved read-only retry, `rectl runtime check --engine java-kotlin --image rea/java-kotlin:0.1.0 --client codex --json` reached Docker 26.1.5 and reported `image_missing`. This is an observed absence of that exact image, not a failure to decompile the target. No image was pulled or built.
- The same read-only metadata retry for `--engine rea --image rea/rea-ghidra:0.1.0` returned `metadata_ready`, image ID `sha256:f3de70bd5e62200dabe654bb64c04b44dfbd1bfad29628c9a6afaabd29d53350`. No container or provider was started by this check; target compatibility and Ghidra readiness remain unverified.

### What the controller actually implements

- The registry identifies DEX signatures (`src/rea/detection.py:220–221`) and the REA engine manifest declares APK and DEX inputs (`engines/rea/engine.yaml:1–7`), but these are routing declarations, not proof of a successful DEX import or method decompilation.
- The `java-kotlin` engine README explicitly says the generic adapter does not yet provide a real JADX integration (`engines/java-kotlin/README.md:3–7`). Its advertised target list is broader than the measured implementation. The capability matrix identifies JAR/class inventory and `javap` as measured while APK/DEX/JADX remains conditional (`references/engine-capabilities.md`, `config/engines.lock.yaml:46–52`).
- `rectl query --kind jvm-dis` is a JVM `.class`/JAR route: `src/rea/experiment_runner.py:68–69` calls `JVM_INSPECTOR`; `src/rea/query_programs.py:109–130` enumerates `.class` entries and invokes `javap`. A raw Android DEX must not be relabeled as a JVM class or JAR. The generic static-query path also reads at most 16 MiB (`src/rea/experiments.py:318–327,336–345`); this limit should be checked against each actual selected input.
- The separate `rea`/Ghidra provider lifecycle has its own path (`src/rea/lifecycle.py:269–305`), so the generic JVM query limit must not be automatically attributed to that provider. Its actual APK/DEX loader and semantic behavior still require an authorized, target-specific probe.

### Suggested bounded sequence for the parent

1. Keep the board APK copy and private REA workspace outside the controller checkout. Record the exact installed package/version and compare the device and copied APK SHA-256. Use `rectl bootstrap --client codex --workspace WORKSPACE --json`, `rectl init --workspace WORKSPACE --new-session --json`, `rectl detect --json -- TARGET`, and `rectl plan --workspace WORKSPACE --session SESSION --json -- TARGET`. A missing MCP registration does not block direct CLI static work; do not modify client configuration solely for this task.
2. Inventory the APK layers without executing them. For any needed DEX or packaged JAR, extract only explicitly selected regular members to a fresh private location, enforce the controller's archive quotas (`config/policy.yaml:12–15`), reject unsafe names/links, and retain original APK/member names, sizes and SHA-256 values. Bind each newly extracted child as a separate actual input; detection of a ZIP does not itself prove DEX coverage.
3. Before `rectl up`, `analyze`, or `query`, write task-specific requirements matching `schemas/runtime-requirements.schema.json`, including exact input paths, the selected engine/image, read-only effects, session-owned outputs, and only the query scope justified by existing user authorization. Run `rectl runtime check --requirements REQUIREMENTS --workspace WORKSPACE --session SESSION --client codex --json`, review its numbered proposal, and record already-established choices with `rectl runtime decide` against its plan ID. Refresh this preflight for each newly extracted or changed target. Installation, global client registration, dynamic execution and network access are separate choices; none follows automatically from static analysis.
4. If the actual input and reviewed scope justify it, try the cached REA/Ghidra provider through its fixed `rectl up`/`query` route, keeping its read-only target mount, non-root, no-network and bounded-resource contract. First measure real provider startup and input compatibility; query observed class/function names rather than invented addresses. On failure retain the error and limitations; do not equate a successful import with recovered semantics. The generic `jvm-dis` path is suitable only for actual compatible JVM class/JAR content, not DEX.
5. For any independently authorized, suitably isolated external parser fallback, preserve exact tool/version, original/member digests, command, output location and static-only effects, and ingest permitted bounded material with `rectl ingest` (`references/evidence-and-delivery.md`). A produced Java-like file is decompiler output, not original source or proof of runtime behavior. No external fallback was run or validated in this investigation.

## Related specifications and files

- `.trellis/spec/trellis-plus/android-capabilities.md` — distinguishes actual target identity, Android/Thanox authority and verified device behavior.
- `research/uid-resolver-source-20261125.md` — source and branch/submodule limits motivating this investigation.
- `/home/wkyuu/.codex/skills/rea/references/target-detection-and-routing.md` — layer coverage and extraction provenance.
- `/home/wkyuu/.codex/skills/rea/references/runtime-and-approval.md` and `security-boundaries.md` — preflight, current authorization, Docker isolation and refusal semantics.
- `/home/wkyuu/cargo/repo/21-geass/skills/rea/src/rea/query_programs.py` — concrete JVM class/JAR handling and bytecode output.

## Caveats / Not Found

No APK, DEX member, class or service implementation was opened or analyzed by this researcher, and no device was contacted. `jadx --version`, a controller registry entry, and Docker image metadata do not establish actual DEX parsing or the source of a running Binder. A fresh successful target-specific provider call and retained evidence are required before claiming such support. The submodule's availability, server-side resolver equivalence, actual device behavior and human security review remain separate questions.

Later follow-up: the REA/Ghidra provider rejected the actual DEX as unsupported. The primary agent then used bounded host-side `dexdump` and radare2 instruction inspection and recorded the resulting qualified findings in `uid-resolver-bytecode-20261125.md`; those later actions do not change the scope of this route survey.
