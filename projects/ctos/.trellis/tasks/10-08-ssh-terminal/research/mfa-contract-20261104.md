# Research: minimal same-transport MFA continuation contract

- Query: freeze safe seams for password/publickey PARTIAL → keyboard-interactive on the original owner, role, KEX and deadline.
- Scope: internal source inspection only; proposed contracts, not implementation or runtime evidence.
- Date: 2026-11-04
- Approved product baseline: `design/ssh-mfa-authentication.md`; user subsequently said “开始”.

## Findings

### 1. Preserve the immutable selection; separate the active method

`ssh_native_client.c:209` copies CSAS once; `:259` hashes its complete bytes. Java independently hashes those bytes before native open (`SshNativeSession.java:575`), and callback credential release recomputes the same hash (`SshNativeCallbackAdapter.java:663`). The actual selected password/key read is guarded by `selected_credential_attempted` in C (`:854`, `:865`) and `selectedCredentialAttempted` in Java (`SshNativeCallbackAdapter.java:710`). This is useful and should remain one-shot throughout the complete chain.

Do **not** replace `authenticationSelection` through `selectAuthentication` mid-session. It is deliberately frozen before native owner adoption (`SshNativeCallbackAdapter.java:598`), participates in source/secret guards and is object-identity-bound to transient material. The second method needs no stored credential read. Therefore keep the original selection object and plan hash, and add a runtime **active method/stage** separately. A stable immutable plan receipt is metadata; it does not by itself authorize stage 2. Stage-2 authority is the native transition's exact original owner/KEX/plan check plus a returned stage receipt, not a callback selection rewrite.

Recommended exact CSAS extension:

```text
CSAS v1/v2: existing byte-for-byte layout and restrictions remain.
CSAS v3: existing layout through the five length-prefixed fields,
         followed by u32 continuationKind = 3.
         Header flags stay zero; only original kind1(password) or4(key) legal.
         Username remains original username; no additional credentials.
```

Use v3 **only** for opted-in chains. Original password entry must remain nonempty; original key entry may be empty or a valid passphrase entry ID. Treat encrypted-key bundle selection according to whether the selected key's entry field is nonempty, while preserving v1/v2 validation. Existing `authentication_selection_version == 2` tests at `ssh_native_client.c:1381` and `:1400` need that explicit extension; simply using version3 would otherwise misinterpret an encrypted key as unencrypted. Keep all existing field/path/key-size/role/nonce/binding checks. Bump MAX_SELECTION_WIRE_BYTES/MAX_WIRE_BYTES by four only if existing conservative capacity needs it.

Legacy v1/v2 have `continuationKind=0`; malformed v3, v3 kind3, unknown continuation or trailing bytes fail. The plan SHA256 includes the appended field. Native's `authentication_selection_kind` can retain its **original** meaning for credential reads; add `active_authentication_kind` and `authentication_stage` for method packet/result handling. `auth_proof` at `:896` must verify active method is either original stage1 or plan-authorized kind3 stage2 while still verifying original role, owner, view, deadline and session digest. `selected_credential_is_live` must additionally require stage1 so direct native selected-read calls cannot regain rights after transition.

### 2. One narrow transition, not a general restart API

Today all authentication outcomes set AUTH_FINISHED (`ssh_native_client.c:1282`), `step` refuses that state without a shell (`:2100`), and each begin method requires AUTH_DISABLED (`:975`, `:1133`, `:1346`). Java likewise starts only from SERVICE_READY and treats PARTIAL as FINISHED (`SshNativeSession.java:736`, `:789`, `:808`, `:852`, `:863`). Keep those single-method rules; add a separate transition entry:

```text
C: ctos_ssh_native_client_continue_keyboard_interactive_auth(client,
       expected_plan_receipt[32], out_stage_receipt[92])
JNI NativeBinding: continueKeyboardInteractiveAuth(handle, byte[] planReceipt) -> byte[]
Java SshNativeSession: continueKeyboardInteractiveAuth() // callers supply no plan/proof
```

