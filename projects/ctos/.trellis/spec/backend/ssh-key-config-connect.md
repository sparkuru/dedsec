# Config and unencrypted private-key connections

## 1. Scope / Trigger

Extend the same production model/connector described in
[the current contract](../../../design/ssh-key-config-connect.md). The selected user case uses
an ordinary OpenSSH private key without a passphrase and a password-disabled endpoint.
Do not infer delivery from the pre-existing parser/private USERAUTH APIs or old password tests.

## 2. Signatures

`SshKeyMaterial.inspect(byte[], long)` returns public-only Status/kind/algorithm/fingerprint and
owned publicBlob/publicLine copies. It uses `ctos_ssh_material_export_public(...)` through JNI.
`SshConfigEvaluator.prepareDirectDetached(guard)` and `directPlan()` return an immutable direct
plan from the actual pinned Options, via the existing owned opaque registry.

Host UI methods: `ssh.library`, `ssh.importFile`, `ssh.importCommit`, `ssh.importCancel`,
`ssh.configRead`, `ssh.configPreview`, `ssh.keyConnect`, `ssh.configConnect`; preserve the prior
password API and ctos/ssh review/state events. Exact metadata payloads belong to the current
domain contract and typed Dart boundary; no method returns private key/credential bytes.

## 3. Contracts

Public extraction parses the supplied actual private container, applies existing software key
policy and a fixed-domain local signing/verification proof, then returns the real raw public
blob. Digest/fingerprint/test peer are not replacements. CSKE v1 header56 binds status/kind/
flags/blob length/public SHA256 to the full blob. Input<=1MiB, original BOOTTIME deadline<=60s;
encrypted, unsupported and malformed outcomes carry no key/proof fields. No passphrase retry,
returned signature or arbitrary signing payload. Wipe all private and JNI scratch on every exit.

Direct mode adds strict active-declaration policy to the same lexer; old effective/catalog/
audit/resource modes remain unchanged. Keep actual Host/Match/Include/first-value/identity-list
semantics. Final plan includes real tuple, ordered expanded managed IdentityFile/known_hosts,
auth enabled flags, identitiesOnly, timeout, PTY and strict host policy. Resolve identity paths
using identity semantics, not Include's relative base. Diagnose unsupported/CWD/external paths
with actual object/line. Never silently drop jump/forward/RemoteCommand/HostKeyAlias, keepalive,
algorithm overrides or any other unconsumed declaration. Strict YES rejects unknown trust;
ASK requires the existing actual fingerprint approval. Multiple actual trust sources must be
consumed or visibly refused, never silently truncated to the first one.

Use the existing guarded bundle transaction for files plus explicit immutable metadata changes.
Bind original source/proposal/candidate, preserve unrelated entries/files/relations, and check
actual private/public material and role/selection/account at final typed release. No secret
reads based solely on a claimed KeyBinding/KEY Account. A supported-graph validator is not a
blank or partial-only inspector; complex metadata restore approval remains closed.

SAF input reads bounded streams into host-owned arrays; no plaintext ToolFiles stage, external
write, persistent grant or URI reread after review. Picker, preview, confirm and final receipt
retain original epoch/revision/registration/deadline/cancel. Replacement is exact and explicit.
ConfigRead returns CONFIG only; source known_hosts never automatically becomes trusted.
Mixed key material must survive old password defaults/status/save and forget-all operations.

Library key IDs bind managed path AND actual public identity. Host rows carry an authenticated
source/epoch/revision/alias binding; configConnect must require/recheck that exact binding before
mutation/TCP. Replacing a key invalidates old IDs and only its dependent runtime KEY Accounts.
Typed private release resource is exactly `path + '\0' + keyIdentity`, checked at both admission
and delivery; path-only comparison rejects the valid selected request or loses identity binding.
Synthetic KEX fixtures need a nonzero session binding; never relax production proof validation.
Generated config must quote/escape actual accepted key paths, covered by the real pinned lexer.
SAF provider query/open/read is synchronous: bounds and original cancel/deadline are checked
after return, not a guarantee of hard wall-clock interruption inside an external provider.

Session startup must consume the exact selected view root and trust path. Legacy password
defaults `ssh/config` / `ssh/known_hosts` are compatibility defaults, never authority for a
key/config view rooted at `.ssh/ctos-manual` or `.ssh/config`. Java passes actual knownHostsPath
through managed socket open; C derives the root from the validated view and requires a real
CONFIG entry with the selected KNOWN_HOSTS direct reference. Never synthesize aliases or skip
these gates to make paths agree. Cover all three roots at real native startup, the Java path
handoff, and independent key-only interoperability using the actual `.ssh/` product paths.
The offered-host issuer must apply the same actual-root/exact-trust-reference rule at KEX;
successful open/banner alone does not exercise that later consumer. Regress actual host
NEW/KNOWN/changed/revoked classification and Android import->approval->authentication->PTY.

## 4. Validation & Error Matrix

Malformed/unsupported/encrypted key -> explicit fixed refusal before import or authentication.
Missing Include/identity -> supplement/edit/cancel, no half graph commit. Changed source/role/
selected identity or lost protection -> fail before secret copy. Late write cancellation ->
shared uncertain result and lock, not a false no-effect receipt. Config auth disabled/unsupported
direct plan -> fail before TCP/credential use; no password or direct-connect fallback.
Diagnostics expose bounded source/path/line/codes, never raw key bodies or exception payloads.

## 5. Good / Base / Bad Cases

Good: native derives public key, exact key/config import commits atomically, Host alias evaluates
to the shown tuple/key, real KEX and publickey SUCCESS precede PTY. Base: previous password and
TOTP data remain usable. Bad: use a canned fixture public blob, parse config in Dart/Java,
persist files before their bindings, auto-trust imported records, or keep a successful old key
selection after source replacement.

## 6. Tests Required

Real C public export/proof software matrix and old material cases; strict native plan and old
parser mode matrices; Java malformed wire/owner/deadline/cleanup, actual encrypted graph
transactions, source replacement/role/key binding and mixed preservation. Flutter protected
imports/editor, preview/replace/cancel/uncertain, key-only empty password, real-metadata Host
selection, stale/lifecycle and narrow/landscape/large-text paths. Independent sshd must disable
password and prove non-echo PTY output; Android import/JNI/UI and actual server require distinct
current authorization/evidence. Main serializes all builds; record only actual results.

## 7. Wrong vs Correct

Wrong: the key parser passed, therefore the installed APK supports private-key login.
Correct: separately verify user-selected import, stored identity, actual selection/KEX/key-only
authentication and terminal handoff; keep engine-only evidence explicitly scoped.
