# Research: First-hop NEW trust in a ProxyJump connection

- Query: How does first/root jump host-key handling differ from the target child and from a non-jump direct connection, and can the direct NEW path be reused safely?
- Scope: internal source, project specifications and prior recorded local fixture evidence; source inspection only.
- Date: 2026-11-23

## Findings

### Files found

- `android/app/src/main/java/im/majo/ctos/SshPasswordConnector.java` — route snapshot, per-hop roles and callbacks, deadlines, credential confirmation, diagnostics and cleanup.
- `android/app/src/main/java/im/majo/ctos/SshNativeCallbackAdapter.java`, `SshHostKeyAuthority.java`, `SshHostKeyReviewMailbox.java` — actual NEW review, proof, candidate and native completion ordering.
- `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`, `SshKnownHostsCandidateHandoff.java`, `SshVaultAdapter.java`, `AppSecrets.java` — exact source receipt, proposal/CAS, live view and revision behavior.
- `android/app/src/main/cpp/ssh_native_client.c`, `android/app/src/main/java/im/majo/ctos/SshNativeSession.java` — normal and provisional callback boundaries, exact parent/child admission and KEX completion.
- `android/app/src/test/jni-product-jump/im/majo/ctos/SshProductDockerTest.java`, `android/ssh/openssh-jump-fixture.sh` — existing separate unknown-root and unknown-target product refusal cases.

### The root jump is a direct transport with a jump role, not a non-jump request

The product prepares its complete config route from one vault snapshot, checks its epoch/revision and `configBinding`, rejects more than one jump, and currently requires server-driven mode with saved-password selection (`SshPasswordConnector.java:845-881`). Each `JumpHop` stores its own effective host, port, user, saved-password identity and exact `KNOWN_HOSTS` path/object ID/object identity (`:819-842,866-876`).

For hop 0, `createJumpConnection` issues a `Program.JUMP` v2 role with hop 0 and the root endpoint's target identity, but the route's final target as `chainIdentity`; it opens a real TCP socket using `openManaged` and the shared view (`SshPasswordConnector.java:977-1016`). For the later target, it first requires an authenticated parent's exact one-shot `JumpAdmission`, prepares a fresh child source, opens `direct-tcpip`, and constructs a child owner (`:1002-1035`; `SshNativeSession.java:2378-2398`). The non-jump direct flow instead issues a `Program.CLIENT` hop-0 role with its own target and opens a normal managed socket (`SshPasswordConnector.java:691-692,753-756,1753-1765`). It is therefore correct to say the first jump has no upstream SSH relay, but not to treat the entire request, role, result or secret binding as an ordinary direct connection.

### Policy and observed refusal

`prepareJumpCallback(hop, child)` installs NEW and CHANGED issuers that reject both root and child. For the root only it sets `rootTrustRefusal` after checking the actual issued role and candidate handoff/provider; NEW under `StrictHostKeyChecking yes` returns `SSH_HOSTKEY_UNKNOWN`, and otherwise returns `SSH_JUMP_EFFECT_GUARD` (`SshPasswordConnector.java:1089-1104`). The supported config decoder accepts only values 2 and 3 (`SshConfigEvaluator.java:300-309`); the locally pinned OpenSSH 10.6p1 `readconf.h:230-231` defines these as YES and ASK, respectively. `SSH_JUMP_HOST_TRUST_REQUIRED` is a later diagnostic only when the exact-refusal and lifecycle checks succeed; cancellation and uncertain persistence take precedence (`SshPasswordConnector.java:625-635,891-928,952-964`). It should not be assumed for every failure merely from an error string.

Prior documented local Docker testing includes an unknown *root* jump configured with `ask`, and separately an unknown target configured with `ask`. The root case asserts the reported trust-required result, one TCP socket, no password prompt, no trust commit and cleanup (`SshProductDockerTest.java:126-146,309-358,386-409`); the fixture checks the jump handshake occurred, no jump userauth/forward occurred, and the target was not reached (`openssh-jump-fixture.sh:416-425`). This is prior host-JNI/real-sshd evidence, not a new test, Android evidence, or a first-trust success.

### Review, signature and exact trust source differ