Suggested fixed stage receipt `CSAPv1`, exactly92 bytes, BE:

```text
u32 magic 0x43534150; u16 version1; u16 flags0;
u32 stage2; u32 originalKind(1|4); u32 activeKind3;
byte[32] immutablePlanSHA256;
u64 originalKexCompletionCycle;
byte[32] originalSessionIdSHA256.
```

Native checks exact supplied hash, owner/thread/environment, original role/view/deadline, exact signed-KEX cycle/digest, completed first method PARTIAL, allowed plan continuation, retained server methods containing exact `keyboard-interactive`, no pending output/rekey, and not previously continued. It atomically consumes the transition and sends the KBI request with the **same username and ssh-connection service** on the same SSH object; `selected_credential_attempted` stays set. It returns the stage receipt only after successful native transition. An alien caller must not consume the legitimate owner's transition; failures after owner-authorized mutation are terminal, without retry/reconstruction of partial state.

Java's owner-only method calls this operation with its private expected selection hash, validates exact92-byte shape/hash/original kind/KEX cycle/session digest against its captured current proof and stores the stage receipt. It moves only FINISHED+matching first-result PARTIAL → KEYBOARD_PENDING. No SERVICE_READY rewind, no new role, no new TCP connection, no repeat secret read or native session claim. Subsequent beginPassword/privateKey/KBI and any second continuation fail. Legacy single-method PARTIAL continues failing at connector level.

Native retains the server method list only from the **current actual FAILURE**; strict current bound1024 bytes, valid ASCII method-name list, no empty elements/trailing bytes. Do not expose this list as authority through arbitrary caller input. The existing FAIL parser (`:1254`) already bounds/validates it but discards it. Copy into owned bounded storage, clear on all terminal/transition/close exits. The transition searches exact tokens; a substring such as `xkeyboard-interactivex` is insufficient. Preserve nonzero SSH boolean interpretation of partial.

Stage1 direct SUCCESS requires no transition and keeps existing promotion. Stage1 rejection, absent KBI, stage2 rejection/PARTIAL and unsupported next-method combinations end the connection. Stage2 KBI continues existing challenge/result APIs, now checked against active kind3; packet60 dispatch uses active method, not the immutable original kind. Password CHANGE_REQUIRED still does not invoke KBI. Only final SUCCESS can create/promote PTY/shell.

### 3. Config order can be encoded without exporting the full list

`ssh_config_eval.c:964`–`:968` currently computes method membership only. `ssh_config_handle.c:336` and `:375` serialize CSCP/CSPPv2. The pinned `readconf.c:3119` explicitly leaves `preferred_authentications` unset for ssh to fill; the wrapper's current Options therefore preserves NULL for no explicit preference. Check this against actual evaluator tests as part of implementation.

Recommend CSCP/CSPPv3 append **two u32 booleans** immediately after the existing KBI-allowed flag:

```text
passwordThenKeyboardAllowed
publickeyThenKeyboardAllowed
```

Compute each from real effective Options: both underlying methods enabled/members and either PreferredAuthentications is NULL or the selected first method's index precedes exact KBI's index. Membership can still use existing validation; ordinal calculation must use actual tokens, not a second config parser. This is the minimal **consumed relative order** required by the approved chains; it avoids exposing unrelated methods or incorrectly enforcing OpenSSH's absent default order on an explicit user-selected chain. A three-way explicit-order representation is unnecessary if the booleans are called capability predicates rather than raw preference metadata.

Java DirectPlan/ProfilePlan fields for v1/v2 default both chain booleans false; old wire cannot authorize a new chain. v3 parses strict0/1 and rejects inconsistent chain=true when either required base method flag=false. Both pre-admission listing and final preparation must check the selected chain predicate against the same current source binding; do not defer a known explicit conflict until after password persistence or opening sockets. Preserve unrelated unsupported options and existing KBI submethod refusal.

### 4. Important pre-existing gap: no config-password path

