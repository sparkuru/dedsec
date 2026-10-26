# Research: Manifest-bound native SSH validation without a live session

- Query: Which pinned OpenSSH/native facilities can validate the actual supplied VaultManifest without a live lease or invented Request; what bounded component should be implemented next?
- Scope: internal; pinned source and project contracts only. No builds, runtime, network, device, real vault/account access or git operations.
- Date: 2026-10-19
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`

## Findings

### Recommendation and limits

The next bounded component should be a **context-free native material inspector** for supplied public/private key and known_hosts bytes. Factor and reuse the current private-key policy/parse primitives rather than calling session-bound authentication. Give the inspector its own bounded operation/cancellation/deadline context, not a fabricated connection role, public lease, target alias, local username or passwd. Initially keep it separate from `AppSecrets.SshContentValidator`: successful material inspection does not establish config semantics, parsed reference closure, credential selection or restored trust approval.

The full validator must receive the actual authenticated source, target or candidate manifest supplied by its caller. Reusing the current vault's public lease is wrong both because it can describe different bytes and because PRIVATE_KEY bodies are absent by construction. An independent content inspection entry is useful without prematurely opening the restore/mutation gate. Keep ordinary effective config evaluation on the existing real-Request path.

There are three independent guarantees to implement and name separately:

1. All supplied resource bodies are structurally valid and obey the supported material policy; associated secrets are bound exactly and, when available, successfully decrypt the selected key.
2. Config declaration/reference admission is defined and checked against the supplied manifest, using shared pinned grammar. Dynamic/contextual behavior is explicitly unresolved or rejected by a newly frozen admission policy, not silently accepted as complete.
3. Actual selected connection evaluation/finalization and exact trust restore review/approval happen with their own real contexts. Finite literal catalog enumeration cannot certify every Host/Match context.

### Files found

Pinned paths below are relative to `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/`.

| Path | Purpose |
| --- | --- |
| `readconf.c:1143-1210,1833-1888,2072-2158` | Existing lexical boundary; Host/Match dispatch; static/dynamic Include traversal and SSH-directory anchoring |
| `sshkey.c:1299-1371,1989-2089` | OpenSSH public text and binary blob parsing, including trailing binary rejection and certificate signature parsing |
| `sshkey.c:2979-3309,3679-3733` | In-memory OpenSSH private envelope/decrypt/parser and PEM fallback; untrusted outer public envelope extraction |
| `sshkey.c:2431-2503` | Explicit supplied-time certificate type/validity/principal checks versus current-wall-clock wrapper |
| `hostfile.c:69-153,158-210,769-959` / `hostfile.h` | Known-host marker/key parsing, optional host matching, hashed-host parsing and callback statuses |
| `android/app/src/main/cpp/ssh_native_client.c:1045-1188,1191-1208,1210-1315` | Actual production software key allowlist, encrypted KDF/framing policy, CSKB and parse/equal/sign/local-verify flow |
| `android/app/src/main/cpp/ssh_hostfile_issuer.c:149-159,209-335` | Actual role/target-bound known_hosts preflight and trust classification, unsuitable as a context-free validator entry |
| `android/app/src/main/cpp/ssh_config_eval.c:172-209,230-258,269-339,391-423,440-460` | Catalog collector/deferred sites; CONFIG-only managed lookup; bounded reader/glob; request-based Include expansion; effective policy checks |
| `android/app/src/main/cpp/ssh_config_eval_hooks.h` | Translation-unit-local, namespaced parser hooks; do not globally interpose session symbols |
| `android/app/src/main/cpp/ssh_file_view.c:249-263` | Explicit PRIVATE_KEY lookup denial |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java:20-77,173-239` | Actual typed manifest bytes, graph/size checks and passphrase identity association; no semantic SSH parsing |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java:38,148,164-175,826-830` | Whole-manifest validator hook and default-deny gate; selected private-key/passphrase identity checks |
| `android/app/src/main/java/im/majo/ctos/SshFileSnapshot.java:38-54` | Public snapshot exposes all metadata but only non-private bodies |
| `.trellis/tasks/10-08-ssh-terminal-client/research/openssh-10.6p1-config-evaluator.patch:375-393,490-535` | Current shared-lexer catalog branch and deferred/static Include adaptation |
| `.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh:8-27,204-237,277-305` | Fixed input source/hash and patched hostfile object replacement, preserving the production archive member set |

### Config: what can be established without a Request

The current catalog branches immediately after the same `strdelim/parse_token/argv_split` grammar, before the opcode switch. It lexically consumes recognized non-Include directives. Consequently `Port invalid`, malformed Match operands and prohibited command/provider/trust options can pass catalog; the contract explicitly says so. `ctos_config_options_check` only checks effective accumulated Options during ordinary evaluation. It is not an all-declarations policy pass. Do not turn catalog success into the whole-manifest validator callback.

The same shared lexical boundary can emit typed **reference declarations** for Include, IdentityFile, CertificateFile and User/GlobalKnownHostsFile, with source object/line and original token semantics, but this is a proposed extension. Reuse each pinned opcode's argument grammar, including per-directive `none`, lists, quoting and expansion behavior; do not split or classify raw config lines in Java. A new generic path-token collector would need an explicit policy for ignored/duplicate/inactive declarations, and cannot claim these declarations are effective references.

Static Include traversal already uses the same pinned Include branch, CONFIG-only managed glob, lexical ordering, SSH-directory relative base, depth8 and current-stack cycle check. It conservatively traverses inactive branches. `ctos_config_catalog_defer` records any `%`/`$` token before expansion and does not read it. These unresolved sites cannot be claimed covered by the current static closure. Even `%u` requires a defined context; `%%` is deliberately conservative in the existing contract.

Distinguish supplied metadata graph, parsed static Include graph and effective contextual resource graph. `VaultManifest.validate` confirms declared paths exist and the graph is acyclic/bounded; it does not compare those paths with parsed Include/IdentityFile/trust directives. Native `object_index` looks through the supplied view and checks CONFIG type; it does not prove each read was a declared direct edge of its current parent. Whole-manifest validation must decide whether declared refs are exact direct edges or an admitted conservative superset, then compare using that definition. It must also define multiple CONFIG roots: the manifest schema has no root registry, while the current public snapshot carries one caller-selected root.

Scanning every supplied CONFIG as a root can provide all-object lexical/resource coverage, but must be named accordingly. It differs from root-reachable catalog discovery and may encounter disconnected snippets intended only as includes. Enumerating only catalog aliases misses wildcard/negated/Match-only contexts. Running ordinary readconf with all branches active or an arbitrary alias/user changes first-value semantics and is not a safe replacement.

A defensible partial inspection can return `CONFIG_LEXICAL_ONLY`, located dynamic reference sites and unresolved context flags. If admission demands complete context-free certification, explicitly reject contextual references/unsupported predicates under a documented product policy; do not silently narrow existing effective evaluator behavior. Full per-directive syntax checking would require further factoring of pinned parse-versus-apply code, especially Match, rather than a second grammar.

### Private keys: reusable checks and passphrase limits

`software_identity` (`ssh_native_client.c:1045`) admits only plain software Ed25519, ECDSA and RSA; rejects certificates, SK and EXT keys; and applies `sshkey_check_rsa_length(key,0)`. The pinned upstream floor is 1024 bits (`sshkey.h:47`, `sshkey.c:1455-1468`), not an implicit product minimum of 2048. Preserve and document the same policy unless the main contract intentionally changes it.

`encrypted_key_policy` (`:1123`) is a necessary **pre-decryption work bound**, currently private to the session file. OpenSSH acceptance requires exact `aes256-ctr` + `bcrypt`, one key, salt1..64 bytes, rounds1..64, exact KDF option fields, strict outer base64/framing and only CR/LF after end marker. Legacy encrypted PEM permits exact RSA/EC labels, `Proc-Type: 4,ENCRYPTED` and AES-128-CBC/AES-256-CBC with 32 hex IV characters. Encrypted PKCS8 is deliberately unsupported; unrestricted PBKDF parameters must not reach OpenSSL. Upstream alone allows KDF rounds up to 2^20 (`sshkey.c:76,3134`) and is not an acceptable application CPU budget.

The actual parser is `sshkey_parse_private_fileblob(sshbuf_from(bytes,len), passphrase, &key, NULL)` (`:1267-1269`). It never needs a filename, prompt, session ID or socket. It first parses OpenSSH and falls back to PEM on INVALID_FORMAT (`sshkey.c:3679-3698`). Do not call pathname-based authfile helpers, agents, SK providers, stock identity loading or user prompting.

The existing encrypted CSKB passphrase is1..8192 bytes, no NUL (`:1191-1208`), cloned into a NUL-terminated temporary buffer and wiped immediately after parsing. Whole-manifest inspection should take explicit bytes/length and preserve that bound; Java String conversion is unnecessary. The manifest's broader secret shape allows arbitrary nonempty bytes, so the native admission must still reject embedded NUL for passphrase use. Never log a passphrase, parse error text derived from content, content digest or private material.

The existing association is `SSH_KEY_PASSPHRASE.identity == "key:" + PRIVATE_KEY.identity` (`VaultManifest.java:213-219`, `AppSecrets.java:826-830`). The structural validator merely requires at least one such private file; it does not prove uniqueness of private identities or passphrase identities. The supplied manifest inspector needs an explicit ambiguity policy or exact caller-selected entry ID. Do not try arbitrary secrets in sequence or guess by filename. Absence of a stored passphrase cannot prove invalid encryption: a partial inspector should return `PASSPHRASE_REQUIRED`/not-fully-validated; a complete admission validator must freeze whether such keys are admitted for future prompting or rejected. Existing schema alone does not decide that policy.

Encrypted OpenSSH outer-public extraction (`sshkey_parse_pubkey_from_private_fileblob_type`, `sshkey.c:3265-3309,3721-3733`) works without a passphrase but merely skips cipher/KDF hints then parses the public field. It does not decrypt, validate checkints/padding, certify private/public agreement or validate all trailing content. Return an explicit unverified-envelope status if using it, never `privateKeyValidated=true`.

After actual private parsing, apply software policy and compare canonical public material through `sshkey_equal_public` with any **explicitly associated** expected public declaration. Manifest FileObject.identity is an application label, not itself a cryptographic fingerprint; no public/private association schema was found. Do not invent association from `.pub` suffix or identity strings without a frozen rule.

Public equality alone is insufficient for deliberately inconsistent serialized Ed25519 secret/public fields. Reuse the current sign + `sshkey_check_sigtype` + local `sshkey_verify` self-check (`ssh_native_client.c:1294-1315`) on a fixed domain-separated validation challenge, not an invented SSH authentication packet. Select explicit RSA SHA2 algorithm; NULL defaults to SHA1 upstream. A successful self-check proves this parsed key can sign for its public key, not ownership of a remote account. The challenge must carry no live session authority. Signing can require local entropy, so distinguish that permitted crypto effect from the no file/network/exec/environment policy.

Private outer framing needs a separately frozen strict rule even for unencrypted keys. Pinned `private2_uudecode` stops at an end marker and can ignore subsequent text; PEM parser is also not evidence of universally strict complete-file framing. Existing encrypted policy is stricter than raw upstream parsing. Avoid claiming all private input suffixes are rejected unless tested/implemented explicitly.

### Public keys and certificates

`sshkey_read` (`sshkey.c:1299-1371`) parses OpenSSH text type+base64, validates text algorithm/curve matches the decoded blob, and leaves the cursor at a comment. It is suitable for a bounded one-record public-key object after a separate file-framing policy decides blank/comment lines, CRLF and trailing additional records. It does not parse authorized_keys options; do not accept them as a bare public-key file. `sshkey_from_blob` is the binary wire parser and rejects trailing decoded bytes (`:1989-2089`), not a complete multi-line text-file validator. Use both in their actual domains, not interchangeably.

Certificate parsing verifies the embedded CA signature and structured critical/extensions encoding (`sshkey.c:1945-1988`), but that is not CA trust approval. `sshkey_cert_check_authority` accepts an explicit verification time/name and checks host/user type, validity and principals (`:2431-2487`); `_now` reads wall time. Context-free content inspection can report certificate syntax/type/validity metadata against an explicitly supplied time. It cannot establish a selected user's principal or trusted CA. Current client authentication rejects certificate identities and host issuer returns CERTIFICATE denial except revocation first; a first inspector should return a distinct unsupported certificate-policy status rather than call them connection-ready. Preserving/storing certificates and using them are different policy choices for the main contract.

### known_hosts: critical null-host gap and trust mapping

`hostkeys_foreach_file(path, FILE*, callback, ctx, NULL, NULL, HKF_WANT_PARSE_KEY,0)` can parse all lines without a fabricated host. The callback sees COMMENT/OK/INVALID, marker, parsed key, hosts text and source line (`hostfile.h`, `hostfile.c:790-959`). Do **not** set HKF_WANT_MATCH: it requires a real host and filters away unrelated invalid entries. Reject INVALID rather than inheriting upstream behavior that skips bad lines during effective lookup. Check `ferror` explicitly: the loop ends on getline==-1 and the function returns its callback/parser status (`:801,957-959`), not a complete stream-read success guarantee.

However, when host==NULL, **hashed-host validation is skipped entirely** (`hostfile.c:849-880`). `match_maybe_hashed`/`host_hash` only run inside that conditional. Existing issuer deliberately passes the actual host to catch malformed salts (`ssh_hostfile_issuer.c:282-288`). Passing a made-up hostname to replicate this is prohibited by the context-free requirement and still does not fully validate hash text: `extract_salt` validates the salt, while a bad encoded digest can simply fail equality. A null-host inspector needs a narrow supplemental check validating `|1|salt|digest` grammar, Base64 decoding both salt/digest to exactly20 bytes, and full token consumption. It can use the parser callback's existing hosts token and existing Base64 primitives without a new hostfile patch or calling host_hash/HMAC. This is a framing check around the shared parser, not a replacement known_hosts parser. It must not claim full plain-host pattern validation.

Focused log/fatal review supports reusing stock foreach with host/ip NULL and a fixed synthetic path label. Reachable foreach diagnostics (`hostfile.c:834,844,893,906,942`) contain fixed messages plus path/line, not raw hosts/body. Raw hash diagnostics (`:856,869`) and `host_hash` HMAC/Base64 fatal calls (`:140,145`) are in the host-matching path and are unreachable with both host/ip NULL. `sshkey.c` has no ordinary log/fatal calls; certificate signature verification may reach `ssh-ed25519.c:259`, which logs fixed text plus an integer failure code. Keep DEBUG_PK/DEBUG_PKOK undefined, since debug-only builds can dump key buffers. The inspected software parsing path has no observed attacker-input fatal beyond upstream allocation/OOM conventions; this is focused source evidence, not a claim about all possible crypto internals. Do not mutate the global log handler. A test log observer can assert bounded fixed safe output separately; do not report zero log effects if safe logs actually occur.

Plain hosts are comma-separated positive/negative pattern lists using `match_hostname`, not the Host-directive token vector. Do not treat literal catalog aliases as the full known_hosts keyspace, or rewrite bracketed `[host]:port`, wildcard, negation, multi-host or hashed selectors into guessed endpoints. Marker syntax is checked by `check_markers` (`:181-210`), accepting only one @revoked/@cert-authority marker. A valid @revoked entry is a legitimate trust record, not malformed content; preserve its meaning. A valid @cert-authority record describes a CA declaration but does not itself approve that CA or make current certificate authentication supported.

The current issuer loads entries matching a **real** host, then `check_key_in_hostkeys` classifies KNOWN/NEW/CHANGED/REVOKED and certificate policy. This requires an actual offered key and is not a manifest-level content check. Context-free inspection can emit bounded trust record metadata/counts/source/marker and parsed key fingerprints; it cannot decide CHANGED/NEW for a connection, resolve a hashed selector to a human hostname, or authorize a replacement.

Restore review additionally compares authenticated source and target and binds exact approval. The existing contract requires complete coverage and visible unknown mappings (`.trellis/spec/backend/app-secrets.md:216-239`). Hashed/wildcard/multi-host mapping must remain explicit/opaque rather than invented. Private/passphrase low-entropy digests must not be published; public key fingerprints are appropriate public metadata, unlike secret digest oracles.

### Proposed API, failure model and execution bounds

These are recommendations for freezing, not existing symbols:

```c
struct ctos_ssh_material_control {
    int (*check)(void *context); /* owner/cancel/deadline; no Java callback */
    void *context;
};
int ctos_ssh_material_public(control, bytes, length, expected_kind,
    result, diagnostic);
