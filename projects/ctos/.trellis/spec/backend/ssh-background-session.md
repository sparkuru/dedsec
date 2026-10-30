# SSH service and established session

## 1. Scope / Trigger

Use for SSH foreground service ownership, Activity reattachment, notification actions, or changes
to established transport lifetime. Product contract: [background session](../../../design/ssh-background-session.md).
Keep pre-auth file/secret/trust admission and App/Root lifecycle unchanged.

## 2. Signatures

- `SshSessionService.attach(Context, SshSessionOwner.Factory, String, int, int) -> CompletableFuture<String>`.
- `Factory.create(BooleanSupplier) -> SshNativeSession`, `cancellationAction(Runnable)`, retryable `close()`.
- `SshSessionService.write(generation, bytes)`, `resize(generation, columns, rows)`, `stop(generation)`.
- `ssh.notificationStatus`, `ssh.requestNotifications`: no arguments, `{enabled:bool}` response.
- `SshTerminalSession.isEstablishedFailureCode(String)`: null-safe, exact fixed-code predicate.
- Closed screen-lock events add optional `reconnect` target: `{mode:'password'|'key'|'config',
  host:String,port:int,username:String,keyId?:String,configAlias?:String}`. Password has neither
  selection field; key requires keyId only; config requires both keyId and configAlias.
- `ctos/terminal` retains data/state. Adds snapshot `{type:'snapshot', generation:int, state, mode,
  remote:true, bytes:Uint8List, truncated:bool, exitStatus?:int, reason?:String}` and
  focus `{type:'focus',generation:int}`. Snapshot bytes <=262144, generations process-monotonic.

## 3. Contracts

The non-exported specialUse service owns the native creation executor from the start; never migrate
a live client across threads. Intent contains only random session ID, no material. START_NOT_STICKY;
empty/stale Intent cannot start a connection. Immediate foreground promotion precedes I/O; startup
failure cleans the pending factory, including retries. Keep owner busy until cleanup confirms.

Exact real SUCCESS and current original role/view/selection permit beginShell promotion. Native
retains exact transport callback/role/KEX/session/PID/UID/owner checks, releases its public-view
borrow and auth selection. Java retains only the established claim, closes the lease and retires
connector authentication providers, contexts and Activity closures. `authenticationRetired` must
be true before established I/O or output publication. Cleanup failure rejects I/O and retains
the same close owner. New auth/file/secret/trust reads remain denied, without poisoning the shell
merely for a rejected post-auth API probe. Prepromotion lock/revoke still rejects. Rekey remains denied.

Vault background timeout, screen-off/manual lock and Activity bridge close still revoke secrets.
Already-established shell is independent of that lease. Screen-off is a separate service policy
which cancels pending/established SSH; explicit cancellation/Stop and protocol failure also end it.
Activity destroys only its subscription and pending form, not the established owner on ordinary recreation.
Service holds one PARTIAL_WAKE_LOCK while active/cleaning and releases it on completion/destruction.
No battery exemptions, Root changes, automatic reconnect, command replay, boot or system-kill recovery.

Established pump failures may publish only allowlisted `VaultException.code`; other exceptions,
unknown/null codes and arbitrary messages remain `SSH_TERMINAL_IO`. Native step failures retain
the original fixed `SSH_SESSION_STEP`; experimental errno categories were removed after review.
The current core deliberately normalizes several socket/parser/internal failures to EIO, and some
raw packet-builder failures do not set errno. Therefore the generic STEP fallback does not exclude
transport loss, and errno alone is not a complete failure-origin taxonomy. Do not add
EIO to the transport bucket or interpret a carried errno as proof of a parser/builder failure origin.
If stronger provenance is required, record a finite origin at the actual native failure site and
prove it with socket and parser/builder fault tests before another classification-based device test.

