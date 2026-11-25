# Android 11 development-board compatibility

## Initial environment

On 2026-11-23 the user selected the non-production `product:rk3568_firefly_aioj` board for independent testing. `adb devices -l` identified one matching device; commands were explicitly directed to it. The board reported Android 11 / API 30, user 0, arm64, and the installed `github.tornaco.android.thanos` package as `9.6-prc` (`versionCode=3404474`). The user reports Pro activation; this report is not an independent activation check.

The `tv_input` Binder descriptor was `github.tornaco.android.thanos.core.IThanos`. Using shell UID 2000 and the locally checked v9.6 source's read-only transaction order, transactions 19 and 20 returned version `9.6` and `I am Thanox!!!`; transaction 24 reported no framework initialization error and transaction 27 reported `Xposed`. These are shell observations, not proof that an ordinary third-party app can access the service.

The user-provided source checkout is `053888f56b8d07b964e848ce1ccb5f2b979bc969`, described by Git as `v9.6-4-g053888f5`. The checked `IThanos`, `IPkgManager`, `IOps` and `Pkg` definitions did not differ between this checkout and tag `v9.6`. Source declarations are not, by themselves, proof of the installed service's wire contract or operation effects.

The installed app APK was copied read-only to a temporary workspace and its SHA-256 matched the board's installed file: `9a5ea71f300f32dc76c4107b01c269cd94508f4e0ea72674bee5c15bb21806b3`. REA's detector identified a ZIP but could not classify the directory within its quota; its Android DEX adapter is conditional. No DEX-level semantic result is claimed from this triage. Board-side probes must establish each needed behavior independently.

## Product compatibility boundary

At the start of this continuation, the bridge deliberately accepted only Android API 36 and Thanox service version `9.1`, so the development board could not use its Thanox operations. The following separately signed task-owned applications established ordinary-app access, UID lookup and specific operation/readback behavior before a board-specific profile was added. Android permission state, Thanox state and actual permission use remain distinct; the shared-UID restriction is retained.

## Ordinary-app operation probe

Two previously absent, separately signed task-owned packages were installed for user 0: the code-free target `im.majo.thanoxbridge.test.board20261123.target` (UID 10120, target SDK 35) and probe `im.majo.thanoxbridge.test.board20261123.probe` (UID 10123). The installed APK hashes matched their generated files: target `b13ad6c1f2a28ca5a65c410f065cd79bd14fd4c78c1fbdf79e5d2ab83eca20f3`, probe `da5e755b8ae8fab2fe52737f75df79f97f543f151f891ca40b8c4da8faf28a7f`. The target signer SHA-256 was `561087f079a141f9262abc89a3cfe1f26f5b3576589dea5a7d3eb7b638f6bdb1`; the probe used a different signer. Shell package enumeration reported one package for target UID 10120.

The ordinary-app probe verified Android API 30, the exact target UID and signer, non-system and non-shared target identity, service `9.6`, root and child Binder descriptors, operation-to-permission mapping and Thanox's exact per-package UID. The strict `PermInfo` decoder accepted the observed replies with no trailing data. CAMERA and RECORD_AUDIO each reported capability flags `background=false`, `runtime=true`, `oneTime=true`; VIBRATE reported all three flags false. The target initially had all three Android permissions granted and AppOps default mode `allow`.

| Operation | Requested Thanox state | Thanox readback | Independent Android permission / AppOps observation |
| --- | --- | --- | --- |
| CAMERA | `DENY` | `DENY` | Denied; UID mode `ignore` |
| CAMERA | `ASK` | `ASK` | Denied with `ONE_TIME`; UID mode `ignore` |
| CAMERA | `IGNORE` | `IGNORE` | Granted with `REVOKED_COMPAT`; UID mode `ignore` |
| CAMERA | `ALLOW_ALWAYS` | `ALLOW_ALWAYS` | Granted; default mode `allow` |
| RECORD_AUDIO | `DENY` | `DENY` | Denied; UID mode `ignore` |
| RECORD_AUDIO | `ASK` | `ASK` | Denied with `ONE_TIME`; UID mode `ignore` |
| RECORD_AUDIO | `IGNORE` | `IGNORE` | Granted with `REVOKED_COMPAT`; UID mode `ignore` |
| RECORD_AUDIO | `ALLOW_ALWAYS` | `ALLOW_ALWAYS` | Granted; default mode `allow` |
| VIBRATE | `IGNORE` | `IGNORE` | Granted with `REVOKED_COMPAT`; UID mode `ignore` |
| VIBRATE | `ALLOW_ALWAYS` | `ALLOW_ALWAYS` | Granted; default mode `allow` |

