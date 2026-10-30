# Encrypted SSH private keys and optional saved passphrases

## 1. Scope / Trigger

Extend the single production direct/config connector per
[the selected contract](../../../design/ssh-encrypted-key-connect.md). This supersedes the
earlier product refusal of encrypted private keys; historic context-free inspector/pair proof
contracts stay strict. No additional SSH engine, plaintext staging, generic secret API or
automatic format migration. Preserve password, TOTP, trust and screen-lock disconnection.

## 2. Signatures

- `SshKeyMaterial.inspect(body, phrase, deadline)` returns public-only CSKEv1 metadata;
  existing no-phrase Java overload and strict old C export remain available. Product C export:
  `ctos_ssh_material_export_public_with_passphrase(body,len,phrase,phrase_len,deadline,out,len)`.
- `ssh.importUnlock(requestId,ticketId,binding,passphrase) -> SshImportPreview`;
  previews add `needsPassphrase:bool`, library keys add `encrypted:bool,hasSavedPassphrase:bool`.
- `ssh.keyConnect` / `ssh.configConnect` add `passphrase:string,useSavedPassphrase:bool,
  savePassphrase:bool`. Missing trio retains old clear-key behavior; partial trio rejects.
- `ssh.forgetKeyPassphrase(requestId,keyId,all) -> {forgotten:true}`; `all=true` requires
  empty keyId. It does not change the existing password-only forget API.
- `AppSecrets.newKeyPassphraseMutationContext`, `beginKeyPassphraseMutation`,
  `saveKeyPassphrase`, `forgetKeyPassphrase` reuse the guarded shared entry mutation pipeline.

## 3. Contracts

Private bytes <=1MiB; phrase <=8192 UTF-8 bytes, no NUL/unpaired surrogate. Native original
BOOTTIME deadline <=60s, all owned input/phrase/wire arrays wipe on every exit. CSKE parsed
flags1(clear)/3(encrypted), failure flags0/2 with no public blob/digest/kind; append
BAD_PASSPHRASE status4 without renumbering old outcomes. Product export enforces current
encryption policy, actual decrypt, complete eligible legacy PEM DER consumption, actual public
derivation and fixed-domain local sign/verify. Encrypted PKCS8 or excessive KDF remain unsupported.

Import retains exact bounded encrypted bytes in a host ticket under original request epoch,
revision, validator registration, deadline and cancel guard. Unlock uses fresh request ID and
exact original ticket/binding, does not reread URI or renew budget. Wrong phrase permits retry;
lock/cancel/expiry/source change closes owned input. Only verified public review becomes
committable. Store the original encrypted container and its proved PUBLIC/KeyBinding atomically;
never persist the import phrase or silently remove the file's own encryption.

Ordinary source admission of an unsaved encrypted key verifies the native bounded envelope
policy plus exact allowed PUBLIC/PRIVATE/KeyBinding paths, IDs, public blob/algorithm/identity,
unique explicit phrase ownership, Accounts and CONFIG references. It is not a decrypted pair
proof. Each actual import and selected use requires actual decrypt/public equality. A stored
phrase is additionally checked against its exact binding. Unrelated operations must not ask for
all unsaved phrases. Historic generic inspector ambiguity must not substitute for explicit
production binding. Complex metadata restore remains closed.

Canonical `SshKeyPassphrase` owns one SSH_KEY_PASSPHRASE Entry per exact KeyBinding; identity
is `key:` + private identity, stable entry ID includes the key locator. Save/update and link,
single/all forget and unlink are one guarded candidate transaction, preserving unrelated data.
Any private container replacement removes the old phrase link/entry, even for the same public
identity. No two-step durable entry/link write and no fallback to a phrase at another path.

Connector checks original source/config/key selection and local decrypt/public equality before
TCP or explicit save. Default transient phrase uses `SshTransientKey`, a fresh once ID, exact
role object/selection/public key/container digest/Account/target/owner thread/original deadline.
It keeps the actual CSASv2 selection. Only that opaque host source can issue its bound typed
PRIVATE read; after actual host/KEX proof and final guarded read it creates one CSKBv1 bundle,
then wipes source/copies. A synthetic entry ID must never authorize a persistent bundle read.
Saved PRIVATE_BUNDLE validates exact `path + NUL + identity + NUL + entryId`, matching binding,
Entry type/identity, Account, role/target and public blob at admission and delivery.

Successful authentication retires phrase material. Lock, foreign mutation, cancel, expiry or
old callbacks revoke pending work; shell service lifecycle stays unchanged. Explicit save can
precede TCP after local proof; do not promise rollback if remote login subsequently fails.
Late ambiguous durable effects lock and return existing uncertain status. Reconnect hints hold
public selection only, never a phrase or prior receipt.

## 4. Validation & Error Matrix

Missing phrase -> SSH_KEY_PASSPHRASE_REQUIRED and input action; incorrect phrase ->
SSH_KEY_BAD_PASSPHRASE and explicit retry; unsupported container/KDF -> SSH_KEY_UNSUPPORTED.
Missing saved phrase -> SSH_KEY_PASSPHRASE_MISSING; stale ticket/key/source -> existing expired /
changed failure. Contradictory phrase modes or oversized/NUL text reject before TCP. Unknown
host still requires actual fingerprint approval; no publickey/password fallback. Mutation
uncertainty remains VAULT_COMMIT_UNCERTAIN, never a fabricated successful deletion/no-effect.

## 5. Good / Base / Bad Cases

Good: unlock original imported ciphertext, compare actual public subject, later type a fresh
one-use phrase or explicitly reuse the saved bound phrase. Base: clear-key and password payloads
still work; forgetting key phrases preserves files and TOTP. Bad: accept NEEDS as full pair
proof, save import input by default, rewrap clear private bytes, or reuse by public identity alone.

## 6. Tests Required

Real ARM material/proof matrix: all software algorithms, approved encrypted OpenSSH/PEM,
wrong/missing/correct phrases, KDF bounds, complete DER, policy refusal, deadline and wiping.
JVM: malformed wire; actual core/store entry+binding atomicity; wrong key/account/selection;
transient once/host-KEX/final copy, saved bundle, same-public replacement, orphan/cross-key
ownership and single/all preservation/cancel. Injected native validators do not prove native
decryption. Flutter: original-ticket retries, late/background cancellation, modes and resets,
confirm/uncertain forget, narrow/landscape/2x layout. Real SSH interop and Android product paths
are separate evidence; build success is not device execution.

## 7. Wrong vs Correct

Wrong: an SSH_KEY_PASSPHRASE entry is written first, and a second write links the key later.
Correct: produce one owned source-bound candidate containing both Entry and KeyBinding change,
validate it outside the core lock and commit with the existing unique effect claim.

Wrong: turn a transient CSASv2 selection into an unguarded no-phrase selection to read the key.
Correct: retain the exact selection and require its opaque transient source at the typed read
context, then bind final bundle publication to the actual host/KEX proof and original request.
