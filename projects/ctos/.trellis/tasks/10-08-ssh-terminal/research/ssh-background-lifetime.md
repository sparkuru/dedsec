# Research: Established SSH background lifetime

- Query: Trace vault/Activity invalidation of an authenticated RUNNING shell and identify the narrow ownership/capability split needed for R8 background keepalive and notification Stop.
- Scope: internal; source inspection only, no device/build/runtime execution.
- Date: 2026-10-26

## Findings

### Files and current behavior

| File | Responsibility / evidence |
| --- | --- |
| `android/app/src/main/java/im/majo/ctos/MainActivity.java` | Owns executor/factory/controller; creation at 66–143, cancellation action at 78–82; factory receives `cancelled || destroyed` at 97; destroy cancels and shuts down at 567–580. |
| `android/app/src/main/java/im/majo/ctos/SshPasswordConnectBridge.java` | Activity-sensitive admission/host review; final handoff checks at 312–339; ordinary close cancels active attempt but only forgets successful `handedOff` at 449–454. |
| `android/app/src/main/java/im/majo/ctos/SshPasswordConnector.java` | Authentication assembly, scoped view and provider ownership; `check` at 359–366 requires sensitive route, original validation registration, deadline and live view; `handoffLive` at 107–108 requires live view. Retryable close at 372–388. |
| `android/app/src/main/java/im/majo/ctos/SshNativeSession.java` | Auth-to-shell promotion 811–825; established operation checks 965–975 and output publication 1122–1129 still depend on view. Staged close 1074–1118. |
| `android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java` | Exact native claim, original thread, role and cancellation checks. Established claim 980–1009 delegates to role `requireBound`, which still requires view. Provider/authority/handoff currently retained in final fields 155–157. |
| `android/app/src/main/java/im/majo/ctos/SshRoleContext.java` | `requireBound` 106–109 checks role active AND public view; `requireActive` 111–115 adds original deadline; transport retain 118–120 only avoids registry expiry but does not detach view. |
| `android/app/src/main/java/im/majo/ctos/VaultSession.java` | Background expiry 101–103 invokes `lock`; lock 68–74 wipes key, increments epoch, revokes attached view. Public view attach 77–84 requires foreground/unlocked original epoch. |
| `android/app/src/main/java/im/majo/ctos/AppSecretsBridge.java` | onPause/onStop 932–946 applies shared background lock policy; close 998–1011 locks vault and cancels activity operations. Screen-off receiver also invalidates vault. |
| `android/app/src/main/java/im/majo/ctos/SshPublicFileLease.java` | `revoke` 142–145 marks inactive and requests native destroy; cleanup can defer while native borrower exists (152–164). Private key bodies are excluded from public-view serialization (59–65, 113–114). |
| `android/app/src/main/cpp/ssh_native_client.c` | Existing exact established identity/KEX proof 1294–1337; line 1302 still requires view borrow bound. Fresh SUCCESS-to-shell promotion 1404–1438. Native close 2077–2115 releases callback/KEX/view/socket in retryable stages. |
| `android/app/src/main/cpp/ssh_view_handle.c` | Borrow-live 233–252 enforces view deadline/revocation; borrow-bound 299–314 ignores time but still enforces view validity/revocation. Borrow release 255–296 can complete deferred destruction. |
| `android/app/src/main/cpp/ssh_callback_adapter.c` | Existing transport promotion 656–680 and check 682–713 bind exact client pointer, thread/environment, native role PID/UID/deadline, cancellation and transport reference without consulting managed vault. |
| `android/app/src/main/cpp/ssh_role_bootstrap.c` | Existing transport retain/snapshot/release 276–324 carries original frozen identity beyond auth deadline; token release remains explicit. |

The present path already separates auth deadline from shell duration. `SshNativeSessionTest.establishedShellOutlivesAuthDeadlineWithoutRenewingSelection` (1810–1829) proves shell I/O after the original deadline while selection reads remain expired. It intentionally does not separate vault/view revocation. Moving the unchanged controller into a service alone will therefore still close after vault lock.

### Minimal safe split (proposal for primary design)

