# Research: current SSH import path portability

- Query: Diagnose directory/individual-import path behavior after feedback that desktop `.ssh/config` paths do not match a phone, and identify what the existing parser actually accepts.
- Scope: read-only internal source and existing-test inspection; no implementation, build, device, network or git operations.
- Date: 2026-11-02
- Latest direction: while this diagnosis was running, the user explicitly chose to remove directory batch import and retain only individual config/private-key imports, without path migration/adaptation. The findings below describe the pre-removal checkout; relocation design is not a current recommendation or implementation task.

## Findings

### Directory import preserves names and CONFIG bytes; it does not migrate a desktop environment

`SshDirectorySnapshot.read` initializes the selected tree root as managed `.ssh`, ignores the selected directory's own name, and appends each child display name (`android/app/src/main/java/im/majo/ctos/SshDirectorySnapshot.java:85`–`:109`). Thus selecting the actual source `.ssh` directory maps its `config` to `.ssh/config` and `keys/foo` to `.ssh/keys/foo`. Selecting a parent that contains `.ssh` instead maps the nested file to `.ssh/.ssh/config`. Source `config.bin` becomes `.ssh/config.bin`; there is no chosen-role rename or root-config selector in this directory flow.

CONFIG candidates use `file.input.body` unchanged (`SshDirectoryImport.java:224`–`:233`), then run native aggregate reference refresh (`:250`–`:261`). This refresh changes graph reference metadata, not source config text. The individual config proposal likewise stores supplied `body` unchanged (`SshImportProposal.java:83`–`:94`). Neither flow replaces `/home/alice` with a phone directory, rewrites `IdentityFile`, rewrites `Include`, or guesses a key from its basename.

The virtual home is fixed `/ctos-vault` (`android/app/src/main/cpp/ssh_file_io.h:7`); it is an internal namespace, not Android shared storage or the actual application data directory. `ctos_ssh_io_resolve` accepts only that absolute prefix, or a `~/` pattern where explicitly permitted (`ssh_file_io.c:29`–`:38`). Managed path validation rejects escapes, empty components, dot/dot-dot, backslash, colon, percent and tilde inside the resulting managed path (`ssh_file_view.c:70`–`:83`).

### Exact path behavior

The following table separates actual resource audit from strict import/connection behavior. Examples assume an ordinary literal value without extra token/environment expansion.

| Declaration | Current native behavior | Import/direct consequence |
| --- | --- | --- |
| `IdentityFile ~/.ssh/foo` | Resource audit expands `~/` against `/ctos-vault`, producing managed `.ssh/foo`; STATIC when an eligible object exists, DEFERRED_KEY_RESOLUTION when absent. | Works for an imported key at `.ssh/foo`; strict config import refuses absent identity. |
| `IdentityFile ~/foo` | Resolves to managed `foo`, **not** `.ssh/foo`. | The directory importer only produces `.ssh/...`, so its key cannot satisfy this reference. Current individual key import also requires `.ssh/...`. |
| `IdentityFile keys/foo` | Resource audit reports DEFERRED_CWD (state 3), without guessing a base. Generic Options inspection can retain this string. | Strict config import rejects it; final direct plan also rejects it because it is neither `~/...` nor `/ctos-vault/...`. |
| `IdentityFile /home/alice/.ssh/foo` or `/Users/alice/.ssh/foo` | Resource audit rejects absolute paths outside `/ctos-vault/` with CTOS_CONFIG_PATH. | No automatic rebasing; import/direct cannot use it. |
| `IdentityFile /ctos-vault/.ssh/foo` | Resolves to managed `.ssh/foo`. | Equivalent internal target to `~/.ssh/foo` when an eligible current key exists. |
| `IdentityFile C:/Users/alice/.ssh/foo` or `C:\Users\alice\.ssh\foo` | These are not POSIX absolute paths; ordinary resource audit initially classifies them as CWD-dependent. | Strict import refuses unresolved CWD; direct resolution refuses them. No drive-letter/Windows-home adaptation. |
| `Include conf.d/foo` | Pinned user-config parser anchors relative Includes at virtual `~/.ssh`, producing managed `.ssh/conf.d/foo`. | Works if the selected CONFIG exists. This applies even inside another included file; the base is not that child's containing directory. |
| `Include ~/.ssh/foo` | Managed `.ssh/foo`. | Eligible CONFIG required; wrong type, missing file and cycles reject. |
| `Include ~/foo` | Managed `foo`. | Not satisfied by directory-imported `.ssh/foo`. |
| `Include /home/alice/.ssh/foo` or `/Users/alice/.ssh/foo` | Glob/path resolver refuses the external absolute root. | No desktop-home migration. |
| `Include C:/Users/alice/.ssh/foo` or `C:\Users\alice\.ssh\foo` | Upstream treats the value as relative, anchors it under `~/.ssh`, then managed path validation rejects colon/backslash. | No Windows path support/adaptation. |

The decisive resource-audit branches are `ssh_config_eval.c:226`–`:254`: external absolute/user-home paths reject; token/environment-dependent resources defer; bare relative values defer; only admitted `~/` or virtual absolute values receive a static target. Strict new-config references reject all states except STATIC/DISABLED (`SshConnectionMaterial.java:357`–`:364`). Stored graph admission separately permits only an absent static IdentityFile for key deletion (`:367`–`:384`); it does not admit arbitrary relative/desktop paths.

Final direct connection resolution is separate: `direct_path` expands tokens, maps `~/` to the fixed virtual home, then calls the same resolver (`ssh_config_eval.c:866`–`:887`). A successful generic Options summary is not evidence that a direct connector can consume every retained path.