Screen-off policy is owned by the service, including startup while no Activity exists. Register a
receiver capturing the exact owner and unregister only on that owner's confirmed release. Check
current power/keyguard on admission, input and Activity reconciliation; never stop a replacement
owner from an old receiver/notification. Immediately set cancellation and fence old I/O, then keep
the original executor/owner until native/factory cleanup and CPU-lock release are confirmed.
Early `failed/SSH_SCREEN_LOCKED` is a logical fence, not completed cleanup. Cleanup may publish
`failed/SSH_TERMINAL_CLEANUP_PENDING`; final `closed/SSH_SCREEN_LOCKED` and its reconnect target
are published only after cleanup. Keep new connections BUSY until release, with no fake early close.

Reconnect targets are immutable RAM metadata from the actual selected connection, never parsed
from a mode label. Do not retain password, private material, config binding, request/ticket/proof or
Activity closure. Only final remote closed screen-lock events/snapshots expose a target. New
generation replaces it; old-generation setters cannot overwrite it. Notification remains generic.
User tap reuses protected route, reloads current library and resolves exact keyId/configAlias and
endpoint/key membership. It creates fresh bindings/trust/auth, with blank transient password and
save/use options unselected. Missing selection or mismatched endpoint requires reselect; same-ID
updated material is checked through current metadata and fresh normal proofs, not a retained hash.
Only an explicit root reconnect tap may authorize one fresh key/config connection after protected
unlock and fresh selection resolution. Consume that one-shot before any asynchronous read, then
use the normal connect path after Form mounting with captured revision, foreground/route and exact
originating closed-generation guards. Password mode still asks for input; initial entry, background,
resume, errors and reload never create/retry an attempt automatically. Normal host-review prompts
remain required. No command or draft replay.

An entirely frozen UID cannot execute close; normal wake can deliver queued screen-off later.
If historical events are completely missed and the device is already unlocked, current keyguard
alone cannot prove past lock. Preserve ordinary Home behavior and state this platform limit rather
than add silent stop-on-every-resume, manual thaw or policy changes.

Notification has generic private status only. Immutable return/Stop PendingIntents include unique
session discriminator and ID; old Stop cannot affect replacement. Return uses a session MIME type
with null Intent.data because Flutter treats data URIs as navigation routes. Stop uses a unique URI.
Return selects existing terminal, preserves
pending sensitive-route completion. Permission/channel denial does not block SSH; UI states that
notification Stop is unavailable. Request permission before entering protected connection form.

`SshTerminalState` retains <=256KiB output in RAM, wipes evictions/closure, no persistence. New
subscriber receives atomic snapshot before live events; old subscriber unsubscription uses identity.
Snapshot resets rendered output/decoder/draft and fences queued input. Disable terminal-generated
responses while replaying retained bytes, so cursor/device queries cannot become repeated input.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Lock/cancel before authentication/promotion | Original admission rejects; no independent shell authority |
| Vault timeout/manual lock after promotion | Key/material remains revoked; shell authority remains independent |
| Screen off after promotion or during startup | Cancel exact owner, fence I/O, retain cleanup ownership; close with fixed SSH_SCREEN_LOCKED reason |
| Failed view/auth retirement | No publication/status/write/step; retain retryable original cleanup owner |
| Stale generation or notification ID | Reject/drop; never target current replacement |
| Service startup denied/timeout/null restart | No connection/reconnect; exact factory cleanup |
| Activity recreation | Same service generation, fresh subscription/snapshot, no input replay |
| Physical lock probe | Require actual noninteractive display, secure device/keyguard lock and revoked vault; never infer from broadcast alone |
| Credential UI not safely observed | Fail boundedly; manual mode requires real public secure keyguard, no disableKeyguard/policy change or credential fallback |
| Notification permission/channel disabled | Continue SSH; show application close path |
| Remote close/network failure/Stop | Fence output and release all owned resources; no reconnect |
| Unknown/native private error text | Fixed generic code; never publish exception text, path, raw errno or cause |

## 5. Good / Base / Bad Cases