1. Create the native client, callback and controller on the final service-owned event-loop executor from the beginning. A live `SshNativeSession` cannot move threads: `MainActivity.AuthenticatedSshFactory` contract 51–56, native session owner checks, JNI callback thread/environment checks all depend on its original thread. Only resource ownership references and UI subscribers may change, not the executor identity.
2. Preserve all pre-auth and promotion gates. The exact client must have real bound auth SUCCESS, fresh selection receipt and clear auth output, original live role/view, original owner PID/UID/thread and actual KEX/host/session identity. Current C promotion 1410–1429 and Java `beginShell` 811–821 are the narrow transition. A promotion race with lock/cancel must fail closed and retain cleanup ownership.
3. After successful promotion, use a dedicated established-transport check that requires active retained role plus the exact owned established claim, but does not call the auth/public-view check. Keep `requireBound` / `requireActive` unchanged for all file/credential/trust/new-auth operations. Java established shell input/output/publication must use only the dedicated claim path after promotion; before it, existing view checks remain mandatory.
4. C established proof should use the already existing exact transport-owner callback check and retained role snapshot/KEX identity. Remove dependence on the public-view borrow only for this proven established path. Release that borrow and clear `managed_view_borrow` / `managed_view` during the transition once auth-only uses have ended. Set the existing release-stage flag only after confirmed release, keeping retryable cleanup on failure. Do not merely keep the decrypted public view alive across lock. This also avoids the single-global-view handle blocking future operations while a shell runs.
5. Release/revoke the Java public lease after native has relinquished its borrow; close authenticated known-host snapshot/provider, candidate handoff, auth mailbox/ticket, transient password, pending-save bytes and auth-only contexts. Avoid `SshPasswordConnector.cancel()` for this retirement: it triggers `cancellationAction` and kills the shell. Add a distinct auth-resource retirement path and keep exact Stop cancellation independent. Connector `check()` and final bridge `handoffLive()` cannot remain the definition of established transport liveness once the view is intentionally retired.
6. The native callback binding itself must stay until client close: C established proof references its slot and current close contract closes it first. It should become transport-only. Gate every managed credential/file/host-review callback after establishment, and release/null managed provider/handoff/authority references where possible. Current Java callback `begin` 270–301 would otherwise still allow auth calls before expiry when the view remains active; a post-establishment deny is stronger and prevents later callbacks from accidentally canceling transport on view failure. Such rejection must grant no fallback/new authority. Closing the callback via its existing `close()` during retirement is invalid while the native session owns it (893–907).
7. Keep actual native transport material (encrypted transport keys, OpenSSH client, owned socket, established public host key, frozen role and session/KEX identity, cancellation marker) through shell lifetime. Input/output queues and terminal history must remain bounded, and old UI subscribers/generations must not receive retained output. No master key, private key or password retention is required for established I/O. Private-key local objects and received private bytes are already freed/wiped inside C auth at 1227–1235; public view never includes private-key body.
8. Keep rekey unsupported. Established proof 1313–1327 rejects changed/rekeying KEX and enforces exact prior cycle/session digest; ordinary/new-secret APIs retain original deadlines and vault checks. Do not replace the existing checks globally with a long-lived role. Promotion grants one existing shell, not arbitrary channels, forwards, new auth, trust write or credential reads.
9. Activity destruction must detach UI subscriptions and close its secrets/auth UI bridge while leaving established service session alone. Authentication still in progress must cancel on losing its foreground/sensitive context. Completed connection factory/resources should no longer capture live Activity/bridge objects via event/guard closures; retain only service-owned transport state and close receipt. Keep explicit Stop and service destruction as cancellation fences and preserve original retryable close order.

### Regression scenarios

- Fresh SUCCESS + exact claim promotes once; promotion before SUCCESS / with wrong selection / stale view / cancellation / different owner cannot retain transport.
- Established shell survives vault background timeout 0 and nonzero, manual vault lock, screen-off lock, Activity detach/destroy/recreate, original auth deadline crossing. Actual vault key and public plaintext view are released; new credential/config/trust/new-connection requests still require foreground/unlock and original contexts.
- A lock/cancel immediately before, during and after native/Java promotion has deterministic outcomes; no RUNNING publication after unconfirmed resource retirement and no leaked native borrow.
- Established shell I/O and output publication remain live after intended auth retirement; explicit Stop/callback cancel/service destruction immediately fence old output and then close resources once. Failed close/release retries retain same owner, handles and service until confirmed.
- Changed native role PID/UID/token, foreign thread, wrong callback/claim, changed host/session/KEX cycle, rekey, cross-session generation and stale notification Stop cannot operate the live session.
- Native authentication-selection receipt / credential / file / trust entry is unavailable after retirement; a rejected post-auth auth callback must never return secret material or accidentally re-enable the view.
- Background output cannot block owner cleanup indefinitely or grow without bound. Returning UI restores current state/retained bounded output without restarting auth or replaying commands.
- Recreating Activity while connecting cancels the pending attempt; recreating after confirmed established handoff preserves only that exact session. Notification return attaches UI; Stop removes only owned resources. Process death does not reconnect or restart automatically.
- Preserve existing auth-expiry/revocation matrices for pre-establishment behavior and App/Root PTY mutual exclusion.

## Related specs

- `.trellis/spec/backend/ssh-password-connect.md`: existing SUCCESS-only shell promotion and original secret/file rights; foreground-only path explicitly incomplete for R8.
- `.trellis/spec/backend/ssh-native.md`: exact native ownership/proofs and retryable cleanup.
- `.trellis/spec/backend/app-secrets.md`: vault key/epoch revocation, original scoped requests and publication fences.
- `.trellis/spec/frontend/terminal-interaction.md`: typed generation isolation and output/remote input behavior.
- `.trellis/tasks/10-08-ssh-terminal/prd.md`: R8 and AC6 background/service ownership, continued vault locking and new-secret gates.

## External references

None used. This report explains checked-in implementation contracts, not current Android foreground-service policy; service-type eligibility, permission and Android version requirements need primary documentation verification by the implementation/design owner.

## Caveats / Not Found

- This is source-based proposed design, not proof of runtime feasibility or completed implementation. No tests/build/device actions ran.
- Public view is single-attached per vault and native view handle is global; keeping it retained and merely bypassing checks would preserve decrypted configuration and block later native view creation. Prefer actual release after established proof.
- Report line numbers refer to the source as inspected before concurrent implementation edits.