The approved product scope says **both chains in manual and config**, but current code has only `ssh.configConnect` for key and `ssh.configKeyboardConnect` (`SshPasswordConnectBridge.java:86`–`:90`). Selecting password drops `_configHost` (`password_connect_page.dart:497`). `SshReconnectTarget.java:18` rejects password with nonempty configAlias. Thus implementation must add real config-password connection, not copy effective host fields into the manual password path (which uses a different managed CONFIG/trust namespace).

Recommended bridge:

```text
ssh.configPasswordConnect(
  requestId,alias,binding,username,password,useSavedPassword,savePassword,
  continueKeyboardInteractive,columns,rows)
```

Use the exact existing password field names discovered in `ssh.passwordConnect` (above spelling is a contract proposal, not a claim about existing names). Resolve effective host/user/port/current binding twice as existing config-key/KBI do. Frozen config username wins; optional user override is only for missing config User. Respect config trust files and StrictHostKeyChecking. Saved password remains actual-target/port/user-bound and guarded by existing own-write/NEW-CAS contract; do not route the operation through the narrow manual-only validator/profile writer which would reject or replace user's imported config. The root trust namespace for config remains its actual managed trust path. If initial imported-config password storage machinery differs, explicitly test preservation of all config bytes/key bindings and unrelated data.

Add `continueKeyboardInteractive` optional typed bool to manual password/key/config-key and new config-password methods (missing=false, non-bool invalid). Reject it on standalone KBI methods. Keep strict allowed-key validation by admitting only the known optional field, not silently ignoring arbitrary keys. Distinguish optional password continuation from standalone keyboard selection in connector constructor state.

Reconnect: add configPassword mode and the optional continuation bool to actual Java/Dart strict reconnection schemas; default absent=false; reject true for keyboard/configKeyboard. Preserve source re-resolution and public-target mismatch checks. RAM-only data includes no response/proof/stage progress.

### 5. Challenge ownership

Create the mailbox **after** successful standalone KBI begin or validated stage2 transition. `SshKeyboardMailbox.java:28` hashes role binding, connection ID, round and a random ticket; its live guard receives exact host/KEX checks from the connector. Extend it with an opaque captured immutable stage receipt (or a native-validated stage value sealed in the owning Java session), and validate the original selected plan plus that stage before present/respond. Only one KBI stage is supported, so round can still begin at1 once per connection; no challenge wire version bump is needed if the stage is internal to mailbox lifetime and existing public tickets remain fresh/unforgeable. Do not reset the UI's `_lastKeyboardRound` in response to remote messages; it resets only with a new connection attempt.

Phase receipt must not enter UI payloads/logs; normal attempt/ticket/binding/round events suffice. Maintain original60s total auth deadline. No new password/phrase release in stage2. The existing callback authentication-selection liveness guard is method-agnostic, so retaining original selection does not require a secret-reader fallback.

## Files found / likely affected boundaries

- `android/app/src/main/cpp/ssh_native_client.[ch]` — immutable plan, selected read, active method, transition, retained method list, final promotion.
- `android/app/src/main/cpp/ssh_session_jni.c` — actual JNI file discovered by session symbols; add bounded owner-mapped transition export alongside existing beginKBI.
- `android/app/src/main/cpp/ssh_config_eval.[ch]`, `ssh_config_handle.c` — effective order predicates and CSCP/CSPPv3.
- `SshAuthenticationSelection.java`, `SshNativeSession.java` — v3 selection encoding, exact expected receipts and restricted phase transition.
- `SshPasswordConnector.java`, `SshPasswordConnectBridge.java` — chain orchestration, config-password path, strict bool and original secret persistence guards.
- `SshReconnectTarget.java`, `SshKeyboardMailbox.java` — exact public reconnect hint and stage-bound mailbox.
- Config library metadata/validator classes reached by real password preparation — inspect before writing to avoid generated-manual CONFIG being applied to imported config.
- `lib/ssh/api.dart`, `password_connect_page.dart`, `library_api.dart`/`library_page.dart` as actually needed — strict typed channel/reconnect, preserved config selection, allow password mode from config entry, default-off option.

