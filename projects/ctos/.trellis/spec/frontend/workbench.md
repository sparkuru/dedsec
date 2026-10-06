# Workbench interaction contract

- Navigation is Overview / Information / Workbench / Terminal. Workbench
  item metadata and parameter forms come from the Python catalogue, not a
  duplicate Dart execution registry.
- Load the catalogue lazily on first entry. Show preparation, failure and
  retry states. Preserve the catalogue when returning from a detail route.
- Each item opens a real secondary route with an AppBar back action.
  Parameters use Form/TextFormField; validate before dispatch and preserve
  exact values. Count Unicode scalar values to agree with Python string length.
- While a task runs, disable duplicate submission and parameter editing;
  expose cancellation until the native result settles. Route disposal cancels
  its task by ID and ignores late responses after unmounting.
- Results show explicit completion/failure/cancellation/timeout, App scope,
  exit code, duration, provenance-bearing data and bounded logs. Primary copy
  actions copy the useful password, hash or preview; complete-envelope JSON
  actions remain in raw result details. Picker cancellation is not success.
- Result capture/start times are formatted for people, omitting only zero
  `.000`; nonzero milliseconds and original native/JSON values remain intact.
  JSON/file saving reports picker cancellation without claiming a file was
  written. Clipboard errors provide retry feedback and do not escape to the UI.
  Destructive file-clear dialogs show deletion effects before storage metrics,
  including large-text landscape; cancellation must invoke no cleanup method.
- Use theme colors and Material controls with >=48 dp touch targets. Keep
  content <=840 dp, scroll long output, and test 375 dp, landscape, large text
  and reduced animation settings. Device UI and permission checks are separate.

- SDK v2 choice/secret/file metadata is rendered by the modular ParameterField.
  Secrets use ordinary text input and start masked; generated passwords are masked
  until an explicit reveal. File selection stores tokens, displays filenames,
  and exports raw artifacts separately from the result JSON.
- HFTP has a dedicated service route. Poll actual host state, disable duplicate
  start/import/clear/config changes while active, and expose explicit stop.
  App sessions require no login, default to LAN port 7888, and keep Android
  notification/session limits. Route disposal never stops the service.
  Explain direct network access and HTTP transport where the user chooses them.

## Product copy on App pages

App display pages are working interfaces, not demos or implementation reports.
Visible copy must help the user choose an input, understand a permission or
effect, interpret an actual result, or recover from a failure. Keep one short
explanation at the relevant control; avoid repeating it in the header, status
card and footer. Put architecture, SDK/protocol details, roadmap, validation
claims and implementation progress in `design/`, not ordinary display pages.
Do not add script IDs or technical-details tiles merely to describe the build.
Actual user-requested logs and raw results may retain necessary diagnostics.

Good: `Root 局域网中继` with `需要 Root 授权；文件仍以 App 权限访问。`
Base: `上传上限（MiB）` with an inline range error when invalid.
Bad: `这是一个演示工作台，基于 Flutter/Java/Python 实现……`, a list of
implementation milestones, or repeated explanations of internal adapters.

During page review, remove copy that answers no user decision. Preserve real
permission requirements, HTTP exposure warnings, unavailable-capability reasons
and recovery actions; brevity must not hide effects or invent readiness.
Check the normal, busy and failure states at narrow width so necessary copy
remains readable and the primary action stays reachable.

## Display projection and input-source contract

### Scope

The Flutter workbench may reorder catalogue entries, localize known fields,
and render useful result summaries. It must not become an execution registry
or change Python/MethodChannel schemas. Show category `tools` before other
scripts and environment; put temporary-file management after task entries.

### Signatures

`ParameterPresentation(scriptId, parameter)` owns labels, choice labels,
helpers and known-field validation. `ScriptPage.submission()` projects its
controllers into `Map<String, String>`. `ResultCard(scriptId, value, api)`
renders the existing result envelope and uses the existing export methods.

### Contracts

- Localized dropdown text never changes `DropdownMenuItem.value`: `hash`,
  `encode`, `decrypt` and `legacy-decrypt` are still their wire values.
- Password advanced fields remain mounted while hidden; collapsing retains
  values, picker filenames and validation. Only hidden advanced errors open
  that section; seed/length errors stay in the basic form.
- Encoder text/file drafts remain mounted. Text submission sends `file: ''`;
  file submission sends `text: ''`. Never clear the inactive controller merely
  to construct a request. Inactive fields do not block validation.