Pinned Include anchoring is visible in fixed upstream `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/readconf.c:2094`–`:2115`. The project patch preserves this base and makes unmatched Include/glob failure a hard error (`android/ssh/openssh-10.6p1-config-evaluator.patch`, `case oInclude` hunks around line 684). `ctos_config_glob` resolves patterns through the virtual namespace (`ssh_config_eval.c:468`); `path_absolute` recognizes only leading `/` in pinned `misc.c:2669`.

### Individual file imports distinguish source filename from managed role

The current UI asks for the managed destination **before** launching the system single-file picker (`lib/ssh/library_page.dart:229`–`:286`). Config defaults to `.ssh/config`; private-key destination defaults to empty with a note that an empty path uses the source name. Host `SshProfilesBridge.pick` receives this role/path and uses `ACTION_OPEN_DOCUMENT` (`:328`–`:340`). On return it obtains the source display name; only an empty destination invokes `SshImportProposal.defaultPath` (`SshProfilesBridge.java:487`–`:498`).

- Selecting source `config.bin` as config with the default destination imports it as `.ssh/config`. Its physical source extension does not determine the role, and its content remains unchanged.
- Selecting source `id_rsa.bin` as private key with an empty destination imports it as `.ssh/id_rsa.bin`; the host does not strip `.bin`. The UI can instead supply `.ssh/id_rsa` explicitly. The actual key still needs native parse/self-proof; filename alone is not an identity proof.
- An empty config destination also defaults to `.ssh/config`; default private names are bounded literal `[A-Za-z0-9_.-]` components (`SshImportProposal.java:146`–`:151`).
- A config that refers to `~/.ssh/id_rsa` will not silently resolve an imported `.ssh/id_rsa.bin`. The existing user control is the explicit managed destination (or separately editing the config), not a migration engine.

### Existing tests and limits of this diagnosis

- `ssh_config_resource_test.c:119`–`:142` asserts STATIC targets for `~/...`/virtual absolute resources, DEFERRED_CWD for bare relative values and DEFERRED_KEY_RESOLUTION for absent static identities. `:150`–`:173` asserts wrong-type, `~other`, external absolute, traversal and wildcard path refusals.
- `ssh_config_eval_test.c:204`–`:242` covers relative Include globs, nested Include still anchored at `.ssh`, `~/.ssh` cycle detection, wrong-type Include, source diagnostics and an admitted virtual absolute Include. Its nested `.ssh/conf/a.conf` contains `Include nested` and resolves `.ssh/nested`, explicitly demonstrating the fixed `.ssh` base.
- Android fixture code `SshPasswordConnectPlatformTest.java:960`–`:964` distinguishes stored missing-static-identity acceptance from relative-key rejection. Existing directory fixture source uses explicitly portable `IdentityFile ~/.ssh/keys/...` (`:1062`); it does not prove desktop absolute-path migration.
- The exact `/home/alice`, `/Users/alice` and Windows examples above are source-branch deductions; this investigation did not add/run those cases. No new runtime or Android DocumentsUI/grant evidence was obtained.

### Parser seam identified before direction changed

Existing RESOURCE_AUDIT already knows CONFIG object, line, resource kind and argument ordinal (`ssh_config_eval.c:256`–`:283`), but its published rows do not retain raw old path values or token byte spans. Include audit edges contain parent/line/child but not original argument ordinal (`:374`–`:384`), and missing external Includes fail before producing a complete useful migration list. Consequently these existing reports are not a ready-made safe per-reference editor.

The project-owned readconf patch has a hook after shared keyword/argv parsing and before Host matching consumes arguments (`openssh-10.6p1-config-evaluator.patch:558`–`:590`). Pinned `argv_split` handles quotes, escapes and comments but returns decoded tokens without source spans (`misc.c:2143`–`:2208`). A future reviewed relocation mechanism would require additional parser-backed provenance; Java/Dart regex substitution or basename matching would not supply it. Per latest user instruction, research stopped here: no relocation contract, new lexer, UI or native operation was designed or implemented.

## Files found

- `SshDirectorySnapshot.java`, `SshDirectoryImport.java` — relative tree mapping and unchanged CONFIG body construction.
- `SshProfilesBridge.java`, `SshImportProposal.java`, `lib/ssh/library_page.dart` — individual role/path selection, source filename fallback and native proof boundary.
- `ssh_config_eval.c`, `ssh_file_io.[ch]`, `ssh_file_view.c` — actual resource/direct/Include path policies and fixed virtual namespace.
- Fixed `readconf.c`, `misc.c`, project config-evaluator patch — pinned grammar, Include anchoring, path classification and existing audit hook.
- Native resource/evaluator tests and Android SSH fixture — existing declared coverage, separate from tests run in this investigation.

## Related specs

`.trellis/spec/backend/ssh-key-config-connect.md`, `ssh-config-resources.md`, `ssh-directory-import.md`, and `design/ssh-directory-import.md` describe the examined behavior. The directory contract is historical once the user-directed removal lands; do not promote this diagnosis into a path-migration requirement.

## External references

None consulted. Android's Download-root selection restriction is outside this source-only investigation; no new platform claim is made here.

## Caveats / Not Found

- No automatic config-path migration, directory destination-role selector, `.bin` suffix stripping or filename-based key reassignment exists in the examined implementation.
- This file is historical diagnosis supporting the user's removal choice. The current requested outcome is single config/private-key imports without path migration.
- No product-code edits or checks were performed by this researcher. Source locations describe the pre-removal checkout and may move while the main agent implements removal.
