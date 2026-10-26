# Detached native SSH configuration input

The later [managed detached extension](ssh-detached-config-managed.md) adds two-phase Java/JNI actual-manifest inspection and aggregate budgets; C-only statements below describe the original prerequisite. Direct native constructors retain this contract.

## 1. Scope / Trigger

Use [the focused contract](../../../design/ssh-detached-config.md) for offline configuration
work on actual supplied manifest files. This native prerequisite does not expose Java/JNI
input, register SshContentValidator, open SSH restore or certify whole-manifest semantics.

## 2. Signatures

```c
struct ctos_ssh_config_file {
    unsigned int type;
    struct ctos_ssh_slice path, body;
};
int ctos_ssh_config_eval_open_detached(const struct ctos_ssh_config_file *files,
    unsigned int count, unsigned int root, uint64_t deadline,
    const struct ctos_ssh_config_request *request, struct ctos_ssh_config_eval **out);
int ctos_ssh_config_eval_open_catalog_detached(const struct ctos_ssh_config_file *files,
    unsigned int count, unsigned int root, uint64_t deadline,
    struct ctos_ssh_config_eval **out);
```

Reuse run/summary/options/catalog/cancel/close/destroy. Native IO's internal
`ctos_ssh_io_init_source(io, files, count, source_live, source, close_stream)` borrows a trusted
native lifetime context; no foreign callback is exposed to Java or configuration text.
`testSshConfigDetachedHost` builds dynamic ARM; `testSshConfigDetachedStaticHost` builds a
standalone static ARM regression. These Gradle tasks compile; QEMU execution is separate.

## 3. Contracts

Complete ordered file metadata, zero-based selected CONFIG root. CONFIG bodies only;
non-CONFIG body is `{NULL,0}`. Input arrays are exclusive for the constructor call and stay
caller-owned. Successful open owns copied paths/CONFIG bytes, independent of caller wiping.
No UUID/epoch/connection/role/session/view token or synthetic CSPV is constructed. Native
diagnostic object retains original 1-based input ordinal, including non-CONFIG records.

Original absolute BOOTTIME deadline is future and at most60s at admission; every stage uses
it without renewal. Cancel is cross-thread atomic; other operations keep pthread/PID/UID
ownership. Keep existing parser trylock, all lexer/evaluator policies, per-evaluation IO/line
budgets and mode guards. Ordinary evaluation requires an actual Request, with no inferred
remote username. Live constructors retain exact original borrow liveness/release semantics.

Owned configuration storage is locked/DONTDUMP before copying and wiped on close. If an
open fails and cleanup cannot finish, `*out` is a CLOSING owner even though return is -1;
caller must retry close and then destroy. Never drop this owner, run it, or read results.
Destroy requires completed close. No JNI wrapper currently hides this C ownership rule.
The8MiB format-body cap does not include metadata/paths or reader mappings. Insufficient
OS lock quota returns a resource error and cleanup, never an unlocked fallback. Current
host8192KiB quota permits real6MiB source+reader validation; exact8MiB shape is tested through
an injected protection failure, not claimed as successful full-size locking.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Null source/out, count0/>128, bad type/root, non-CONFIG body | Reject; no parsing or caller wiping |
| Invalid/duplicate path or file/directory prefix collision | Reject; no host path fallback |
| CONFIG >1MiB or total >8MiB | Reject, no truncation |
| Expired or >60s admission deadline | Reject; do not create a fresh budget |
| Cancel/expiry/wrong owner/parser busy | Preserve lifecycle diagnostics; no valid result |
| Retained cleanup resource after failed open/close | Keep exact cleanup owner for retry |
| Dynamic Include in catalog | Located deferred site, declaration-only result |
| Parse success with references not authenticated | Still no whole-manifest admission proof |

## 5. Good / Base / Bad Cases

Good: independently evaluate supplied candidate B while live A remains untouched. Base:
scan an explicitly selected orphan CONFIG and retain its true ordinal. Bad: invent an alias
to evaluate all Host/Match contexts or treat same-input Include presence as an authenticated
parent-to-child reference edge.

## 6. Tests Required

Use actual pinned parser and observed effects0. Cover independent inputs, caller mutation,
nondefault/orphan roots, ordinals, static/glob/deferred Include, source/type/path/count/size
bounds, actual Request/raw Options, cancel/deadline/owner/reentry/parser busy, wiped storage
and retryable cleanup including failed construction. Rerun existing evaluator/catalog/handle
and file-view/IO matrices. Keep compiler and runtime checks distinct; Android execution is
separate. Normal APK excludes test observers/fixture peer and keeps original JNI exports.
Fault/wipe wrappers must track managed DONTDUMP mappings only: libc also calls madvise with
MADV_DONTNEED and may unmap unrelated allocator memory. Forward those unchanged. Retain
ENOENT for absent private paths, EACCES for existing private paths, and validate absent/bad-port
Requests before borrowing as in the legacy constructor. See [actual evidence](../../../design/verification.md#20261023-detached-config).

## 7. Wrong vs Correct

Wrong: manufacture live vault metadata or attach a detached source to the singleton registry
so it can enter the old parser. Correct: a native owned file collection with original
lifetime, adapted IO, same parser and explicit historical-only results. Whole-manifest
orchestration still needs all CONFIG roots, aggregate work limits, exact Include references,
all-branch policy, keys/certificates/credentials/trust and actual Request enforcement before
provider registration or restore admission.