- Hash hides direction but retains its compatible controller/default value.
- Artifact save passes the original artifact token to `exportFile`. A conversion
  preview is `{kind, encoding, value, byte_length}`; copy `value`, label binary
  Base64 previews, and never mistake this object for a plaintext string.
- IP provider fields are nested under the adapter's `data`. Device/memory
  summaries retain their `source` and `capturedAt`; absent values stay unknown.
- Generated passwords start masked in primary and raw views. Direct password
  copy and full JSON copy/save are explicit actions; JSON exports keep the
  complete original envelope. Mask known passwords in stdout/stderr before
  JSON encoding so quote, backslash and newline escaping cannot expose them.
  Unknown items/fields/results use metadata/raw
  fallbacks instead of acquiring execution handlers in Dart.

### Validation and error cases

| State | Expected outcome |
| --- | --- |
| Password length outside integer 1–128 | Inline error, no dispatch |
| Encoder file source without selected token | Select-file error, no dispatch |
| Encoder text source with an old file draft | Submit text and empty file token |
| Hidden inactive encoder input is oversized | Active input remains usable |
| Hidden password advanced value is invalid | Open advanced fields and show error |
| Picker cancellation | Preserve existing selection; no false success |
| HFTP starting/running | Explicit stop remains reachable; no duplicate start |
| Leaving HFTP page | Stop only polling, keep service lifetime unchanged |

### Good, base and bad cases

Good: select a file, switch to text, submit text, then switch back and see the
retained file. Base: a new script uses its catalogue labels and raw result.
Bad: translate the enum sent to Python or leave an old file token in a text
request, causing the backend to choose the file.

### Required assertions

Cover unordered catalogue priority/all entry reachability, exact submitted
keys/values, advanced retention, source switching/clearing/cancellation,
direct clipboard values, masked raw details, original JSON/file exports,
HFTP busy/start/stop/confirmation and route disposal. Exercise 375dp,
landscape, 2x text and reduced motion; live keyboard/SAF/service checks remain
separate device acceptance.

### Wrong and correct

Wrong: send `{text: draft, file: previousToken}` after selecting text.
Correct: preserve both controllers, submit `{text: draft, file: ''}`.

## Ordinary IME, aligned actions and HFTP configuration

### Scope and trigger

The TARGET-PHONE picture feedback changes keyboard presentation and adds persisted
HFTP configuration across Dart, Java and Python. Tool execution metadata and
SDK requests stay unchanged; remove numeric title prefixes and script-ID
technical-details tiles from visible routes.

### Signatures

`MaskedTextController.buildTextSpan` renders UTF-16-length bullets without
setting platform `obscureText`. `WorkbenchActions(children)` stretches text
actions with 48dp minimum height. `WorkbenchDropdown(child)` supplies
`ButtonThemeData.alignedDropdown`. `HftpSettings.fromJson` owns configuration
decoding; `WorkbenchApi.hftpStart(host, port, maxUploadMiB:, treeUri:, rootRelay:)` sends
only the exact chosen configuration.

### Contracts

Secret controllers retain original text/selection/composition, disable
autocorrect/IME personalized learning, and mask accessibility values while
hidden. Keep enableSuggestions=true: Flutter maps false to Android
VISIBLE_PASSWORD even with obscureText=false, triggering TARGET-PHONE Secure
Keyboard. Ordinary suggestions may appear; the user's normal-keyboard choice
takes priority. Reveal is explicit; do not mutate system IME settings.
Test the popup Material's bounds rather than only closed field width.

HFTP native configuration methods return string fields host/port/maxUploadMiB/
directoryName/treeUri. Defaults: `0.0.0.0`, `7888`, `32`, private directory,
empty treeUri. Directory selection cancellation returns null and retains
drafts. Selecting a tree changes only directory fields in the current form,
preserving unsaved port/limit. Native start persists the submitted values.
Selected user directories have no clear/import action; clearing is exclusively
for the default library and still requires confirmation. A late poll captured
before an explicit operation must not overwrite its status.

### Validation and error matrix

| Condition | Outcome |
| --- | --- |
| Configuration still loading or failed | Disable start; offer configuration retry |
| Port outside integer 1024–65535 | No native start; show error |
| Upload outside integer 1–1024 MiB | No native start; show error |
| Picker pending/service active | Lock configuration and duplicate operations |
| Directory selection cancelled | Keep prior directory and text drafts |
| Native permission revoked | Surface actual failure; never silently share another directory |
| Stale status poll resolves after start | Keep newer explicit-operation state |

### Good, base and bad cases