int ctos_ssh_material_private(control, bytes, length,
    passphrase, passphrase_length, optional_expected_public,
    result, diagnostic);
int ctos_ssh_material_known_hosts(control, bytes, length,
    result, diagnostic);
```

Use synchronous caller-scoped borrows of exact manifest arrays, private native scratch as required, and zero/wipe all temporary secret copies and sshkey/sshbuf objects on every exit. Results contain only bounded metadata, never private contents, passphrases, pointers or a generic content getter. Do not store native caller buffers after return. A later JNI manifest validator should clone/copy while the authenticated manifest owner holds it, recheck the owning job before/after crypto and publication, and never reinterpret public-view tokens as private access.

Use separate status domains: invalid API/lifecycle errno, located content diagnostic (object index/line, fixed reason), and explicit inspection completeness (e.g. ENCRYPTED_NEEDS_PASSPHRASE, CERTIFICATE_POLICY_UNSUPPORTED). Suggested content reasons: FORMAT, UNSUPPORTED_FORMAT/ALGORITHM, KDF_LIMIT, WRONG_PASSPHRASE_OR_CORRUPT, PUBLIC_MISMATCH, PRIVATE_SELF_CHECK, KNOWN_HOST_HASH, LIMIT. Upstream wrong-passphrase errors can overlap corruption, so avoid universally claiming a wrong user password. No partial valid result on malformed/limit failure.

Keep existing128 objects,1MiB per object,8MiB bodies; public wire blob at most64KiB to match actual auth preflight (`ssh_native_client.c:1239-1257`); passphrase8192. Freeze per-row known_hosts8192 and total rows16384 (or another explicit budget) before implementation; stock getline can otherwise allocate the full1MiB row. The control check must run between rows/objects and before/after parse/sign/publication. Cancellation cannot preempt a single synchronous bcrypt call with current upstream API; rounds<=64 bounds work but does not prove a hard deadline. Encrypted PEM/OpenSSL parse also has no cooperative mid-call cancel. Record this honestly; tests must verify post-call deadline/cancel prevents result publication.

Factor shared software/key encrypted-policy helpers into a focused module and use them from existing native authentication and the new inspector. Preserve the already validated auth/KEX/session proof sequencing, shared credential-attempt flag and packet construction. Regression must verify the helper extraction changes no existing accepted/rejected format or authority path. The inspector never sends a packet, creates a socket, requests a credential, issues a role, writes known_hosts or grants restore approval.

### Main-session freeze refinement

The main session selected native material inspection with direct Java supplied-manifest orchestration, no attach hook. For a bounded first implementation, native success should be named **PARSED**, with precise separate flags for expected-public comparison/self-check if those are performed. Raw private parsing is not proof of universal complete outer-file framing or mathematical seed/public consistency; without sign/verify the latter must remain unclaimed. This permits a useful parser prerequisite without expanding it into an authentication-equivalence guarantee.

For PUBLIC_KEY/CERTIFICATE, establish complete **physical record** consumption before calling sshkey_read: bounded body/rows, reject NUL, isolate exactly one nonempty/noncomment key record and reject another physical record under an explicit CRLF/blank/comment policy. The remainder after the parsed Base64 token is an OpenSSH comment. A same-line second-key-looking string is indistinguishable from a valid free-form comment under that grammar. Either allow comments and explicitly treat that suffix as comment, or choose/document a stricter no-comment policy; do not falsely claim sshkey_read detects arbitrary same-line additional keys. Binary blob trailing bytes are independently rejected upstream.

Use the stock null-host known_hosts foreach plus supplemental exact |1| salt/digest decode described above; no production hostfile patch is required for that frozen PARSED scope. Keep safe fixed parser logging distinguishable from host file/network/environment/exec effect traps. Extract only the exact encrypted-policy helper shared with auth as necessary, and rerun full authentication ARM regression after that extraction.

### Meaningful acceptance tests

- Real pinned parsing: plain OpenSSH Ed25519/ECDSA p256/p384/p521/RSA; actual supported unencrypted PEM combinations; canonical public output; algorithm mismatch, malformed base64/blob, extra records/suffixes, NUL/row/body bounds, cert/SK/EXT policy classification.
- Key relation: exact expected public succeeds; different public fails; mutated Ed25519 secret with unchanged serialized public fails local signature verification; RSA SHA2 self-check is explicit. Never use fingerprint-string equality instead of actual key comparison.
- Encrypted matrix: current aes256-ctr/bcrypt and AES128/256 traditional RSA/EC PEM; correct/wrong/absent passphrase; NUL and8192/8193 bytes; rounds0/1/64/65, salt0/1/64/65, wrong cipher/KDF, trailing KDF fields, nkeys!=1, huge rounds and encrypted PKCS8 rejected **before** expensive derivation; exact framing/suffix behavior. A read-only callback/counter test should prove preflight rejection avoids parser/decrypt invocation.
- known_hosts: empty/comments, CRLF, all supported software key types, unknown/multiple markers, valid revoked/CA records; malformed key on an unrelated/nonmatching row must fail; valid and malformed `|1|` hashes including salt, digest length/encoding, extra delimiters/trailing data; plain comma/negation/wildcard/bracket selectors preserved. No fabricated host passed to parser. Inject read failure and bound long lines before stock getline allocation.
- Candidate binding: two manifests with same ids but different bodies yield different checks; actual supplied candidate wins, current public lease is never consulted; private metadata placeholder cannot count as private-key parse; duplicate identity/passphrase associations explicitly classified rather than trial-decrypted.
- Lifecycle: owner/PID/UID as frozen, cancel/expiry before/after parse and at final publication, failure cleanup and secret wiping, no partial result after invalid input; no host file, env/NSS/DNS/exec/socket/credential callback/trust write effects. Permit only documented local crypto/entropy effects for self-check.
- Full regression after refactor: existing private/auth native synthetic suite, hostfile issuer classification, ordinary evaluator3736 and handle5201 plus catalog71923 **rerun counts from new binaries**, fresh JVM/strict/lint/normal/fixture/APK checks as main determines. Prior counts are historical expectations, not tests run by this research.
- Retained gates: no production attachSshValidator solely for material/catalog success; mixed SSH mutation/import still default deny; exact restore reviewer/approval remains independently required; pure-TOTP behavior unchanged.

### Related specs, contracts and external references

- `design/ssh-config-catalog.md`: declarationsOnly, static Include and located deferred sites; semantic validator absent.
- `design/ssh-config-evaluator.md`, `design/ssh-config-managed.md`: complete raw Options, actual Request, borrow ownership and final live guards.
- `design/ssh-launcher.md`: production sequence and no fake policy/connector shortcuts.
- `.trellis/spec/backend/ssh-native.md`: current catalog/evaluator/auth/private-key/trust contracts and fixed production closure.
- `.trellis/spec/backend/app-secrets.md:204-239`: whole resulting manifest validation, separate exact bundle/restore approval, protected credential projection.
- Prior research `catalog-native-20261019.md`, `catalog-vault-20261019.md`, `private-key-userauth-20261019.md`: context, not current runtime evidence; source takes precedence where historical APIs were incomplete.
- Versions used: local pinned OpenSSH10.6p1 and existing production OpenSSL3.5.9 closure. No external sources fetched, version changes proposed or dependency updates performed.

## Caveats / Not Found

- No production whole-manifest semantic validator, independent private material inspector, parsed reference-to-manifest edge checker, root registry or complete final connection plan was found.
- No complete context-free known_hosts hashed-token validator is currently exposed. Existing null-host parser call alone is incomplete; a supplemental exact salt/digest framing check is sufficient for the frozen PARSED scope without a new hostfile patch.
- No canonical public/private association rule exists in VaultManifest beyond application identity labels and key-passphrase association. Freeze it rather than inferring `.pub` paths.
- No universal context-free validation claim is defensible for arbitrary Match/host/user/Include expansions. Material inspection is an incremental prerequisite, not full admission.
- No code, tests, build or device execution was performed. Only this report was written; concurrent agents' work was preserved. The role-isolated protocol forbids loading implement.jsonl/check.jsonl; task/design/spec/source were read directly because no research-only manifest was supplied.