After each `ASK` or `IGNORE`, the probe restored `ALLOW_ALWAYS` before the next relevant stage; all three operations ended in their initial effective allow state. CAMERA and RECORD_AUDIO originally had `USER_SET` flags which were absent after restoration, so exact flag restoration was not established. The board declares camera and microphone hardware features, but these fixtures contain no code and did not use hardware. `ALLOW_FOREGROUND_ONLY` was not exercised because the service reported no background capability; it must not be presented as verified on this profile.

## Target SDK 30 comparison

A second previously absent code-free target, `im.majo.thanoxbridge.test.board20261123.target30` (UID 10124), targeted SDK 30 and declared the same three permissions. A separately signed ordinary probe, `im.majo.thanoxbridge.test.board20261123.probe30` (UID 10127), used the same checks as the SDK 35 experiment. Its exact source differences and manifests are retained under `board-probe/sdk30/`. The installed target and probe hashes matched `c51fdff69bdfeca673dee902a59ca63704d01c273e80d2966e46d8bbb8d9252c` and `bffadfc8eebbdaf1351ed253734e3f4f8795e28a05af0c7ac4335ddec3f3c6eb`; the target used the same temporary signing certificate as the SDK 35 target. The board reported one package for UID 10124.

All three initial Android permissions were granted with default AppOps mode `allow`. The probe observed the same operation mappings and capability flags as for SDK 35, then independently exercised the same CAMERA and RECORD_AUDIO `DENY`, `ALLOW_ALWAYS`, `ASK`, and `IGNORE` states and VIBRATE `IGNORE` and `ALLOW_ALWAYS`. Thanox readback and Android permission/AppOps observations matched the preceding table. All three permissions ended in effective allow; original permission flags were not exactly reconstructed. These results support SDK 30 and 35 as two separately tested target values on this board, not all intervening SDK versions, other ROMs, or actual hardware use.

## Product and shared-UID validation

The final debug product APK had SHA-256 `33d3a3304b148dd0a38806039605ef2b5ac2ac83960a6e26a16940cd8c027804`, matching the copy installed for user 0. On the SDK 35 target, an exact-package search worked without first loading the full app list. The detail view identified Thanox 9.6, reported 18 unreadable operations and limited changes to non-system targets with SDK 30 or 35. CAMERA offered `ALLOW_ALWAYS`, `ASK`, `IGNORE` and `DENY`, without the unverified foreground-only state. Through the product UI, CAMERA was set to `DENY`, RECORD_AUDIO to `IGNORE`, and VIBRATE to `IGNORE`; independent Android permission and AppOps reads matched the corresponding probe observations in the table. Each was subsequently restored through the UI to `ALLOW_ALWAYS`.

On the SDK 30 target, the CAMERA UI likewise offered `ASK` without foreground-only. Setting `ASK` produced a denied Android runtime permission with `ONE_TIME` and UID AppOps `ignore`; setting `ALLOW_ALWAYS` restored effective allow. A final independent read of both targets found all three Android permissions granted and their AppOps in explicit or default allow mode. The original runtime-permission flags were not exactly reconstructed.

Two additional code-free, same-signed SDK 30 fixtures, `im.majo.thanoxbridge.test.board20261123.shareda` and `im.majo.thanoxbridge.test.board20261123.sharedb`, shared UID 10130. Their APK hashes were `1b6f0d31d6b2a74b54f9025cc5a3c1e3930b629d4df76e759931b7226f3e0201` and `1a3de42c7951cd7584b957bfd14f8e59ee337607bcabd99099908cc07b2e61fa`. The product identified the shared UID and displayed an explicit read-only explanation; the CAMERA AppOps row was labelled read-only and was not clickable. No permission write was attempted on these two fixtures. The separate earlier phone experiment and native rejection test are recorded in `shared-uid-proof.md`.

## Cleanup and limits

The two probes, four targets and the debug product were absent before this board test and were uninstalled afterwards. Android package-manager queries including uninstalled packages returned no entries for the task prefix or product package. The specific task-created installation files under `/data/local/tmp` were absent. The temporary build workspace, including both signing keys and the password, and the copied Thanox APK / stopped REA workspace were removed. The code-free targets never exercised camera, microphone or vibration hardware; the tests establish the recorded state transitions and readback only. The phone profile was covered by local tests but was not retested on the previously designated phone during this board continuation.