Good: select a local directory, retain draft port/limit, start, explicitly stop
and switch back to the private library. Base: default LAN/7888/32 MiB remains
stopped until tapped. Bad: clear a selected user directory, force a global
keyboard change, or let an old stopped poll re-enable start after launch.

### Required tests

Assert original secret Unicode text/UTF-16 selection/composition, masked
semantics and ordinary IME config. Exercise actual popup/button bounds at
375dp, landscape and 2x fonts. Cover default and restored config, exact start
payload, limit bounds, cancellation, external directory action restrictions,
picker busy state and stale polling. OEM keyboard/SAF/LAN require device checks.

### Wrong versus correct

Wrong: use `obscureText: true` and assume every ROM uses the selected ordinary
keyboard. Correct: mask Flutter presentation and verify TARGET-PHONE's actual IME.
Wrong: apply every late poll unconditionally. Correct: compare the captured
operation revision and ignore responses from before the current operation.

## HFTP optional Root and log state

### Scope / trigger

Root transport selection and log clearing cross config/start/status channels;
the UI must preserve drafts while displaying the actual service state.

### Signatures

`HftpSettings.rootRelay` is bool, default false; `hftpStart(..., rootRelay:
bool)` sends it explicitly. `WorkbenchApi.hftpClearLogs()` returns a complete
status map; `hftpLogLines(status)` projects only string log entries.

### Contracts

Decode true only from boolean true. Directory picker/default changes update
only directory fields, preserving Root/port/limit drafts. Switching to
loopback clears Root and switching back does not re-enable it. Configuration
is locked while busy/active; copy and clear logs remain independent actions.
Show running App/Root mode from native `status.rootRelay`, not the checkbox.
Status `revision` rejects old business/poll responses; independent
`logsRevision` rejects pre-clear logs. Apply only logs from clear's response,
and ignore a clear response if another business operation has since started.
Returning stops polling, not the service; failed/stopped logs stay visible.

### Validation / error matrix

| Condition | Outcome |
| --- | --- |
| Missing/string/numeric rootRelay in config | Decode false |
| Loopback host | Clear selection; no Root start |
| Root unavailable/denied | Display actual reason; ordinary mode remains selectable |
| Poll started before clear or service operation | Preserve newer relevant state/logs |
| Copy/clear failed | Retain logs, display short error and allow retry |

### Good / base / bad

Good: select Root LAN, choose/cancel a directory and retain the draft. Base:
ordinary App mode starts with explicit false. Bad: use checkbox state to label
a running server, or replace running status with an old clear response.

### Required tests

Assert exact bool Channel payload, strict decoding, draft retention and
loopback cancellation; busy locks, native mode display, log polling/retention,
exact copy, independent clear and stale response races. Check full-width
actions and bounded selectable logs at 375dp/landscape/2x fonts. Actual Root
availability and LAN reachability need device evidence.

### Wrong versus correct

Wrong: `status = await hftpClearLogs()` after a newer start.
Correct: compare operation revision and merge only the returned log snapshot.

## HFTP stop boundary and log PageStorage regression

Treat native `stopping` or strict boolean `closing: true` as occupied: show
`停止中…` for normal stopping, preserve failed reason while failed cleanup is
closing, and disable repeated start, stop and configuration changes until
cleanup settles. The explicit
stop action immediately projects stopping while awaiting the native response;
do not optimistically project stopped or enable start from a failed session
whose owned resources are still closing. Normal stop clears current reason,
retains logs and never restarts the service.

Each persisted widget state needs its own PageStorage path. The log
ExpansionTile stores bool; its SingleChildScrollView and SelectableText
scroll positions store double. An unkeyed nested scrollable can inherit the
tile's key and overwrite the bool, causing a release ErrorWidget. Use separate
PageStorageKeys for the tile, outer log scroll, selectable log text and error
text. A ValueKey on the text does not isolate PageStorage writes.

Required regression: retain the same PageStorageBucket, scroll real bounded
logs, remove/rebuild the page, then collapse the tile and clear/append logs.
Assert no framework exception, usable actions and preserved expansion state.
Also poll starting/running -> stopping -> stopped and assert no duplicate
start/config changes during the stop boundary. Initial static rendering and
returning with a fresh bucket do not establish these lifecycle contracts.

Use `服务异常` for failed sessions: a runtime failure is not necessarily a
startup failure. The Root choice should state permission, App-owned file
access and actual power cost briefly; keep implementation/lease protocol
detail in design/spec. A registered CPU lock does not justify a guaranteed
screen-off-running claim when the device acceptance fails.