For a non-jump direct request, the connector creates a `SshHostKeyReviewMailbox` tied to its issued role; its NEW issuer checks `strictHostKeyChecking`, waits for an exact `Review`, records the approved review, and exposes a role/target-bound `SshVaultAdapter` plus `requireApprovedProposal` checks (`SshPasswordConnector.java:693-729,1918-1935,1998`). The provider captures an exact role-bound `SnapshotReceipt` for `rootPath`, known-hosts path, object ID/identity and base digest (`SshManagedKnownHostsProvider.java:624-665`); handoff builds one candidate from the native-supplied replacement and wipes the handed-off input (`SshKnownHostsCandidateHandoff.java:32-78`).

The normal NEW native path selects the actual hostfile and transfers the candidate before invoking managed host evidence (`ssh_native_client.c:1280-1301,1375-1389`). Java consumes that candidate into a bound proposal, obtains the ordinary NEW approval and calls the managed write-back before returning the host result (`SshNativeCallbackAdapter.java:733-773,1365-1378`; `SshManagedKnownHostsProvider.java:770-804,821-865`). Native requires NEW's result to carry `CAS_COMMITTED` and the exact role, endpoint, fingerprint and digests (`ssh_native_client.c:1145-1183`). This ordinary direct NEW review/write occurs during the host-key callback, before native subsequently reports signed KEX/NEWKEYS completion; after completion the owner confirms the pending NEW acknowledgement before userauth (`ssh_native_client.c:1432-1475,4331-4334`; `SshNativeCallbackAdapter.java:1552-1563,1609-1614,1683-1702`; `SshPasswordConnector.java:757-769`). `Proof.permitsCredentials()` includes NEW (`SshHostKeyAuthority.java:165-201`), so it must not be repurposed as a non-authorizing pending signed-key ticket.

For a first/root jump, `prepareJumpCallback` also uses `snapshotFor` and a managed handoff, but creates `SshVaultAdapter` with a host-key gate that always denies; it deliberately does not install the direct mailbox/approved-proposal wiring (`SshPasswordConnector.java:1089-1118`). Thus replacing only the NEW issuer's rejection with an approval still cannot run the existing trust writer: `SshVaultAdapter.requireTrustBinding` invokes that gate and the trust proposal gate (`SshVaultAdapter.java:212-220,234-263`). The child's `snapshotForChild`, unlike root `snapshotFor`, binds a fresh receipt and native hostfile wire to the exact already-attached view in one public read (`SshPasswordConnector.java:1105-1113`; `SshManagedKnownHostsProvider.java:667-714`; `AppSecrets.java:3083-3126`).

The newer signed-only provisional NEW path is not a drop-in root path. Native checks support for its optional provisional hooks only when `client->upstream_relay != NULL`, i.e. on the child; when enabled it separately handles the candidate, review and signed KEX milestone without ordinary host proof (`ssh_native_client.c:1274-1278,1306-1325,1401-1404,1454-1467`). The current joined child review/poll is test-only, not a production root review or persistence flow. A root-specific reuse or new flow would need its own exact role/source/connection contract and checks; this investigation does not choose one.

### Deadline, mutation and cancellation prevent a mechanical direct reuse

The direct NEW issuer calls `mailbox.requireApproved(review)` without a transport deadline or owner poll; its live callback calls `checkTransport()` only while `reviewingChanged`, not while reviewing NEW (`SshPasswordConnector.java:693-714`). The ordinary no-poll mailbox uses the role deadline and its own maximum wall budget; the transport deadline is included only in the separate poll overload (`SshHostKeyReviewMailbox.java:134-163,203-216,233-266`). The non-jump KEX loop checks its transport deadline after the native step returns (`SshPasswordConnector.java:754-764`). A first jump starts its own `transportDeadline` before connection and checks it around the KEX loop (`:984-989,1013-1016,1036-1044`); simply calling the direct no-poll issuer would not by itself enforce that hop deadline while the user is considering the ticket. The synchronous callback also runs inside native step, so no claim about remote disconnect during its UI wait follows merely from the later deadline check.

