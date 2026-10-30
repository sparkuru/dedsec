# Research: encrypted private-key import and optional passphrase persistence

- Query: Which changes are necessary for encrypted-key import, direct/config login, default one-use passphrases and explicit save/forget on the existing formal SSH line?
- Scope: internal source investigation only; no build, network, device, personal key or account access.
- Date: 2026-10-30
- Active task: `.trellis/tasks/10-08-ssh-terminal`; the user selected this increment after the delivered lock-disconnect/reconnect milestone.

## Findings

### Current path and actual blockers

| File | Role / evidence |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/SshKeyMaterial.java:15-21,31-49,63-80` | Current public extraction has no passphrase argument, four statuses, and PARSED accepts only flags1; nonparsed results contain no public material. |
| `android/app/src/main/cpp/ssh_material_inspect.c:505-558` | CSKE public extraction already derives the actual public subject and performs fixed-domain sign/verify, but calls `parse_private(..., NULL, 0, 1, ...)`. |
| `android/app/src/main/cpp/ssh_material_jni.c:16-43` | JNI copies/wipes body and wire; add a bounded phrase copy with matching Throwable/exception cleanup. |
| `android/app/src/main/java/im/majo/ctos/SshImportProposal.java:22-75` | Actual import currently rejects NEEDS_PASSPHRASE and creates PRIVATE+PUBLIC+KeyBinding atomically using the exact body. |
| `android/app/src/main/java/im/majo/ctos/SshProfilesBridge.java:49-109,233-279,321-358` | Request owns original epoch/revision/deadline/context; current blocked ticket has neither proposal nor retained input. An encrypted-import retry therefore needs explicitly owned retained host bytes. |
| `android/app/src/main/java/im/majo/ctos/SshConnectionMaterial.java:104-138` | `checkShape` rejects every nonnull KeyBinding phrase reference. |
| `android/app/src/main/java/im/majo/ctos/SshSavedPassword.java:59-77` | Current nonTOTP entry checking permits only canonical password Accounts; all SSH_KEY_PASSPHRASE entries reject. |
| `android/app/src/main/java/im/majo/ctos/SshPasswordManifestValidator.java:55-94` | Every PRIVATE must currently be PARSED; each binding is re-derived without a phrase. Merely adding a UI field cannot make encrypted material usable. |
| `android/app/src/main/java/im/majo/ctos/SshPasswordConnector.java:89-103,223-233,283-336` | Key/config assembly has no phrase source and emits only the no-phrase private selection; actual target/config/key binding is otherwise already selected before TCP. |
| `android/app/src/main/java/im/majo/ctos/SshPasswordConnectBridge.java:286-305` | Exact channel argument sets currently contain no phrase/save/reuse fields. |
| `android/app/src/main/java/im/majo/ctos/SshAuthenticationSelection.java:41-59,65-96` | Nonempty phrase ID already produces CSASv2 with immutable exact key/path/public/role/phrase metadata. |
| `android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java:158-166,178-202,223-265` | Selected private request already chooses typed plain PRIVATE or PRIVATE_BUNDLE; release rechecks exact selection and actual host/KEX/role. |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java:2702-2712,2729-2746,3071-3097` | Existing CSKBv1 exact private+phrase bundle. Production KEY-target guard currently permits only SSH_PRIVATE_KEY and two-part resource, so selected saved bundles currently reject before release. |
| `android/app/src/main/java/im/majo/ctos/SshManagedProvider.java:182-197` | Existing transient dispatch only accepts PASSWORD. A distinct selected transient-key phrase branch is necessary. |
| `android/app/src/main/java/im/majo/ctos/SshTransientPassword.java:35-121` | Useful pattern for origin/role/selection/owner/one-attempt/host/KEX/final-publication guards, with no persistent entry. |
| `android/app/src/main/java/im/majo/ctos/SshBundleProposal.java:18-28,187-218` | Existing proposal supports exact files and metadata Relations but copies all existing entries unchanged. Atomic phrase entry plus binding save/forget requires a coherent entry-delta extension or equally bounded shared mutation candidate. |

### Native support is narrower than the general phrase label

`ssh_key_policy.c:122-176` accepts OpenSSH single-key `aes256-ctr` + bcrypt, salt1..64 and rounds1..64. Legacy RSA/EC PEM permits only exact traditional labels and AES-128-CBC/AES-256-CBC envelope (`:85-117`). Encrypted PKCS8 is deliberately excluded before arbitrary KDF parameters are processed (`:135-140`).

The existing public extraction uses strict parser mode. `ssh_material_inspect.c:203-209` rejects legacy encrypted PEM in this mode even though native authentication supports it. Add an explicit bounded product export path rather than silently weakening the strict context-free inspector/pair proof APIs.

