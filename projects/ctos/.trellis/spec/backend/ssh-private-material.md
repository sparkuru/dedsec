# All-private material and v1 credential ownership

## 1. Scope / Trigger

Use [the contract](../../../design/ssh-private-material-ownership.md) to visit every supplied
PRIVATE_KEY and every nonTOTP entry without a live vault/view/role. This complements explicit
pair proof; it does not supply a persisted private-peer graph, password account model,
certificate trust or complete admission. Keep v1, providers and restore/connector unchanged.

## 2. Signatures

```java
new SshManifestPrivateMaterialInspector().inspect(manifest, originalDeadline, cancellation);
static native byte[] verifyPrivate(byte[] body, byte[] phrase, long originalDeadline);
```

```c
ctos_ssh_material_verify_private(body, length, phrase, phrase_length, deadline, output60);
```

One new private-material JNI; original config15/session19/material1/pair1 remain. No handle,
registry, mapping, socket or caller signing payload. Body slices borrowed synchronously.

## 3. Contracts

Interpret existing v1 phrase `key:`+private.identity exactly. A phrase with zero private
candidates is ORPHAN, multiple private candidates AMBIGUOUS_PRIVATE; one private candidate
but multiple phrases AMBIGUOUS_PASSPHRASE; only both unique OWNER_RESOLVED. Invalid typed
namespace INVALID_NAMESPACE. Preserve unique private locator even for duplicate phrases;
multiple/zero owner, invalid namespace and password have null/-1 locator. Never compare
secret bytes to resolve duplicates or select the first phrase. Legal v1 already rejects
orphans/invalid phrase namespace; retain its structure checks.

Each private with no phrase is verified with null, including repeated identities with zero
phrases. A unique private+phrase selects that exact entry; ambiguous private ownership yields
OWNER_AMBIGUOUS, null phrase/-1 and null verification, no copying/JNI for that private.
Password target: namespace is TARGET_DEFERRED, other namespace INVALID_NAMESPACE. Suffix
stays opaque; never parse usernames from examples, clone passwords/TOTP or unrelated bodies.

Reuse shared strict owned-private parser, software policy, all RSA component16384bit/no
multiprime policy, digest and signing helper. Clone public fields from parsed private as
plain subject; fixed domain `ctOS SSH private material proof v1` including NUL plus canonical
plain subject SHA256 is signed and verified with that subject. RSA rsa-sha2-256, sig<=16KiB,
checked sigtype, no signature returned. No fabricated external peer or public-pair guarantee.
Legacy encrypted PEM refused before parser/KDF; clear private+phrase unsupported, bounded
OpenSSH encryption unchanged. Existing parser nonpreemptibility/cost precedes RSA bounds.

CSKPv1 exact60 bytes: u32 magic0x43534b50/version1/status/kind/flags/stage/materialType2 then
32-byte public subject SHA256. Status MATCH0/MISMATCH1/MALFORMED2/UNSUPPORTED3/NEEDS4/BAD5;
only MATCH/COMPLETE0, MISMATCH/SIGNER4 and other/PRIVATE1. Allowed flags ENCRYPTED1/
PRIVATE_ONLY4/DERIVED_SUBJECT8/SIGNER_VERIFIED16;4 required,8+16 together only on MATCH.
Only MATCH has kind1..5/nonzero digest; failure kind/proofbits/digest0, NEEDS/BAD encrypted.
Java rejects other lengths/magic/version/type/enum/combination. CSKA/CSMI not reinterpreted.

Creator-thread, oneattempt, cross-instance ThreadLocal guard, same<=60s BOOTTIME deadline
through preflight/copy/per-key/native/cleanup/final publication, atomic cancellation. Up to
128files/1024entries, body1MiB/allmanifest8MiB, selectedphrase1..8192/noNUL. Establish cleanup
slots before copies, each private/selectedphrase copied once, private bytes processed<=8MiB.
No closing/erasing caller loan. Native/JNI bounded heap scratch is not all-heap locked memory.

Immutable private and nonTOTP lists preserve actual ordinals. Report allPrivateDeclarationsVisited
and allNonTotpCredentialsVisited=true even if visited material/owner fails; allPrivateMaterialsVerified
only every nonambiguous private MATCH, allPassphraseOwnersResolved only every phrase OWNER_RESOLVED
(both allow empty set). Always admissionComplete/keyAssociationsChecked/passwordTargetsChecked/
trustChecked=false; TARGET_DEFERRED must remain visible, not swallowed by other aggregate booleans.
Material verification quantifies this strict supported subset and signing consistency, not
general DER canonicality or complete consumption of every private-key format.

## 4. Validation & Error Matrix

Material failures and ownership defects stay explicit per-entry; malformed API, wire, bounds,
cancel, expiry, owner/reentry or JNI failures reject entire job, no prefix. ALLOC→ENOMEM,
SYSTEM/LIBCRYPTO→EIO/-1/output0, never MISMATCH; signature material failure→MISMATCH/SIGNER.
Finally wipe every exclusive body/phrase/wire on IOException/Runtime/Linkage/Error and normal
return, then original lifetime fence. Cancellation does not erase a still-borrowed input or
create another worker to hide nonpreemptible KDF/crypto.

## 5. Good / Base / Bad Cases

Good: unique phrase owner and correct bounded OpenSSH phrase yield OWNER_RESOLVED+MATCH.
Base: two same-identity clear private files and zero phrases each get actual self-proof.
Bad: two same-identity private files plus one phrase cause AMBIGUOUS_PRIVATE and no native
attempt/ambiguous secret copy; two same-owner phrases remain ambiguous even if bytes equal.

## 6. Tests Required

Strict testSshPrivateMaterialHost/StaticHost builds and separate real ARM runtime exercise all
five software kinds, inconsistent Ed secret, phrase modes, RSA, KDF/refusal, system/signature
faults and every publication deadline/effects0. Preserve independent original pair/material
matrices. JVM uses independent CSKP vectors, actual supplied candidate bytes, all-private and
credential coverage, unique/duplicate identity and phrase handling, no unrelated copies,
immutable partial reports, whole-job failure/wiping, creator/oneattempt/reentry and original
deadline/final fence. Do not weaken v1 to test impossible orphan/namespace loans. Opt-in
platform fixture compilation is not Android runtime; normal/fixture lint/APK isolation and
native bytes/exports need actual verification in [results](../../../design/verification.md).

## 7. Wrong vs Correct

Wrong: treat duplicate private identity as unconditional invalidity, auto-select a phrase,
call PARSED a signing proof or turn target:test@example:22 into an invented account record.
Correct: audit exact existing metadata and actual private bytes; keep ambiguity, deferred
target, nonpreemptible parser costs and missing graph/trust/full admission explicit.