Good: background locks the vault, a fresh Activity restores the same authenticated SSH, and exact
notification Stop closes it. Base: foreground direct password/key flow keeps the same typed terminal.
Bad: extend vault unlock to keep SSH alive, retain decrypted config, move native handle to a new
thread, stop by generic notification action, or feed cached terminal responses back to the server.

## 6. Tests Required

JVM: auth retirement failure gates I/O; post-auth reads denied without breaking shell; cancel and
role closure still revoke; bounded replay/eviction, exact subscription and monotonic generations;
owner create failure/start rejection/cancel-before-create/close retries on same executor.
Test known fixed Vault codes, unknown/null/newline/private-text fallback and exact cleanup/release
count. Model a real pre-auth deadline expiry; directly closing a role before owner cleanup
invalidates the ownership proof.
Native: revoked/retired public view continuation only after actual SUCCESS, credential denial,
exact cancel, retained KEX/owner checks and old preauth matrices. Flutter: strict snapshot parsing,
UTF-8 suffix, stale/closed resurrection, current remote focus, replay query suppression, queued
input fencing, denied permission and resumed-before-form, narrow/landscape/large-text layout.
Android opt-in fixture `ctosSshBackground=true` adds actual Home/timed lock/command/notification
return/recreate/command/Stop/old-Stop/wakelock assertions to key-only bridge scenario. Wait for
the recreated Activity's actual EventChannel listener/sink; ActivityMonitor completion alone
does not establish Dart subscription readiness. Before intercepting terminal output, reuse the
existing Ui helper to verify retained rendered split marker, fresh real input/output and
overview-to-notification terminal navigation. Preserve every failed run separately; diagnostic
metadata is allowlisted, screenshots require the owned fixture window and original assertions
still rethrow. These checks remain separate from physical screen-off, ROM behavior, permission
denial and notification-drawer human interaction.

`ctosSshNotificationsDenied=true` is a separate API33+ scenario requiring
`ctosSshBackground=true` and prohibiting `ctosSshPhysicalLock=true`. Before Activity creation or
connection, require actual POST_NOTIFICATIONS denial and disabled application notification status.
The fixture never changes permissions/settings. Reuse key import/authentication, Home/timed vault
lock, same-generation PTY and Activity recreation; return via the own Activity, without querying
active notifications or using notification PendingIntents. Retain the real Flutter event sink through
cached/new rendered output, disabled-notification guidance and the application close button. Only
after service release, CPU-lock release and locked-vault checks may test observers replace that sink.
Compilation alone does not establish a device pass.

Before switching from the real terminal input/output probe to overview navigation, account for
narrow-screen IME hiding NavigationBar (`main.dart`); the board's wide NavigationRail does not
exercise this. Require current attached/focused own Activity and actual window insets. Send normal
BACK only if IME is visible (API30+ Type.ime; API28/29 system vs stable bottom inset), wait boundedly
for real IME hidden/overview visible and require terminal route retained. Never dismiss unconditionally
or mutate Flutter state. Emit only fixed `navigation_ime_visible_before_back`,
`navigation_ime_hidden_after_back`/`navigation_ime_already_hidden`, `navigation_overview_visible`
status stages. Preserve earlier failures separately; a denied-state pass does not prove permission
dialog button interaction, physical phone lock or Doze.

`ctosSshNotificationChannelDenied=true` instead requires API28+ and an existing own `ctos_ssh`
channel with actual IMPORTANCE_NONE; it is mutually exclusive with the runtime-permission flag.
Use the same background opt-in/no-physical-lock gate and disabled-notification flow. Set the channel
only through externally authorized normal Settings UI; the fixture never changes its state. This
does not test POST_NOTIFICATIONS. On the API30 board, blocking before the first FGS post was
observed to become importance2; that run must fail. After the first post, normal Settings blocking
produced importance0/userLockedFields4/fgServiceShown=true and the unchanged strict scenario passed.
Record both states/runs; never fake denial or relax the resumed-state importance check.

