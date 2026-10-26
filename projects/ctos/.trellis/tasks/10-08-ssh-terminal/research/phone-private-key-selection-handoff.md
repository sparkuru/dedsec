# Research: Phone private-key selection handoff

- Query: Why did tapping “使用此私钥” on the authorized Android 16 phone return to a password form?
- Scope: internal, read-only code/evidence investigation; no device operations or code changes
- Date: 2026-10-26

## Findings

### Observed evidence

The main session supplied sanitized phone UI evidence under
`/tmp/ctos-phone-ssh-20261026.plE6pOaG/`. `ui-library.json` has one key and a
“使用此私钥” button. `ui-keyselected.json` is the SSH connection form with a password
field and “保存主机信息；密码默认仅本次使用。” This proves the captured frame was
in password mode. The evidence does not contain the navigation result or elapsed
time relative to protected route completion, so it does not alone prove that the
key result was lost.

The main session subsequently repeated the **explicit private-key mode →
private-only picker → use key** path. Its settled capture retained the private
identity card, private mode and draft host/user for more than ten seconds. This
confirms that the current phone APK can return and retain a selected key through
the real protected route. The initial general-library observation remains
ambiguous; it is not sufficient evidence for a production handoff defect.

### Files and executable control flow

- `lib/ssh/library_page.dart`: typed selection model, protected library route,
  key selection and native read retirement.
- `lib/ssh/password_connect_page.dart`: caller-owned auth mode, defaults reload,
  selection reapplication and lifecycle fences.
- `lib/secrets/flows.dart`: protected route result and removal/protection lifetime.
- `test/ssh_key_config_test.dart`: existing manual/config selection widget coverage;
  immediate fake native defaults/protection replies.
- `android/app/src/main/java/im/majo/ctos/SshPasswordConnectBridge.java`: actual
  asynchronous native defaults read and monitor scheduling.

1. The actual library button invokes `_select(key)`
   (`library_page.dart:670`, `library_page.dart:674`). `_select` checks the live
   current library, marks its owner leaving, retires its exact native read, and
   pops a **non-null** `SshLibrarySelection(key: key, host: host)`
   (`library_page.dart:481`).
2. `showSshLibrary` calls `showSensitiveSecretRoute<SshLibrarySelection>`
   (`library_page.dart:17`). The generic helper returns the pushed route result
   (`flows.dart:62`), while its `finally` waits for route removal, a frame, and
   release of only the child protection owner (`flows.dart:65`).
3. Parent `_manageIdentities` keeps `_managing=true` while awaiting that entire
   protected route (`password_connect_page.dart:223`, `:231`, `:238`). This
   suppresses normal route-coverage invalidation (`:122`) and clears only the
   password on non-foreground lifecycle while management owns the route (`:130`).
4. On return, the parent intentionally reloads native defaults (`:243`). The
   reload clears `_identityMode`, `_identity`, and `_configHost` (`:165`–`:172`)
   before its native read. After reload success, the parent reapplies the
   non-null selected key and sets `_identityMode=true` (`:248`–`:255`).
5. If the result is null (e.g. actual Back/cancel), the parent retains password
   mode after refresh. Thus the observed final ready password form is consistent
   with a null result, but no inspected code turns a successful key selection into
   null. A capture during refresh also sees password-mode state, although the
   chosen branch should follow in the same asynchronous continuation once native
   defaults finishes.
6. The existing manual-key widget test really covers this navigation/selection
   source branch: it taps the library key, checks absence of password input,
   preserves tuple, and checks only `ssh.keyConnect` is sent (`ssh_key_config_test.dart:104`).
   Its fake `ssh.defaults` and `secrets.protect` replies are immediate
   (`:538`, `:552`, `:555`); it does not independently prove actual phone route
   completion timing/native delayed release behavior.
7. Native defaults itself is asynchronous: worker reads supported metadata then
   main thread publishes the reply and starts a guarded monitor
   (`SshPasswordConnectBridge.java:119`, `:127`, `:142`, `:149`). Matching failed/
   cancelled idle events invalidate the parent (`password_connect_page.dart:569`),
   but that produces an unready/error form rather than the captured clean ready
   password form.
8. There is a separate concrete **error visibility** weakness, not proven to have
   occurred in this phone run: `_manageIdentities` catches an exception from the
   protected library Future and assigns `_error` (`password_connect_page.dart:236`),
   but then unconditionally calls `_loadDefaults` (`:243`), which clears `_error`
   (`:168`). For example, a failing protection release in the helper's `finally`
   prevents assignment of the pop result even if selection itself succeeded.
   That failure would be hidden by the following defaults reload. Native
   protection release is normally idempotent (`AppSecretsBridge.java:201`,
   `SensitiveWindow.java:29`); no supplied log demonstrates such a failure here.

### Narrow diagnostic / regression recommendation

- Recapture after a settled return and repeat the explicit key-mode selector path
  before attributing the behavior to a code defect; retain exact clicked node and
  resulting frame. The radio selection itself was not included in sanitized
  node attributes, but the visible password field already establishes auth mode.
- If repeated settled key-selection loss is confirmed, add delayed native
  protection-release/defaults response tests, route-current changes during the
  removal frame, and an assertion that a non-null key selection is retained and
  can only send `ssh.keyConnect` after the original ownership checks.
- A production change should be limited to the parent selection handoff/state
  ownership (and its meaningful test), unless runtime evidence identifies the
  generic route result as the failure. Do not skip `_exposed`, protection,
  lifecycle, cancellation or source binding to preserve a stale selection.
- A failing child protection-release test should retain the actual error and
  forbid claiming a selected identity succeeded; a silent reset to ready password
  mode is not a useful failure receipt. This is a proposed focused regression,
  not justification to change code before the main session confirms task scope.

### Related specs

- `.trellis/spec/frontend/state-management.md`: asynchronous revisions, mounted
  checks, owned screen state and stale result rejection.
- `.trellis/spec/frontend/app-secrets.md`: acquire before sensitive content,
  retain through removal and release exact protection owner; host response is
  authoritative.

### External references

None consulted. Findings derive from current repository source and the main
session’s sanitized runtime captures, not assumptions about another Flutter or
Android release.

## Caveats / Not Found

- No exact defect explains the initial general-library capture. The explicit
  private-only repeat succeeded and remained stable; navigation result, native
  reply timings and lifecycle ordering from the initial capture are unavailable.
- No private key, vault content, device commands, builds or code modifications
  were performed by this researcher.
