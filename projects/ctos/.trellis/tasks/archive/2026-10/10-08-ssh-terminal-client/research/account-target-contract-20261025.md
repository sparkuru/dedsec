# Research: persisted SSH Account target contract

- Query: Trace the existing target/host/port/username boundaries and recommend a narrow Account declaration without interpreting v1 identity strings or changing live authorization.
- Scope: internal; read-only Java/C/spec inspection. This is research for the primary agent's final contract, not an implemented or approved schema.
- Date: 2026-10-25

## Findings

### Files found

All Java paths below are relative to `android/app/src/main/java/im/majo/ctos/`; C paths are relative to `android/app/src/main/cpp/`.

| File | Purpose / useful anchor |
| --- | --- |
| `SshRoleContext.java:84` | Live role bootstrap validation and immutable exact target/host/port coordinates. |
| `SshNativeRoleBootstrap.java:89` | Native bootstrap wire decode; target/host private validators at 252/262. |
| `ssh_role_bootstrap.c:72` | Native target charset; host predicate at 89. |
| `SshAuthenticationSelection.java:22` | Username selection and explicit password/private-key selection; strict UTF-8 helper at 110. |
| `SshConfigEvaluator.java:100` | Request alias and remote-user inputs; strict string helper at 768. |
| `ssh_config_eval.c:579` | Native Request host/user policy; effective hostname normalization at 846. |
| `SshHostKeyAuthority.java:59` | Host evidence compares exact coordinates; proof check at 133. |
| `SshCredentialBridge.java:84` | Exact selection/role/host proof gates before typed credential access. |
| `SshVaultAdapter.java:60` | Adapter passes opaque target identity through unchanged. |
| `AppSecrets.java:2467` | Secret access checks exact entry identity/type; key bundle ownership at 2829. |
| `VaultManifest.java:175` | v1 structural validation; existing ID helper at 245. |
| `SshManifestPrivateMaterialInspector.java:222` | Password namespace is checked, actual target remains deferred. |
| `android/app/src/test/java/im/majo/ctos/SshNativeSessionTest.java:385` | Selection explicitly preserves username ` 用户😀 `, including spaces. |
| `android/app/src/test/java/im/majo/ctos/SshConfigEvaluatorTest.java:144` | Standard UTF-8 Request encoding, Unicode names, port=0 encoding. |

### Existing behavior is not one shared target validator

| Boundary | Actual validation / normalization |
| --- | --- |
| v1 non-TOTP entry identity | `VaultManifest.java:178–186`: UTF-8 length <=512; regex `(?:target|key):[A-Za-z0-9_@.:%+/\\[\\]-]{1,500}`. The suffix is not parsed. IDs are globally unique across entries/files, but identities need not be unique. |
| Managed role target | `SshRoleContext.java:84–103,175`: nonempty, <=512 UTF-8 bytes, no NUL/C0/DEL. Broader than native target; not strict malformed-surrogate validation. |
| Native/bootstrap target | `ssh_role_bootstrap.c:72–86`, `SshNativeRoleBootstrap.java:252–259`: nonempty ASCII <=512; only letters/digits and `_`, `-`, `.`, `:`, `@`, `+`, `/`. No mandatory `target:` prefix. `%`, `[` and `]` accepted in v1 entry identities are rejected here. |
| Role/bootstrap host | `SshRoleContext.java:180`, `SshNativeRoleBootstrap.java:262`, `ssh_role_bootstrap.c:89`: nonempty ASCII <=253; letters/digits plus `.:%[]-`. This is a character/length predicate, not a valid DNS/IP parser. It accepts bracket characters, zone markers, trailing dots and uppercase without validating their placement. No lowercasing, bracket stripping, IDNA, DNS, NFC or address compression. |
| Role/bootstrap port | Integer 1..65535 (`SshRoleContext.java:89`, `SshNativeRoleBootstrap.java:105`). No default or zero sentinel here. |
| Authentication username | `SshAuthenticationSelection.java:22–34,110–121`: nonempty valid UTF-8, <=255 bytes, reject all `Character.isISOControl` (C0, DEL, C1), reject malformed/unpaired surrogates with encoder REPORT. Exact string preserved. Spaces, Unicode and non-control punctuation are not generally forbidden; no trim/casefold/NFC. |
| Managed config Request | `SshConfigEvaluator.java:100–119,768–797`: alias/local user/local hostname nonempty <=255 bytes; remoteUser/fallback/tag may be empty, <=255 bytes. Strict UTF-8, no ISO controls or unpaired surrogates. Port 0 means unspecified override; 1..65535 explicit. This is request syntax, not a final endpoint. |
| Native config Request | `ssh_config_eval.c:579–581` applies upstream hostname/remote-user predicates in addition to managed text rules. Therefore managed string acceptance alone does not prove native config acceptance. |
| Native effective hostname | `ssh_config_eval.c:846–855`: expands HostName `%h`, rejects empty/too-long/upstream-invalid host. If it has `:` or `%`, or consists only of digits/dots, preserve spelling; otherwise fold ASCII A–Z to lowercase. No DNS/IDNA resolution. Final-pass config is then evaluated at 859–861. |
| Native effective username | Literal request override is assigned at 835; config-derived User is percent-expanded and checked at 862–877. Defaults are filled at 880. Never infer persisted actual username from alias, target suffix or local UID. |

