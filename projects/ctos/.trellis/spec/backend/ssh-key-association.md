# Explicit supplied SSH key associations

## 1. Scope / Trigger

Use [the contract](../../../design/ssh-key-association.md) when proving a caller-selected
private/public or private/certificate-subject pair from an actual exclusive VaultManifest
loan. This is a fixed local signing proof, not whole-manifest association discovery,
certificate trust, password-target validation or semantic admission. No provider/reviewer
registration, production restore or connector opening follows from MATCH.

## 2. Signatures

```java
new SshManifestKeyAssociationInspector().inspect(manifest,
    pairs, originalDeadline, cancellation);
new Association(privateFileId, peerFileId, passphraseEntryIdOrNull);
```

```c
ctos_ssh_material_verify_pair(peer_type, private_body, private_length,
    phrase, phrase_length, peer_body, peer_length, original_deadline, output60);
```

New JNI `SshManifestKeyAssociationInspector.Native.verify` adds one export; old material1,
config15/session19 and CSMIv1 remain unchanged. Native slices are synchronously borrowed,
with no registry, mapping, handle or live auth capability.

## 3. Contracts

Creator-thread, one attempted job; ThreadLocal rejects same/cross-instance reentry.
Snapshot 1..128 immutable exact-ID tuples; reject duplicates. Resolve PRIVATE then PUBLIC
or CERTIFICATE in the supplied manifest, independently of labels/paths/suffixes. A selected
phrase must be the exact SSH_KEY_PASSPHRASE entry and its identity must equal `key:` plus
selected private identity. Other same-identity entries do not make this selection ambiguous.
Omitted phrase stays null; never guess, copy unrelated secrets or retry alternate phrases.

Establish all cleanup slots before sensitive copies; clone each selected file/phrase once.
Per-body<=1MiB, all manifest files<=8MiB, phrase1..8192/noNUL; sum private+peer bytes for
every tuple including repeats<=64MiB. No closure of caller loan or erasure of its arrays.
Reuse one <=60s original BOOTTIME deadline through preflight/copy/native/result/cleanup and
final publication. Atomic cancellation returns promptly, but a synchronous KDF/crypto call
is not preemptible; do not start extra workers to conceal this boundary.

Refactor shared owned-key private/public parsers in ssh_material_inspect.c, preserving old
CSMI results. Strict pair rejects traditional encrypted PEM before parser/KDF; clear key
with a nonempty supplied phrase is UNSUPPORTED, not MATCH with an ignored credential.
Supported encrypted OpenSSH retains shared aes256-ctr/bcrypt/salt1..64/rounds1..64 policy.
Plain PEM retains the existing complete top-level DER SEQUENCE check, not a new proof of
arbitrary DER canonicality. Existing material/auth format compatibility is unchanged.

Demote a copied certificate subject before shared software-key policy/digest/equality.
New pair only: all present RSA n/e/d/p/q/dmp1/dmq1/iqmp<=16384bits and no multiprime.
This check occurs after parser return; PEM RSA_blinding_on and other existing parser work
remain nonpreemptible. Do not tighten shared auth/key policy as a side effect.
Equal public fields are necessary; then sign fixed `ctOS SSH key association proof v1`
domain (including its NUL) plus canonical plain subject SHA256. RSA uses rsa-sha2-256;
other software keys use their plain SSH name. Bound signature to16KiB, check sigtype and
verify using the peer plain subject. Never accept caller signing data or return signature.
Embedded certificate signature parsing does not establish trusted CA/time/principal/use.

CSKAv1 exactly60 bytes: big-endian u32 magic0x43534b41/version1/status/kind/flags/stage/peerType,
then32-byte plain subject SHA256. Status0 MATCH,1 MISMATCH,2 MALFORMED,3 UNSUPPORTED,4 NEEDS,
5 BAD. Stage0 COMPLETE,1 PRIVATE,2 PEER,3 RELATION,4 SIGNER. Only MATCH/0, MISMATCH/3|4,
MALFORMED|UNSUPPORTED/1|2 and NEEDS|BAD/1 are valid. Allowed flags ENCRYPTED1/PAIR_ONLY4/
PUBLIC_EQUAL8/SIGNER_VERIFIED16;4 required. Only MATCH carries both8+16, kind1..5 and nonzero
digest. All failure kind/digest/proofbits zero; NEEDS/BAD encrypted. PeerType3|4 must match
actual selected file. Java independently enforces all closure and immutable metadata.
Report explicitPairsOnly=true/admissionComplete=false/trustChecked=false/
credentialTargetsChecked=false. allRequestedPairsMatch quantifies only this nonempty list.

## 4. Validation & Error Matrix

Wrong ID/type/phrase metadata, bounds, duplicate tuples, wire/lifetime/owner faults reject
the whole job with fixed IOException and no prefix report. Ordinary material results remain
per-entry; public inequality is MISMATCH/RELATION and failed signing proof MISMATCH/SIGNER.
SSH_ERR_ALLOC_FAIL maps ENOMEM; SYSTEM/LIBCRYPTO map EIO and whole-job failure, never material
mismatch. All native output is zero on system/lifetime failure. Preserve pending JNI
exceptions. All owned keys/buffers/signature/phrase/wire copies clean up on Error as well
as normal exits. JNI bounded heap scratch is not a claim that all OpenSSL heaps are locked.

## 5. Good / Base / Bad Cases

Good: exact selected phrase decrypts an OpenSSH private key, plain peer subject matches and
real sign+verify yields MATCH. Base: omitted phrase on encrypted input yields NEEDS with
no proof fields. Bad: identical serialized public fields with a different Ed25519 seed
yields MISMATCH/SIGNER, not MATCH; caller arrays survive and all owned copies are wiped.

## 6. Tests

Use testSshKeyAssociationHost/StaticHost for strict NDK builds; execute the static ARM
binary under QEMU separately. New C matrix includes the old material baseline, actual
five software kinds, cert subject, inconsistent Ed25519 seed, RSA component/multiprime
limits, pre-KDF refusals, system/signature faults and deadline/output cleanup. Run original
material standalone to preserve its independent7784-check evidence. JVM injected binding
proves exact IDs, ownership, wire closure, lifecycle and64MiB/128pair budgets, not crypto.
Platform fixture is opt-in and separately scoped; compilation is not Android runtime.
Normal/fixture lint, package isolation, signer, JNI exports and shared library bytes also
need verification. Actual results belong in [verification](../../../design/verification.md).

## 7. Wrong vs Correct

Wrong: `if (sshkey_equal_public(private, peer)) return MATCH;` accepts a serialized public
field without proving usable secret material. Correct: require public equality, fixed
domain sign, checked signature type and successful verify with the demoted peer subject.
Whole-certificate digest equality, label/suffix pairing, automatic phrase selection,
caller payload signing and promoting MATCH to trust are also forbidden.

Check actual supplied loan, exact phrase IDs, omitted phrase, cleanup ownership before
copy, repeated-work budget, plain subject, proof failure/system separation, RSA components,
old CSMI compatibility, original deadline and zero failure fields. Keep association
authority/coverage, password account/target, full format validity, certificate trust and
final Request/provider/restore/connector obligations explicit.
