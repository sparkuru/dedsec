# Development Environment

## Initialization State and Before-Dev Checkpoint

Only archived prototypes currently have build inputs. The new Kotlin APK and Flask service do not yet have build roots, dependency locks or executable entry points. A `hako`, `dev.sh`, `preview.sh` or `.env.example` generated now would invent commands and unused configuration. This checkpoint must be completed before the first product implementation:

1. Decide Flask's role and whether real Bluetooth resides on Android, Linux, or both. Do not grant a container host networking, privileged mode or blanket device mounts merely to make a lab test pass.
2. Select the active source paths and pin compatible JDK/Gradle/Android SDK and Python/dependency versions. Provide the Gradle wrapper rather than relying on a global Gradle. Read the applicable language guidelines before writing scripts or Python.
3. Inspect existing wrappers; if absent, initialize a Docker development-command wrapper (`hako` or equivalent) using the available `dev-it-in-docker` procedure. Pin its image, user/group, home/cache mounts and repository/service ownership labels. Record exact build, lint and test commands here. Android builds can run in a container; a real phone/Bluetooth acceptance check still requires an explicitly connected device.
4. When Flask has an actual previewable entry, provide one service lifecycle in `dev.sh` and a thin root `preview.sh`. Keep service ownership and teardown in that lifecycle, not in parallel scripts. Validate shell syntax, formatting and ShellCheck as available, plus isolated real-container startup/reuse/stop checks.

Observed tools: Python, Node, Docker CLI, Java and adb are on PATH; global Gradle and uipro are absent. UUPM is installed project-locally through its CLI. Docker daemon inspection in this sandbox failed with socket permission denied; no service/build execution was attempted. Tool presence alone proves neither Android SDK readiness nor connected hardware. No SDK download, app dependency install or container was started.

## Required Preview Lifecycle When Implemented

After parsing/validating root configuration and host prerequisites, resolve the Docker endpoint (explicit `DOCKER_CONTEXT` before `DOCKER_HOST`, otherwise inspect the current context using supported CLI commands). Check for owned services first: reuse healthy matching instances, and fail with an explicit recovery action for unhealthy, incomplete or changed instances instead of silently replacing them.

With no existing instance, build only a missing image; install only missing project dependencies in the wrapper container using the committed lock. Installation must not publish ports or inject runtime secrets. Recheck required dependency entries after installation, then start the service group in the background and wait for bounded application readiness. Document the entry checks' inability to detect stale dependency versions. Offline/no-install constraints override automatic preparation and must yield actionable errors. Never rewrite manifests/locks, change `.env`, reset user data or run tests as part of startup.

Support `start` (also the default), `status`, `stop`, `down`, `build`, `--help` and `--verbose`, resolving paths from the script location. Only start prepares missing resources. Help must work without reading configuration. Status, stop and down must not build, install or start; down preserves data. Repeated calls reuse owned resources and tolerate an already-stopped group. Validate cold first use, healthy reuse, changed instances, preparation failure, readiness failure, another working directory and data preservation. Report stub/fixture evidence separately from real Docker execution and caches.

## Environment Setup Contract

No runtime environment keys or local `.env` were created: there is no consumer yet. When one is implemented, update its loader and secret-free root `.env.example` together. Put configurable image/runtime settings and all listeners/published bindings there. Document exact key consumers, loading/override precedence, required user values and restart/rebuild commands; parse dotenv as data, never source it as shell code.

First use will be: copy `.env.example` to `.env` only if absent, edit the listed required values, then `./preview.sh`. Distinguish host tools/daemon from resources start prepares, and explain first downloads. Do not advertise these commands as available until implemented.

On later config changes, compare key presence and append only missing safe entries to an existing local file, preserving values, empty values, comments and formatting. Missing files use full first setup, not a partial patch. Never silently rename/remove local values or print secrets/diffs. Test missing/custom/empty/new-key/repeated-update fixtures when implementing synchronization. Report tracked secret files without rewriting history.

## Preview Output and Network Contract

No preview address or health route is assigned at initialization. For a future trusted-LAN service, configure listener and publish bind for the intended phone access path, normally `0.0.0.0`; respect explicit local/internal-only limits. Never alter firewall rules. Use actual routes and effective ports, not invented `/admin` or docs pages.

After every required service passes readiness, emit one `System is ready.` summary. Nonempty sections appear in this order: `Open:`, `Local only (preview host):`, `Listeners:`, `Published:`, `Internal only:`, `Notes:`. Under Open/Local, put `<entry label> (<service>):` followed by each complete URL on its own plain line. Group by service, deduplicate, omit empty sections. Report every actual listener with scope/protocol and every actual host mapping, marking inactive mappings; internal/container and loopback destinations stay in their respective sections. Wildcards are never browser destinations.

For wildcard host publishing run `ip -br a` on the actual publishing host on each start/ready status; include all assigned eligible addresses from UP/UNKNOWN rows, including secondary/bridge/VPN addresses, removing CIDR and duplicates. Skip DOWN, loopback/unspecified and zone-dependent IPv6 link-local addresses. IPv4 publishing does not prove IPv6; bracket applicable IPv6 URLs. Specific binds advertise their actual addresses; loopback only goes under Local. Missing/failing discovery is an error, not a reason to guess an address. No eligible address means a note and no Open entries. For remote Docker use an authorized discovery route or explicit host configuration; never substitute the caller's addresses. Printed candidates do not establish physical-phone reachability.

Routine setup logs go to stderr; concise `[log]`, `[warn]`, `[error]` use cyan/yellow/red only for an actual TTY, with NO_COLOR/TERM=dumb respected. Titles/readiness/sections may use semantic colors; URLs remain plain. Hide routine Docker/framework chatter behind verbose or failure diagnostics. On terminal failure return nonzero with no success summary. Before cleanup verify repository/scope/service ownership, read a bounded recent log tail with a timeout, and redact injected secrets, account identifiers, bearer tokens and authenticated URLs. Redaction/log-read failure must not dump raw output, prevent cleanup or replace the original failure. Remove only this attempt's resources and preserve data. A running watcher is not readiness.

When implementing a renderer, test multiple interfaces/addresses, duplicate/CIDR removal, UP/UNKNOWN/DOWN, IPv4/IPv6/specific/loopback/internal mappings, missing ip, inactive mappings, HTTPS, remote endpoints/older CLI operations, never-ready services, sensitive log values, log timeout/ownership mismatch and preserved exit status. Use an equivalent project renderer or the available reference with its notices; do not source a developer-machine skill path at runtime.