Exact equality is operational: `SshRoleContext.java:147–163` hashes target/host/port into role binding; `SshHostKeyAuthority.java:59–63,133–137` compares exact targetIdentity and host plus port/connection/hop. A new persisted model must not silently replace these with casefolded/DNS-equivalent lookup.

### Credential meaning is explicit declaration, not proof of a remote account

- `AppSecrets.java:1444–1449` stores the caller's target identity verbatim; `2467–2472` later requires the chosen entry's identity to equal the requested target and its type to match. There is no host/username parser or derivation at this boundary.
- `SshCredentialBridge.java:84–104,126–167,192–215` requires the actual selected entry/key, current role, verified host, KEX and bounded operation. A persisted Account must not replace any of these gates or create a role/receipt.
- `SshAuthenticationSelection.java:9–19` explicitly calls selected public bytes a declaration, not parsed-key proof. Similarly, an Account saying `alice` at `host:22` with a password entry proves only explicit metadata correspondence, not password correctness, existence of the remote user, host trust, or that the declared key is accepted there.
- `SshManifestPrivateMaterialInspector.java:222–224` reports password `target:` namespace as `TARGET_DEFERRED`; `design/ssh-private-material-ownership.md:27–33` explicitly forbids parsing its suffix into host/port/username. Preserve every raw v1 record; do not fabricate Account records, parse URI-like examples, or attach a first matching password.
- No production `SshTarget*`, SSH URI parser or persisted profile endpoint API was found in main Java/`lib/`. Existing URI consumers are unrelated TOTP/SAF/HFTP code. `design/ssh-launcher.md:8–10,124` still separates the future immutable connection plan and connector. This negative search is scoped to the repository paths inspected, not an assertion about all historical artifacts.

### Recommended representation to freeze explicitly

The compatible narrow shape is one immutable metadata record:

`Account(id, targetIdentity, host, port, username, authKind, passwordEntryId | keyBindingId)`

1. `id`: required existing identifier rule `[A-Za-z0-9_-]{1,128}`. Keep explicit IDs; do not derive them from host or target strings. Extend global manifest ID uniqueness across new record kinds if those kinds share a reference namespace.
2. `targetIdentity`: required opaque **existing** `target:` identity, byte-for-byte. Use the v1 `target:` namespace/length/character rule for compatibility, not an invented `target:user@host:port` grammar and not `target:` + Account ID. Require the password entry's identity to equal this field exactly. Keep targetIdentity independent of host/port/user.
3. `host`: required actual destination coordinate, not a config alias or whole URI. A narrow compatibility choice is the current role/bootstrap ASCII predicate, <=253, with the supplied spelling preserved exactly. No trimming, casefolding, trailing-dot removal, bracket stripping, IDNA, DNS or IP canonicalization inside Account construction/decode. The predicate only proves representability; it does not certify a valid network endpoint. Host endpoint syntax hardening requires a separately frozen policy and evidence, not a hidden side effect of persistence.
4. `port`: required explicit integer 1..65535. Do not persist config Request's `0` sentinel or infer 22 in a decoder.
5. `username`: reuse the exact selection boundary semantics: nonempty strict UTF-8 <=255 bytes, no ISO controls or malformed surrogate, preserve spaces and Unicode. Do not apply URI percent-decoding or normalize text. Native config has additional policy; applying it retroactively would narrow existing direct authentication selections.
6. `authKind`: closed enum, exactly one of PASSWORD or KEY. PASSWORD requires exactly one `passwordEntryId` resolving to SSH_PASSWORD with exact targetIdentity; KEY requires exactly one explicit keyBindingId. Reject both/neither, wrong type and dangling reference. No empty-string sentinel, union fallback, first-match search or trial of multiple secrets. A KeyBinding may be shared by explicitly named Accounts; its material proof remains separate.
7. A metadata validator may prove those structural/exact relationships without copying a password. It must not label the endpoint authenticated, open a credential operation, or grant provider/restore/connector admission.

Important distinction: preserving a v1-valid `target:...%...` or brackets does not make it compatible with the current native role target predicate. Keep it representable/deferred as metadata and require the future live path to reject an unrepresentable role explicitly; do not delete/repair it on migration. If the primary instead wants every new Account to be immediately role-representable, it must expressly choose the intersection predicate for **new Account creation only**, and document that legacy password records can remain unattached. Neither choice may tighten raw-v1 decode.

