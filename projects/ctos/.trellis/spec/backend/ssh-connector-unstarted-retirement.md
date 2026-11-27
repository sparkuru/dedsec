# SSH connector material before factory admission

## 1. Scope / Trigger

An SSH connector owns constructor-created password or passphrase material before `create()` enters. A bridge rejection, cancellation failure, or already-published STOP can race the service owner admitting that factory. Retire only material that the connector has not transferred to an entered creation; this contract does not complete started-connector cancellation or all bridge results.

## 2. Signatures

`SshPasswordConnector.retireIfUnstarted() -> boolean` and `create(BooleanSupplier)` share the connector monitor. `SshSessionOwner.Cancellation.admitFactoryStart()` orders actual connector creation against the same atomic STOP state used for terminal admission. The bridge uses retirement on a failed completion or synchronous attach failure without using a `started()` snapshot as ownership proof.

## 3. Contracts

The connector monitor orders retirement and creation. If retirement wins, mark the connector cancelled, erase its owned pending arrays and close its own transient password/key; future `create` fails before entering or consuming material. If create wins, retirement returns false, and only the original factory/session owner may finish its admitted work and cleanup. Constructor-check failure uses the unstarted retirement path without entering JNI, preserving the original exception.

Within that same connector admission, the non-blocking factory-start CAS orders against the shared STOP. STOP first denies entry and retires the unstarted material even when native invalidation is still draining. Connector admission first remains an entered creation and may later observe STOP through its existing guards. An already admitted outer `SshSessionOwner` still owns the factory in either case; local retirement never releases that owner or proves native cleanup.

If shared cancellation throws, try unstarted retirement and rethrow the original failure; if creation was already admitted, do not erase its in-flight material. Cancellation takes stable password/key references under the connector monitor and closes those references outside it, so concurrent unstarted retirement cannot turn a checked reference into null. Do not hold the connector monitor while invoking JNI, vault/session operations, mailbox callbacks or owner actions.

Selected transient password/key, deferred phrase and automatic-save installation sites now have their own [started-material admission contract](ssh-started-material-admission.md). Mailbox publication, other started materials and cleanup of already admitted material remain separate. A pending native cancellation may retain material until creation is denied or cancellation returns. The [connection result contract](ssh-connection-result-delivery.md) separately covers service owner publication and at-most-once bridge reply attempts; it does not prove delivery or other native cleanup.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Local retirement before connector create | Erase constructor-owned material and reject later create |
| Connector create admitted before retirement | Do not wipe material or release its owner from the retirement caller |
| Shared STOP wins before connector admission | Deny create and erase unstarted material without waiting for native drain |
| Outer owner admitted but connector create not entered | Retire only local material; keep the original owner until its cleanup is confirmed |
| Shared cancellation throws before create | Retire local material, preserve the thrown error and unknown native state |
| Synchronous bridge attach throws before create | Retire the same connector; no claim about Android service ownership |

## 5. Good / Base / Bad Cases

Good: an outer factory is admitted, STOP arrives while native effects drain, and the connector refuses its still-unentered create while the owner remains retained. Base: retirement wins before any owner starts and clears only its local material. Bad: `started() == false` is treated as proof that service ownership has not transferred, and the bridge closes or discards that owner.

## 6. Tests Required

Use the real connector with barriers on both sides of create/retire admission, and inspect original password/passphrase array references after retirement. Cover constructor failure without native cancellation, concurrent retirement versus cancel, and a real `SshSessionOwner` whose factory has been admitted but has not entered the connector. Verify STOP while the shared cancellation monitor is blocked without waiting on that monitor from factory admission. Use isolated host JNI to hold an actual effect during cancellation and inject native begin failure, checking material, result and exact owner retention separately. A host bridge object created without Android constructors may test its actual attach and completion branches with a fake attacher, dispatcher and result recorder; it is not Android Looper or guaranteed peer-delivery evidence.

## 7. Wrong vs Correct

Wrong: clear the current connector after a failed future merely because `started()` was false, or let a JNI error skip the unstarted password wipe. Correct: order local material retirement against actual connector entry and shared STOP, retain any independently adopted owner, and leave unknown native cleanup to its own owner.
