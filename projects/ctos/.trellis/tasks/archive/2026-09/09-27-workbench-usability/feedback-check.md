# Picture feedback quality review — 2026-09-27

脱敏重放约定：执行命令前设置 `PROJECT_ROOT` 为项目绝对路径、`EVIDENCE_DIR` 为独立临时证据目录，并重建所列历史夹具；原始机器路径不保留。

Scope: initial R1–R6 workbench changes plus appended R7–R13 image feedback.
Read check.jsonl, PRD/design/implement, frontend/backend workbench contracts,
validation profile and relevant Dart/Java/Python source. Preserve all existing
work; no installation, device operation, Root change, current build or commit
was performed by this reviewer.

## Findings fixed

1. `HftpDocuments.java`: the final document could be enumerated between
   provider creation and registering its document ID as in-progress. Reserve
   the parent document ID plus exact filename before createDocument; hide that
   name from HFTP listing/resolution until copy/cleanup finishes. This does not
   promise atomic visibility to other Android apps.
2. `HftpDocuments.java`, `HftpTreeBroker.java`: a read failure after its size
   header appended an error JSON object to the file stream. Mark that phase
   with StreamingException and close the connection without another response.
   Errors before the framing header retain normal structured responses.
3. `ctos_tools/hftp.py`: a failed download after HTTP 200 could append HTTP 400
   inside the advertised file body. Once headers begin, read/EOF/close failures
   only close the connection; errors before headers keep normal HTTP handling.
   Correct the PUT docstring so it does not promise atomic SAF publication.
4. `ToolFiles.java`: importing/clearing the private HFTP library did not lock
   against an HFTP directory/notification preparation operation. Add the shared
   HftpBridge.preparing guard to both operations. Rejecting a duplicate document
   request now preserves the original request's pending result/export state.
5. `PortableToolsTest.java`: add a targeted ownership/preparing regression.
   Its fake Activity intercepts the picker; it never launches a real picker,
   modifies preferences, grants a URI, or accesses/clears library files.

## Reviewed boundaries

- Dart preserves SDK keys, advanced/file drafts, inactive-source projection,
  explicit output tokens, masked original JSON and exact secret editing values.
  Ordinary IME masking preserves selection/composition and masked semantics;
  popup bounds and 48dp full-width text actions have actual widget assertions.
- HFTP decodes native configuration centrally, preserves unsaved port/limit
  across directory changes, locks active/pending operations and ignores stale
  status polls after explicit actions. Selected user directories have no clear
  or import-library action. Navigation never starts/stops a service.
- App supplies no credentials; Python remains the HTTP server. Java owns
  Android foreground lifecycle, URI grants and streaming document operations.
  CLI authentication compatibility remains explicit and tested.
- Native defaults/config/start agree with Dart and Python on LAN/7888, upload
  1–1024 MiB, private quota max(128 MiB, 4×upload), independent 1GiB download,
  and unchanged normal ToolFiles 32/128 MiB limits.
- Broker verifies peer UID, validates names/depth, bounds headers/responses,
  uses four workers plus four queued sockets, applies socket idle timeout and
  closes its sockets/workers on stop. Revoked grants do not silently fall back
  to another directory. Activity-close callbacks retain ownership boundaries.
- SAF creates owned temporary and final documents, verifies exact provider
  names, avoids provider rename as an exclusive commit, and deletes only owned
  documents on failure. No selected-directory startup cleanup scan exists.
  PathStorage retains exclusive renameat2, symlink and regular-file checks.

