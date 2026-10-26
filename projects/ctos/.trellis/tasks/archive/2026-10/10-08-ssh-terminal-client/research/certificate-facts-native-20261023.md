# Research: supplied OpenSSH certificate facts before trust admission

- Query: Identify the smallest reusable certificate-semantic increment over actual supplied CERTIFICATE bytes without fabricating a host, user, Request or trusted CA.
- Scope: internal; read-only inspection of current project code and locally pinned upstream sources. Only this research file was written. No build, binary, network, device, account or git operation.
- Date: 2026-10-23
- Active task: `.trellis/tasks/10-08-ssh-terminal-client`. No saved-hook notice or complete hook marker was present; used the supplied active-task path. Read workflow, PRD/design/implement top and relevant backend specs. Research-role instructions exclude implement/check JSONL loading.

## Findings

### Files found

| File | Purpose |
| --- | --- |
| `android/app/src/main/cpp/ssh_material_inspect.c` | Shared owned-key public/private parsing, text and lifecycle bounds, CSMIv1 and explicit pair proof. |
| `android/app/src/main/cpp/ssh_material_inspect.h` | Current 1MiB/65536-byte-line/16384-line limits and synchronous borrowed APIs. |
| `android/app/src/main/cpp/ssh_material_jni.c` | Bounded JNI scratch, pending-exception preservation, output and deadline fences. |
| `android/app/src/main/cpp/ssh_key_policy.c` | Existing software Ed25519/ECDSA/RSA policy; explicitly rejects cert/SK/external keys. |
| `android/app/src/main/cpp/ssh_hostfile_issuer.c` | Existing production certificate denial after upstream revocation classification. |
| `android/app/src/main/java/im/majo/ctos/SshManifestKeyAssociationInspector.java` | Exact supplied IDs, selected-copy ownership, original deadline and immutable partial-only reports. |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java` | Actual typed files; at most128 files,1MiB each,8MiB total. |
| `android/app/src/test/cpp/ssh_material_inspect_test.c` | Real software-key/certificate generation, single-record and fixed-effect observers. |
| `android/app/src/test/cpp/ssh_key_association_test.c` | Explicit fixed-challenge proof tests; expired host certificate demonstrates pair/trust separation. |
| `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/sshkey.[ch]` | Pinned certificate parser, data fields, embedded signature verification and contextual checks. |
| Same pinned directory, `sshbuf-getput-basic.c`, `sshbuf.h`, `auth-options.c` | CString normalization, wire bignum bound and server-side critical-option policy. |

### What parsing already proves, and what it does not

1. Project `ssh_material_inspect.c:253` already has a shared `parse_public(type, body, length, result, key_out)` helper returning one owned `sshkey`. It permits blank/comment lines and one physical public/cert record with standard trailing comments; a second record rejects. Its declared CERTIFICATE check is at `:278`. A new operation in the same module can reuse this helper and `text_bounds`, rather than adding a second armor/base64/certificate parser. CSMIv1 and CSKAv1 behavior need not change.
2. Pinned `sshkey.c:1854` `cert_parse` reads serial, certificate type, key ID, principals, validity, critical/extension buffers, reserved bytes, CA public key and signature. It accepts only USER1/HOST2 at `:1914`; other type values return `SSH_ERR_KEY_CERT_UNKNOWN_TYPE`.
3. Principal count is limited to256 (`sshkey.h:96`, `sshkey.c:1925`). Each is decoded through `sshbuf_get_cstring`, not validated as a DNS name, account name, UTF-8 string or meaningful principal for this app. Empty or duplicate principal values are not independently rejected there. An empty whole principal list is structurally parseable.
4. Critical options and extensions are only checked as alternating SSH strings (`sshkey.c:1957`). The parser neither recognizes their names/payload semantics nor proves ordering, uniqueness, supported purpose or safe applicability. Reserved data is consumed without a content restriction (`:1877`). Do not turn this into a claim of complete certificate canonicality.
5. Parsing then verifies the signature over the retained certificate blob using the **embedded** CA public key (`sshkey.c:1976`). That proves consistency with the embedded signer, not that this signer is an approved CA. `sshkey_type_is_valid_ca` at `:399` accepts every compiled noncertificate key type. Embedded nested CA certificates reject because `allow_cert=0` at `:1904`/`:2027`.
6. `sshkey_read` calls the ordinary `sshkey_fromb` route (`sshkey.c:1299`, `:2093`), which supplies no subject or CA signature-algorithm allowlists. Consequently old material PARSED does not prove a modern CA-signature policy. If the build supports it, `ssh-rsa` signing can be structurally verified even though new app authentication explicitly chooses RSA SHA-2. Pinned `sshkey_check_cert_sigtype` at `:2152` is a reusable policy check; adding an allowlist is a separate new-operation contract, not an implicit meaning of parser success.
7. `sshkey_from_blob_internal` rejects any leftover decoded blob bytes (`sshkey.c:2063`). This complements the project's one-physical-record boundary. It does not make comment text part of the certificate or require a canonical base64/text representation.

### Contextual primitives are not whole-store certificate admission

`sshkey_cert_check_authority(k, want_host, wildcard_pattern, verify_time, name, reason)` at pinned `sshkey.c:2431` is usable **after the caller supplies real semantics**:

- It checks actual USER versus HOST purpose, `[valid_after, valid_before)` against an explicit unsigned Unix-time value, and requires at least one principal.
- `name=NULL` skips only principal matching (`:2466`); type/time/nonempty-list checks still happen. Passing NULL is therefore not a universal context-free validity check and does not establish principal applicability.
- `wildcard_pattern=0` uses exact `strcmp`;1 uses `match_pattern` (`:2471`). The caller must freeze whether user/host patterns are intended for the real use, not synthesize a name to make the check pass.
- Its name suggests authority, but it never compares the embedded CA against an approved CA, known_hosts rule, trust store or reviewer decision. Success is not CA authority.
- It does not process critical options or revocation.

`sshkey_cert_check_authority_now` at `:2490` reads `time(NULL)`, and `sshkey_cert_check_host` at `:2506` uses that current time, host wildcard matching, rejects any nonempty critical options, and optionally applies CA-signature algorithms. Neither establishes trusted CA selection. They should not be called with fake host/user values during detached whole-manifest inspection. BOOTTIME job lifetime is a different clock domain from the certificate's Unix validity timestamps.

Production is deliberately still closed: `ssh_hostfile_issuer.c:319` first asks upstream hostkeys classification, then at `:320` maps every offered certificate except HOST_REVOKED to the explicit CERTIFICATE denial. A facts operation must not silently remove this gate or let PARSED/subject MATCH approve KEX, credentials or restore.

### Algorithms, fingerprints and representations

- For the subject, copy public fields and demote with `sshkey_from_private`/`sshkey_drop_cert` (pinned `sshkey.c:1598`, `:2288`), then apply existing software policy. Do not pass a cert directly to `ctos_ssh_key_software_identity` (`ssh_key_policy.c:55` explicitly rejects it).
- The embedded CA is already plain; apply a separately frozen reporting policy to it. Existing software policy is a conservative reusable subset (Ed25519, EC256/384/521, RSA at pinned minimum1024). This would constrain **new facts success**, not change old CSMI/CSKA/auth acceptance.
- Public wire RSA mpints are capped at16384/8 bytes (`sshbuf.h:41`). There are no private factors/CRT values in these inputs. Embedded CA signature verification occurs inside parsing before any app postparse key policy; do not claim that a later check gates all crypto cost.
- Keep three distinct SHA256 values if exported: whole certificate blob, canonical plain subject blob, embedded CA public blob. The current CSMI key digest uses the whole certificate (`ssh_material_inspect.c:63`); current CSKA deliberately uses the demoted subject (`:451`). They are different identities and cannot be interchanged for CA selection or reviewer binding.
- Raw certificate timestamps and serial are unsigned64. Preserve all bits, including `0xffffffffffffffff`; do not narrow them to positive signed Java long, milliseconds or Date. Fixed-width lowercase hex strings in immutable Java metadata are one simple exact representation; a raw long is acceptable only if every consumer uses explicit unsigned comparisons/formatting.
- Critical encoding caveat: `sshbuf_get_cstring` at `sshbuf-getput-basic.c:281` permits one trailing NUL. Principal arrays retain C strings but not their original wire lengths. `strlen` therefore loses that trailing-NUL distinction. Fields also need not be valid UTF-8. A facts API should not claim raw UTF-8/canonical text validation based on these C strings. Counts/lengths and digest-only metadata avoid this issue; exposing text requires an explicit parser-effective-versus-raw encoding contract and independently strict transport. Do not decode invalid UTF-8 using replacement characters and then treat the replacement as the signed principal.

### Smallest feasible implemented increment

Recommended native operation: **bounded detached certificate facts**, not a trusted-certificate predicate. It supplies missing reviewer/semantic-validation inputs while requiring no new persisted format or fake Request.

Suggested C shape, subject to the main session's final contract:

```c
int ctos_ssh_material_inspect_certificate(
    const unsigned char *body, size_t length, uint64_t original_deadline,
    unsigned char output[168]);
