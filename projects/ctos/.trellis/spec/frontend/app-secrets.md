# Shared secret flows

## 1. Scope / Trigger

Applies to `lib/secrets/{api,flows,settings_page}.dart`, settings/security entry and future
Authenticator/SSH consumers. Host contracts are in [backend/app-secrets.md](../backend/app-secrets.md);
product scope remains [design/app-authentication.md](../../../design/app-authentication.md).

## 2. Signatures

`SecretVaultApi.status()`, `mutate(method, arguments, request)`,
`cancel(requestId)`, `cancelImport(previewId)`, `acquireProtection()` and
`releaseProtection(owner)` wrap `ctos/native` `secrets.*`.
`ensureSecretVaultUnlocked(...)` owns create/import/password/registered quick
flows and returns to the original consumer. It does not authorize a native
business operation. Every release/write remains checked by the host.

`showSecretExportFlow(context, api: ...)` returns a typed
`SecretExportReceipt?` through password/filename authentication, preview and
transaction receipt. `recoverSecretExport(context, api: ..., recovery: ...)`
requires a typed `SecretExportRecovery`, explicit confirmation and native tree
reauthorization. `SecretExportPreview`, `SecretExportRemaining`,
`SecretExportReceipt` and `SecretRestoreReview` strictly parse bounded metadata.

## 3. Contracts

- Parse typed status and preview maps strictly. No raw seeds, data keys or
  generic secret enumeration are accepted by Flutter.
- `SecretRequest` IDs are single-use at the host. A completed wrong-password
  attempt gets a fresh ID on retry; a cancelled route scope remains cancelled.
  Concurrent use is BUSY, cancellation is idempotent. Request lifetime must
  not turn stale route success into new consumer access.
- Retain the host terminal outcome in `SecretRequest.completion`. Cancellation
  of a management write/export waits for that outcome; committed or uncertain
  effects show a protected acknowledgement receipt before route removal.
  A cancelled submission continuation cannot pop that receipt. Import propagates
  uncertainty so its caller cannot claim that original data was retained.
- Acquire window protection and await success before constructing sensitive
  content. Clear password controllers, remove the route, await route completion
  and a removed frame, then release only its owned token. Keep nested owners.
- Settings are shared and contain only background-unlock seconds, no clipboard
  cleanup preference or cleanup capability field. The user manages copied codes.
  Unavailable SSH import
  is shown as a limitation, not a successful mixed-backup restore.
- Management always prompts for the actual required password even if the vault
  is unlocked. Separate source/target passwords and trust consent in imports.
  Recheck fresh status before returning an unlock flow as successful.
- `ensureSecretVaultUnlocked` tries registered quick unlock immediately, including
  when the platform currently reports it unavailable; unregistered vaults go
  directly to the protected password route. Only a failed quick attempt opens
  cancel/password/quick choices. A retry rereads status and allocates a fresh
  SecretRequest; failures never automatically loop system authentication.
  Explicit App cancel/back ends this unlock flow, while system authentication
  cancellation is a failed attempt with recovery choices. Quick success and
  fresh status completion must still belong to the current consumer route and
  match its captured generation. Cancel failed/popped/destroyed scopes and ignore
  late results; these choices do not change management password requirements.
- Export uses fresh password authentication, one bounded filename and a native
  directory picker, then metadata-only same-name overwrite confirmation bound
  to `exportId`. Do not reuse the password for commit or claim existing files
  are protected by a Boolean alone. Declining/cancelling waits for native
  cleanup receipt; `exported` can include residual cleanup rows. Show exact
  remaining locations and whether presence is known or uncertain. Recovery is
  a separate explicit action and picker reauthorization, never automatic.
  A stable `exportRecoveryError` is shown in settings and blocks export/recovery;
  it does not block ordinary vault bootstrap/unlock or expose journal contents.
- Import renders all bounded `restoreReview` coverage/trust changes/conflicts,
  including SSH target removals when source is TOTP-only. Pass the exact
  `reviewBinding` with confirmation; no raw key/config/known_hosts contents.
  Null review is valid only for a pure TOTP source and target. Do not hide
  unknown mappings, truncate risk rows or fabricate a completed trust review.
- Render practical failure/recovery text. Uncertain commits/exports require
  checking actual state/output; do not promise rollback or hide a possibly
  completed operation behind a local cancellation Boolean.

## 4. Validation & Error Matrix

| Condition | UI |
| --- | --- |
| Wrong password / damaged authenticated file | same retry message |
| Unsupported SSH validation | explain unsupported backup; keep original |
| Stale/expired state | restart flow, no stale success |
| System unavailable/locked out | retain password recovery |
| Registered quick authentication fails/cancels | show recovery choices after the attempt |
| No registered quick / App cancel or back | password directly / end the current flow |
| `VAULT_EXPORT_UNCERTAIN` | backup may exist; check chosen location |
| Export terminal receipt has remaining files | show role/location/present-or-unknown and recovery action, including after success |
| `VAULT_COMMIT_UNCERTAIN` | locked; reopen with password and verify content |
| Unknown/malformed channel payload | safe bounded failure, no raw payload dump |

## 5. Good / Base / Bad Cases

- Good: wrong-password retry gets a new request; host result determines effect
  outcome; sensitive pages never render before the protection owner exists.
- Base: ordinary workbench/settings stay accessible without creating or reading
  a vault; settings/security lazily opens full management before business pages
  are implemented. Password protection and owner lifetimes are unchanged.
- Bad: global unlocked Boolean, generic raw secret channel, route pop releasing
  protection before secret frame removal, late success entering a new route.

## 6. Tests Required

Focused widget tests cover status parsing, retries/cancelled scopes, settings
bounds, source/target import and trust consent, window acquire-before-render /
release-after-removal, route destruction and keyboard accessibility. Review real
Flutter CJK/Material renders at 320 dp with keyboard and scrollable lower actions.
Widget fakes do not verify Android secure windows, Keystore or actual SAF.
Quick-flow regressions also assert no first-choice dialog, one attempt before
recovery, fresh retry IDs/status, failed-system password fallback, App back,
disposed/covered routes and stale generation; management actions still prompt
for actual passwords even with a registered, unlocked session.
Export tests cover filename bounds, collision confirmation/decline, fresh commit
request without password, cancelled preview cleanup, terminal failed/uncertain
and successful-with-residual receipts, recovery confirmation and stale IDs.
Restore tests cover target-side removals, exact review binding forwarding and
complete scrollable metadata review; fake semantic projections are not production
SSH validation evidence.

## 7. Wrong vs Correct

```text
Wrong: reuse the failed request ID for the second password attempt.
Correct: keep the active route scope but allocate a fresh host request ID.

Wrong: pop route -> immediately clear FLAG_SECURE.
Correct: clear controllers -> remove route -> await removed frame -> release
         exactly that route's protection owner.
```