`ctosSshPhysicalLock=true` additionally requires background opt-in and current target/normal
device-unlock authorization. Assert real SLEEP plus PowerManager noninteractive,
KeyguardManager deviceLocked/keyguardLocked and vault locked. Wait boundedly for old owner release,
CPU-lock release, exact closed/SSH_SCREEN_LOCKED snapshot with reconnect hint and fenced old input.
WAKEUP/public requestDismissKeyguard requests normal secure SystemUI. The fixture
only checks bounded visible credential-node metadata, never its text or credential contents;
emit `ctos_stage=await_device_unlock` only after that gate. External authorized input must recheck
current SystemUI focus/lock immediately before input. Wait for actual device-unlocked and Activity
foreground, then assert vault remains locked and no SSH auto-reconnect. Recreate the Activity,
require actual rendered disconnect/reconnect controls, explicitly unlock/reconnect through the normal
bridge and assert a fresh generation/KNOWN trust/PTY. Replay the captured old receiver to prove
replacement isolation, then retain the separate Home/recreation/notification tests. Include initial
already-locked and bounded failure-recovery gates; do not capture the credential screen or store
credentials in source, request JSON, arguments or logs. This is short lock-disconnection validation,
not survival or Doze evidence; it does not replace end-to-end tapping of the new reconnect button.

Physical diagnostics remain explicit opt-ins:

- `ctosSshManualDeviceUnlock=true`: normal WAKEUP/requestDismiss, secure/interactive/public-keyguard
  checks, fixed `await_manual_device_unlock` with initial/screen_lock/failure_recovery gate, then
  <=120s for actual device unlock and Activity foreground. Never enter phone credentials.
- `ctosSshScreenOffHoldSeconds` and `ctosSshForegroundIdleSeconds` are obsolete and explicitly
  rejected; do not silently accept old survival-probe arguments.
- `ctosSshScreenOffDiagnostics=true` now records only first exact-generation remote terminal
  state/reason/time on primary failure, in a fresh owned notice file before cleanup. No thread
  watchdog, hold loop, deep-idle sampling, locals/heap, output bytes or full event maps.
- `ctosSshResetOwnedFixtureVault=true`: before Activity creation require exact isolated
  Application/package, UID different from formal app, Root disabled, no sessions/dispatch/Activity;
  preflight only app-secrets.bin/.bak/.new as regular files with NOFOLLOW, check each deletion,
  then retain the original fresh-data checks. Never clear prefs, Keystore, permissions or formal data.

Preserve the primary Exception/Error and attach cleanup failures as suppressed; attempt each owned
cleanup independently, and fail if cleanup alone fails. Do not change Thanox, VPN or system policies
to obtain a pass. A user whitelist/settings screenshot is not proof of template execution behavior.
Historical survival probes and kernel-freeze evidence live in design/verification.md; they do not
establish the new screen-off acceptance goal. A fresh user-driven attempt gets a new generation.

For a suspected process pause, use separately authorized read-only ADB sampling of the exact fixture
PID/UID cgroup and its ancestors: `cgroup.freeze` is the local request, `cgroup.events` frozen is the
effective completed state. A child with local freeze=0 can still inherit a frozen UID ancestor; neither
Android `isFrozen=false`, ps stateS nor an own PARTIAL_WAKE_LOCK disproves this. Capture before and
after normal wake without writing freeze files. This proves the observed freeze, not who requested
it; identify template/ROM ownership separately. Never manually thaw or alter another app's rules.

## 7. Wrong vs Correct

Wrong: `onDestroy -> service.stop()`; `role.requireBound()` for established shell; replay bytes
through a renderer with its remote-response callback enabled.
Correct: detach subscriber; exact established transport claim after confirmed auth retirement;
reset renderer and suppress callbacks during cached replay, then resume live data normally.
Wrong physical probe: send only ACTION_SCREEN_OFF and claim secure physical lock or cleanup.
Correct: real power input plus actual power/keyguard/vault states, exact owner closure and release,
then normal unlock with no automatic reconnection and a separately authorized fresh attempt.