Native login already handles a CSASv2-selected CSKBv1 bundle, parses with its phrase, wipes scratch, checks software policy and compares actual private public subject with the immutable expected public key (`ssh_native_client.c:1144-1175`). Thus a transient phrase can reuse the actual native authentication format; it must not be converted into a generic native signing capability.

### Recommended minimum coherent contract

1. Preserve the encrypted private file bytes verbatim inside the common vault. Do not silently strip the private file's own encryption, stage plaintext on disk, or persist a phrase when the user selected one-use.
2. Extend `SshKeyMaterial.inspect(body, phrase, deadline)` with old no-phrase overload. Native output remains public-only; add explicit BAD_PASSPHRASE and encrypted PARSED flag semantics, keeping zero public fields for failures. Verify the actual subject and fixed-domain self-proof with the supplied phrase. Preserve current parser/policy/bounds and deadline checks.
3. `ssh.importFile` may return a needs-phrase ticket whose original request owns a clone of the bounded file input. Proposed `ssh.importUnlock(requestId,ticketId,binding,passphrase)` validates that exact ticket/source/epoch/revision/context and original deadline, reuses retained bytes, and produces the existing normal public review/commit ticket. Wrong phrase must not commit or reread URI. Every cancel/lock/background/expiry/replacement closes retained bytes. Phrase goes only frontend-to-host, never back in metadata.
4. The ordinary production validator cannot require every unsaved key's phrase on unrelated TOTP/password/config operations. For encrypted keys without saved phrases, explicitly admit only the supported encrypted envelope/policy and public/graph shape under authenticated storage; actual private/public mathematical association is established on approved import and required again when that key is used. A no-phrase structural outcome is not a decrypted-material proof. Existing unencrypted validation remains complete. Saved phrases can additionally be checked when present. Metadata restore stays closed until its separate graph/content review obligations are satisfied.
5. Library key rows expose only `encrypted` and `hasSavedPassphrase` plus current public metadata. Proposed key/config connect payload extends existing exact arguments with `passphrase`, `useSavedPassphrase`, `savePassphrase`. Default is one-use; saved reuse sends an empty phrase, and contradictory modes reject. Host validates selected key/config/source binding before consuming phrase or opening TCP.
6. A transient phrase source uses a fresh `once-*` selection phrase ID, exact selected key path/public identity, account/target, role object, owner thread and original request budget. Bind once; after actual host/KEX proof, release the private file through its guarded typed read and combine it with the still-owned transient phrase into CSKB for the exact native callback. Repeat final guard checks and wipe all copies on every exit. Do not call the persistent-bundle getter for a synthetic phrase ID or bypass account validation.
7. Saved phrase Entry has type SSH_KEY_PASSPHRASE, identity `key:` + exact private identity, deterministic canonical ID bound to the KeyBinding locator, and a unique exact KeyBinding.passphraseEntryId. Entry creation/update and binding link must commit atomically. Key replacement must remove obsolete phrase entries/links even when public identity is unchanged but encrypted container/phrase changed; public identity alone does not prove the old phrase remains correct.
8. The main-session frozen contract uses `ssh.forgetKeyPassphrase(requestId,keyId,all)` to remove exact or all key-phrase entries and null the matching binding links in one guarded transaction, preserving private/public/config/trust/TOTP/password Accounts. Re-import same material or a second path must not make phrase ownership ambiguous. This distinct key-phrase API must not silently change the existing password-only forget API.
9. Extend `AppSecrets.checkSavedPasswordRead` for KEY targets to allow the exact SSH_PRIVATE_KEY_BUNDLE resource `path + NUL + identity + NUL + entryId`. Recheck binding->phrase ID, Entry identity/type, actual selected KEY Account/target and public key at both admission and delivery. Plain private reads remain two-part exact. For transient synthetic phrase selection, validate the expected transient mode through a narrowly bound host capability; never treat arbitrary entry IDs as persistent authority.
10. Wrong phrase returns a fixed safe error and retains original key/library; permit explicit user retry with a fresh bounded request. Wrong local phrase should be diagnosed before TCP where practical. Saved-phrase update should happen only after actual local decrypt/public verification; cancel/late effect follows existing uncertain-write handling. Connection handoff and lock reconnect hints carry no phrase/old receipt, and successful authentication retires the source promptly.

### Suggested implementation ownership split

