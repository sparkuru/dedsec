# SSH ProxyJump Fixture Candidate Transfer

## Scenario: fixture candidate transfer

### 1. Scope / Trigger

Use only for a successfully approved and reserved signed-NEW fixture candidate. The transfer preserves exact candidate ownership for the temporary child; the product connector does not call it, and it does not authorize a trust write, credential use or reconnection.

### 2. Signatures

```java
FixtureCandidateTransfer FixtureCandidateReservation.transferFor(SshNativeSession child) throws VaultException;
void FixtureCandidateTransfer.requireHeldFor(SshNativeSession child) throws VaultException;
void FixtureCandidateTransfer.close();
```

### 3. Contracts

Only the original owner thread and exact child may transfer a live reservation. Under the provider, candidate and reservation monitors, move the exact candidate byte array into a new provider-owned transfer, clear the candidate's reference and mark it consumed. Do not clone, issue a normal proposal or restore the candidate on failure. The review, source, role and deadline are sampled again before return. A failed post-transfer check revokes and wipes the moved body; ordinary candidate consumption and proposal creation remain closed.

The candidate, signed review or provider may revoke the reservation from another thread; revocation wipes transferred content. Source, role or view invalidation makes subsequent live checks fail, but does not itself guarantee immediate wiping: the existing review, candidate or provider owner must still complete cleanup. Transfer closure belongs to its owner thread; invalid or repeated use fails without consuming another owner's material.

Neither `transferFor` nor `requireHeldFor` rechecks the original native effect generation. A separate short, generation- and deadline-bound admission must be designed before a trust write. Never recapture a newer generation to make an old child appear live, take Java lifecycle or vault monitors while holding that short native permit, or hold it over disk I/O.

### 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Wrong owner or child, revoked reservation, replay or prior close | Reject without delivering candidate content |
| Transfer wins before candidate, review or provider close | One owner holds the original body until revocation wipes it |
| Candidate closes or review revokes before transfer | Transfer fails; established cleanup retains ownership |
| Source, role or view becomes stale | Further live checks reject; independent owner cleanup remains responsible for wiping |
| Native generation changes without a corresponding Java state change | Transfer may still appear held; it must not authorize an effect |

### 5. Good / Base / Bad Cases

Good: move the exact approved body once and wipe it on owner cleanup. Base: a previously closed candidate prevents transfer. Bad: treat a successful transfer or Java liveness sample as permission to persist or authenticate.

### 6. Tests Required

Exercise actual Java/JNI synthetic children for approval, exact object identity, owner/session rejection, replay, closure and body wiping. Use deterministic barriers around transfer and lifecycle revocation, including failure after the body moves but before return. Separately demonstrate original native-generation invalidation and ensure no trust write, credential or shell follows. Keep host fixtures distinct from Android and real-server evidence.

### 7. Wrong vs Correct

Wrong: pass the transfer straight into a durable writer using a fresh generation. Correct: preserve its non-authorizing ownership and build a separate admission ordered against the original chain, deadline, revocation and uncertain storage outcome.
