# Prolonged request handling and board verification

## Cause and implementation

The earlier security review identified an untimed wait in an inspected Thanox 9.6 write path. The original `MainActivity` used one executor for every channel request, so an unresponsive Binder call could leave that executor occupied and its Flutter caller waiting indefinitely. No actual Thanox hang was observed or induced in this continuation; the server-side evidence and its limits remain in `uid-resolver-bytecode-20261125.md` and `security-review-agent-20261125.md`.

The channel now uses a process-scoped serial coordinator with a bounded queue. A request receives a 60-second uptime deadline once the worker begins it. On timeout the caller receives one error; a write is explicitly uncertain and may still happen. Waiting work is rejected without execution, and new work is rejected until the running call really returns. Activity destruction removes that Activity's unstarted requests and detaches its responses, but does not cancel a running call. An execution `Error` leaves the coordinator closed for the rest of the process, including when it is reported after a timeout. A client stop or restart cannot establish that the server canceled an issued mutation.

The independent `trellis-check` reviewer fixed two issues found in the first implementation: the timeout now accounts for time elapsed before the main thread receives the start notification, and fatal-error guidance explains that closing an Activity may not stop its process. The timer uses Android uptime, so deep sleep pauses it and a blocked main thread cannot deliver its callback immediately. The review was performed by an agent, not a human.

## Controlled checks

- Android `RequestCoordinatorTest` exercised normal order, bounded queue, timed-out reads and writes, delayed or racing event-loop notifications, rejection of queued work, late results, Activity destruction, delivery exceptions, executor rejection, ordinary exceptions, and fatal errors including one after timeout. The final focused run passed 15 tests; the subsequent full Android unit run passed 48 tests across six suites.
- Flutter analysis and all 36 Flutter tests passed. A widget test at 375 × 812 logical pixels and twice the normal text size uses the actual timeout and busy messages; it verifies that a timed-out write remains visibly uncertain and the old write control is absent while a fresh detail read is rejected as busy.
- Android `lintDebug` passed with zero errors and four existing warnings. The final debug APK built offline; its SHA-256 was `c50a0822594ab0de543e086a51b119be943707ec7559d332a5782fb93e6a9f9f`.

The controlled timeout tests use fake event scheduling and blocked work; they are not an observation of a live Thanox service hang or proof that a Binder call can be canceled.

## Development-board smoke test

On 2026-11-27 the designated `product:rk3568_firefly_aioj` board reported Android API 30, current user 0, Thanox `9.6-prc` (`versionCode=3404474`), and a registered `tv_input` service. The product package and the newly named test target were absent even when retained uninstalled packages were queried. The final product APK above was installed as ordinary UID 10237; its installed SHA-256 matched the local APK. The code-free, separately signed target `im.majo.thanoxbridge.test.board20261127.target` targeted SDK 35, was assigned UID 10236, and had APK SHA-256 `c8693eb555c55f1abe27fefa681dd736be19d5f8a2484ca8a42c935b2b4bed42`; its installed hash matched. The target and product signing certificates differed.

The target was installed with its declared runtime permissions granted. Its initial CAMERA permission was granted, with `USER_SET` among the flags, and its AppOps was default `allow`. The product's exact-package lookup found it without requesting the full app list, and its details showed Thanox 9.6 with the existing 18 unreadable operations. Through the product UI, CAMERA was changed to `DENY`; independent Android readback showed `granted=false`, UID mode `ignore`, and package mode `ignore`. The UI reported the observed `DENY` state. The product then changed CAMERA back to `ALLOW_ALWAYS`; independent readback showed `granted=true` and default AppOps `allow`, and the UI reported the observed allow state. RECORD_AUDIO remained granted. The original CAMERA `USER_SET` flag was no longer present after restoration, so exact flag restoration is not claimed.

Both temporary packages were uninstalled and subsequent package queries including retained uninstalled entries returned no matches. The temporary signing key, fixture files and screenshots were removed; `tv_input` remained registered. The test verifies the normal product path and effective state transitions on this board, not actual camera use, a real stalled Binder transaction, cross-process cancellation, or the historical phone's tightened compatibility path.
