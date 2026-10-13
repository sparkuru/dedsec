# Authenticator presentation contract

## 1. Scope / Trigger

Applies to `lib/authenticator/`, Workbench entry and the shared sensitive-route
helper. Authority: [design/authenticator.md](../../../design/authenticator.md),
[shared flows](app-secrets.md) and [host API](../backend/authenticator.md).

## 2. Signatures

`showAuthenticator(context, api, canOpen)` reuses common unlock/bootstrap and
`showSensitiveSecretRoute`. The shared route helper checks its originating
route and optional `canOpen` before and after asynchronous protection acquisition.
Workbench passes mounted/active, so changing a tab cannot open a late route.

`AuthenticatorApi.list(SecretRequest(readOnly:true))` uses the reserved read
namespace. `preview`, `commit`, `cancelPreview`, `rename`, `delete`, `copy` wrap
typed host calls. All writes/copy use ordinary new SecretRequests. Request
retries retain their read-only namespace; cancellation uses the same actual ID.
`sessionEvents` shares a native listener across nested protected routes.
The API's injectable seconds clock defaults to real `DateTime.now`.

## 3. Contracts

- Strictly parse allowed fields/counts/IDs:1024entries,16candidates,512UTF8
  metadata bytes, six ASCII digits, SHA1/6/30 candidate parameters, distinct IDs.
  Never accept a raw seed or URI from the host. Only manually user-entered
  seed/URI controllers exist briefly; clear them after preview/retirement.
- Wait for a validated epoch/unlocked/foreground event before showing codes.
  Malformed/error/missing/end-of-stream remains closed beyond subsequent timer
  ticks; retry actually restores a valid subscription. Nested routes receive
  the latest valid event from a live shared listener, not stale global unlocked
  state after the last subscriber leaves.
- Code presentation requires mounted/current/uncovered route, resumed App,
  healthy event stream and host foreground. Lock/epoch change/coverage/background
  clears old maps and cancels old reads. Revisions reject late async results.
- Use host time and actual current wall clock, refresh at most one list request
  at a time. Hide a snapshot from a different30second window even when a native
  refresh stalls; countdown must not freeze as a valid old code. Host copy
  recomputes current code; never copy a cached Flutter code.
- Acquire protection before sensitive route construction; release only its own
  token after route removal/frame. Keep nested owners. Shared settings remain
  one page; ordinary Workbench/App/Root pages have no new login wall.
- Add methods share a metadata-only preview and explicit candidate confirmation;
  no automatic add or whole-image batch. Delete confirms, rename preserves
  identity/seed. Zero-timeout external picker invalidation is a recoverable
  failure, not permission to revive old requests.
- Cancellation of a possible write waits for its host completion. Already
  completed or uncertain outcomes receive protected acknowledgement before
  leaving; no false claim of rollback based on local cancellation flags.
- Native scanner/picker failure can complete while Dart is still `inactive`
  after Android resume but before window focus. Retain only the mapped public
  error when `mounted && revision == _revision && !request.cancelled && !_leaving`;
  render it on resume. Never use this exception to publish previews or codes:
  successful secret-bearing results still require the existing visibility,
  epoch and session guards. Leaving/cancelling must reject late errors too.
- A successful camera/image ticket may arrive before Dart/window-focus resume.
  Hold only its already parsed metadata in the original async operation and
  wait at most3seconds (Timer plus monotonic elapsed check). Every wait/final
  publication checks original request identity, revision, epoch, cancellation,
  mounted/current/uncovered route and healthy foreground visibility. Render no
  candidate before visibility. Coverage, leaving, lock/epoch or stream failure
  retires the request; manual results do not receive the picker wait. Timeout
  retires the ticket and permits a fresh request with a stable foreground error.
  Do not renew the native120second ticket; subtract handoff time from UI expiry.
- Use ctOS theme/Card/layout,48dp semantic actions, account/code/remaining-time
  hierarchy and scrollable forms/confirmations at320dp/2x/keyboard. No architecture
  progress, raw errors/seeds or extra cleanup settings in product copy.

## 4. Validation & Error Matrix

| Condition | Presentation |
| --- | --- |
| `TOTP_INPUT` / unsupported combination | explain SHA1/6/30 and check inputs |
| image/camera unavailable or permission denied | retry/image/manual recovery |
| stream error/EOF/malformed payload | hide codes until valid recovered event |
| wall-clock window changed or slow refresh | remove expired code, refresh/retry |
| popped/covered/inactive origin during async unlock | no late route or data |
| cancelled committed/uncertain write | acknowledge actual host outcome |

## 5. Good / Base / Bad Cases

Good: multi-code image shows account choices, one confirmed host ticket adds one
entry. Base: empty/locked page provides useful add/unlock paths. Bad: timer restores
codes after event failure, cached-code clipboard writes, or a late protected
route opens over a different tab.

## 6. Tests Required

Fake-channel tests cover strict maps, prefix retry/cancel, valid event startup,
stream error/EOF lasting beyond poll and recovery, late result/coverage/lock,
wall-clock jumps/stalled boundary, explicit selection/cancel/no autocommit,
rename/delete, uncertainty, current native copy, origin/Workbench active guard,
protect-before-render/release-after-frame and320dp/2x keyboard accessibility.
Render representative states with real CJK/Material fonts. Mocks/renders do not
prove Android camera, selected provider, secure window, clipboard or real MFA.
Test inactive native failure followed by resume, cancelled epoch late failure,
and leave/re-enter late failure without raw error exposure or automatic commit.
Also verify early successful picker metadata remains hidden until same-epoch
foreground recovery, with explicit choice/commit; cancellation after receipt
on epoch/stream/coverage/leave, and bounded no-resume timeout/fresh retry.
Real fixture UI waits for the list-only enabled `${title}更多操作` Button with
the Authenticator heading and no add/confirm/edit route before reading committed
ciphertext; account text on the confirmation page is not a completion oracle.
An exact completed-write acknowledgement is also a valid protected outcome,
not permission to dismiss an unknown, failed or uncertain acknowledgement.
Before dismissing it, authenticate exactly the original baseline entry and
the explicitly selected candidate, including IDs, public fixture seed/profile
and absence of any unselected/foreign entries; verify the real secure window.
Capture the observed window before the oracle, then freshly bind unique exact
passive title/body and its enabled advertised Button in that same fixture
window immediately before CLICK. Do not wait for a later same-label button.
Return through the exact add-page toolbar and require the actual list action.
Entries below the viewport may require bounded advertised semantic scrolling;
each scroll must freshly prove the exact list/window with no add/edit/confirm/
acknowledgement route. Keep the target action's visibility, role, owner and
enabled checks; neither ciphertext alone nor a generic list label replaces it.

## 7. Wrong vs Correct

Wrong: show a stream error once, then let the next timer show codes again.
Correct: keep stream health false until a newly validated event is received.

Wrong: acquireProtection completes and pushes despite a covered/inactive origin.
Correct: recheck originating route/current consumer before constructing the route.