API compatibility: minSdk is 28. The [official DocumentsContract reference](https://developer.android.com/reference/android/provider/DocumentsContract)
confirms public isChildDocument begins at API29 and findDocumentPath at API26;
the implementation guards the former and requires the fallback canonical path
to begin at the selected root. [AOSP DocumentsProvider](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/provider/DocumentsProvider.java)
also enforces descendant checks for tree URIs. These are provider/grant checks,
not a claim that SAF exposes inode-level symlink metadata. API28 itself was not
device-tested.

## Verification

| Check | Actual result |
| --- | --- |
| `./hako flutter analyze` | Pass; no issues |
| `./hako flutter test` | Pass; 52/52, no tap warnings |
| Temporary Python HTTP/storage/protocol suite | Pass; 21/21 |
| `./hako bash -lc 'cd android && ./gradlew :app:lintRelease :app:assembleReleaseAndroidTest --console=plain'` | Pass; 0 errors / 5 existing warnings; test APK compiled |
| `git diff --check` | Pass |

Python command from `${EVIDENCE_DIR}/ctos-feedback-service-check`:

```sh
PYTHONPATH=${PROJECT_ROOT}/android/app/src/main/python PYTHONDONTWRITEBYTECODE=1 UV_CACHE_DIR=${EVIDENCE_DIR}/ctos-tools-uv-cache uv run --offline --no-sync pytest -q test_hftp_feedback.py test_stream_failure.py
```

The isolated existing uv environment supplies pytest. Original 17 checks cover
no-login/CLI authentication, >32MiB uploads/quota scaling, independent download
cap, binary exactness, overwrite/path/symlink/special-file rejection, mkdir,
cleanup, seven-byte short writes and a 1.28MiB HTTP/abstract-socket roundtrip.
Four added checks cover EOF/read/close failures after download headers and a
missing-file response before headers. Python tests bind only ephemeral loopback
and test abstract sockets. The sandbox first rejected socket creation; the
same isolated command passed after scoped execution approval. A first Gradle
attempt used the nonexistent debug test variant; corrected to this project's
release testBuildType before recording the pass.

Evidence:

- `${EVIDENCE_DIR}/ctos-feedback-final-python-check.log`
- `${EVIDENCE_DIR}/ctos-feedback-final-flutter-test.log`
- `${EVIDENCE_DIR}/ctos-feedback-final-android-check.log`
- `build/app/reports/lint-results-release.txt`
- Python runner SHA256: `test_hftp_feedback.py` =
  `3c3138813705302a152293e55f4fb2f1a863e30273b5a87c73ee33a1d350c3c3`;
  `test_stream_failure.py` =
  `1db695d0de17f47a3de84787e911fc78a5b189d102590d08a5affee80823f41e`.

## Findings not fixed / acceptance gaps

No remaining source-code blocker found. Human/device acceptance is still
required for the newly built APK's TARGET-PHONE ordinary keyboard, real SAF grants,
LAN reachability/transfer, notification/background/stop and provider failure
behavior. The reviewer compiled the new instrumentation regression but did not
execute it. Browser tests run earlier in the main session do not count as a
device pass; main will rerun browser checks on final source and record exact
APK/device evidence separately.

SAF publication remains non-atomic to other local apps; abrupt process death or
revoked write permission may prevent removal of this request's hidden partial.
No adversarial external replacement or API28/OEM compatibility is claimed.
The previous `98f44069…` device result is historical and does not validate this
iteration. The browser validation profile's old authenticated-only wording was
flagged to main for spec synchronization.

## R8 ordinary IME device finding and follow-up review

The main session's actual `cf740d27…` TARGET-PHONE test still showed Secure Keyboard.
`obscureText:false` alone was insufficient: Android inputType `0x80091`
contained VISIBLE_PASSWORD. The [Flutter engine change](https://dart.googlesource.com/external/github.com/flutter/engine/+/8bbf25ecf6a2577266e02c0fb4bd68169e9168a4)
explains why enableSuggestions:false selects that variation to suppress
suggestions. This corrects the initial widget-level ordinary-IME assumption;
the initial automated pass did not establish OEM keyboard behavior.

The UI agent changed only `ParameterField.enableSuggestions` to true and added
the engine rationale. Secrets still disable autocorrect, IME personalized
learning, smart quotes and smart dashes. Original values, selections,
compositions, Flutter masking and masked semantics remain unchanged. Input
suggestions may appear because requesting the ordinary IME takes precedence
over the original no-suggestions display rule; no system keyboard setting
was changed. Widget assertions now check the actual channel configuration's
suggestions/correction/learning flags as well as the original editing value.

This reviewer verified that narrow change without further source fixes:

- `./hako flutter analyze`: pass, no issues.
- `./hako flutter test`: pass, 52/52.
- `git diff --check`: pass.
- Logs: `${EVIDENCE_DIR}/ctos-feedback-ime-final-analyze.log` and
  `${EVIDENCE_DIR}/ctos-feedback-ime-final-flutter-test.log`.

No Android/Python code changed in this follow-up, so their previously passing
checks were not repeated. Main reports three native TARGET-PHONE tests have now
passed; those device results belong in main's separate actual-device record.
No new APK was built or installed by this reviewer. The corrected APK's actual
ordinary IME still requires main's targeted device verification; do not infer
success from the updated Flutter channel flags alone.

## Downloads provider compatibility follow-up review

Read-only review of the latest `HftpConfig`, `HftpDocuments`, `HftpBridge` and
`PortableToolsTest` changes found no new source-code blocker or weakened file
boundary. No application source was changed by this reviewer in this pass.

- The allowlist adds only the known local Downloads authority
  `com.android.providers.downloads.documents`; cloud and arbitrary providers,
  non-content schemes and non-tree URIs remain rejected. Each root query still
  requires the exact persisted URI with both read and write grants.
- Only ExternalStorage retains the additional volume/path document-ID prefix
  check. Downloads opaque raw/MediaStore/numeric IDs are never decoded into
  filesystem paths. Every non-root document still requires the provider's
  descendant check: public isChildDocument on API29+, canonical
  findDocumentPath rooted at the selected tree on API28. Unavailable or
  incompatible provider containment fails instead of bypassing the check.
- Directory traversal still resolves visible child names within the granted
  tree. Regular descriptors, depth/name bounds, exact-name checks, owned hidden
  temporary files, target reservations, no-overwrite creation and owned-only
  cleanup remain unchanged. [AOSP DownloadStorageProvider](https://raw.githubusercontent.com/aosp-mirror/platform_packages_providers_downloadprovider/master/src/com/android/providers/downloads/DownloadStorageProvider.java)
  inherits FileSystemProvider, implements canonical findDocumentPath and
  delegates createDocument to its superclass before maintaining download
  metadata. This supports the local-provider design; it is not a claim that
  every OEM provider is already tested.
- The initial `primary:Download` URI is supplied only as EXTRA_INITIAL_URI to
  the system picker. It neither opens a raw filesystem path nor grants access;
  selection, returned read/write flags and persistable-grant validation still
  precede root access. Existing selected trees retain their picker hint.
- Native boundary tests now accept both local tree authorities and reject a
  non-tree Downloads URI. The opt-in selectedLocalDirectoryProviderIsConfirmed
  test requires an explicit expected authority and previously selected tree;
  it verifies the grant/root without changing permissions or printing paths.

`git diff --check` passed. No Flutter/Python/Gradle/APK/device action was run in
this pass: main reports the corresponding Android lint/test APK check already
passed with 0 errors and 5 existing warnings. Real Downloads selection,
opaque-child traversal and exact transfers remain main's device acceptance
work; the earlier ExternalStorage-only scope in this record is superseded by
the two-local-provider compatibility change above.
