# Connection snapshots and app identity

## 1. Scope / Trigger

Use this contract when changing “信息 → 连接”, `Collector.connections`, app metadata, or connection search. The result is a point-in-time `ss -tunape` snapshot. A newly opened app can own sockets that did not exist when the previous snapshot was captured.

## 2. Signatures

- Java: `Collector.connections(Context context, boolean root) -> JSONObject` through `ctos/native` method `connections`.
- Dart: `ConnectionReport.parse(String output, {Map<String, dynamic> apps = const {}})` and `ConnectionReport.search(String query) -> List<ConnectionEntry>`.
- Dart: `ConnectionSnapshotState.received(Map<String, dynamic> result, DateTime now)` and `.failed(String reason)`.

## 3. Contracts

- Native response retains `output: String`, `partial: bool`, `exit: int`, and `timeout: bool`. `apps` is a JSON object keyed by decimal socket UID. Each value is an array of `{label, packageName, applicationName, processName, icon?, alias?, userId?}`. `icon` is Base64 PNG; `alias` is a custom launcher title when available. `userId` is the Android user ID, distinct from launcher profile serial.
- Only UIDs seen in the current `ss` output get app rows. Package-list lines may contain comma-separated UIDs for the same package, including a clone profile. Match by full UID, then deduplicate by `UID + packageName`; a shared UID can still name several packages and does not prove one owner.
- On the verified Oplus device, fixed Root read-only queries obtain launcher favorites and the user ID ↔ profile serial mapping. The launcher title is optional; another ROM may provide none. Clone rows then retain the base app label/icon when visible, plus package and user ID. Never substitute a clone's UID with the main user's UID.
- `ConnectionReport.search` trims and folds case. If any entry has an exact app label, alias, application name, process name, or package-name match, return only those entries. Otherwise use the existing substring search across connection fields. `tim` must prefer the exact clone alias over unrelated `runtime` substrings.
- Entering the connection page and returning to ctOS while it is visible call `loadConnections`. A 30-second timer marks a snapshot stale; refreshing preserves the previous data while loading. Failed refresh retains the last successful data, capture time, and visible error. Export keeps the original connection text/state and omits Base64 icon metadata.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Root unavailable | App-permission `ss` result is marked partial; other pages remain usable. |
| UID missing from `ss` | Do not attach app metadata or claim app ownership. |
| Package metadata or launcher provider unavailable | Keep any known package, original label, user ID, and placeholder icon; omit alias. |
| Refresh throws or times out | Show failure and dated previous snapshot; do not turn it into “no connections” or silently reauthorize Root. |
| Query has no matches in the snapshot | Show zero matches and a refresh hint; do not imply the app has no live sockets. |
| Alias name occurs inside another app name | Exact alias matches take precedence over broad substrings. |

## 5. Good / Base / Bad Cases

- Good: Open QQ clone, return to the visible connection page, then search `tim`: a fresh snapshot shows UID `99910377`, `tim`, QQ, user 999 and `com.tencent.mobileqq` on the verified PLR110.
- Base: Search `com.tencent.mobileqq` and see main/clone rows that actually have sockets; if the OEM alias is unavailable, the clone remains identifiable by user ID.
- Bad: Search an old snapshot after opening QQ and report zero as current; or parse only `10377` from `uid:10377,99910377` and lose the clone row.

## 6. Tests Required

- `test/connection_info_test.dart`: parse clone UID and alias; `search('tim')` excludes a `Runtime Helper` substring match; `search('qq')` still finds QQ.
- `test/state_ui_test.dart`: lifecycle resume refreshes the snapshot and new QQ rows become searchable; a later failed refresh retains the row and shows an error.
- `DeviceTest.cloneLauncherAliasMapsToCloneUidProfile`: serial 10 maps to user 999 and the `tim` launcher title maps to `999:com.tencent.mobileqq`.
- After Java changes run Android lint and a release Android test APK build. On an authorized target, run applicable Root/PTY instrumentation tests and record any unavailable Vector result separately. On Oplus, verify a live clone UID with an explicit `adb -s` target; never treat a past device result as current acceptance.

## 7. Wrong vs Correct

```dart
// Wrong: reopening the page leaves a pre-QQ snapshot in place.
if (value == 3 && !connectionState.hasSnapshot) loadConnections();

// Correct: reentering the page collects again and keeps old rows while loading.
if (value == 3) loadConnections();
```

See [architecture](../../../design/architecture.md) for product behavior and [verification](../../../design/verification.md) for dated device evidence.
