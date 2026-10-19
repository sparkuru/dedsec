# Research: managed immutable SSH authentication selection

- Query: Define a narrow, production-connected managed metadata selection for explicit SSH wire username and exact typed password/private-key identity, ahead of native USERAUTH implementation.
- Scope: internal; current Java managed provider, credential bridge, callback adapter, role/KEX proof, vault manifest and synthetic JVM tests. Read-only investigation except this report; no builds, device, Docker, network, real vault, account or credential access.
- Date: 2026-10-18

Implementation follow-up: the frozen managed slice is now implemented and formally
reviewed. Focused46 and fresh full JVM201 tests pass, as do lint/Release/package
checks. Actual evidence and remaining native/passphrase/key-match limits are in
[`design/verification.md`](../../../../design/verification.md#2026-10-18--managed-immutable-ssh-authentication-selection).
The report below records the source research before implementation.

## Findings

### Recommendation and frozen direction from main

Implement a package-private immutable `SshAuthenticationSelection`, and connect it to an explicit one-shot adapter selection API, a selection-aware credential callback overload, and selection-aware managed provider/bridge overloads. This slice carries managed metadata and can release only the exact selected typed bytes after existing host/KEX checks. It does not serialize a receipt to C/JNI, send USERAUTH packets, parse a private key, or prove authentication.

Main has selected these boundaries during this investigation:

- Exact immutable selection object identity is the managed receipt; no native digest/wire format is frozen now.
- Bind the exact `SshRoleContext` object, not only `role.binding()`.
- Freeze selection on the event-loop owner before the first callback or native-session claim; no replacement. The setup lock is sticky even if a claim later fails.
- A selected adapter rejects the old kind-only byte callback. Unselected adapters preserve the legacy compatibility contract.
- Selected mode supports only PASSWORD and PRIVATE_KEY. No passphrase field in this slice; KEY_PASSPHRASE and KEYBOARD_INTERACTIVE fail `SSH_AUTH_SELECTION`. Legacy mode retains its existing behavior.
- Public-key blob is a bounded preselected declaration. Matching it to the parsed private key is a future native responsibility.

### Files found

| File | Description |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/SshManagedProvider.java` | Trust source/CAS ownership and legacy kind-only secret request selection. |
| `android/app/src/main/java/im/majo/ctos/SshCredentialBridge.java` | Exact typed vault request creation; final host/role/KEX effect guards and request cleanup. |
| `android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java` | Same-thread callback state machine, proof state, bounded secret handoff, native-session claim and retryable close. |
| `android/app/src/main/java/im/majo/ctos/SshRoleContext.java` | Immutable hop metadata, private nonces, live view/deadline and legacy role digest. |
| `android/app/src/main/java/im/majo/ctos/SshKexProof.java` | KEX cycle/session digest and role/host matching. |
| `android/app/src/main/java/im/majo/ctos/SshRoleRegistry.java` | Exact role ownership and nonce replay checks. |
| `android/app/src/main/java/im/majo/ctos/VaultManifest.java` | Typed entry/file identities, relative path policy and passphrase/key relationship. |
| `android/app/src/main/java/im/majo/ctos/AppSecrets.java` | Exact entry/path, type, target/connection and vault generation/epoch checks at release. |
| `android/app/src/main/java/im/majo/ctos/VaultOperation.java` | One-shot vault capability, exact role/target binding. |
| `android/app/src/main/java/im/majo/ctos/VaultCodec.java` | Existing strict UTF-8 encoder/decoder patterns and byte wiping. |
| `android/app/src/test/java/im/majo/ctos/SshProductionBridgeTest.java` | Synthetic real AppSecrets tests of typed requests and adapter/provider integration. |
| `android/app/src/test/java/im/majo/ctos/SshNativeCallbackAdapterTest.java` | Owner thread, callback ordering, close retry, cancel and lifecycle tests. |
| `android/app/src/test/java/im/majo/ctos/SshNativeSessionTest.java` | Native-session claim and cleanup ownership tests. |
| `.trellis/tasks/10-08-ssh-terminal-client/research/next-userauth-20261017.md` | Prior upstream research and the identified missing username/selection binding; not repeated here. |

### Existing code patterns and gaps

1. **Kind-only selection can choose another same-kind entry.** `SshManagedProvider.java:163-172` calls `CredentialRequestSource.open(...)`, checks only `request.kind == kind`, then releases. Exact entry/path fields are in `Request`, but the native caller supplies no selection receipt. This is the compatibility seam; do not claim it has the new selection guarantee.

2. **Reuse exact vault checks.** `SshCredentialBridge.java:72-94` selects a typed entry at `role.targetIdentity`/connection; `:107-127` selects exact private-key path/keyIdentity. `:138-155` repeats request-open, live role, host proof, KEX proof and operation-role checks inside AppSecrets' final effect guard. `AppSecrets.java:682-708` checks entry ID/type/identity, copies bytes, runs the guard immediately before protected receiver effect, and wipes the copy. `AppSecrets.java:770-790` does the equivalent for PRIVATE_KEY type and exact declared key identity. New selection overloads should retain this machinery rather than replacing it.

3. **Legacy role binding does not cover nonces.** `SshRoleContext.java:139-148` hashes program/PID/hop/connection/target/host/port/view/deadline, but not roleNonce or connectionBinding. Those bytes remain private immutable clones (`:61-62,77-78,134-135`). `SshRoleRegistry.java:28-35` separately rejects replay of nonce/binding. Selection must check `candidateRole == selectedRole`; checking the digest alone can accept a separately issued same-metadata role in another registry. Optional cloned nonce/binding comparisons may document the full binding but do not replace object identity.

4. **Username is absent.** Role metadata has opaque targetIdentity (`SshRoleContext.java:52-64`), and neither KEX proof nor provider creates a canonical wire username. Never parse or split `targetIdentity` as `user@host`. Keep the supplied username byte-exact after strict encoding.

5. **Proof checks remain mandatory.** `SshNativeCallbackAdapter.java:513-529` issues KEX proof only after host proof; `:565-582` requires both proofs before provider release. `SshKexProof.java:47-53` binds role/hop/connection/target/fingerprint; it does not make the new selection unnecessary. Future native auth must also compare its current accepted KEX cycle/session, since this managed slice does not transport selection to native.

6. **Reuse callback concurrency and cleanup.** `SshNativeCallbackAdapter.java:252-335` owns thread/reentry/liveness checks and finish-time cancellation/expiry checks; `:582-600` wipes returned bytes on failed finish or exceptions. Selection-aware callback must use this same begin/finish path. `:688-704` clears proof state after native close; `:721-759` preserves retryable native cleanup. Do not create a callback route that skips those guards.

### Proposed narrow API contract

Suggested API shapes (names may be adjusted by main/implementer):

```java
SshAuthenticationSelection.password(role, username, entryId);
SshAuthenticationSelection.privateKey(role, username, path, keyIdentity, publicKeyBlob);
adapter.selectAuthentication(selection);
adapter.onNativeCredentialBytes(kind, selection);
provider.credentialBytes(role, hostProof, kexProof, kind, selection);
bridge.openSelected(role, hostProof, kind, selection);
```

The two factories ensure mutually exclusive typed shapes. Fields are final; byte arrays are private, cloned on input and access. There is no raw secret, parsed private key, request source, lambda, arbitrary path opener, serialization, equals-by-value capability or public mutator in the selection. A package-private exact-object receipt suffices until the separately designed native transport exists.

Recommended fields and bounds:

| Field | Rule |
| --- | --- |
| Selected role | Non-null exact `SshRoleContext` object; live at creation/selection/use through the caller's clock; check its view/deadline at every effect. |
| Username | Explicit nonempty strict UTF-8, 1–255 encoded bytes; reject malformed UTF-16/unpaired surrogates with encoder REPORT; reject NUL and all ISO control code points. Preserve whitespace, case and Unicode bytes; no trim/normalization/target parsing. |
| Password entry ID | Exact nonempty `[A-Za-z0-9_-]{1,128}`, matching manifest ID policy. No key/path/public-blob fields for password. |
| Private-key path | Nonempty strict UTF-8 ≤512 bytes and existing `VaultManifest.path` rules: relative, ≤8 segments, no traversal/empty segment, absolute path, backslash, colon, percent, tilde or glob characters; additionally reject ISO controls. Check null before calling existing helper. |
| Key identity | Nonempty strict UTF-8 ≤512 bytes; no NUL or ISO controls. Do not infer fingerprint or username from the label. |
| Selected public-key blob | Non-null 1–`VaultManifest.MAX_FILE_BYTES` bytes (1 MiB), consistent with existing public host-key blob/file caps (`SshNativeSession.java:22`, `ssh_native_client.h:15`). Clone before retaining; future native parse must reject invalid/trailing/unsupported key content. A smaller cap would be a deliberate policy choice, not an existing limit. |

`VaultCodec.java:30-39,52-65` provides the REPORT encoder/decoder pattern. `String.getBytes(UTF_8)` alone replaces malformed surrogate input; do not reuse it as the strict validation step. `SshRoleContext.text` and `VaultManifest.utf8` currently use replacement encoding, so a new strict helper is required locally for this selection without widening this slice to refactor all text handling.

### Selection installation and lifecycle

- `selectAuthentication` is an explicit opt-in on the event-loop owner. Validate non-null selection, exact adapter role, active role/view/deadline, managed provider availability, not cancelled/closing/closed/inCallback, no previous successful selection, no previously started owner callback, and no nativeSessionOpening/nativeSessionOwned.
- Install only after all validation passes. A failed validation must not partially install a selection. Record callback-started state on the first legitimate owner callback entry, independently of whether it later fails; otherwise a failed host/KEX callback could permit late plan changes. Wrong-thread attempts must not count as owner activity or corrupt a valid owner's pending selection.
- Mark selection setup permanently locked when any native-session claim is first attempted; failed-open claim release must not reopen setup. A dedicated sticky lock avoids relying only on current `nativeSessionOpening/nativeSessionOwned`, which can return to false.
- One successful installation cannot be replaced, even with the same object. A selected adapter's legacy boolean availability route already cannot release bytes; selected kind-only byte calls must fail `SSH_AUTH_SELECTION` before touching the old request source.
- The new byte overload requires `suppliedSelection == frozenSelection`. Equal fields in a freshly constructed object are insufficient. It also requires PASSWORD for password selection, PRIVATE_KEY for private-key selection, existing host/KEX proofs, and current role liveness. Selected KEY_PASSPHRASE/KBI requests fail closed.
- New provider overload directly opens the fixed selection through the bridge and never invokes `CredentialRequestSource`, including on errors. Keep the old provider overload only for unselected legacy compatibility.
- Save selection in the new bridge Request, recheck selected role/kind/exact resource immediately before release and in the final effect guard. Keep one-shot Request close/cancel on every success/failure. If selected and legacy release overloads coexist, selected Requests must not be releasable through a path that omits the selection guard.
- Immutable metadata needs no secret-style close method. Adapter cancellation/closure makes every future callback unusable; role closure/view revoke/deadline independently invalidate the selection. Callback finish still wipes result bytes on concurrent cancel. Clearing the adapter's selected reference on cleanup is optional, but must not allow a replacement selection on an already used/cancelled adapter.
- Do not add a global selection registry, persistence, random ID, MethodChannel field or native handle for this slice. Exact-object receipt is explicitly a same-process managed contract, not a cryptographic or cross-JNI proof.

### Passphrase limitation discovered

`VaultManifest.java:213-217` requires SSH_KEY_PASSPHRASE entry identity to be `"key:" + PRIVATE_KEY.identity`; the existing test fixture follows this (`SshProductionBridgeTest.java:707-714`). But current `SshCredentialBridge.openCredential` calls `beginSshCredential` with `role.targetIdentity` (`:88`), and `VaultOperation.bindRole/requireRole` requires `operation.target == role.targetIdentity` (`VaultOperation.java:70-89`). A normal target-bound role therefore cannot use the generic credential request to authorize the key-scoped passphrase entry merely by selecting the correct kind.

Main chose to omit passphrase fields and selected passphrase release entirely now. Future encrypted-key work needs a separate design for credential resource identity versus role target, preserving final role/host/KEX authorization and exact selected key binding. It cannot be honestly reported as already covered by the password/private-key selection overloads. Do not change VaultOperation/AppSecrets to work around it in this slice.

### Public-key declaration limitation

`AppSecrets.java:775,787` checks PRIVATE_KEY type and the manifest's descriptive `file.identity`; it does not parse its body or prove that it matches a declared public blob. `VaultManifest.FileObject` has no parsed public-key binding (`VaultManifest.java:52-68`). The new blob is an immutable selected expectation only. Future native code must parse the private key in memory, compare the parsed public identity to this blob, use the explicit username in signed data, and validate the native receipt/current KEX before sending or signing. A metadata factory cannot claim that these cryptographic checks have happened.

### Minimal file and test plan

Implementation ownership can be one focused Java unit:

1. New `SshAuthenticationSelection.java` with typed factories, strict text/shape bounds, defensive copies, exact-role validation.
2. `SshManagedProvider.java` selection-aware overload that bypasses old request source.
3. `SshCredentialBridge.java` selected Request construction and final selection guard, reusing existing vault operations.
4. `SshNativeCallbackAdapter.java` one-shot freeze and callback overload using existing state machine; legacy-mode behavior retained.
5. New `SshAuthenticationSelectionTest.java` for strict metadata/role identity/copy semantics, plus existing production bridge and adapter tests for actual integration. No C/JNI/upstream/build configuration changes should be necessary under current source-set conventions.

Meaningful acceptance matrix:

- Username ASCII 255 bytes accepted/256 rejected; multibyte byte boundary; empty/NUL/CR/LF/C1 controls/unpaired high/low surrogate rejected; supplementary Unicode valid; no normalization/trim.
- Password/private-key factory shapes and identifier/path/keyIdentity limits; input/accessor blob mutation cannot alter retained expectation; zero/oversized blob rejected.
- Same-metadata separately issued role with different nonce/binding rejected despite matching legacy role digest; other hop/target/connection/role/view/deadline rejected.
- Selection only before first owner callback/claim, one-shot; wrong-thread/late/reentry attempts cannot replace; failed native-session claim and close-retry do not reopen selection.
- Selected callback rejects absent/equal-but-distinct receipt, wrong kind, selected passphrase/KBI and legacy kind-only callback; old request source call counter remains zero.
- Real synthetic in-memory AppSecrets selected password and selected private-key release after KNOWN and NEW/CAS proof paths; existing pre-host/pre-KEX checks remain. Add two same-kind entries/keys to prove exact selected resource wins and legacy source cannot substitute another same-kind resource.
- Wrong vault entry type, target identity, path/keyIdentity, cancelled request, role/view expiry and vault generation/epoch mismatch prevent receiver effects; final guard must be exercised rather than only pre-checks.
- Adapter concurrent cancellation/expiry/reentry after provider selection must discard/wipe pending bytes; close cleanup and legacy tests continue to pass.

Reusable tests: `SshProductionBridgeTest.java:211-252` has real synthetic typed bridge release and missing-KEX/wrong-kind checks; `:255-310` already drives NEW trust approval/CAS, adapter KEX and password/private-key bytes; `:564-600` covers role expiry, mismatched KEX and wrong returned kind. Its `fixtureManifest` (`:704-717`) needs extra synthetic entries/keys for same-kind substitution tests. Existing adapter tests cover ownership/cancellation/reentry/close and should be extended instead of copied wholesale. Main owns Gradle/JVM/lint/Release checks; this researcher did not execute them.

## Related specs

- `.trellis/spec/backend/ssh-native.md`, especially callback contract and managed-provider sections around lines 1691–1783, failure table around 2118–2125 and existing tests around 2150–2160.
- `.trellis/spec/backend/app-secrets.md`: typed consumer, one-shot operation, final effect guard and vault revocation semantics.
- `design/constraints.md`: project scope, current authorization and honest implementation/runtime reporting.
- `design/plan.md`: username/immutable selection is the open precondition after current C service negotiation; full authentication remains open.
- Active task PRD/design/implementation plan and prior `research/next-userauth-20261017.md` were read; historical upstream work is not a new research finding or runtime claim.

## External references

None needed. All conclusions are from current checked-out project source. Prior pinned OpenSSH research is referenced above rather than repeated or replaced with unstable web material.

## Caveats / Not Found

- No native/JNI selection transport, USERAUTH packet, cryptographic key match, authentication success or Android/device execution is added or proven by this report.
- The current `role.binding()` omits nonces; exact role object identity is required for this managed-only slice.
- Key-scoped passphrase release needs a later capability contract; selected mode must currently reject it.
- The bounded public-key blob is only a declaration until native parsing/matching is implemented.
- No implementation files, specs, task metadata or build outputs were modified by this researcher. No git operation was used.