A root trust CAS is not an ordinary in-memory acknowledgement. `AppSecrets.updateTrust` can commit, advances its session epoch for the exact continuation and treats a failure after confirmed persistence as uncertain (`AppSecrets.java:2184-2229`); `commitEncoded.beforeCommit` increments `contentRevision` and invalidates native jump effects (`:3384-3399`). The product's `check()` rejects a jump chain if its current revision differs from the connector's original `initialRevision` (`SshPasswordConnector.java:1943-1953`), and the config-selection binding was checked against that original snapshot before any socket (`:845-852`). Therefore a naive change to allow direct root NEW CAS would conflict with existing jump revision checks and leave the original shared public snapshot and route binding requiring an explicit continuation or fresh-request design. These are source-derived implications, not an observed post-write product trace. Do not accept `revision + 1` as proof of the expected writer or assume the shared view contains the new hostfile bytes.

The existing direct post-write acknowledgement contract remains important: the synchronous factory declares `mayWriteTrust`, screen lock/service rejection can defer its result until it finishes, and the exact role/provider receipt is confirmed only after native completion; possibly durable unconfirmed writes report `VAULT_COMMIT_UNCERTAIN` (`SshSessionOwner.java:16-24,127-151,186-206,220-243`; `.trellis/spec/backend/ssh-trust-commit-ack.md:19-29`). A future root flow must preserve those guarantees and explicitly order cancellation, actual effect admission and its own revision/epoch changes; the current receipt's confirmation is not a pre-write chain-wide reservation.

### Credentials, cleanup and tests

For each jump hop, userauth discovery starts only after KEX completes and both changed/NEW native-completion confirmations are checked (`SshPasswordConnector.java:1036-1052`). If the server does not return NONE_SUCCESS, the effective method must choose password, an exact saved entry must exist, and the user must confirm that hop's saved-password ticket before the selected password begins; this uses the actual host and KEX proofs (`:1053-1067,1137-1165`). The code permits a server's NONE_SUCCESS branch without a password prompt; prior dual-sshd product evidence uses password authentication and should not be extended to claim real-server none coverage.

Closing a failed jump request walks child/relay owners from target back to root, then callback/handoff/provider/source/role, shared view and registry; each field is cleared only after its cleanup returns (`SshPasswordConnector.java:1225-1249`). The service owner's unattached cleanup retries failures rather than releasing the owner (`SshSessionOwner.java:335-350`), so any future trust-written retry or reconnect result must wait for its exact owners to be released. No conclusion about all injected cleanup failures follows from these source statements.

Before opening a root NEW product path, separately test: `yes` rejects NEW before review or write; `ask` presents the actual jump endpoint, source and offered key and does not silently approve; signed KEX, original hop deadline, cancellation/lock and relevant UI ticket ordering; exact candidate/source and no premature selected secret; confirmed/failed/uncertain writes including native acknowledgement; self-induced and unrelated epoch/revision/generation changes; teardown and a genuinely fresh config/view request; and both root-NEW/target-KNOWN and root-KNOWN/target-NEW. Keep direct NEW and known-jump regressions, zero-auth/no-forward refusal and `none` success as separate scenarios, and label host synthetic, real sshd, Android and real-user evidence distinctly. These are proposed tests, not test results.

## External references

No external network or site was used. The inspected local pinned OpenSSH header is `build/ssh-native-g1/stock-run.lOHF9Oai/sources/openssh-10.6p1/readconf.h:230-231`; the archive identity and historical fixture evidence are recorded in `design/verification.md`.

## Related specs

- `.trellis/spec/backend/ssh-password-connect.md` — non-jump direct NEW, exact proof/CAS and credential gate.
- `.trellis/spec/backend/ssh-trust-commit-ack.md` — native acknowledgement, cancellation ordering and uncertain persistence.
- `.trellis/spec/backend/ssh-proxyjump-child.md` — child-only provisional signed NEW, relay and cleanup contracts.
- `.trellis/tasks/10-08-ssh-terminal/research/proxyjump-new-write-boundaries-20261123.md` — candidate, deadline, revision and fresh-request design gaps.

## Caveats / Not Found

- No source code was changed and no new test, device, network, installation, real-account operation or commit was performed in this research.
- Prior unknown-root Docker evidence is a rejection baseline. It does not prove user review, successful trust persistence, root NEW continuation, real Android behavior or all cleanup races.
- Neither live root write nor retire-then-write has a proven safe implementation here; existing sequential state checks are not claimed to order a later vault write against cancellation.
