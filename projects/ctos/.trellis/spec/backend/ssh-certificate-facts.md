# Supplied certificate facts and embedded signature policy

## 1. Scope / Trigger

Use [the contract](../../../design/ssh-certificate-facts.md) to inspect every supplied
CERTIFICATE without a live vault/view or fabricated host/user/purpose/approved CA/time.
Embedded-signature consistency and algorithm policy are facts, not trusted CA, current
validity, principal applicability, revocation or complete admission. Preserve v1, old
material/pair/private APIs and production certificate/provider/restore/connector gates.

## 2. Signatures

```java
new SshManifestCertificateInspector().inspect(manifest, originalDeadline, cancellation);
static native byte[] inspectCertificate(byte[] body, long originalDeadline);
```

```c
ctos_ssh_material_inspect_certificate(body, length, deadline, output168);
```

One new certificate JNI; old config15/session19/material1/pair1/private1 unchanged. Native
borrowed slices are synchronous, with no registry/handle/mapping/signing callback.

## 3. Contracts

Reuse text_bounds and parse_public(CERTIFICATE), not a second cert/parser or custom crypto.
Upstream cert_parse verifies embedded CA signature before app policy. Copy/demote subject;
subject and embedded CA must meet shared software policy and pair RSA component16384bit/
no-multiprime bounds. CA signature allowlist exactly ssh-ed25519, ecdsa-sha2-nistp256/384/521,
rsa-sha2-256/512; call sshkey_check_cert_sigtype and map actual signature_type. New operation
rejects parsed legacy ssh-rsa/SHA1 as UNSUPPORTED; old operations keep their policies.

Preserve actual USER1/HOST2, serial/valid_after/valid_before, principals count and raw
critical/extensions section byte lengths. Hash three distinct roles with separate results:
retained whole certblob, canonical plain subject, canonical embedded CA public serialization.
Hashes need not be unequal (subject and CA can be the same key). Snapshot parsed physical
record line before key_result resets result.line. No keyID/principal/option strings returned.
Empty/duplicate/nonUTF8/trailing-NUL-normalized principals and unknown options/time intervals
remain parsed facts, not raw canonicality/semantics claims; do not read wall clock or call
contextual cert_check_authority with a fake/null target to imply generic trust validation.

CSCFv1 exact168bytes: u32 magic0x43534346/version1/status/flags/type/subjectKind/caKind/
signatureAlgorithm/principalCount/criticalBytes/extensionBytes/sourceLine (offset0..44),
u64 serial48/after56/before64, SHA256 cert72/subject104/CA136. FACTS0/MALFORMED1/UNSUPPORTED2;
flags precisely7 for FACTS,1 otherwise. FACTS type1|2, kinds1..5, sigEnum1Ed/2EC256/3EC384/
4EC521/5RSA256/6RSA512; CA kinds1..4 require same enum,5 requires5|6. Principals0..256,
section bytes0..1MiB, physical line1..16384, each digest nonzero. All failure facts/u64/digests
zero except sourceLine0..16384. Unsigned u64 values retain every bit in Java serialBits/
validAfterBits/validBeforeBits, including high-bit/MAX; never signed-reject/compare time.
Strict decoder rejects any other length/header/enum/combination/trailing bytes or protocol.

Creator-thread, oneattempt, cross-instance ThreadLocal, original<=60s BOOTTIME through
preflight/copy/parse/hash/cleanup/output/JNI write/Java final wipe fence; cancellation atomic,
not preemptive parser/crypto and no extra worker. All128file/1024entry counts and1MiBbody/
8MiBmanifest totals preflight before copy. Only CERT bodies copied, once each, with cleanup
slots established first; no private/phrase/password/TOTP/unrelated bodies. Output<=21504bytes.
Zero certificates no JNI but same final fence. No close/erase of caller's exclusive loan.

Immutable entries preserve actual file ID/path/identity/ordinal/status/sourceLine and nullable
Facts. Facts contain only numeric/public data above. Report allCertificateFilesInspected=
factsOnly=true; admissionComplete/trustChecked/timeValidityChecked/principalApplicabilityChecked/
revocationChecked=false. allCertificateFactsAvailable quantifies allCERT statusFACTS, empty true.

## 4. Validation & Error Matrix

Malformed material or parsed-but-policy-unsupported cert is a per-file result, not FACTS;
input bounds/API/lifetime/wire/JNI failures deny whole job, no prefix. ALLOC→ENOMEM and
SYSTEM/LIBCRYPTO→EIO/-1/output0 when these errors reach wrapper; upstream masks some deep
getter/CA errors as format errors, which cannot be reconstructed here. Postparse policy
does not gate earlier embedded verification costs. Preserve pending JNI exceptions, bounded
heap scratch and wipes; no claim all parser/OpenSSL heaps are locked. Every normal/IOException/
Linkage/Runtime/Error/cancel/expiry exit wipes exclusive bodies/wire and fences publication.

## 5. Good / Base / Bad Cases

Good: valid modern embedded signature yields exact three public digests and numeric facts.
Base: expired host certificate with uint64MAX serial remains FACTS and timeValidityChecked=false.
Bad: forged signature→MALFORMED, parsed RSA SHA1→UNSUPPORTED, zeroed success digest or facts on
failure→whole-job wire rejection. Neither valid embedded CA nor facts-only success approves KEX.

## 6. Tests Required

Strict testSshCertificateFactsHost/StaticHost and separate ARM runtime cover real subject/CA
kinds, type, u64 bits, sourceLine, independent digests, SHA1 rejection/SHA2 success, retained
time/principal/options facts, malformed input/signature/framing/bounds, system/deadline/output
cleanup/effects0. Preserve old material/pair/private matrices; generated PEM truncation-case
CHECK counts can vary, so use actual logs/source hashes/exit codes. JVM independent CSCF vectors,
allCERT supplied input/copy/ownership, no unrelated secrets, immutable limited booleans,
unsigned extremes, lifecycle/Error/reentry/final wipe and budgets. Platform fixture uses a
public synthetic cert and exact opt-in; compilation is not Android runtime. Normal/fixture
lint/packaging/signature/exports/library sections need actual [results](../../../design/verification.md).

## 7. Wrong vs Correct

Wrong: equate embedded signature with approved CA, use signed comparisons for SSH u64, hash
the whole cert as its subject, discard physical sourceLine during hashing, or call contextual
authority checks with invented input. Correct: retain exact typed public facts, explicit
modern signature policy and all missing trust/time/principal/revocation obligations.
