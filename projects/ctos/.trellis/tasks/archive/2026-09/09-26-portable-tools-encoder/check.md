# Encoder core checks

Date: 2026-09-26. Scope: the pure `ctos_tools/encoder.py` core only.

## Implemented contract

- `transform(data: bytes, operation: str, direction: str = "encode") -> bytes` supports `base64`, `url`, and `unicode`, with explicit `encode`, `decode`, and `auto`; invalid choices raise `ValueError`.
- Base64 preserves binary data, accepts whitespace and omitted padding when decoding, and retains the reference script's canonical validation and auto detection. Explicit decoding of empty input is rejected, matching the reference.
- URL uses `quote_plus` / `unquote_plus`; URL and Unicode require UTF-8 input. Unicode encoding and supported `\\u` / `\\U` decoding match the reference, including ordinary non-ASCII text and emoji. Invalid code points raise `ValueError`.
- `hashes(data)` returns lowercase keys `md5`, `sha1`, `sha256`, `sha512` and hex values.
- `display(data)` returns `kind`, `encoding`, `value`, and `byte_length`; UTF-8 is marked as text, other bytes as binary Base64. The host owns presentation limits and raw-byte exports.
- Standard library only; no CLI, environment mutation, file I/O, networking, or module-level execution beyond imports and definitions. Public functions reject non-byte inputs with `TypeError`.

## Executed evidence

Temporary workspace: `/tmp/ctos-encoder-check-20260926`; uv cache: `/tmp/ctos-encoder-uv-cache`. Existing `/usr/bin/python3` (CPython 3.13.5) was used without downloading or installing an interpreter or dependencies.

```text
env UV_CACHE_DIR=/tmp/ctos-encoder-uv-cache uv init --bare --no-workspace --no-readme
env UV_CACHE_DIR=/tmp/ctos-encoder-uv-cache uv venv --offline --python /usr/bin/python3 .venv
env UV_CACHE_DIR=/tmp/ctos-encoder-uv-cache PYTHONDONTWRITEBYTECODE=1 uv run --offline --no-sync --python .venv/bin/python check_encoder.py
PASS: 142 assertions; byte/text/auto/error/hash/display/static checks.
```

Checks covered all 256 byte values; padded, unpadded, and whitespace Base64; malformed and noncanonical Base64; URL spaces/plus signs/Chinese/emoji; ordinary Chinese and Unicode escapes/emoji; invalid UTF-8 and Unicode code points; invalid operation/direction/input types; known `abc` digests for all four algorithms; display metadata and lossless binary representation; reference comparisons across all modes; syntax, English source, annotations, docstrings, and imports.

The reference was imported read-only with bytecode disabled:
`/home/wkyuu/cargo/repo/04-flyMe2theStar/03-genshin/code/python/26-ez-encoder.py`.
Reference SHA-256: `01b5c075901188c98ef5675e2712fef0a73bd337597fbcdd3cbaabc914eb47fc`.
Temporary check script SHA-256: `1feebb1e6658cc5caa63a0178cf68d54cd3ff01f8486f58ea2d06b2e77b8cd4d`.

An initial `uv python list --only-installed` without the cache override failed on the read-only default cache before any tests. Repeating it with `UV_CACHE_DIR` under `/tmp` succeeded; all project initialization and test commands used that override.

Whitespace checks passed for both owned files using `git diff --no-index --check /dev/null <file>`.

## Remaining integration validation

SDK registration, CLI reuse, host output bounds, selected-file access/export, APK packaging, and Android/device verification belong to the parent task and were not changed or claimed as passed here.

## Parent integration completed

The parent subsequently verified SDK registration, CLI reuse, bounded binary
artifacts, exact final APK source packaging, ARM64 startup, phone conversion
and token rejection, and real SAF import/cancel/export/read-back. Final phone
regression passed all seven scoped tests. Evidence and compatibility limits
are in [the parent check](../09-26-portable-tools/check.md). Implementation and
acceptance are complete; task status retains the uncommitted WIP for review.