```

Implement in existing `ssh_material_inspect.c` and reuse owned-key `parse_public(CERTIFICATE, ...)`; no registry, OS mapping, private input, passphrase, signing callback or caller payload. The only cryptographic verification is the pinned certificate parser's embedded-signature check. Free every local owned key and temporary public blob. Return0 for a material outcome; invalid API/bounds/deadline/system error is -1/errno with output zeroed.

A deliberately small, fixed facts-only wire could be168 bytes:

- Twelve big-endian u32 fields (48bytes): new magic/version/status/factsOnlyFlags/certificateType/subjectKind/caKind/signatureAlgorithm/principalCount/criticalByteLength/extensionByteLength/sourceLine.
- Three big-endian unsigned64 fields (24bytes): serial/validAfter/validBefore.
- Three32-byte public digests (96bytes): certificate/plainSubject/embeddedCA.

Freeze status and closed signature-algorithm enums before code. For example FACTS0/MALFORMED1/UNSUPPORTED2, mandatory factsOnly1, no trust flag, SUCCESS fields only for FACTS and zero fields/digests for failure except fixed source line. A signature algorithm enum can explicitly represent `ssh-rsa` as legacy metadata without approving it, or new-op policy can reject it as UNSUPPORTED; **choose one explicitly**. Exact numeric layout above is a proposal, not a frozen implementation contract.

This minimum intentionally returns no key ID, raw principal or option payload strings. Principal count and critical/extensions lengths are exact parser facts; an empty list, expired time interval or nonempty critical section must not be relabeled trusted/usable. A later contextual native check should accept actual target/purpose/approved-CA policy, or a richer facts wire can be added only after its real consumers require it. Avoid inventing a parallel general certificate parser solely to recover text the current struct normalized.

Suggested Java orchestration scans **every actual CERTIFICATE file** in the supplied exclusive VaultManifest, zero certificates succeeds without JNI, copies only those public bodies and retains IDs/original ordinals. Reuse one original<=60s BOOTTIME deadline and atomic cancellation through copying, each call, decoding, cleanup and final publication; no per-file budget renewal. At most128 certificates/1MiB perbody/8MiB manifest input; fixed output bounds results to21504 bytes. All-CERT coverage is a real structural coverage claim, but not whole-store trust coverage. Report `allCertificateFilesInspected=true`, `factsOnly=true`, `admissionComplete=false`, `trustChecked=false`, `principalApplicabilityChecked=false`, `revocationChecked=false`; no production provider/reviewer or restore opening.

If the main priority is closing complete-manifest admission, authoritative relationship and credential-target model work is still required; this facts increment does **not** solve that model. It is useful specifically as a small reusable input boundary for the eventual trust/reviewer and cert semantics layer.

### Errors, lifetime and validation proposal

- Preserve `SSH_ERR_ALLOC_FAIL`→ENOMEM and SYSTEM/LIBCRYPTO→EIO when those errors reach project code (`ssh_material_inspect.c:48`). Certificate malformed/signature/type/unknown-key categories remain material outcomes, never FACTS.
- Important upstream limitation: `cert_parse` normalizes some getter errors to INVALID_FORMAT (`sshkey.c:1868..1880`) and embedded-CA parse errors to INVALID_SIGN_KEY (`:1904..1907`). Some underlying allocation errors are therefore already masked before the wrapper receives them. Do not claim every deep parser OOM is distinguishable without a separate reviewed pinned-parser change; existing project mapping cannot recover lost error classes.
- Parsing/embedded verification is synchronous and nonpreemptible. Check original deadline before/after parser, public hashing, cleanup and final output/JNI publication. Atomic Java cancellation prevents later work and result publication, not the already-running upstream verify.
- JNI can follow existing bounded heap/public-input scratch and pending-exception discipline (`ssh_material_jni.c:14`, new pair cleanup at `:102`). New entry on a new class adds one JNI export; old15config/19session/1material/1pair and wires unchanged. Do not call this lock-all-parser-memory evidence.
- Native real vectors: five subject and five CA software kinds; USER/HOST type; valid/future/expired/empty/inverted intervals as accurately retained facts; serial/time0/high-bit/MAX; principals0/256/257, empty/duplicate/trailing-NUL/nonUTF8; nonempty/unknown/duplicate critical/extensions framing; corrupted cert/CA/signature, nested CA cert, decoded suffix, second physical record, PUBLIC input type mismatch; RSA SHA256/SHA512 and explicit SHA1-reporting policy; each fingerprint compared with independently serialized source key; row/NUL/size limits; real deadline fences and system-error seams; no file/env/DNS/network/command effects; FD cleanup.
- JVM vectors: actual source/candidate and all-CERT coverage; no unrelated private/credential copies; exact original ordinals; strict168-byte wire/enums/type/counts/flags/u64 preservation/zero-failure-fields/trailing rejection; independent owned input/output cleanup; zero-CERT no JNI; cancellation/Error/owner/reentry/final wipe fence; immutable metadata and all partial-only booleans. New opt-in platform fixture is compile-only until separately authorized.
- Regression: old material7784, pair and auth/PTY source boundaries, config behavior, strict/lint/JVM/normal+fixture/APK isolation and exact JNI counts. Builds/execution were not performed by this research.

### Related specs and primary references

Relevant project specs: `.trellis/spec/backend/ssh-native.md` (actual material facts and certificate denial), `ssh-key-association.md` (plain subject and non-trust proof), `error-handling.md`, `quality-guidelines.md`; relevant design contracts: `design/ssh-manifest-inspection.md`, `design/ssh-key-association.md`, `design/ssh-config-resources.md`, `design/ssh-validation-lifecycle.md`.

External references: none fetched. Evidence uses locally pinned OpenSSH10.6p1 source files above and existing project policy. The source tree's PROTOCOL points to the certificate draft, but `PROTOCOL.certkeys` is absent in this pinned tree; no network/draft content was consulted and no external normative claims are asserted.

## Caveats / Not Found

- No approved CA authority source, global association coverage model, password-account/username semantics or complete reviewer was found in this native path. Facts are not evidence that an imported CA is approved for any host or user.
- Critical-option policy for USER certificates is server-side/contextual in pinned `auth-options.c`; importing that whole module would add unrelated server policy and is not this increment. HOST's blanket critical-option rejection also needs actual HOST use, not generic file existence.
- Existing production offered-host certificates remain deliberately denied after revocation classification. A facts-only module must not weaken that behavior.
- Exact limits, status/wire constants, legacy CA-signature policy and Java API proposed here require a frozen main-session contract before implementation. No source edit, spec change or runtime result is claimed.
