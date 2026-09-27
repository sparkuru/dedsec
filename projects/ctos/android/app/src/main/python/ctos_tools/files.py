# -*- coding: utf-8 -*-
"""Opaque input capabilities and atomic, private result artifacts."""

import ctypes
import errno
import os
import re
import sys
import uuid
from pathlib import Path

MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_STORE_BYTES = 128 * 1024 * 1024


def commit_exclusive(temporary: Path, destination: Path) -> None:
    """Atomically publish a completed file without replacing another entry."""
    if os.name == "nt":
        os.rename(temporary, destination)
        return
    libc = ctypes.CDLL(None, use_errno=True)
    rename = getattr(libc, "renameat2", None)
    arguments = (-100, os.fsencode(temporary), -100, os.fsencode(destination), 1)
    signature = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    if rename is None and hasattr(sys, "getandroidapilevel"):
        if os.uname().machine != "aarch64":
            raise OSError(errno.ENOTSUP, "Exclusive file commit requires Android ARM64")
        # API 28/29 lack the libc symbol; their ARM64 App policies allow syscall 276.
        rename = libc.syscall
        arguments = (276, *arguments)
        signature = [ctypes.c_long, *signature]
    if rename is None:
        os.link(temporary, destination)
        temporary.unlink()
        return
    rename.argtypes = signature
    rename.restype = ctypes.c_long if len(arguments) == 6 else ctypes.c_int
    if rename(*arguments) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error), os.fspath(destination))


def read_bounded(path: Path) -> bytes:
    """Read a regular, non-symlink file within the supported size limit."""
    if path.is_symlink() or not path.is_file():
        raise ValueError("Expected a regular file")
    with path.open("rb") as stream:
        data = stream.read(MAX_FILE_BYTES + 1)
    if len(data) > MAX_FILE_BYTES:
        raise ValueError("File exceeds 32 MiB")
    return data


def read_input(root: Path | None, token: str) -> bytes:
    """Resolve only a file copied by the host's document picker."""
    if root is None or not re.fullmatch(r"[a-f0-9]{32}\.input", token):
        raise ValueError("No valid selected file")
    return read_bounded(root / token)


def write_artifact(root: Path | None, data: bytes, name: str) -> dict:
    """Publish a unique output without overwriting any existing source."""
    if root is None or not root.is_dir():
        raise ValueError("Private file store is unavailable")
    if len(data) > MAX_FILE_BYTES:
        raise ValueError("Output exceeds 32 MiB")
    entries = list(root.iterdir())
    if len(entries) >= 2000:
        raise ValueError("Private file store entry limit reached")
    used = sum(path.stat().st_size for path in entries if path.is_file())
    if used + len(data) > MAX_STORE_BYTES:
        raise ValueError("Private file store is full; clear it before retrying")
    token = uuid.uuid4().hex + ".output"
    temporary = root / (token + ".partial")
    try:
        with temporary.open("xb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        commit_exclusive(temporary, root / token)
    finally:
        temporary.unlink(missing_ok=True)
    return {"token": token, "name": name, "bytes": len(data)}