- **Native material worker:** `ssh_material_inspect.[ch]`, `ssh_material_jni.c`, `SshKeyMaterial.java`, `ssh_private_material_test.c`, `SshKeyMaterialTest.java`; define exact export/result signature first and preserve unrelated inspector modes.
- **Production host worker:** shared guarded candidate/entry+Relations transactions, shape/admission, `SshImportProposal`, `SshProfilesBridge`, `SshPasswordConnector`, `SshPasswordConnectBridge`, transient-key source and `AppSecrets` exact reads, plus meaningful host tests. This is tightly coupled security logic and should remain one coordinated owner.
- **Flutter worker:** `lib/ssh/library_api.dart`, library and connection pages and relevant widget tests once exact metadata/argument contract is frozen. Secrets remain masked, cancellable, not in reconnect hints or public status; retained controllers clear on completion/disposal.
- Main coordinates build serialization and final source/contract review. The workers must preserve parallel edits and avoid starting independent full builds against changing source.

### Existing validation entry points

- `./hako flutter analyze`; `./hako flutter test` (focus on `test/ssh_key_config_test.dart`, `test/ssh_password_connect_test.dart` first).
- Release JVM focused tests: existing `SshKeyMaterialTest`, `SshConnectionRelationsTest`, `SshSavedPasswordTest`, selection/credential/typed-read lifecycle suites; then full `:app:testReleaseUnitTest`.
- Native material/public-export matrix: `:app:testSshPrivateMaterialStaticHost` / `:app:testSshPrivateMaterialHost`, plus existing material and explicit pair matrices. Gradle build task registrations are at `android/app/build.gradle:380-452`; builder supports `android/ssh/android-material-inspector-build.sh --output <new-dir> --static-private-test`.
- Native authentication matrix: `:app:testSshNativeClientHost`; actual separate ARM execution must be recorded distinctly from compile success. Existing `ssh_native_client_test.c:3931-3960` covers encrypted software-key formats and encrypted PKCS8 refusal.
- Independent key-only interoperability builder: `android/ssh/android-password-client-build.sh --output <new-dir> --key-interop`; current runner derives the public export without a phrase, so encrypted interop needs a corresponding fixture argument/CSKB path rather than claiming old clear-key success.
- Normal `:app:lintRelease :app:assembleRelease`, isolated fixture build/lint, APK v2/source/native/input audits per current validation profile. No use of `./hako current` or `dist` replacement is implied.
- Existing Android fixtures: `SshPasswordAuthPlatformTest` already exercises selected encrypted private bundle; `SshPasswordConnectPlatformTest` owns product import/config/actual PTY paths. Build/fixture success does not establish Android execution. Device install/real endpoint checks still need the current target/authorization.

## External References

No internet lookup was needed or performed. This research uses the repository's pinned OpenSSH 10.6p1/native source and existing software-key policy, not an assertion about unpinned current upstream behavior.

## Related Specs

- `.trellis/spec/backend/ssh-key-config-connect.md`: public derivation, exact import and direct native plan; its current encrypted refusal is the behavior being extended.
- `.trellis/spec/backend/ssh-password-connect.md`: default transient and explicit persistence, original-context mutation and guarded delivery.
- `.trellis/spec/backend/app-secrets.md`: original contexts, detached validations/loans, final typed guard and uncertain-effect handling; current sections300-305 describe exact private bundles.
- `.trellis/spec/backend/ssh-private-material.md`: strict context-free proof and ambiguous phrase ownership, which must remain separate from the new production bounded encrypted subset.
- `.trellis/spec/backend/ssh-manifest.md`: one model and content-based restore gate; preserve TOTP and metadata.
- `.trellis/spec/frontend/app-secrets.md`, `.trellis/spec/frontend/terminal-interaction.md`, `.trellis/spec/trellis-plus/validation.md`: sensitive routes, safe async state, native device/human review boundaries.

## Caveats / Not Found

- Findings are source evidence only. No code, spec, task root or build state was changed; no test or runtime pass is claimed.
- A complete persistent-phrase entry+binding transaction does not exist yet. `saveSsh` alone would write an unlinked entry and fail current shape; two separate durable writes are not a substitute.
- Repeated public identities at different paths can exist today. Existing generic material inspector resolves phrases by identity and may see ambiguity even when new exact KeyBinding references are valid; do not silently select the first phrase. Prefer the explicit production binding when validating its supported subset.
- The existing generic private material inspector deliberately refuses legacy encrypted PEM even though native authentication policy supports it. Keep that strict historic report API unchanged unless the parent explicitly expands that independent contract.
- Future restoration of an encrypted graph with no passphrase cannot be called mathematically verified; this increment must preserve the closed metadata-review gate.
- Never admit arbitrary NEEDS_PASSPHRASE records by status alone. The production native-aware validator must still prove a bounded allowed encrypted envelope, exact PRIVATE/PUBLIC/KeyBinding locator and identity/algorithm/public-blob shape, unique allowed phrase owner, Account targets and actual CONFIG references. Actual decrypt/public equality is mandatory on import and selected use. Generic context-free inspector results are not that complete product contract.
