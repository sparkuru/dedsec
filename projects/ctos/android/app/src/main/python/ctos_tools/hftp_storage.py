# -*- coding: utf-8 -*-
"""Streaming file stores for private paths and an authorized Android tree."""

import json
import os
import secrets
import socket
import stat
from contextlib import contextmanager
from pathlib import Path
from typing import BinaryIO, Iterator

from ctos_tools.files import commit_exclusive


class QuotaError(ValueError):
    """A bounded store or directory cannot accept another entry."""


def validate_parts(parts: tuple[str, ...]) -> None:
    """Validate the same portable relative names on both storage backends."""
    if len(parts) > 8:
        raise ValueError("Path too deep")
    for part in parts:
        if (not part or part.startswith(".") or any(char in part for char in "/\\\x00")
                or any(ord(char) < 32 or ord(char) == 127 for char in part)
                or len(part.encode("utf-8")) > 255):
            raise ValueError("Invalid path")


def transfer(source: BinaryIO, destination: BinaryIO, size: int) -> None:
    """Copy exactly the advertised bytes with constant memory usage."""
    remaining = size
    while remaining:
        block = source.read(min(65536, remaining))
        if not block:
            raise ValueError("Incomplete transfer")
        pending = memoryview(block)
        while pending:
            written = destination.write(pending)
            if written is None or written <= 0:
                raise OSError("Incomplete transfer write")
            pending = pending[written:]
        remaining -= len(block)


class PathStorage:
    """Confine private-library operations and retain exclusive file commits."""

    def __init__(self, root: Path, quota: int) -> None:
        if root.is_symlink() or not root.is_dir():
            raise ValueError("Expected a real library directory")
        self.root = root.resolve(strict=True)
        self.quota = quota

    def _path(self, parts: tuple[str, ...]) -> Path:
        validate_parts(parts)
        current = self.root
        for part in parts:
            current = current / part
            if current.is_symlink():
                raise ValueError("Symlinks are not shared")
        if not current.resolve().is_relative_to(self.root):
            raise ValueError("Path escapes library")
        return current

    def stat(self, parts: tuple[str, ...]) -> dict:
        """Return only the metadata needed to browse and stream a file."""
        path = self._path(parts)
        if not path.exists() or not (path.is_file() or path.is_dir()):
            return {"exists": False}
        return {"exists": True, "directory": path.is_dir(), "size": path.stat().st_size}

    def listing(self, parts: tuple[str, ...]) -> list[dict]:
        """Bound enumeration before sorting; never expose private partials."""
        result = []
        for entry in self._path(parts).iterdir():
            if entry.is_symlink() or entry.name.endswith(".partial") or not (entry.is_file() or entry.is_dir()):
                continue
            try:
                validate_parts((entry.name,))
            except ValueError:
                continue
            result.append({"name": entry.name, "directory": entry.is_dir()})
            if len(result) >= 1000:
                break
        return sorted(result, key=lambda entry: (not entry["directory"], entry["name"]))

    def _check_write(self, parts: tuple[str, ...], size: int) -> Path:
        path = self._path(parts)
        if not parts or not path.parent.is_dir():
            raise ValueError("Invalid destination")
        if path.exists():
            raise FileExistsError("Destination exists")
        count, used = 0, 0
        for entry in self.root.rglob("*"):
            if entry.is_symlink():
                continue
            count += 1
            used += entry.stat().st_size if entry.is_file() else 0
            if count >= 2000 or used + size > self.quota:
                raise QuotaError("Private library quota exceeded")
        if used + size > self.quota:
            raise QuotaError("Private library quota exceeded")
        return path

    def mkdir(self, parts: tuple[str, ...]) -> None:
        """Create one new directory without replacing any entry."""
        self._check_write(parts, 0).mkdir()

    def write(self, parts: tuple[str, ...], source: BinaryIO, size: int) -> None:
        """Publish a complete upload and remove only its own temporary file."""
        path = self._check_write(parts, size)
        temporary = path.parent / ("." + secrets.token_hex(16) + ".partial")
        try:
            with temporary.open("xb") as stream:
                transfer(source, stream, size)
                stream.flush()
                os.fsync(stream.fileno())
            commit_exclusive(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)

    @contextmanager
    def read(self, parts: tuple[str, ...]) -> Iterator[tuple[BinaryIO, int]]:
        """Open a regular file without following its final symlink."""
        path = self._path(parts)
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise ValueError("Expected a regular file")
            yield stream, os.fstat(stream.fileno()).st_size


class SafStorage:
    """Use the service-owned Android broker without resolving SAF to a path."""

    def __init__(self, socket_name: str) -> None:
        if not socket_name.startswith("ctos-hftp-") or len(socket_name) > 100:
            raise ValueError("Invalid document broker")
        self.socket_name = socket_name

    @contextmanager
    def _connection(self, operation: str, parts: tuple[str, ...], size: int = 0) -> Iterator[BinaryIO]:
        validate_parts(parts)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
            connection.settimeout(15)
            connection.connect("\x00" + self.socket_name)
            request = {"operation": operation, "parts": parts, "size": size}
            connection.sendall(json.dumps(request, ensure_ascii=False).encode("utf-8") + b"\n")
            with connection.makefile("rwb", buffering=65536) as stream:
                yield stream

    @staticmethod
    def _response(stream: BinaryIO) -> dict:
        raw = stream.readline(524289)
        if len(raw) > 524288 or not raw.endswith(b"\n"):
            raise ValueError("Invalid document response")
        value = json.loads(raw)
        if not isinstance(value, dict) or not value.get("ok"):
            reason = value.get("reason", "Document operation failed") if isinstance(value, dict) else "Invalid document response"
            code = value.get("code", "") if isinstance(value, dict) else ""
            if code == "exists":
                raise FileExistsError(reason)
            if code == "quota":
                raise QuotaError(reason)
            raise ValueError(reason)
        return value

    def stat(self, parts: tuple[str, ...]) -> dict:
        """Ask Android for current metadata and permission validation."""
        with self._connection("stat", parts) as stream:
            return self._response(stream)

    def listing(self, parts: tuple[str, ...]) -> list[dict]:
        """Read a bounded list of visible children in the selected tree."""
        with self._connection("list", parts) as stream:
            return self._response(stream)["entries"]

    def mkdir(self, parts: tuple[str, ...]) -> None:
        """Create a new folder through DocumentsContract."""
        with self._connection("mkdir", parts) as stream:
            self._response(stream)

    def write(self, parts: tuple[str, ...], source: BinaryIO, size: int) -> None:
        """Stream to an owned temporary document and await exclusive commit."""
        with self._connection("write", parts, size) as stream:
            self._response(stream)
            transfer(source, stream, size)
            stream.flush()
            self._response(stream)

    @contextmanager
    def read(self, parts: tuple[str, ...]) -> Iterator[tuple[BinaryIO, int]]:
        """Keep the socket alive for the duration of a streamed download."""
        with self._connection("read", parts) as stream:
            response = self._response(stream)
            yield stream, response["size"]