## Decisive tests and build entry points

1. `android/app/src/test/cpp/ssh_native_client_test.c` already has real encrypted socketpair peers and selection receipt matrices. Extend with both first-method PARTIAL→KBI→SUCCESS+PTY, same SSH session ID/connection counters, multi/zero prompts, direct SUCCESS, rejection, wrong/absent methods, stage2 PARTIAL, unsolicited packet60, duplicate continuation, old/wrong receipt, wrong owner/thread/expired/cancel/view and selected-read attempts after transition. Keep all legacy v1/v2 matrices. `android/ssh/android-password-client-build.sh --client` builds static AArch64/Bionic client matrix against fixed production archive; QEMU run is separate from build and Android JNI evidence.
2. `ssh_config_*test.c` and `SshDirectConfigPlanTest.java`: real pinned Options with absent preferred list, matching order, reversed order, missing first/KBI, explicit disabled methods, Includes/Host precedence, strict v1/v2 false defaults, v3 malformed/inconsistent/trailing wire. Existing config build runner `android/ssh/android-config-evaluator-build.sh` and applicable detached/managed direct matrices are separate.
3. `SshAuthenticationSelectionTest.java`, `SshNativeSessionTest.java`, `SshNativeCallbackAdapterTest.java`, `SshKeyboardMailboxTest.java`, `SshProductionBridgeTest.java`, `SshReconnectTargetTest.java`: one selected secret read across chain; no generic credential call for stage2; stage receipt exact/hash/cycle/method checks and original-budget cleanup. Fake binding tests prove Java ownership only, not real wire.
4. Existing `test/ssh_password_connect_test.dart`, `ssh_key_config_test.dart`, `ssh_keyboard_interactive_test.dart`: default-off checkbox only in password/key, standalone hidden/cleared, method changes preserve intended config and clear secrets/continuation appropriately, correct channel payload for all four entry variants, configPassword reconnect, direct SUCCESS vs partial awaiting server event, back/background/late reply and cleanup.
5. `./hako flutter analyze`, `./hako flutter test`; JVM release unit tests plus release/fixture lint/build; actual CJK/font/keyboard/narrow/landscape/large-text captures. Production archive/JNI/package provenance must match final source before claiming builds verified.
6. Actual Android synthetic-service tests are a later separately authorized gate; use both password+KBI and publickey+KBI with first-stage counters/one transport and real PTY. Existing `SshPasswordConnectPlatformTest.java` is a suitable fixture owner. Compiling added instrumentation is not running it.

## External references

- Prior main-session inspection in `design/ssh-mfa-authentication.md` references RFC4252 §5.1 and RFC4256 §3. No network requests were made in this research subtask; no newer protocol/library claims.
- Pinned local OpenSSH10.6p1 readconf source: `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/readconf.c:3119` (local retained source, not mutated).

## Related specs

- `.trellis/spec/backend/ssh-native.md`: immutable selection/KEX proof and SUCCESS-only shell promotion.
- `.trellis/spec/backend/ssh-keyboard-interactive.md`: bounded current challenge/response, original deadline, method-aware packet60 and distinct evidence gates.
- `.trellis/spec/backend/ssh-password-connect.md`: exact saved-password target, own-write continuation and no generic credential fallback.
- `.trellis/spec/backend/ssh-config-identity-selection.md`: actual config source/effective target and imported bytes preservation.
- `.trellis/spec/backend/ssh-background-session.md`: original owner retirement and RAM reconnect.

## Caveats / Not Found

- Recommendations above are proposed exact contracts for the main session to accept/freeze, not existing APIs. No tests, build, network, device, real account or git operation ran.
- Verify the exact JNI basename before using the illustrative affected-file list; discover by beginKeyboardInteractiveAuth symbols.
- Config-password is a real additional cross-layer path required by the approved matrix. Reusing the manual password profile writer without imported-config preservation tests would be incorrect.
- Main session owns public design/spec/task metadata and deciding whether compact relative-order capability predicates satisfy the frozen consumed-order contract; this report writes only inside task research.