When a future config plan uses an Account, compare the Account's explicit actual host/port/username with the final effective plan at an explicit boundary. The config evaluator's existing hostname lowercasing must remain visible there; do not silently treat an uppercase stored host as equal to a lowercase plan. The implementation may require the user to select/update the actual effective coordinate before binding it. An alias/catalog summary is not a final target or authorization receipt.

### Cardinality, duplicates and bounds: decisions, not current facts

- Existing identities and endpoint spellings are not unique. Recommended narrow policy: unique Account ID and unique exact `targetIdentity` per Account list; duplicate account IDs/target strings fail instead of selecting one. This ensures exact legacy password target lookup has at most one declared account. This is a new schema choice, not current v1 behavior.
- Do not merge accounts by lowercased host or host/port/user tuple. Different opaque targets can intentionally refer to the same tuple with different explicit credentials. Permit that if all consumers use Account IDs. If the product wants tuple lookup or one credential per endpoint, uniqueness is a separate product choice that must be stated before implementation.
- One Account selects one credential relation. Several Accounts may deliberately reference one KeyBinding; password-entry cardinality follows the chosen exact target uniqueness. Multiple v1 password entries for one target remain valid v1 data: each Account names one exact entry, and any requirement to cover every old entry must report the remaining entries explicitly rather than attach arbitrary extras.
- A conservative new bound would be <=1024 Accounts (existing `VaultManifest.MAX_ENTRIES`) and <=128 KeyBindings (existing `MAX_FILES`), plus existing 12 MiB total encoded plaintext (`VaultEnvelope.java:20`). These are proposed bounds, not an existing schema contract; multiple legitimate bindings per private file could require a different binding cap. Check raw counts before allocation and encoded aggregate size with checked arithmetic, reject rather than truncate.
- Do not add secret values, secret-derived hashes, host-resolution caches, native role bindings or authentication receipts to Account metadata.

### Reusable validators / implementation guidance

The matching predicates currently exist as **private** helpers, not reusable package APIs: `VaultManifest.identifier`, `SshAuthenticationSelection.strictText`, `SshRoleContext.host`, `SshNativeRoleBootstrap.host/target`, `SshConfigEvaluator.checkedText/utf8`. A small shared validator for the new schema can mirror identifier, role-host and selection-username semantics. Extracting existing helpers is possible but must preserve every caller's exception and validation contract; do not unify differently permissive target/text rules by accident. Avoid `InetAddress`, `URI`, IDN helpers or resolver APIs for metadata validation.

Suggested meaningful contract cases for the future implementer: raw-v1 identity strings containing `%[]` survive unchanged and do not manufacture an Account; typed reference mismatch and both/neither auth relation fail; uppercase/trailing-dot/scoped/bracketed host spelling round-trips byte-exact; 253/254-byte ASCII hosts; port 0/65536 rejection; 255/256-byte multibyte usernames; unpaired surrogate and C1-control rejection; ` 用户😀 ` preserved; duplicate exact target rejection versus duplicate endpoint tuple policy; full count/aggregate bounds; metadata validation never fetches/copies secrets or issues a live receipt.

### Related specs and external references

- Read `.trellis/workflow.md`, `.trellis/spec/backend/index.md`, `.trellis/spec/backend/ssh-native.md` and project landing/plan/constraints. The SSH spec at 298–319 separates Request and raw Options, forbids DNS/host environment effects and treats final connection planning separately.
- `design/ssh-config-managed.md:55–72`: strict standard UTF-8 managed wire, controls and port=0 semantics; summary is not a launch plan.
- `design/ssh-config-evaluator.md:109` and `.trellis/spec/backend/ssh-native.md:316–319`: bounded decoded jump host/user, missing remote-user handling and unsupported canonicalization/local environment.
- `design/ssh-private-material-ownership.md:16–33`: opaque v1 password target, exact key owner declaration and deferred remote account proof.
- Local external-source reference only: cached OpenSSH 10.6p1 `build/ssh-native-g1/stock-run.zPRULpSC/sources/openssh-10.6p1/readconf.c:3440–3470`. `ssh_valid_hostname` rejects leading `-`, selected shell punctuation and whitespace/control; `ssh_valid_ruser` rejects leading `-`, controls, shell punctuation and whitespace followed by `-`. These predicates are additional config safety checks, not DNS/username normalization or Account validators. This is an inspected cached source snapshot, not fresh external verification; production wrapper references are in `ssh_config_eval.c:55–56,579–581` and hook declarations.

## Caveats / Not Found

- No build, test execution, runtime, network, device, real-account, secret access, source edits or git operations were performed. Cited tests were read, not rerun.
- Account/schema, target uniqueness, count limits, role-compatible-only creation and final-plan equality rules above remain recommendations for the primary agent to freeze. No silent user choice or claim of implemented persistence is intended.
- Raw v1 structure, native bootstrap target and native config safety predicates deliberately differ. Requiring one global validator would change current behavior and can reject previously accepted data.
- This research covers endpoint/selection contracts. Manifest reconstruction paths, full key graph migration, proof/provider admission and UI design are outside this focused topic.
